package com.litert.gateway

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.LocaleListCompat

class HelpActivity : AppCompatActivity() {

    private lateinit var serverUrlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved language before setContentView
        val prefs = getSharedPreferences("LiteRTGateway", MODE_PRIVATE)
        val language = prefs.getInt("language", 0)
        val locale = if (language == 0) {
            java.util.Locale.SIMPLIFIED_CHINESE
        } else {
            java.util.Locale.ENGLISH
        }
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        setContentView(R.layout.activity_help)

        serverUrlText = findViewById(R.id.serverUrlText)

        // Get server URL from shared preferences
        val port = prefs.getInt("port", 8080)
        serverUrlText.text = "http://localhost:$port"
    }
}