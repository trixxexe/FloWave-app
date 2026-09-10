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
        return serialize(DEFINITIONS.associate { it.key to typedDefault(it) })
    }

    fun getValue(jsonStr: String, key: String): String {
        val def = DEFINITIONS.firstOrNull { it.key == key } ?: return ""
        val token = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|true|false|-?\\d+(?:\\.\\d+)?)")
            .find(jsonStr)?.groupValues?.getOrNull(1)
        if (token.isNullOrBlank()) return def.defaultValue
        return if (token.startsWith('"') && token.endsWith('"')) {
            token.substring(1, token.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
        } else token
    }

    fun getBoolean(jsonStr: String, key: String): Boolean {
        return getValue(jsonStr, key).toBoolean()
    }

    fun updateValue(jsonStr: String, key: String, value: Any): String {
        val source = try { JSONObject(jsonStr) } catch (e: Exception) { JSONObject() }
        val values = DEFINITIONS.associate { def ->
            def.key to if (def.key == key) value else source.opt(def.key).takeUnless { it == JSONObject.NULL } ?: typedDefault(def)
        }
        return serialize(values)
    }

    private fun typedDefault(def: SettingDefinition): Any = when (def.type) {
        SettingType.BOOLEAN -> def.defaultValue.toBoolean()
        SettingType.INT -> def.defaultValue.toIntOrNull() ?: 0
        SettingType.FLOAT -> def.defaultValue.toFloatOrNull() ?: 0f
        else -> def.defaultValue
    }

    private fun serialize(values: Map<String, Any?>): String = values.entries.joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}"
    ) { (key, value) ->
        "\"${escape(key)}\":${encode(value)}"
    }

    private fun encode(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean, is Number -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
