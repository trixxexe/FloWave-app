package com.example.flowave.utils

import org.json.JSONObject
import java.io.File

enum class SettingType {
    BOOLEAN,
    STRING,
    INT,
    FLOAT,
    COLOR,
    CHOICE
}

data class ChoiceOption(val value: String, val label: String)

data class SettingDefinition(
    val key: String,
    val type: SettingType,
    val defaultValue: String,
    val category: String,
    val label: String,
    val description: String,
    val choices: List<ChoiceOption> = emptyList(),
    val advanced: Boolean = false
)

object SettingsSchema {
    val DEFINITIONS = listOf(
        // Audio
        SettingDefinition(
            key = "audio_normalization",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
            category = "Audio",
            label = "Audio Normalization",
            description = "Maintains consistent loudness levels across all tracks."
        ),
        SettingDefinition(
            key = "gapless_playback",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
            category = "Audio",
            label = "Gapless Playback",
            description = "Eliminates silence between consecutive tracks."
        ),
        SettingDefinition(
            key = "preferred_quality",
            type = SettingType.CHOICE,
            defaultValue = "Ultra FLAC (24-bit)",
            category = "Audio",
            label = "Default Audio Quality",
            description = "Preferred streaming and download bitrate.",
            choices = listOf(
                ChoiceOption("Standard (128kbps)", "Standard (128kbps)"),
                ChoiceOption("High (320kbps)", "High (320kbps)"),
                ChoiceOption("Ultra FLAC (24-bit)", "Ultra FLAC (24-bit)")
            )
        ),
        
        // Appearance
        SettingDefinition(
            key = "list_density",
            type = SettingType.CHOICE,
            defaultValue = "Comfortable",
            category = "Appearance",
            label = "List Density",
            description = "Controls vertical padding and list spacing.",
            choices = listOf(
                ChoiceOption("Compact", "Compact"),
                ChoiceOption("Comfortable", "Comfortable"),
                ChoiceOption("Spacious", "Spacious")
            )
        ),
        SettingDefinition(
            key = "theme_mode",
            type = SettingType.CHOICE,
            defaultValue = "LIQUID_GLASS",
            category = "Appearance",
            label = "App Theme",
            description = "Choose the app-wide glass gradient or AMOLED appearance.",
            choices = listOf(
                ChoiceOption("LIQUID_GLASS", "Liquid Glass (dark)"),
                ChoiceOption("AMOLED_DARK", "AMOLED black")
            )
        ),
        SettingDefinition(
            key = "font_scale",
            type = SettingType.CHOICE,
            defaultValue = "1.0",
            category = "Appearance",
            label = "Font Scale",
            description = "Adjust text size throughout the application.",
            choices = listOf(
                ChoiceOption("0.8", "0.8 (Small)"),
                ChoiceOption("1.0", "1.0 (Normal)"),
                ChoiceOption("1.2", "1.2 (Large)"),
                ChoiceOption("1.4", "1.4 (Extra Large)")
            )
        ),
        SettingDefinition(
            key = "show_visualizer",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
            category = "Playback",
            label = "Show visualizer",
            description = "Show the animated visualizer over artwork in the full player."
        ),
        SettingDefinition(
            key = "show_mini_player_progress",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
            category = "Playback",
            label = "Mini-player progress",
            description = "Show the seek progress control in the mini-player."
        ),

        // Advanced
        SettingDefinition(
            key = "custom_accent_color",
            type = SettingType.COLOR,
            defaultValue = "#FF1744",
            category = "Appearance",
            label = "Accent Color Picker",
            description = "Choose a custom color for your theme highlights.",
            advanced = true
        ),
    )

    fun getDefaultJson(): String {
        val json = JSONObject()
        for (def in DEFINITIONS) {
            when (def.type) {
                SettingType.BOOLEAN -> json.put(def.key, def.defaultValue.toBoolean())
                SettingType.INT -> json.put(def.key, def.defaultValue.toIntOrNull() ?: 0)
                SettingType.FLOAT -> json.put(def.key, def.defaultValue.toFloatOrNull() ?: 0.0f)
                else -> json.put(def.key, def.defaultValue)
            }
        }
        // The one-argument form is also available in the plain JVM test runtime;
        // Android's pretty-print overload is not implemented by some stubs.
        return json.toString()?.takeIf { it.isNotBlank() } ?: "{}"
    }

    fun getValue(jsonStr: String, key: String): String {
        val json = try { JSONObject(jsonStr) } catch (e: Exception) { JSONObject() }
        val def = DEFINITIONS.firstOrNull { it.key == key } ?: return ""
        return if (json.has(key)) {
            json.get(key).toString()
        } else {
            def.defaultValue
        }
    }

    fun getBoolean(jsonStr: String, key: String): Boolean {
        return getValue(jsonStr, key).toBoolean()
    }

    fun updateValue(jsonStr: String, key: String, value: Any): String {
        val json = try { JSONObject(jsonStr) } catch (e: Exception) { JSONObject() }
        json.put(key, value)
        return json.toString()?.takeIf { it.isNotBlank() } ?: jsonStr
    }
}
