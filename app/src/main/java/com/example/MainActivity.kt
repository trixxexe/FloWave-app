package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.flowave.ui.MainScreen
import com.example.flowave.ui.theme.FloWaveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.flowave.utils.FloWaveCrashHandler.init(this)
        enableEdgeToEdge()
        setContent {
            FloWaveTheme {
                MainScreen()
            }
        }
    }
}

