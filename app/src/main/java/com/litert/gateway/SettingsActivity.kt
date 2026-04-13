package com.litert.gateway

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
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
    private lateinit var temperatureSeekBar: SeekBar
    private lateinit var temperatureValueText: TextView
    private lateinit var maxTokensSeekBar: SeekBar
    private lateinit var maxTokensValueText: TextView
    private lateinit var maxContextSeekBar: SeekBar
    private lateinit var maxContextValueText: TextView
    private lateinit var selectFolderButton: Button
    private lateinit var defaultFolderButton: Button
    private lateinit var modelPathText: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var textBackendSpinner: Spinner
    private lateinit var visionBackendSpinner: Spinner
    private lateinit var audioBackendSpinner: Spinner
    private lateinit var imageMaxDimensionSpinner: Spinner
    private lateinit var languageSpinner: Spinner
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

        prefs = getSharedPreferences("LiteRTGateway", MODE_PRIVATE)

        // Apply saved language BEFORE setContentView
        val language = prefs.getInt("language", 0)
        applyLanguage(language)

        setContentView(R.layout.activity_settings)

        initViews()
        loadSettings()
        setupListeners()
        scanModels()
    }

    private fun initViews() {
        portEditText = findViewById(R.id.portEditText)
        debugCheckBox = findViewById(R.id.debugCheckBox)
        temperatureSeekBar = findViewById(R.id.temperatureSeekBar)
        temperatureValueText = findViewById(R.id.temperatureValueText)
        maxTokensSeekBar = findViewById(R.id.maxTokensSeekBar)
        maxTokensValueText = findViewById(R.id.maxTokensValueText)
        maxContextSeekBar = findViewById(R.id.maxContextSeekBar)
        maxContextValueText = findViewById(R.id.maxContextValueText)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        defaultFolderButton = findViewById(R.id.defaultFolderButton)
        modelPathText = findViewById(R.id.modelPathText)
        modelSpinner = findViewById(R.id.modelSpinner)
        textBackendSpinner = findViewById(R.id.textBackendSpinner)
        visionBackendSpinner = findViewById(R.id.visionBackendSpinner)
        audioBackendSpinner = findViewById(R.id.audioBackendSpinner)
        imageMaxDimensionSpinner = findViewById(R.id.imageMaxDimensionSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        saveButton = findViewById(R.id.saveButton)

        // Setup backend spinners
        val backendOptions = listOf("CPU", "GPU", "NPU")
        val backendAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, backendOptions)
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        textBackendSpinner.adapter = backendAdapter
        visionBackendSpinner.adapter = backendAdapter
        audioBackendSpinner.adapter = backendAdapter

        // Setup image max dimension spinner
        val dimensionOptions = listOf("512px", "1024px", "1536px", "2048px", "3072px (原始)")
        val dimensionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dimensionOptions)
        dimensionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        imageMaxDimensionSpinner.adapter = dimensionAdapter

        // Setup language spinner
        val languageOptions = listOf(getString(R.string.lang_chinese), getString(R.string.lang_english))
        val languageAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageOptions)
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = languageAdapter
    }

    private fun loadSettings() {
        portEditText.setText(prefs.getInt("port", 8080).toString())
        debugCheckBox.isChecked = prefs.getBoolean("debug_log", false)

        // Load temperature (0.0 to 2.0, default 0.8)
        val temperature = prefs.getFloat("temperature", 0.8f)
        temperatureSeekBar.progress = (temperature * 100).toInt()
        temperatureValueText.text = String.format("%.1f", temperature)

        // Load maxTokens (2K to 100M, stored as int, default 8192)
        val maxTokens = prefs.getInt("max_tokens", 8192)
        maxTokensSeekBar.progress = tokensToProgress(maxTokens)
        maxTokensValueText.text = formatTokens(maxTokens)

        // Load maxContextLength (4K to 128K, stored as int, default 131072)
        val maxContextLength = prefs.getInt("max_context_length", 131072)
        maxContextSeekBar.progress = contextLengthToProgress(maxContextLength)
        maxContextValueText.text = formatContextLength(maxContextLength)

        // Load backends
        val backendMap = mapOf("CPU" to 0, "GPU" to 1, "NPU" to 2)
        prefs.getString("text_backend", "GPU")?.let { textBackendSpinner.setSelection(backendMap[it] ?: 1) }
        prefs.getString("vision_backend", "GPU")?.let { visionBackendSpinner.setSelection(backendMap[it] ?: 1) }
        prefs.getString("audio_backend", "CPU")?.let { audioBackendSpinner.setSelection(backendMap[it] ?: 0) }

        // Load image max dimension (default 1024)
        val dimensionMap = mapOf(512 to 0, 1024 to 1, 1536 to 2, 2048 to 3, 3072 to 4)
        val imageMaxDim = prefs.getInt("image_max_dimension", 1024)
        imageMaxDimensionSpinner.setSelection(dimensionMap[imageMaxDim] ?: 1)

        // Load language (default 0 = Chinese)
        val language = prefs.getInt("language", 0)
        languageSpinner.setSelection(language)

        updateModelPathText()
    }

    private fun updateModelPathText() {
        val useDefault = prefs.getBoolean("use_default_folder", true)
        if (useDefault) {
            val defaultDir = getExternalFilesDir(null)?.let { File(it, "models") }
            modelPathText.text = if (defaultDir != null && defaultDir.exists()) {
                getString(R.string.default_dir_format, defaultDir.absolutePath)
            } else {
                getString(R.string.default_dir_format, getString(R.string.not_set))
            }
        } else {
            val uri = prefs.getString("model_folder_uri", null)
            modelPathText.text = getString(R.string.custom_dir_format, uri?.substringAfterLast("/") ?: getString(R.string.not_selected))
        }
    }

    private fun setupListeners() {
        selectFolderButton.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val paths = listOf(
                "/storage/emulated/0/Download" to "Download",
                "/storage/emulated/0/Documents" to "Documents",
                "/storage/emulated/0" to getString(R.string.internal_storage)
            ).filter { File(it.first).exists() }

            if (paths.isEmpty()) {
                folderPicker.launch(null)
            } else {
                val names = paths.map { it.second }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.select_model_folder)
                    .setItems(names) { _, which ->
                        val selectedPath = paths[which].first
                        prefs.edit().putString("model_folder_uri", selectedPath).apply()
                        prefs.edit().putBoolean("use_default_folder", false).apply()
                        scanModels()
                        updateModelPathText()
                    }
                    .setNegativeButton(R.string.custom) { _, _ ->
                        folderPicker.launch(null)
                    }
                    .setNeutralButton(R.string.cancel, null)
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

        // Temperature SeekBar listener
        temperatureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 100f
                temperatureValueText.text = String.format("%.1f", temp)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // MaxTokens SeekBar listener
        maxTokensSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val tokens = progressToTokens(progress)
                maxTokensValueText.text = formatTokens(tokens)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // MaxContext SeekBar listener
        maxContextSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val contextLen = progressToContextLength(progress)
                maxContextValueText.text = formatContextLength(contextLen)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

        // Save temperature (0.0 to 2.0)
        val temperature = temperatureSeekBar.progress / 100f
        prefs.edit().putFloat("temperature", temperature).apply()

        // Save maxTokens
        val maxTokens = progressToTokens(maxTokensSeekBar.progress)
        prefs.edit().putInt("max_tokens", maxTokens).apply()

        // Save maxContextLength
        val maxContextLength = progressToContextLength(maxContextSeekBar.progress)
        prefs.edit().putInt("max_context_length", maxContextLength).apply()

        // Save backends
        val backends = listOf("CPU", "GPU", "NPU")
        prefs.edit().putString("text_backend", backends[textBackendSpinner.selectedItemPosition]).apply()
        prefs.edit().putString("vision_backend", backends[visionBackendSpinner.selectedItemPosition]).apply()
        prefs.edit().putString("audio_backend", backends[audioBackendSpinner.selectedItemPosition]).apply()

        // Save image max dimension
        val dimensions = listOf(512, 1024, 1536, 2048, 3072)
        prefs.edit().putInt("image_max_dimension", dimensions[imageMaxDimensionSpinner.selectedItemPosition]).apply()

        // Save language
        prefs.edit().putInt("language", languageSpinner.selectedItemPosition).apply()

        // Apply language change
        applyLanguage(languageSpinner.selectedItemPosition)

        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun applyLanguage(languageIndex: Int) {
        val locale = if (languageIndex == 0) {
            java.util.Locale.SIMPLIFIED_CHINESE
        } else {
            java.util.Locale.ENGLISH
        }
        val appLocale = androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(locale.toLanguageTag())
        )
    }

    private fun formatTokens(tokens: Int): String {
        return when {
            tokens >= 1000000 -> "${tokens / 1000000}M"
            tokens >= 1000 -> "${tokens / 1000}K"
            else -> tokens.toString()
        }
    }

    private fun progressToTokens(progress: Int): Int {
        // Progress: 0-100 maps to 2K-100M logarithmically
        // 0 -> 2000, 50 -> ~32K, 100 -> 100000000
        val minLog = kotlin.math.ln(2000.0)
        val maxLog = kotlin.math.ln(100000000.0)
        val scale = (maxLog - minLog) / 100.0
        return kotlin.math.exp(minLog + scale * progress).toInt()
    }

    private fun tokensToProgress(tokens: Int): Int {
        val minLog = kotlin.math.ln(2000.0)
        val maxLog = kotlin.math.ln(100000000.0)
        val scale = (maxLog - minLog) / 100.0
        return ((kotlin.math.ln(tokens.toDouble()) - minLog) / scale).toInt().coerceIn(0, 100)
    }

    private fun formatContextLength(tokens: Int): String {
        return when {
            tokens >= 1024 -> "${tokens / 1024}K"
            else -> tokens.toString()
        }
    }

    private fun progressToContextLength(progress: Int): Int {
        // Progress: 0-100 maps to 4K-128K logarithmically
        // 0 -> 4096, 50 -> ~32K, 100 -> 131072
        val minLog = kotlin.math.ln(4096.0)
        val maxLog = kotlin.math.ln(131072.0)
        val scale = (maxLog - minLog) / 100.0
        return kotlin.math.exp(minLog + scale * progress).toInt()
    }

    private fun contextLengthToProgress(tokens: Int): Int {
        val minLog = kotlin.math.ln(4096.0)
        val maxLog = kotlin.math.ln(131072.0)
        val scale = (maxLog - minLog) / 100.0
        return ((kotlin.math.ln(tokens.toDouble()) - minLog) / scale).toInt().coerceIn(0, 100)
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
            listOf(getString(R.string.no_model))
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
