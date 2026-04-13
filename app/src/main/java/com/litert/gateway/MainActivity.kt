package com.litert.gateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.litert.gateway.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var llmService: LlmService? = null
    private var isBound = false
    private val logMessages = mutableListOf<String>()

    private val prefs by lazy { getSharedPreferences("LiteRTGateway", Context.MODE_PRIVATE) }

    private val statusText by lazy { findViewById<TextView>(R.id.statusText) }
    private val urlText by lazy { findViewById<TextView>(R.id.urlText) }
    private val settingsButton by lazy { findViewById<Button>(R.id.settingsButton) }
    private val helpButton by lazy { findViewById<Button>(R.id.helpButton) }
    private val startButton by lazy { findViewById<Button>(R.id.startButton) }
    private val stopButton by lazy { findViewById<Button>(R.id.stopButton) }
    private val logText by lazy { findViewById<TextView>(R.id.logText) }
    private val clearLogButton by lazy { findViewById<Button>(R.id.clearLogButton) }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("message")?.let { message ->
                runOnUiThread { appendLog(message) }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LlmService.LocalBinder
            llmService = binder.getService()
            llmService?.logBuffer?.forEach { msg -> appendLog(msg) }
            llmService?.onLog = { msg -> runOnUiThread { appendLog(msg) } }
            llmService?.debugLog = if (prefs.getBoolean("debug_log", false)) {
                { msg -> runOnUiThread { appendLog(msg) } }
            } else null
            isBound = true
            updateStatus()
            appendLog("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            llmService?.onLog = null
            llmService?.debugLog = null
            llmService = null
            isBound = false
            appendLog("Service disconnected")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver,
            IntentFilter("com.litert.gateway.ACTION_LOG")
        )

        requestPermissions()
        setupUI()
        updateStatus()
        appendLog("App started. Click Settings to configure.")
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupUI() {
        helpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }

        clearLogButton.setOnClickListener {
            logMessages.clear()
            logText.text = ""
            appendLog("日志已清除")
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun startService() {
        val modelPath = prefs.getString("last_model_path", null)
        if (modelPath.isNullOrEmpty()) {
            Toast.makeText(this, "请先在设置中选择模型", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        appendLog("Starting service...")
        appendLog("Model: ${modelPath.substringAfterLast("/")}")

        val port = prefs.getInt("port", 8080)
        appendLog("Port: $port")

        val intent = Intent(this, LlmService::class.java).apply {
            putExtra("model_path", modelPath)
            putExtra("port", port)
        }

        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopService() {
        appendLog("Stopping service...")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, LlmService::class.java))
        updateStatus()
        appendLog("Service stopped")
    }

    private fun updateStatus() {
        if (isBound && llmService != null) {
            statusText.setText(R.string.status_running)
            statusText.setTextColor(0xFF4CAF50.toInt())
            urlText.text = llmService!!.getServerUrl()
            urlText.visibility = View.VISIBLE
            startButton.isEnabled = false
            stopButton.isEnabled = true
            settingsButton.isEnabled = false
        } else {
            statusText.setText(R.string.status_stopped)
            statusText.setTextColor(0xFFFF5722.toInt())
            urlText.visibility = View.GONE
            startButton.isEnabled = true
            stopButton.isEnabled = false
            settingsButton.isEnabled = true
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        logMessages.add(logLine)
        if (logMessages.size > 100) {
            logMessages.removeAt(0)
        }
        logText.text = logMessages.joinToString("\n")
        val scrollView = findViewById<android.widget.ScrollView>(R.id.logScrollView)
        scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
