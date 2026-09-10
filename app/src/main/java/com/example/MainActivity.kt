package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.flowave.ui.MainScreen
import com.example.flowave.ui.theme.FloWaveTheme
import com.example.flowave.diagnostics.FloWaveLogger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FloWaveLogger.getInstance(this).info("lifecycle", "activity_created")
        com.example.flowave.utils.FloWaveCrashHandler.init(this)
        enableEdgeToEdge()
        setContent {
            FloWaveTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        FloWaveLogger.getInstance(this).info("lifecycle", "activity_destroyed")
        super.onDestroy()
    }
}
