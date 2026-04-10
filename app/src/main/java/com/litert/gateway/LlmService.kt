package com.litert.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.litert.gateway.openai.chatCompletionsRoute
import com.litert.gateway.openai.healthRoute
import com.litert.gateway.openai.modelsRoute
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "LiteRTGateway"

class LlmService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var startupIntent: Intent? = null

    // Log buffer accessible from outside
    val logBuffer = mutableListOf<String>()
    var onLog: ((String) -> Unit)? = null
    var debugLog: ((String) -> Unit)? = null

    val llmEngine = LlmEngine()

    companion object {
        const val PORT = 8080
        const val CHANNEL_ID = "LiteRTGatewayChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): LlmService = this@LlmService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startupIntent = intent
        val notification = createNotification("Starting...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            startServer()
        }

        return START_STICKY
    }

    private suspend fun startServer() {
        val modelPath = startupIntent?.getStringExtra("model_path")
            ?: "/data/local/tmp/model.litertlm"
        val port = startupIntent?.getIntExtra("port", PORT) ?: PORT

        log("Loading model: ${modelPath.substringAfterLast("/")}")
        updateNotification("Loading model...")

        val loadResult = llmEngine.initializeWithResult(modelPath, applicationContext, getSharedPreferences("LiteRTGateway", MODE_PRIVATE))

        if (!loadResult.success) {
            log("ERROR: ${loadResult.error}")
            updateNotification("Load failed: ${loadResult.error}")
            // Stop service since it cannot function
            stopSelf()
            return
        }

        log("Model loaded successfully")
        updateNotification("Model loaded. Starting server...")

        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                chatCompletionsRoute(llmEngine) { msg -> debugLog?.let { it(msg) } }
                modelsRoute()
                healthRoute(llmEngine)
            }
        }

        try {
            server?.start(wait = false)
            log("Server started on http://localhost:$port")
            updateNotification("Server running on http://localhost:$port")
        } catch (e: Exception) {
            log("Server error: ${e.message}")
            updateNotification("Server error: ${e.message}")
            stopSelf()
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        logBuffer.add(message)
        onLog?.invoke(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LiteRT Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LLM Gateway Service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        try {
            server?.stop(500, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Ignore
        }
        llmEngine.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun getServerUrl(): String {
        val port = startupIntent?.getIntExtra("port", PORT) ?: PORT
        return "http://localhost:$port"
    }
}
