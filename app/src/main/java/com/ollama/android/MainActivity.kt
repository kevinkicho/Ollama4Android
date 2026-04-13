package com.ollama.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ollama.android.ui.OllamaAndroidApp
import com.ollama.android.ui.theme.OllamaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OllamaTheme {
                OllamaAndroidApp()
            }
        }
    }
}
