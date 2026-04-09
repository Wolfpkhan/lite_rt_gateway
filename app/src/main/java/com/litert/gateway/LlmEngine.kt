package com.litert.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "LlmEngine"

data class InitResult(val success: Boolean, val error: String? = null)

class LlmEngine {
    private var engine: Engine? = null
    private var isInitialized = false
    private val cacheDir: File by lazy { File(context.cacheDir, "vision_images").also { it.mkdirs() } }

    private lateinit var context: Context

    fun initializeWithResult(modelPath: String, context: Context): InitResult {
        this.context = context
        if (isInitialized) {
            return InitResult(true)
        }

        return try {
            Log.i(TAG, "Initializing LiteRT-LM engine...")
            Log.i(TAG, "Model: ${modelPath.substringAfterLast("/")}")
            Log.i(TAG, "Native library dir: ${context.applicationInfo.nativeLibraryDir}")

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.path
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

    fun initialize(modelPath: String, context: Context) {
        initializeWithResult(modelPath, context)
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

    private fun buildContents(text: String, imageUrls: List<String>): Contents {
        if (imageUrls.isEmpty()) {
            return Contents.of(Content.Text(text))
        }

        val contentList = mutableListOf<Content>()

        // Add images first, then text
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
                // Parse base64 data URL: data:image/jpeg;base64,<data>
                val parts = url.substringAfter("data:").split(";base64,")
                if (parts.size == 2) {
                    Base64.decode(parts[1], Base64.DEFAULT)
                } else null
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                // Download from URL - not implemented for now
                Log.w(TAG, "HTTP image URLs not supported yet")
                null
            }
            else -> {
                // Treat as file path
                try {
                    File(url).readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read image file: ${e.message}")
                    null
                }
            }
        }
    }

    fun isReady(): Boolean = isInitialized && engine != null

    fun close() {
        try {
            engine?.close()
        } catch (e: Exception) {
            // Ignore
        }
        engine = null
        isInitialized = false
    }
}
