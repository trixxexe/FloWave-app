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
            key = "mini_player_style",
            type = SettingType.CHOICE,
            defaultValue = "Dynamic Island",
            category = "Appearance",
            label = "Mini Player Style",
            description = "Choose floating, standard bar, or dynamic island style.",
            choices = listOf(
                ChoiceOption("Compact Bar", "Compact Bar"),
                ChoiceOption("Dynamic Island", "Dynamic Island"),
                ChoiceOption("Glass Floating", "Glass Floating")
            )
        ),
        SettingDefinition(
            key = "home_sections_order",
            type = SettingType.CHOICE,
            defaultValue = "Featured,Recent,Playlists",
            category = "Appearance",
            label = "Home Sections Order",
            description = "Visibility and sequence of sections on the home tab.",
            choices = listOf(
                ChoiceOption("Featured,Recent,Playlists", "Featured -> Recent -> Playlists"),
                ChoiceOption("Recent,Featured,Playlists", "Recent -> Featured -> Playlists"),
                ChoiceOption("Playlists,Featured,Recent", "Playlists -> Featured -> Recent")
            )
        ),

        // Gestures
        SettingDefinition(
            key = "swipe_left_gesture",
            type = SettingType.CHOICE,
            defaultValue = "Next Track",
            category = "Gestures",
            label = "Swipe Left Gesture",
            description = "Action when swiping left on player/queue.",
            choices = listOf(
                ChoiceOption("Next Track", "Next Track"),
                ChoiceOption("Previous Track", "Previous Track"),
                ChoiceOption("Toggle Play", "Toggle Play"),
                ChoiceOption("Mute/Unmute", "Mute/Unmute"),
                ChoiceOption("Show Queue", "Show Queue")
            )
        ),
        SettingDefinition(
            key = "swipe_right_gesture",
            type = SettingType.CHOICE,
            defaultValue = "Previous Track",
            category = "Gestures",
            label = "Swipe Right Gesture",
            description = "Action when swiping right on player/queue.",
            choices = listOf(
                ChoiceOption("Next Track", "Next Track"),
                ChoiceOption("Previous Track", "Previous Track"),
                ChoiceOption("Toggle Play", "Toggle Play"),
                ChoiceOption("Mute/Unmute", "Mute/Unmute"),
                ChoiceOption("Show Queue", "Show Queue")
            )
        ),
        SettingDefinition(
            key = "double_tap_gesture",
            type = SettingType.CHOICE,
            defaultValue = "Toggle Play",
            category = "Gestures",
            label = "Double Tap Gesture",
            description = "Action when double-tapping on player.",
            choices = listOf(
                ChoiceOption("Next Track", "Next Track"),
                ChoiceOption("Previous Track", "Previous Track"),
                ChoiceOption("Toggle Play", "Toggle Play"),
                ChoiceOption("Mute/Unmute", "Mute/Unmute"),
                ChoiceOption("Show Queue", "Show Queue")
            )
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
        SettingDefinition(
            key = "manual_api_key_override",
            type = SettingType.STRING,
            defaultValue = "",
            category = "Advanced",
            label = "Manual API Key Override",
            description = "Force use of a specific YouTube API key instead of scraped/hardcoded.",
            advanced = true
        ),
        SettingDefinition(
            key = "manual_client_version_override",
            type = SettingType.STRING,
            defaultValue = "",
            category = "Advanced",
            label = "Manual Client Version Override",
            description = "Force use of a specific InnerTube client version.",
            advanced = true
        ),
        SettingDefinition(
            key = "network_timeout",
            type = SettingType.CHOICE,
            defaultValue = "15",
            category = "Advanced",
            label = "Network Timeout (Seconds)",
            description = "Connection and read timeouts for remote API queries.",
            choices = listOf(
                ChoiceOption("5", "5 seconds"),
                ChoiceOption("10", "10 seconds"),
                ChoiceOption("15", "15 seconds"),
                ChoiceOption("30", "30 seconds")
            ),
            advanced = true
        ),
        SettingDefinition(
            key = "cache_size_limit",
            type = SettingType.CHOICE,
            defaultValue = "512",
            category = "Advanced",
            label = "Cache Size Limit (MB)",
            description = "Maximum storage size for offline audio cache.",
            choices = listOf(
                ChoiceOption("128", "128 MB"),
                ChoiceOption("256", "256 MB"),
                ChoiceOption("512", "512 MB"),
                ChoiceOption("1024", "1024 MB")
            ),
            advanced = true
        ),
        SettingDefinition(
            key = "engine_fallback_order",
            type = SettingType.CHOICE,
            defaultValue = "InnerTube,Piped,Invidious",
            category = "Advanced",
            label = "Engine Fallback Order",
            description = "Enabled engines and fallback priority sequence.",
            choices = listOf(
                ChoiceOption("InnerTube,Piped,Invidious", "InnerTube -> Piped -> Invidious"),
                ChoiceOption("Piped,InnerTube,Invidious", "Piped -> InnerTube -> Invidious"),
                ChoiceOption("Invidious,InnerTube,Piped", "Invidious -> InnerTube -> Piped")
            ),
            advanced = true
        ),
        SettingDefinition(
            key = "debug_log_toggle",
            type = SettingType.BOOLEAN,
            defaultValue = "false",
            category = "Advanced",
            label = "Debug Logs Enabled",
            description = "Save detailed connection logs for troubleshooting.",
            advanced = true
        )
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
        return json.toString(4)
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
        return json.toString(4)
    }
}
