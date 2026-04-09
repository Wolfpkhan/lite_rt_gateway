package com.litert.gateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
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

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("LiteRTGateway", Context.MODE_PRIVATE)
    }

    private val statusText by lazy { findViewById<TextView>(R.id.statusText) }
    private val urlText by lazy { findViewById<TextView>(R.id.urlText) }
    private val modelPathText by lazy { findViewById<TextView>(R.id.modelPathText) }
    private val modelSpinner by lazy { findViewById<Spinner>(R.id.modelSpinner) }
    private val defaultFolderButton by lazy { findViewById<Button>(R.id.defaultFolderButton) }
    private val selectFolderButton by lazy { findViewById<Button>(R.id.selectFolderButton) }
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
    ) { }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)

            // Save URI
            prefs.edit().putString("model_folder_uri", it.toString()).apply()

            appendLog("已选择目录: ${it.lastPathSegment}")
            scanModels()
        }
    }

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
        appendLog("App started. Select a folder or use default.")
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

        // Check storage permission for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("需要存储权限才能选择外部目录")
            }
        }
    }

    private fun setupUI() {
        defaultFolderButton.setOnClickListener {
            prefs.edit().remove("model_folder_uri").apply()
            modelPathText.text = "使用默认目录"
            scanModels()
        }

        selectFolderButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    appendLog("请在设置中开启存储权限")
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                    return@setOnClickListener
                }
            }

            // Show folder selection dialog with common paths
            val paths = listOf(
                "/storage/emulated/0/Download" to "Download",
                "/storage/emulated/0/Documents" to "Documents",
                "/storage/emulated/0" to "内部存储",
                "/storage/sdcard1" to "SD卡"
            ).filter { File(it.first).exists() }

            val names = paths.map { it.second }.toTypedArray()

            android.app.AlertDialog.Builder(this)
                .setTitle("选择模型目录")
                .setItems(names) { _, which ->
                    val selectedPath = paths[which].first
                    prefs.edit().putString("model_folder_uri", selectedPath).apply()
                    modelPathText.text = "目录: $selectedPath"
                    scanModels()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }

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

        scanModels()
    }

    private fun getModelFolder(): File? {
        val path = prefs.getString("model_folder_uri", null)
        if (path == null) {
            // Default: app's external files directory
            return getExternalFilesDir(null)?.let { dir ->
                File(dir, "models").also {
                    if (!it.exists()) it.mkdirs()
                }
            }
        }

        // User entered path - verify it exists
        return try {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir
            } else {
                appendLog("目录不存在: $path")
                null
            }
        } catch (e: Exception) {
            appendLog("访问目录失败: ${e.message}")
            null
        }
    }

    private fun scanModels() {
        val modelDir = getModelFolder()
        if (modelDir == null || !modelDir.exists()) {
            modelPathText.text = "未选择目录"
            return
        }

        modelPathText.text = "目录: ${modelDir.name}"

        availableModels = try {
            if (modelDir.isDirectory) {
                modelDir.listFiles()
                    ?.filter { file ->
                        file.extension in listOf("litertlm", "tflite", "gguf") ||
                        file.name.endsWith(".tflite") || file.name.endsWith(".gguf")
                    }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            appendLog("扫描失败: ${e.message}")
            emptyList()
        }

        appendLog("找到 ${availableModels.size} 个模型")
        availableModels.forEach { f ->
            appendLog("  - ${f.name} (${f.length() / 1024 / 1024}MB)")
        }

        // Populate spinner
        val modelNames = if (availableModels.isEmpty()) {
            listOf("无模型")
        } else {
            availableModels.map { it.name }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        modelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (availableModels.isNotEmpty() && position < availableModels.size) {
                    selectedModelPath = availableModels[position].absolutePath
                } else {
                    selectedModelPath = ""
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selectedModelPath = ""
            }
        }

        if (availableModels.isNotEmpty()) {
            selectedModelPath = availableModels[0].absolutePath
        } else {
            selectedModelPath = ""
        }
    }

    private fun startService() {
        if (selectedModelPath.isEmpty()) {
            appendLog("ERROR: No model found")
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
            defaultFolderButton.isEnabled = false
            selectFolderButton.isEnabled = false
            modelSpinner.isEnabled = false
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(0xFFFF5722.toInt())
            urlText.visibility = View.GONE
            startButton.isEnabled = availableModels.isNotEmpty()
            stopButton.isEnabled = false
            defaultFolderButton.isEnabled = true
            selectFolderButton.isEnabled = true
            modelSpinner.isEnabled = true
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
