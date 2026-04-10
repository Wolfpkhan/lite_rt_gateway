package com.litert.gateway

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {

    private lateinit var serverUrlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        serverUrlText = findViewById(R.id.serverUrlText)

        // Get server URL from shared preferences
        val prefs = getSharedPreferences("LiteRTGateway", MODE_PRIVATE)
        val port = prefs.getInt("port", 8080)
        serverUrlText.text = "http://localhost:$port"
    }
}