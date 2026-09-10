package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.flowave.ui.MainScreen
import com.example.flowave.ui.theme.FloWaveTheme
import com.example.flowave.diagnostics.FloWaveLogger
import com.example.flowave.data.repository.ProfileRepository
import com.example.flowave.utils.SettingsSchema
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FloWaveLogger.getInstance(this).info("lifecycle", "activity_created")
        com.example.flowave.utils.FloWaveCrashHandler.init(this)
        enableEdgeToEdge()
        setContent {
            val profileRepository = remember { ProfileRepository(this@MainActivity) }
            val settingsJson by profileRepository.settingsJson
                .collectAsState(initial = SettingsSchema.getDefaultJson())
            val accent = runCatching {
                Color(android.graphics.Color.parseColor(SettingsSchema.getValue(settingsJson, "custom_accent_color")))
            }.getOrNull()
            FloWaveTheme(
                darkTheme = true,
                accentColor = accent,
                fontScaleFactor = SettingsSchema.getValue(settingsJson, "font_scale").toFloatOrNull() ?: 1f
            ) {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        FloWaveLogger.getInstance(this).info("lifecycle", "activity_destroyed")
        super.onDestroy()
    }
}
