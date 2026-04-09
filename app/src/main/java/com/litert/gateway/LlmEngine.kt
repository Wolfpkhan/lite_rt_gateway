package com.litert.gateway

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val TAG = "LlmEngine"

data class InitResult(val success: Boolean, val error: String? = null)

class LlmEngine {
    private var engine: Engine? = null
    private var isInitialized = false

    fun initializeWithResult(modelPath: String, context: Context): InitResult {
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
        return withContext(Dispatchers.IO) {
            val e = engine ?: return@withContext "Error: Engine not initialized"

            e.createConversation().use { conversation ->
                try {
                    val result = conversation.sendMessage(prompt)
                    result.toString()
                } catch (ex: Exception) {
                    Log.e(TAG, "Inference failed: ${ex.message}", ex)
                    "Error: ${ex.message}"
                }
            }
        }
    }

    fun chatStream(prompt: String, temperature: Double?): Flow<String> = flow {
        val e = engine ?: run {
            emit("Error: Engine not initialized")
            return@flow
        }

        e.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { msg ->
                emit(msg.toString())
            }
        }
    }.flowOn(Dispatchers.IO)

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
