package com.example

import com.example.flowave.utils.SettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSchemaTest {
    @Test
    fun defaultsContainOnlySupportedCustomizationKeys() {
        val defaults = SettingsSchema.getDefaultJson()
        assertEquals("LIQUID_GLASS", SettingsSchema.getValue(defaults, "theme_mode"))
        assertTrue(SettingsSchema.getBoolean(defaults, "show_visualizer"))
        assertTrue(SettingsSchema.getBoolean(defaults, "show_mini_player_progress"))
        assertTrue(SettingsSchema.DEFINITIONS.none { it.key == "manual_api_key_override" })
        assertTrue(SettingsSchema.DEFINITIONS.none { it.key == "swipe_left_gesture" })
    }

    @Test
    fun updatesPersistInSerializedSettings() {
        val updated = SettingsSchema.updateValue(
            SettingsSchema.getDefaultJson(),
            "show_visualizer",
            false
        )
        assertEquals("false", SettingsSchema.getValue(updated, "show_visualizer"))
        assertEquals("AMOLED_DARK", SettingsSchema.getValue(
            SettingsSchema.updateValue(updated, "theme_mode", "AMOLED_DARK"),
            "theme_mode"
        ))
    }
}
