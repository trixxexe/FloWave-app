package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.theme.*

@Composable
fun EqualizerScreen(
    audioEngine: FloWaveAudioEngine,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val eqState by audioEngine.equalizerState.collectAsState()
    val scrollState = rememberScrollState()

    val presets = listOf("Flat", "Bass Boost", "Electronic", "Rock", "Pop", "Jazz", "Vocal")
    var selectedPreset by remember { mutableStateOf("Flat") }

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
            IconButton(onClick = onBackClick, modifier = Modifier.testTag("eq_back_btn")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CyanNeon)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DSP Hardware Equalizer", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Preset Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(presets.size) { index ->
                val name = presets[index]
                FilterChip(
                    selected = selectedPreset == name,
                    onClick = {
                        selectedPreset = name
                        applyPreset(name, audioEngine)
                    },
                    label = { Text(name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanNeon,
                        selectedLabelColor = PureBlack
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 10-Band Equalizer Sliders
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "10-Band Parametric Equalizer",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    eqState.bandFrequenciesHz.forEachIndexed { bandIdx, freq ->
                        val currentMb = eqState.bandLevelsMs.getOrElse(bandIdx) { 0 }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "${currentMb / 100}dB",
                                color = CyanNeon,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Slider(
                                value = currentMb.toFloat(),
                                onValueChange = { newValue ->
                                    audioEngine.setEqualizerBandLevel(bandIdx, newValue.toInt().toShort())
                                },
                                valueRange = -1500f..1500f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp)
                            )

                            Text(
                                text = formatFreq(freq),
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // DSP Effects Sliders (Bass Boost, Virtualizer, Loudness Enhancer)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("DSP Effects & Spatial Audio", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                // Preamp Boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Preamp Gain Boost", color = TextSecondary, fontSize = 14.sp)
                        Text("${"%.1f".format(eqState.preampDb)} dB", color = CyanNeon, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = eqState.preampDb,
                        onValueChange = { audioEngine.setPreampDb(it) },
                        valueRange = -10f..10f,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )
                }

                // Stereo Balance
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stereo Balance", color = TextSecondary, fontSize = 14.sp)
                        Text(
                            text = when {
                                eqState.stereoBalance < -0.1f -> "Left (${"%.1f".format(-eqState.stereoBalance)})"
                                eqState.stereoBalance > 0.1f -> "Right (${"%.1f".format(eqState.stereoBalance)})"
                                else -> "Center"
                            },
                            color = CyanNeon, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = eqState.stereoBalance,
                        onValueChange = { audioEngine.setStereoBalance(it) },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )
                }

                // Reverb Presets
                Column {
                    Text("Acoustic Reverb Engine", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val reverbs = listOf(
                            "None" to android.media.audiofx.PresetReverb.PRESET_NONE,
                            "Small Room" to android.media.audiofx.PresetReverb.PRESET_SMALLROOM,
                            "Medium Room" to android.media.audiofx.PresetReverb.PRESET_MEDIUMROOM,
                            "Large Room" to android.media.audiofx.PresetReverb.PRESET_LARGEROOM,
                            "Medium Hall" to android.media.audiofx.PresetReverb.PRESET_MEDIUMHALL,
                            "Large Hall" to android.media.audiofx.PresetReverb.PRESET_LARGEHALL,
                            "Plate" to android.media.audiofx.PresetReverb.PRESET_PLATE
                        )
                        items(reverbs.size) { idx ->
                            val item = reverbs[idx]
                            FilterChip(
                                selected = eqState.presetReverbName == item.first,
                                onClick = { audioEngine.setPresetReverbName(item.first, item.second) },
                                label = { Text(item.first, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanNeon, selectedLabelColor = PureBlack)
                            )
                        }
                    }
                }

                // Bass Boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Bass Boost", color = TextSecondary, fontSize = 14.sp)
                        Text("${(eqState.bassBoostStrength / 10)}%", color = CyanNeon, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = eqState.bassBoostStrength.toFloat(),
                        onValueChange = { audioEngine.setBassBoost(it.toInt().toShort()) },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )
                }

                // Virtualizer
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("3D Spatial Virtualizer", color = TextSecondary, fontSize = 14.sp)
                        Text("${(eqState.virtualizerStrength / 10)}%", color = PurpleNeon, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = eqState.virtualizerStrength.toFloat(),
                        onValueChange = { audioEngine.setVirtualizer(it.toInt().toShort()) },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = PurpleNeon, activeTrackColor = PurpleNeon)
                    )
                }

                // Loudness Enhancer
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Loudness Enhancer (Gain)", color = TextSecondary, fontSize = 14.sp)
                        Text("${"%.1f".format(eqState.loudnessEnhancerGainDb)} dB", color = PinkNeon, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = eqState.loudnessEnhancerGainDb,
                        onValueChange = { audioEngine.setLoudnessEnhancerGain((it * 100).toInt()) },
                        valueRange = 0f..12f,
                        colors = SliderDefaults.colors(thumbColor = PinkNeon, activeTrackColor = PinkNeon)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Hardware Output Toggles
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Audiophile Hardware Output", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Bit-Perfect USB DAC Direct", color = TextPrimary, fontSize = 14.sp)
                        Text("Bypass OS resampling for external DACs", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = eqState.isBitPerfect,
                        onCheckedChange = { audioEngine.toggleBitPerfect(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = DarkSurface)
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0x22FFFFFF))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Mono Audio Mode", color = TextPrimary, fontSize = 14.sp)
                        Text("Combine stereo channels into single mono mix", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = eqState.isMono,
                        onCheckedChange = { audioEngine.toggleMono(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = DarkSurface)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

private fun formatFreq(hz: Int): String {
    return if (hz >= 1000) "${hz / 1000}k" else "${hz}Hz"
}

private fun applyPreset(presetName: String, audioEngine: FloWaveAudioEngine) {
    val gains = when (presetName) {
        "Bass Boost" -> listOf<Short>(600, 500, 300, 100, 0, 0, 0, 0, 0, 0)
        "Electronic" -> listOf<Short>(400, 300, 100, 0, -100, 200, 400, 500, 400, 300)
        "Rock" -> listOf<Short>(500, 300, -100, -200, 0, 200, 400, 500, 500, 400)
        "Pop" -> listOf<Short>(-100, 200, 400, 500, 300, 0, -100, -100, 200, 300)
        "Jazz" -> listOf<Short>(300, 200, 100, 200, -100, -100, 0, 200, 300, 400)
        "Vocal" -> listOf<Short>(-300, -200, 100, 300, 500, 400, 300, 100, -100, -200)
        else -> listOf<Short>(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    gains.forEachIndexed { i, g ->
        audioEngine.setEqualizerBandLevel(i, g)
    }
}
