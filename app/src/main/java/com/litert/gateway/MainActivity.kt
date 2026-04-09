package com.litert.gateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var llmService: LlmService? = null
    private var isBound = false
    private var selectedModelPath: String = ""
    private var availableModels: List<File> = emptyList()
    private val logMessages = mutableListOf<String>()

    private val statusText by lazy { findViewById<TextView>(R.id.statusText) }
    private val urlText by lazy { findViewById<TextView>(R.id.urlText) }
    private val modelSpinner by lazy { findViewById<Spinner>(R.id.modelSpinner) }
    private val modelPathText by lazy { findViewById<TextView>(R.id.modelPathText) }
    private val refreshButton by lazy { findViewById<Button>(R.id.refreshButton) }
    private val startButton by lazy { findViewById<Button>(R.id.startButton) }
    private val stopButton by lazy { findViewById<Button>(R.id.stopButton) }
    private val logText by lazy { findViewById<TextView>(R.id.logText) }
    private val debugCheckBox by lazy { findViewById<android.widget.CheckBox>(R.id.debugCheckBox) }
    private val clearLogButton by lazy { findViewById<Button>(R.id.clearLogButton) }
    private val portEditText by lazy { findViewById<android.widget.EditText>(R.id.portEditText) }

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
            // Flush buffered logs first, then set up callback
            llmService?.logBuffer?.forEach { msg -> appendLog(msg) }
            llmService?.onLog = { msg -> runOnUiThread { appendLog(msg) } }
            llmService?.debugLog = if (debugCheckBox.isChecked) {
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
        ActivityResultContracts.RequestPermission()
    ) { /* Handle result if needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver,
            IntentFilter("com.litert.gateway.ACTION_LOG")
        )

        requestPermissions()
        setupModelSpinner()

        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }
        refreshButton.setOnClickListener { setupModelSpinner() }
        debugCheckBox.setOnCheckedChangeListener { _, isChecked ->
            llmService?.debugLog = if (isChecked) {
                { msg -> runOnUiThread { appendLog(msg) } }
            } else null
            appendLog("调试日志: ${if (isChecked) "开启" else "关闭"}")
        }

        clearLogButton.setOnClickListener {
            logMessages.clear()
            logText.text = ""
            appendLog("日志已清除")
        }

        updateStatus()
        appendLog("App started. Select model and click Start.")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupModelSpinner() {
        availableModels = getAvailableModels()
        appendLog("Found ${availableModels.size} models")
        availableModels.forEach { appendLog("  - ${it.name} (${it.length() / 1024 / 1024}MB)") }

        val modelNames = if (availableModels.isEmpty()) {
            listOf("No models found")
        } else {
            availableModels.map { it.name }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (availableModels.isNotEmpty() && position < availableModels.size) {
                    selectedModelPath = availableModels[position].absolutePath
                    modelPathText.text = selectedModelPath
                } else {
                    selectedModelPath = ""
                    modelPathText.text = ""
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedModelPath = ""
                modelPathText.text = ""
            }
        }

        if (availableModels.isNotEmpty()) {
            selectedModelPath = availableModels[0].absolutePath
            modelPathText.text = selectedModelPath
        } else {
            modelPathText.text = "No models found"
        }
    }

    private fun getAvailableModels(): List<File> {
        return try {
            val searchDirs = mutableListOf<File>()

            getExternalFilesDir(null)?.let { appDir ->
                val modelsDir = File(appDir, "models")
                if (!modelsDir.exists()) modelsDir.mkdirs()
                searchDirs.add(modelsDir)
            }

            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
                    if (it.exists()) searchDirs.add(it)
                }
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let {
                    if (it.exists()) searchDirs.add(it)
                }

                val aiEdgeDir = File("/storage/emulated/0/Android/data/com.google.ai.edge.gallery/files")
                if (aiEdgeDir.exists()) {
                    searchDirs.add(aiEdgeDir)
                }
            }

            val sdCardPaths = listOf(
                File("/storage/sdcard1"),
                File("/storage/0000-0000")
            )
            sdCardPaths.forEach { path ->
                if (path.exists() && path.isDirectory) {
                    searchDirs.add(path)
                }
            }

            val allModels = mutableListOf<File>()
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    searchModelsInDir(dir, allModels, maxDepth = 3)
                }
            }
            allModels.sortedBy { it.name }
        } catch (e: Exception) {
            appendLog("Error scanning models: ${e.message}")
            emptyList()
        }
    }

    private fun searchModelsInDir(dir: File, results: MutableList<File>, maxDepth: Int) {
        if (maxDepth <= 0) return
        try {
            dir.listFiles()?.forEach { file ->
                when {
                    file.isDirectory -> searchModelsInDir(file, results, maxDepth - 1)
                    file.extension in listOf("litertlm", "tflite", "gguf") -> results.add(file)
                    file.name.endsWith(".tflite") || file.name.endsWith(".gguf") -> results.add(file)
                }
            }
        } catch (e: Exception) {
            // Permission denied
        }
    }

    private fun startService() {
        if (selectedModelPath.isEmpty()) {
            appendLog("ERROR: No model selected")
            return
        }

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        appendLog("Starting service...")
        appendLog("Model: ${File(selectedModelPath).name}")

        val port = portEditText.text.toString().toIntOrNull() ?: 8080
        appendLog("Port: $port")

        val intent = Intent(this, LlmService::class.java).apply {
            putExtra("model_path", selectedModelPath)
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
            statusText.text = "Running"
            statusText.setTextColor(0xFF4CAF50.toInt())
            urlText.text = llmService!!.getServerUrl()
            urlText.visibility = View.VISIBLE
            startButton.isEnabled = false
            stopButton.isEnabled = true
            modelSpinner.isEnabled = false
            refreshButton.isEnabled = false
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(0xFFFF5722.toInt())
            urlText.visibility = View.GONE
            startButton.isEnabled = true
            stopButton.isEnabled = false
            modelSpinner.isEnabled = true
            refreshButton.isEnabled = true
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
        // Scroll to bottom
        val scrollView = findViewById<android.widget.ScrollView>(R.id.logScrollView)
        scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
