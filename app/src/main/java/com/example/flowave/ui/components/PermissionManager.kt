package com.example.flowave.ui.components

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flowave.ui.theme.CyanNeon
import com.example.flowave.ui.theme.DarkSurface
import com.example.flowave.ui.theme.PureBlack
import com.example.flowave.ui.theme.TextMuted
import com.example.flowave.ui.theme.TextPrimary
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionManager(
    onPermissionsGranted: () -> Unit = {}
) {
    val permissionsNeeded = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsNeeded)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            onPermissionsGranted()
        }
    }

    if (!permissionsState.allPermissionsGranted) {
        var dialogDismissed by remember { mutableStateOf(false) }

        if (!dialogDismissed) {
            AlertDialog(
                onDismissRequest = { dialogDismissed = true },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Permissions",
                        tint = CyanNeon,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Permissions Required",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "FloWave needs audio access to index and play songs stored on your device. Playback notifications are optional.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Audio Library Storage Access", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            permissionsState.launchMultiplePermissionRequest()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanNeon,
                            contentColor = PureBlack
                        ),
                        modifier = Modifier.testTag("grant_permissions_btn")
                    ) {
                        Text("Grant Permissions", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialogDismissed = true }) {
                        Text("Later", color = TextMuted)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}
