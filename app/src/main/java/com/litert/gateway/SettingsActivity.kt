package com.litert.gateway

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var portEditText: EditText
    private lateinit var debugCheckBox: CheckBox
    private lateinit var selectFolderButton: Button
    private lateinit var defaultFolderButton: Button
    private lateinit var modelPathText: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var textBackendSpinner: Spinner
    private lateinit var visionBackendSpinner: Spinner
    private lateinit var audioBackendSpinner: Spinner
    private lateinit var saveButton: Button

    private var availableModels: List<File> = emptyList()
    private var selectedModelPath: String = ""

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)
            prefs.edit().putString("model_folder_uri", it.toString()).apply()
            prefs.edit().putBoolean("use_default_folder", false).apply()
            scanModels()
            updateModelPathText()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("LiteRTGateway", MODE_PRIVATE)

        initViews()
        loadSettings()
        setupListeners()
        scanModels()
    }

    private fun initViews() {
        portEditText = findViewById(R.id.portEditText)
        debugCheckBox = findViewById(R.id.debugCheckBox)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        defaultFolderButton = findViewById(R.id.defaultFolderButton)
        modelPathText = findViewById(R.id.modelPathText)
        modelSpinner = findViewById(R.id.modelSpinner)
        textBackendSpinner = findViewById(R.id.textBackendSpinner)
        visionBackendSpinner = findViewById(R.id.visionBackendSpinner)
        audioBackendSpinner = findViewById(R.id.audioBackendSpinner)
        saveButton = findViewById(R.id.saveButton)

        // Setup backend spinners
        val backendOptions = listOf("CPU", "GPU", "NPU")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, backendOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        textBackendSpinner.adapter = adapter
        visionBackendSpinner.adapter = adapter
        audioBackendSpinner.adapter = adapter
    }

    private fun loadSettings() {
        portEditText.setText(prefs.getInt("port", 8080).toString())
        debugCheckBox.isChecked = prefs.getBoolean("debug_log", false)

        // Load backends
        val backendMap = mapOf("CPU" to 0, "GPU" to 1, "NPU" to 2)
        prefs.getString("text_backend", "GPU")?.let { textBackendSpinner.setSelection(backendMap[it] ?: 1) }
        prefs.getString("vision_backend", "GPU")?.let { visionBackendSpinner.setSelection(backendMap[it] ?: 1) }
        prefs.getString("audio_backend", "CPU")?.let { audioBackendSpinner.setSelection(backendMap[it] ?: 0) }

        updateModelPathText()
    }

    private fun updateModelPathText() {
        val useDefault = prefs.getBoolean("use_default_folder", true)
        if (useDefault) {
            val defaultDir = getExternalFilesDir(null)?.let { File(it, "models") }
            modelPathText.text = if (defaultDir != null && defaultDir.exists()) {
                "默认目录: ${defaultDir.absolutePath}"
            } else {
                "默认目录: 未设置"
            }
        } else {
            val uri = prefs.getString("model_folder_uri", null)
            modelPathText.text = "自定义: ${uri?.substringAfterLast("/") ?: "未选择"}"
        }
    }

    private fun setupListeners() {
        selectFolderButton.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "请先在设置中开启存储权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val paths = listOf(
                "/storage/emulated/0/Download" to "Download",
                "/storage/emulated/0/Documents" to "Documents",
                "/storage/emulated/0" to "内部存储"
            ).filter { File(it.first).exists() }

            if (paths.isEmpty()) {
                folderPicker.launch(null)
            } else {
                val names = paths.map { it.second }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择模型目录")
                    .setItems(names) { _, which ->
                        val selectedPath = paths[which].first
                        prefs.edit().putString("model_folder_uri", selectedPath).apply()
                        prefs.edit().putBoolean("use_default_folder", false).apply()
                        scanModels()
                        updateModelPathText()
                    }
                    .setNegativeButton("自定义") { _, _ ->
                        folderPicker.launch(null)
                    }
                    .setNeutralButton("取消", null)
                    .show()
            }
        }

        defaultFolderButton.setOnClickListener {
            prefs.edit().remove("model_folder_uri").apply()
            prefs.edit().putBoolean("use_default_folder", true).apply()
            scanModels()
            updateModelPathText()
        }

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

        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        // Save port
        val port = portEditText.text.toString().toIntOrNull() ?: 8080
        prefs.edit().putInt("port", port).apply()

        // Save debug
        prefs.edit().putBoolean("debug_log", debugCheckBox.isChecked).apply()

        // Save backends
        val backends = listOf("CPU", "GPU", "NPU")
        prefs.edit().putString("text_backend", backends[textBackendSpinner.selectedItemPosition]).apply()
        prefs.edit().putString("vision_backend", backends[visionBackendSpinner.selectedItemPosition]).apply()
        prefs.edit().putString("audio_backend", backends[audioBackendSpinner.selectedItemPosition]).apply()

        // Save selected model
        if (selectedModelPath.isNotEmpty()) {
            prefs.edit().putString("last_model_path", selectedModelPath).apply()
        }

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun scanModels() {
        val modelDir = getModelFolder()
        availableModels = if (modelDir != null && modelDir.exists() && modelDir.isDirectory) {
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

        // Update spinner
        val modelNames = if (availableModels.isEmpty()) {
            listOf("无模型")
        } else {
            availableModels.map { it.name }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        // Select last used model
        val lastModel = prefs.getString("last_model_path", null)
        if (lastModel != null && availableModels.isNotEmpty()) {
            val index = availableModels.indexOfFirst { it.absolutePath == lastModel }
            if (index >= 0) {
                modelSpinner.setSelection(index)
                selectedModelPath = lastModel
            } else {
                selectedModelPath = availableModels.firstOrNull()?.absolutePath ?: ""
            }
        } else {
            selectedModelPath = availableModels.firstOrNull()?.absolutePath ?: ""
        }
    }

    private fun getModelFolder(): File? {
        val useDefault = prefs.getBoolean("use_default_folder", true)
        if (useDefault) {
            return getExternalFilesDir(null)?.let { File(it, "models").also { d -> d.mkdirs() } }
        }

        val uriString = prefs.getString("model_folder_uri", null) ?: return null
        return try {
            if (uriString.startsWith("/")) {
                // It's a file path
                File(uriString).takeIf { it.exists() && it.isDirectory }
            } else {
                // It's a content URI
                val doc = DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriString))
                if (doc != null) {
                    val tempDir = File(cacheDir, "model_folder").also { it.mkdirs() }
                    copyDocToTemp(doc, tempDir)
                    tempDir
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun copyDocToTemp(doc: DocumentFile, destDir: File) {
        try {
            destDir.listFiles()?.forEach { it.delete() }
            doc.listFiles().forEach { file ->
                if (file.isFile) {
                    val destFile = File(destDir, file.name ?: return@forEach)
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
