package com.litert.gateway

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LtMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.litert.gateway.openai.Message
import com.litert.gateway.openai.Tool as OpenAITool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "LlmEngine"

data class InitResult(val success: Boolean, val error: String? = null)

// Result from chat including tool calls
data class ChatResult(
    val text: String = "",
    val toolCalls: List<com.google.ai.edge.litertlm.ToolCall>? = null
)

data class BackendConfig(
    val text: String = "GPU",
    val vision: String = "GPU",
    val audio: String = "CPU"
)

// Session info for LRU cache
private data class SessionInfo(
    val session: com.google.ai.edge.litertlm.Conversation,
    val lastAccessTime: Long,
    val lastMessageCount: Int
)

// LRU Cache constants
private const val MAX_ACTIVE_SESSIONS = 5
private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes

class LlmEngine {
    private var engine: Engine? = null
    private var isInitialized = false
    private val cacheDir: File by lazy { File(context.cacheDir, "vision_images").also { it.mkdirs() } }
    private val inferenceMutex = Mutex()  // Serialize concurrent requests (LiteRT-LM only supports one session)

    private lateinit var context: Context

    // Generic OpenApiTool wrapper that converts any OpenAI tool definition
    private inner class GenericOpenApiTool(
        private val name: String,
        private val description: String,
        private val parametersJson: String
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            return """
            {
                "name": "$name",
                "description": "${description.escapeJson()}",
                "parameters": $parametersJson
            }
            """.trimIndent()
        }

        override fun execute(paramsJsonString: String): String {
            // Tool execution is done by caller, not by the model
            // This is not called when automaticToolCalling = false
            return """{"error": "Tool execution should be done by caller"}"""
        }
    }

    // LRU Session Cache
    private val sessionCache = object : LinkedHashMap<String, SessionInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionInfo>?): Boolean {
            val oldest = eldest?.value ?: return false
            // Keep at least 1 session, evict if: too many sessions OR idle timeout
            if (size <= 1) return false
            return size > MAX_ACTIVE_SESSIONS ||
                   System.currentTimeMillis() - oldest.lastAccessTime > IDLE_TIMEOUT_MS
        }
    }

    /**
     * Generate session ID from system prompt MD5
     */
    private fun getSessionId(messages: List<Message>): String {
        val systemPrompt = messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n") { it.content ?: "" }
        return if (systemPrompt.isEmpty()) {
            "stateless-${System.currentTimeMillis()}"
        } else {
            systemPrompt.md5()
        }
    }

    /**
     * MD5 hash of a string
     */
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get or create a cached conversation session
     * Note: LiteRT-LM only supports one active session, so we close old sessions before creating new ones
     */
    private fun getOrCreateSession(
        messages: List<Message>,
        temperature: Double?,
        tools: List<OpenAITool>?
    ): com.google.ai.edge.litertlm.Conversation {
        val e = engine ?: throw IllegalStateException("Engine not initialized")
        val sessionId = getSessionId(messages)

        // Check active sessions
        if (sessionCache.containsKey(sessionId)) {
            val info = sessionCache[sessionId]!!
            // Update access time
            sessionCache[sessionId] = info.copy(lastAccessTime = System.currentTimeMillis())
            Log.i(TAG, "Session cache HIT: $sessionId")
            return info.session
        }

        // LiteRT-LM only supports one active session - close all existing sessions first
        closeAllSessions()

        // Create new session
        Log.i(TAG, "Session cache MISS: $sessionId, creating new")
        val nonSystemMessages = messages.filter { !it.role.equals("system", ignoreCase = true) }

        // Use buildConversationConfig to create config with full history
        val conversationConfig = buildConversationConfig(nonSystemMessages, temperature, tools)

        val conversation = e.createConversation(conversationConfig)
        sessionCache[sessionId] = SessionInfo(
            session = conversation,
            lastAccessTime = System.currentTimeMillis(),
            lastMessageCount = nonSystemMessages.size
        )

        return conversation
    }

    /**
     * Clean up stale sessions that have timed out
     */
    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val toRemove = sessionCache.filter { (_, info) ->
            now - info.lastAccessTime > IDLE_TIMEOUT_MS
        }
        toRemove.forEach { (id, info) ->
            try {
                info.session.close()
                Log.i(TAG, "Closed stale session: $id")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close session: $id: ${e.message}")
            }
            sessionCache.remove(id)
        }
    }

    /**
     * Close all cached sessions
     */
    fun closeAllSessions() {
        sessionCache.values.forEach { info ->
            try {
                info.session.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close session: ${e.message}")
            }
        }
        sessionCache.clear()
        Log.i(TAG, "All sessions closed")
    }

    private var defaultTemperature: Float = 0.8f
    private var defaultMaxTokens: Int = 8192
    private var maxContextLength: Int = 131072  // 128K default (total input+output tokens)

    fun initializeWithResult(modelPath: String, context: Context, prefs: SharedPreferences): InitResult {
        this.context = context

        // Load default inference parameters
        defaultTemperature = prefs.getFloat("temperature", 0.8f)
        defaultMaxTokens = prefs.getInt("max_tokens", 8192)
        maxContextLength = prefs.getInt("max_context_length", 131072)  // maxNumTokens
        Log.i(TAG, "Default temperature: $defaultTemperature, maxTokens: $defaultMaxTokens, maxContext: $maxContextLength")

        if (isInitialized) {
            return InitResult(true)
        }

        return try {
            Log.i(TAG, "Initializing LiteRT-LM engine...")
            Log.i(TAG, "Model: ${modelPath.substringAfterLast("/")}")
            Log.i(TAG, "Native library dir: ${context.applicationInfo.nativeLibraryDir}")

            // Get backend configs
            val textBackend = createBackend(prefs.getString("text_backend", "GPU") ?: "GPU")
            val visionBackend = createBackend(prefs.getString("vision_backend", "GPU") ?: "GPU")
            val audioBackend = createBackend(prefs.getString("audio_backend", "CPU") ?: "CPU")

            Log.i(TAG, "Backends - Text: $textBackend, Vision: $visionBackend, Audio: $audioBackend")

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = textBackend,
                visionBackend = visionBackend,
                audioBackend = audioBackend,
                cacheDir = context.cacheDir.path,
                maxNumTokens = maxContextLength
            )

            engine = Engine(engineConfig)
            engine?.initialize()
            isInitialized = true
            Log.i(TAG, "Engine initialized successfully")
            InitResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization failed: ${e.message}", e)
            InitResult(false, e.message ?: "Unknown error")
        }
    }

    private fun createBackend(name: String): Backend {
        return when (name.uppercase()) {
            "CPU" -> Backend.CPU()
            "GPU" -> Backend.GPU()
            "NPU" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            else -> Backend.GPU()
        }
    }

    fun initialize(modelPath: String, context: Context) {
        initializeWithResult(modelPath, context, context.getSharedPreferences("LiteRTGateway", Context.MODE_PRIVATE))
    }

    suspend fun chat(prompt: String, temperature: Double?): String {
        return chatMultiModal(prompt, emptyList(), temperature)
    }

    fun chatStream(prompt: String, temperature: Double?): Flow<String> = chatStreamMultiModal(prompt, emptyList(), temperature)

    suspend fun chatMultiModal(text: String, imageUrls: List<String>, temperature: Double?): String {
        return withContext(Dispatchers.IO) {
            val e = engine ?: return@withContext "Error: Engine not initialized"

            try {
                val contents = buildContents(text, imageUrls)
                e.createConversation().use { conversation ->
                    val result = conversation.sendMessage(contents)
                    result.toString()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Inference failed: ${ex.message}", ex)
                "Error: ${ex.message}"
            }
        }
    }

    /**
     * Chat with full OpenAI message history (stateless)
     * Uses ConversationConfig.initialMessages to pass full conversation history
     * Note: LiteRT-LM only supports one session at a time, so requests are serialized
     */
    suspend fun chatWithMessages(
        messages: List<Message>,
        temperature: Double?,
        tools: List<OpenAITool>? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                // Get or create cached session (handles history internally)
                val conversation = getOrCreateSession(messages, temperature, tools)

                // Send only the last message (history is cached)
                val lastMessage = messages.lastOrNull()
                    ?: return@withContext ChatResult(text = "Error: No message")
                val (textContent, mediaUrls) = parseOpenAIMessage(lastMessage)
                val contents = buildContents(textContent, mediaUrls)

                val result = conversation.sendMessage(contents)
                val toolCalls = result.toolCalls
                Log.i(TAG, "sendMessage completed. toolCalls: $toolCalls, toolCalls size: ${toolCalls?.size}")

                if (!toolCalls.isNullOrEmpty()) {
                    ChatResult(text = result.toString(), toolCalls = toolCalls)
                } else {
                    ChatResult(text = result.toString(), toolCalls = null)
                }
            } catch (parseEx: Exception) {
                Log.w(TAG, "Tool call parsing failed: ${parseEx.message}")
                ChatResult(text = "Error: ${parseEx.message}")
            } catch (ex: Exception) {
                Log.e(TAG, "Inference failed: ${ex.message}", ex)
                ChatResult(text = "Error: ${ex.message}")
            }
        }
    }

    fun chatStreamMultiModal(text: String, imageUrls: List<String>, temperature: Double?): Flow<String> = flow {
        val e = engine ?: run {
            emit("Error: Engine not initialized")
            return@flow
        }

        try {
            val contents = buildContents(text, imageUrls)
            e.createConversation().use { conversation ->
                conversation.sendMessageAsync(contents).collect { msg ->
                    emit(msg.toString())
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Stream inference failed: ${ex.message}", ex)
            emit("Error: ${ex.message}")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming chat with full OpenAI message history (stateless)
     * Uses ConversationConfig.initialMessages to pass full conversation history
     * Note: LiteRT-LM only supports one session at a time, so requests are serialized
     */
    fun chatStreamWithMessages(
        messages: List<Message>,
        temperature: Double?,
        tools: List<OpenAITool>? = null
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            try {
                // Get or create cached session
                val conversation = getOrCreateSession(messages, temperature, tools)

                // Send only the last message (history is cached)
                val lastMessage = messages.lastOrNull()
                    ?: run {
                        emit("Error: No message")
                        return@flow
                    }
                val (textContent, mediaUrls) = parseOpenAIMessage(lastMessage)
                val contents = buildContents(textContent, mediaUrls)

                // Stream response
                conversation.sendMessageAsync(contents).collect { msg ->
                    emit(msg.toString())
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Stream inference failed: ${ex.message}", ex)
                emit("Error: ${ex.message}")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Build ConversationConfig from OpenAI messages with full history support
     */
    private fun buildConversationConfig(
        messages: List<Message>,
        temperature: Double?,
        tools: List<OpenAITool>? = null
    ): ConversationConfig {
        var systemInstruction: Contents? = null
        val initialMessages = mutableListOf<LtMessage>()

        for (msg in messages) {
            when (msg.role.lowercase()) {
                "system" -> {
                    // Use the last system message as instruction
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            systemInstruction = Contents.of(it)
                        }
                    }
                }
                "user" -> {
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            initialMessages.add(LtMessage.user(it))
                        }
                    }
                }
                "assistant" -> {
                    // For assistant messages with tool_calls, we don't add them to initialMessages
                    // since we're using automaticToolCalling = false
                    // The caller will handle tool_calls by executing tools and sending results
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            initialMessages.add(LtMessage.model(it))
                        }
                    }
                }
                "tool" -> {
                    // Tool response message from caller
                    msg.content?.let { result ->
                        msg.toolCallId?.let { callId ->
                            // Wrap in Contents for Message.tool()
                            val toolResponseContent = Contents.of(Content.Text("$callId: $result"))
                            initialMessages.add(LtMessage.tool(toolResponseContent))
                        }
                    }
                }
            }
        }

        // Build sampler config (use request temperature or default)
        val effectiveTemp = temperature?.toDouble() ?: defaultTemperature.toDouble()
        val samplerConfig = SamplerConfig(
            topK = 10,
            topP = 0.95,
            temperature = effectiveTemp
        )

        // Convert OpenAI tools to LiteRT OpenApiTool
        val liteRtTools: List<com.google.ai.edge.litertlm.ToolProvider> = tools?.mapNotNull { openAiTool ->
            val function = openAiTool.function
            val paramsJson = function.parameters?.toString() ?: "{}"
            Log.i(TAG, "Converting tool: ${function.name}, params: $paramsJson")
            val openApiTool = GenericOpenApiTool(
                name = function.name,
                description = function.description,
                parametersJson = paramsJson
            )
            tool(openApiTool)  // Wrap with tool() helper
        } ?: emptyList()
        Log.i(TAG, "liteRtTools size: ${liteRtTools.size}")

        return ConversationConfig(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            samplerConfig = samplerConfig,
            tools = liteRtTools,
            automaticToolCalling = false  // Return tool_calls to caller
        )
    }

    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun buildContents(text: String, imageUrls: List<String>): Contents {
        if (imageUrls.isEmpty()) {
            return Contents.of(Content.Text(text))
        }

        val contentList = mutableListOf<Content>()

        for (url in imageUrls) {
            try {
                when {
                    url.startsWith("data:image") -> {
                        val imageBytes = decodeImageUrl(url)
                        if (imageBytes != null) {
                            contentList.add(Content.ImageBytes(imageBytes))
                        }
                    }
                    url.startsWith("data:audio") -> {
                        val audioBytes = decodeAudioUrl(url)
                        if (audioBytes != null) {
                            contentList.add(Content.AudioBytes(audioBytes))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode media: ${e.message}")
            }
        }

        if (text.isNotEmpty()) {
            contentList.add(Content.Text(text))
        }

        return Contents.of(contentList)
    }

    private fun decodeAudioUrl(url: String): ByteArray? {
        return try {
            val parts = url.substringAfter("data:").split(";base64,")
            if (parts.size == 2) {
                Base64.decode(parts[1], Base64.DEFAULT)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio: ${e.message}")
            null
        }
    }

    private fun decodeImageUrl(url: String): ByteArray? {
        return when {
            url.startsWith("data:") -> {
                val parts = url.substringAfter("data:").split(";base64,")
                if (parts.size == 2) {
                    Base64.decode(parts[1], Base64.DEFAULT)
                } else null
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                Log.w(TAG, "HTTP image URLs not supported yet")
                null
            }
            else -> {
                try {
                    File(url).readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read image file: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Parse OpenAI Message to extract text content and media URLs
     * Handles both simple string content and multi-modal content arrays
     */
    private fun parseOpenAIMessage(message: Message?): Pair<String, List<String>> {
        if (message == null) return Pair("", emptyList())

        val content = message.content ?: return Pair("", emptyList())

        // For now, assume content is a plain string
        // TODO: Support multi-modal content arrays if needed
        return Pair(content, emptyList())
    }

    fun isReady(): Boolean = isInitialized && engine != null

    fun close() {
        closeAllSessions()
        try {
            engine?.close()
        } catch (e: Exception) {
            // Ignore
        }
        engine = null
        isInitialized = false
    }
}
