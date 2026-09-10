package com.example.flowave.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flowave.data.model.UserProfile
import com.example.flowave.ui.components.*
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.SettingsSchema
import com.example.flowave.utils.SettingDefinition
import com.example.flowave.utils.SettingType
import com.example.flowave.diagnostics.DiagnosticEntry
import com.example.flowave.diagnostics.DiagnosticLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userProfile: UserProfile,
    totalListeningTimeMs: Long,
    totalPlayCount: Int,
    onBackClick: () -> Unit,
    onSaveProfile: (String, String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onBackgroundPresetSelected: (String) -> Unit = {},
    onCustomBgUrlEntered: (String?) -> Unit = {},
    onAudioSettingsChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    settingsJson: String = SettingsSchema.getDefaultJson(),
    onSettingUpdated: (String, Any) -> Unit = { _, _ -> },
    onExportSettings: suspend () -> String = { "" },
    onImportSettings: suspend (String) -> Boolean = { _ -> false },
    diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    onExportDiagnostics: () -> Unit = {},
    onClearUnsavedDiagnostics: () -> Unit = {},
    onClearAllDiagnostics: () -> Unit = {},
    onProtectDiagnostic: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var usernameInput by remember { mutableStateOf(userProfile.username) }
    var bioInput by remember { mutableStateOf(userProfile.bio) }
    var customUrlInput by remember { mutableStateOf(userProfile.customBgUrl ?: "") }
    var isEditing by remember { mutableStateOf(false) }

    // Multi-tap advanced mode detection
    var versionClickCount by remember { mutableStateOf(0) }
    var isAdvancedUnlocked by remember { mutableStateOf(false) }

    // Dialog state controllers
    var activeChoiceDef by remember { mutableStateOf<SettingDefinition?>(null) }
    var activeStringDef by remember { mutableStateOf<SettingDefinition?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    fun openInstagram(handle: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$handle"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.testTag("profile_back_btn")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Settings & User Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Profile Card
        LiquidGlassProfileWidget(
            profile = userProfile,
            onEditClick = { isEditing = !isEditing }
        )

        if (isEditing) {
            Spacer(modifier = Modifier.height(12.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Edit Profile Preferences", color = TextPrimary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bioInput,
                        onValueChange = { bioInput = it },
                        label = { Text("Status / Quote") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onSaveProfile(usernameInput, bioInput)
                            isEditing = false
                            Toast.makeText(context, "Profile updated", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                    ) {
                        Text("Save Preferences", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Custom Background Support Section
        Text("App Background & Visual Theme", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Background Style Preset", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(10.dp))

                val presets = listOf(
                    "LIQUID_GLASS" to "Liquid Glass",
                    "AMOLED_DARK" to "Pitch Black OLED",
                    "CYBER_CYAN" to "Cyber Neon",
                    "NEON_PURPLE" to "Midnight Violet"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.take(2).forEach { (key, label) ->
                        val isSelected = userProfile.backgroundPreset == key && userProfile.customBgUrl.isNullOrEmpty()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) CyanNeon else DarkSurface)
                                .clickable {
                                    onBackgroundPresetSelected(key)
                                    onCustomBgUrlEntered(null)
                                    Toast.makeText(context, "Applied $label theme", Toast.LENGTH_SHORT).show()
                                }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) PureBlack else TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.drop(2).forEach { (key, label) ->
                        val isSelected = userProfile.backgroundPreset == key && userProfile.customBgUrl.isNullOrEmpty()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) CyanNeon else DarkSurface)
                                .clickable {
                                    onBackgroundPresetSelected(key)
                                    onCustomBgUrlEntered(null)
                                    Toast.makeText(context, "Applied $label theme", Toast.LENGTH_SHORT).show()
                                }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) PureBlack else TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GlassBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Custom Background Image URL", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customUrlInput,
                        onValueChange = { customUrlInput = it },
                        placeholder = { Text("https://images.unsplash.com/...", fontSize = 12.sp, color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (customUrlInput.isNotBlank()) {
                                onCustomBgUrlEntered(customUrlInput.trim())
                                Toast.makeText(context, "Custom wallpaper applied", Toast.LENGTH_SHORT).show()
                            } else {
                                onCustomBgUrlEntered(null)
                                Toast.makeText(context, "Reset to preset background", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                    ) {
                        Text("Apply")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Curated Theme Quick Picks
        Text("Accent Color Palette", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Curated Primary Accent Color", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(10.dp))
                
                val curatedColors = listOf(
                    "Cyan" to "#00F0FF",
                    "Violet" to "#FF1744",
                    "Purple" to "#A020F0",
                    "Green" to "#00FF00",
                    "Gold" to "#FFD700"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    curatedColors.forEach { (name, hex) ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val activeHex = SettingsSchema.getValue(settingsJson, "custom_accent_color")
                        val isSelected = activeHex.replace("#", "").lowercase() == hex.replace("#", "").lowercase()
                        
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onSettingUpdated("custom_accent_color", hex)
                                    Toast.makeText(context, "$name Accent applied", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = PureBlack, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Render dynamic categories
        val categories = listOf("Audio", "Appearance", "Gestures")
        categories.forEach { category ->
            val defs = SettingsSchema.DEFINITIONS.filter { it.category == category }
            if (defs.isNotEmpty()) {
                Text("$category Preferences", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))

                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        defs.forEach { def ->
                            when (def.type) {
                                SettingType.BOOLEAN -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text(def.description, color = TextMuted, fontSize = 11.sp)
                                        }
                                        Switch(
                                            checked = SettingsSchema.getBoolean(settingsJson, def.key),
                                            onCheckedChange = {
                                                onSettingUpdated(def.key, it)
                                                // sync legacy options directly
                                                if (def.key == "audio_normalization" || def.key == "gapless_playback") {
                                                    val norm = if (def.key == "audio_normalization") it else SettingsSchema.getBoolean(settingsJson, "audio_normalization")
                                                    val gapless = if (def.key == "gapless_playback") it else SettingsSchema.getBoolean(settingsJson, "gapless_playback")
                                                    onAudioSettingsChanged(norm, gapless)
                                                }
                                            }
                                        )
                                    }
                                }
                                SettingType.CHOICE -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeChoiceDef = def }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text(def.description, color = TextMuted, fontSize = 11.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = SettingsSchema.getValue(settingsJson, def.key),
                                                color = CyanNeon,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                SettingType.STRING -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeStringDef = def }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text(def.description, color = TextMuted, fontSize = 11.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val displayVal = SettingsSchema.getValue(settingsJson, def.key)
                                            Text(
                                                text = if (displayVal.length > 15) displayVal.take(12) + "..." else displayVal,
                                                color = PurpleNeon,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Render Advanced Category if unlocked
        if (isAdvancedUnlocked) {
            Text("Advanced Developer Options", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
            Spacer(modifier = Modifier.height(8.dp))

            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Accent Color Custom Hex Input Trigger
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomColorDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hex Color Picker", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Define any custom UI highlight hex code directly", color = TextMuted, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = SettingsSchema.getValue(settingsJson, "custom_accent_color"),
                                color = CyanNeon,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Icon(Icons.Default.Colorize, contentDescription = "Pick Color", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Loop through Advanced settings
                    val advDefs = SettingsSchema.DEFINITIONS.filter { it.category == "Advanced" }
                    advDefs.forEach { def ->
                        when (def.type) {
                            SettingType.BOOLEAN -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(def.description, color = TextMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = SettingsSchema.getBoolean(settingsJson, def.key),
                                        onCheckedChange = { onSettingUpdated(def.key, it) }
                                    )
                                }
                            }
                            SettingType.CHOICE -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeChoiceDef = def }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(def.description, color = TextMuted, fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = SettingsSchema.getValue(settingsJson, def.key),
                                            color = CyanNeon,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            SettingType.STRING -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeStringDef = def }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(def.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(def.description, color = TextMuted, fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val displayVal = SettingsSchema.getValue(settingsJson, def.key)
                                        Text(
                                            text = if (displayVal.length > 15) displayVal.take(12) + "..." else displayVal,
                                            color = PurpleNeon,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Backup and Restore Section
        Text("Data Management & Backups", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Export or Restore settings JSON", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Enables backup, hand-editing configuration, or transferring profile to other devices.", color = TextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val path = onExportSettings()
                                if (path.isNotEmpty()) {
                                    Toast.makeText(context, "Settings exported successfully!", Toast.LENGTH_LONG).show()
                                    // Also show sharing log
                                    android.util.Log.d("ProfileScreen", "Backup stored at: $path")
                                } else {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleNeon, contentColor = PureBlack),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Listening Statistics
        DiagnosticLogsCard(
            entries = diagnosticEntries,
            onExport = onExportDiagnostics,
            onClearUnsaved = onClearUnsavedDiagnostics,
            onClearAll = onClearAllDiagnostics,
            onProtect = onProtectDiagnostic
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Listening Statistics
        Text("Analytics & Usage Metrics", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LiquidGlassStatWidget(
                    title = "Listening Time",
                    value = "${"%.1f".format(totalListeningTimeMs / (1000.0 * 3600))} hrs",
                    subtitle = "Hi-Res & Online",
                    icon = Icons.Default.HourglassBottom,
                    accentColor = CyanNeon
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                LiquidGlassStatWidget(
                    title = "Total Plays",
                    value = "$totalPlayCount",
                    subtitle = "Session counter",
                    icon = Icons.Default.Equalizer,
                    accentColor = PurpleNeon
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Professional About App & Developer Section
        Text("About FloWave & Developers", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(10.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(CyanNeon, PinkNeon))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = PureBlack)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.clickable {
                            versionClickCount++
                            if (versionClickCount >= 5 && !isAdvancedUnlocked) {
                                isAdvancedUnlocked = true
                                Toast.makeText(context, "Advanced developer options unlocked!", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text("FloWave Music Engine", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "v2.4 Pro Build • Open Source Core" + if (isAdvancedUnlocked) " [DEV MODE]" else "",
                            color = if (isAdvancedUnlocked) CyanNeon else TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "High-resolution audio player with native InnerTube YouTube extraction, FloWave client downloader, 10-band parametric EQ, synced LRC lyrics display, and Room offline database integration.",
                    color = TextPrimary.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GlassBorder)
                Spacer(modifier = Modifier.height(14.dp))

                Text("Core Developers & Creators", color = CyanNeon, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Developer 1: not_your_ritam
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .clickable { openInstagram("not_your_ritam") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Ritam", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("@not_your_ritam", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = { openInstagram("not_your_ritam") },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon.copy(alpha = 0.15f), contentColor = CyanNeon),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Instagram", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Developer 2: ritam.localhost
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .clickable { openInstagram("ritam.localhost") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = PurpleNeon, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Ritam Dev", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("@ritam.localhost", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = { openInstagram("ritam.localhost") },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleNeon.copy(alpha = 0.15f), contentColor = PurpleNeon),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Instagram", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Dynamic Choice Dialog selector
    val currentChoice = activeChoiceDef
    if (currentChoice != null) {
        val def = currentChoice
        val currentValue = SettingsSchema.getValue(settingsJson, def.key)
        AlertDialog(
            onDismissRequest = { activeChoiceDef = null },
            title = { Text("Select ${def.label}", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(def.description, color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    def.choices.forEach { choice ->
                        val isSelected = choice.value == currentValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) CyanNeon.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    onSettingUpdated(def.key, choice.value)
                                    if (def.key == "preferred_quality") {
                                        onQualitySelected(choice.value)
                                    }
                                    activeChoiceDef = null
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(choice.label, color = if (isSelected) CyanNeon else TextPrimary, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeChoiceDef = null }) {
                    Text("Cancel", color = CyanNeon)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Dynamic Custom String Input Dialog selector
    val currentString = activeStringDef
    if (currentString != null) {
        val def = currentString
        var textInput by remember { mutableStateOf(SettingsSchema.getValue(settingsJson, def.key)) }
        AlertDialog(
            onDismissRequest = { activeStringDef = null },
            title = { Text(def.label, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(def.description, color = TextMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSettingUpdated(def.key, textInput)
                        activeStringDef = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeStringDef = null }) {
                    Text("Cancel", color = CyanNeon)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Custom Hex Color Picker Dialog
    if (showCustomColorDialog) {
        var hexInput by remember { mutableStateOf(SettingsSchema.getValue(settingsJson, "custom_accent_color")) }
        val parsedColor = remember(hexInput) {
            try {
                val cleanHex = hexInput.trim().replace("#", "")
                if (cleanHex.length == 6) {
                    Color(android.graphics.Color.parseColor("#$cleanHex"))
                } else if (cleanHex.length == 8) {
                    Color(android.graphics.Color.parseColor("#$cleanHex"))
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
        AlertDialog(
            onDismissRequest = { showCustomColorDialog = false },
            title = { Text("Custom Accent Color", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter any custom color hex code (e.g. #FF1744 for pink-red, #00FFCC for teal).", color = TextMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { hexInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live Preview: ", color = TextPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsedColor ?: Color.Gray)
                                .border(1.dp, Color.White, CircleShape)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (parsedColor != null) {
                            onSettingUpdated("custom_accent_color", hexInput)
                            showCustomColorDialog = false
                            Toast.makeText(context, "Applied custom color $hexInput", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid hex format", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomColorDialog = false }) {
                    Text("Cancel", color = CyanNeon)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Dynamic Restore Settings Dialog
    if (showImportDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Restore Settings JSON", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste raw settings JSON to restore your preferences.", color = TextMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = rawJson,
                        onValueChange = { rawJson = it },
                        placeholder = { Text("""{"preferred_theme":"GLASS","list_density":"Comfortable"...}""", color = TextMuted, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val success = onImportSettings(rawJson)
                            if (success) {
                                Toast.makeText(context, "Settings restored successfully!", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            } else {
                                Toast.makeText(context, "Invalid backup configuration", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                ) {
                    Text("Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = CyanNeon)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun DiagnosticLogsCard(
    entries: List<DiagnosticEntry>,
    onExport: () -> Unit,
    onClearUnsaved: () -> Unit,
    onClearAll: () -> Unit,
    onProtect: (String, Boolean) -> Unit
) {
    var levelFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val categories = entries.map { it.category }.distinct().sorted()
    val filtered = entries.filter { entry ->
        (levelFilter == null || entry.level == levelFilter) &&
            (categoryFilter == null || entry.category == categoryFilter)
    }.takeLast(60).reversed()

    Text("Diagnostics & Logs", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Spacer(modifier = Modifier.height(8.dp))
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${entries.size} recent entries • ${entries.count { it.protected }} protected",
                color = TextMuted,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text("All") }
                )
                DiagnosticLevel.values().forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = { levelFilter = if (levelFilter == level) null else level },
                        label = { Text(level.name) }
                    )
                }
                Box {
                    FilterChip(
                        selected = categoryFilter != null,
                        onClick = { categoryMenuOpen = true },
                        label = { Text(categoryFilter ?: "Category") },
                        leadingIcon = { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(14.dp)) }
                    )
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All categories") },
                            onClick = { categoryFilter = null; categoryMenuOpen = false }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = { categoryFilter = category; categoryMenuOpen = false }
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export")
                }
                OutlinedButton(onClick = onClearUnsaved, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear unsaved")
                }
            }
            TextButton(onClick = { confirmClearAll = true }) {
                Text("Clear all logs", color = MaterialTheme.colorScheme.error)
            }
            if (filtered.isEmpty()) {
                Text("No diagnostic entries match this filter.", color = TextMuted, fontSize = 12.sp)
            } else {
                filtered.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (entry.protected) Icons.Default.Lock else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (entry.level == DiagnosticLevel.ERROR) MaterialTheme.colorScheme.error else CyanNeon,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${entry.level} • ${entry.category}/${entry.event}",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(entry.message.ifBlank { "—" }, color = TextMuted, fontSize = 11.sp, maxLines = 2)
                        }
                        IconButton(
                            onClick = { onProtect(entry.id, !entry.protected) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                if (entry.protected) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = if (entry.protected) "Unprotect" else "Protect",
                                tint = PurpleNeon,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all diagnostic logs?") },
            text = { Text("This permanently removes protected and unsaved entries.") },
            confirmButton = {
                Button(onClick = { confirmClearAll = false; onClearAll() }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }
}
