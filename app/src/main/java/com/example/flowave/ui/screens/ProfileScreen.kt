package com.example.flowave.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flowave.data.model.UserProfile
import com.example.flowave.ui.components.*
import com.example.flowave.ui.theme.*

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var usernameInput by remember { mutableStateOf(userProfile.username) }
    var bioInput by remember { mutableStateOf(userProfile.bio) }
    var customUrlInput by remember { mutableStateOf(userProfile.customBgUrl ?: "") }
    var isEditing by remember { mutableStateOf(false) }

    var normEnabled by remember { mutableStateOf(true) }
    var gaplessEnabled by remember { mutableStateOf(true) }

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

        // Widget 3: Liquid Glass Profile Card
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

        // Preferred Quality Liquid Glass Widget
        Text("Audio Processing & Output Stream", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        LiquidGlassQualitySelectorWidget(
            selectedQuality = userProfile.preferredQuality,
            onQualitySelected = { quality ->
                onQualitySelected(quality)
                Toast.makeText(context, "Streaming quality: $quality", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Volume Loudness Normalization", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("EBU R128 gain matching for smooth playback", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = normEnabled,
                        onCheckedChange = {
                            normEnabled = it
                            onAudioSettingsChanged(normEnabled, gaplessEnabled)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Gapless Track Transition", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Zero silent pause between album tracks", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = gaplessEnabled,
                        onCheckedChange = {
                            gaplessEnabled = it
                            onAudioSettingsChanged(normEnabled, gaplessEnabled)
                        }
                    )
                }
            }
        }

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
                    Column {
                        Text("FloWave Music Engine", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("v2.4 Pro Build • Open Source Core", color = TextMuted, fontSize = 12.sp)
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
}

