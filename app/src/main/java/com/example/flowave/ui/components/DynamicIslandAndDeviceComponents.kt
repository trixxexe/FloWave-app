package com.example.flowave.ui.components

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.data.model.Track
import com.example.flowave.ui.theme.*

data class SystemAudioDevice(
    val name: String,
    val typeName: String,
    val icon: ImageVector,
    val isBluetooth: Boolean,
    val isConnected: Boolean
)

fun getConnectedAudioDevices(context: Context): List<SystemAudioDevice> {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val devicesList = mutableListOf<SystemAudioDevice>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.forEach { dev ->
            val (name, icon, isBt) = when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val label = dev.productName.toString().ifEmpty { "Bluetooth Audio Device" }
                    Triple(label, Icons.Default.Bluetooth, true)
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Triple("Wired Headphones", Icons.Default.Headphones, false)
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> Triple("USB Audio DAC", Icons.Default.Usb, false)
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Triple("Phone Speaker (Stereo)", Icons.Default.VolumeUp, false)
                else -> Triple("Audio Output Device", Icons.Default.Speaker, false)
            }

            devicesList.add(
                SystemAudioDevice(
                    name = name,
                    typeName = dev.productName.toString().ifEmpty { "Audio Output" },
                    icon = icon,
                    isBluetooth = isBt,
                    isConnected = true
                )
            )
        }
    }

    if (devicesList.isEmpty()) {
        devicesList.add(SystemAudioDevice("Phone Speaker (Stereo)", "Built-in Speaker", Icons.Default.VolumeUp, false, true))
        devicesList.add(SystemAudioDevice("Bluetooth Headphones / Car", "Wireless Output", Icons.Default.Bluetooth, true, false))
    }

    return devicesList.distinctBy { it.name }
}

@Composable
fun DynamicAudioDeviceSelectorPill(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeviceDialog by remember { mutableStateOf(false) }
    val connectedDevices = remember { getConnectedAudioDevices(context) }
    val activeDevice = connectedDevices.firstOrNull { it.isConnected } ?: connectedDevices.first()

    // Roundish pill dynamically fetched from system
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Brush.horizontalGradient(listOf(CyanNeon.copy(alpha = 0.6f), PinkNeon.copy(alpha = 0.4f))), RoundedCornerShape(20.dp))
            .clickable { showDeviceDialog = true },
        color = DarkSurface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = activeDevice.icon,
                contentDescription = null,
                tint = CyanNeon,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = activeDevice.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }

    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CyanNeon)
                    Text("Connected Audio Routes", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dynamically detected system audio output devices:", color = TextMuted, fontSize = 12.sp)

                    connectedDevices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (dev == activeDevice) CyanNeon.copy(alpha = 0.15f) else DarkBackground)
                                .clickable {
                                    Toast.makeText(context, "Routing audio output to ${dev.name}", Toast.LENGTH_SHORT).show()
                                    showDeviceDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(CyanNeon.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(dev.icon, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                                }
                                Column {
                                    Text(dev.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(if (dev.isBluetooth) "Bluetooth A2DP High-Res" else "Direct System Bus", color = TextMuted, fontSize = 11.sp)
                                }
                            }

                            if (dev == activeDevice) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = CyanNeon,
                                    contentColor = PureBlack
                                ) {
                                    Text("ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Done", color = CyanNeon)
                }
            },
            containerColor = DarkSurface
        )
    }
}

/**
 * System Dynamic Island Floating Pill for active music playback
 */
@Composable
fun DynamicIslandWidget(
    currentTrack: Track?,
    isPlaying: Boolean,
    waveform: FloatArray,
    onExpandClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentTrack == null) return

    var isExpanded by remember { mutableStateOf(false) }

    // Live animated pulse for the Dynamic Island
    val transition = rememberInfiniteTransition(label = "island_pulse")
    val barScale by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bars"
    )

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(CyanNeon.copy(alpha = 0.8f), PinkNeon.copy(alpha = 0.6f))),
                        RoundedCornerShape(28.dp)
                    )
                    .clickable {
                        isExpanded = !isExpanded
                        onExpandClick()
                    },
                color = PureBlack.copy(alpha = 0.95f),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Album Artwork
                    AsyncImage(
                        model = currentTrack.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=200",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                    )

                    // Song Info
                    Column(
                        modifier = Modifier.widthIn(max = 140.dp)
                    ) {
                        Text(
                            text = currentTrack.title,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentTrack.artist,
                            color = CyanNeon,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Dynamic Live Waveform Animation in Pill
                    Canvas(
                        modifier = Modifier
                            .width(32.dp)
                            .height(16.dp)
                    ) {
                        val barWidth = 3.dp.toPx()
                        val space = 2.dp.toPx()
                        val count = 4
                        for (i in 0 until count) {
                            val factor = if (isPlaying) (0.3f + 0.7f * ((i + 1) * barScale % 1.0f)) else 0.2f
                            val h = size.height * factor
                            val x = i * (barWidth + space)
                            drawRoundRect(
                                color = if (i % 2 == 0) CyanNeon else PinkNeon,
                                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                                size = androidx.compose.ui.geometry.Size(barWidth, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                        }
                    }

                    // Quick Play/Pause
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
