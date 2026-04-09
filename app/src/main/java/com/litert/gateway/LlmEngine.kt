package com.litert.gateway

import android.content.Context
import android.content.SharedPreferences
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

private const val TAG = "LlmEngine"

data class InitResult(val success: Boolean, val error: String? = null)

data class BackendConfig(
    val text: String = "GPU",
    val vision: String = "GPU",
    val audio: String = "CPU"
)

class LlmEngine {
    private var engine: Engine? = null
    private var isInitialized = false
    private val cacheDir: File by lazy { File(context.cacheDir, "vision_images").also { it.mkdirs() } }

    private lateinit var context: Context

    fun initializeWithResult(modelPath: String, context: Context, prefs: SharedPreferences): InitResult {
        this.context = context
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
