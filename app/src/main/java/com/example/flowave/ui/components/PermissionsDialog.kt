package com.example.flowave.ui.components

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.example.flowave.ui.theme.CyanNeon
import com.example.flowave.ui.theme.DarkSurface
import com.example.flowave.ui.theme.PureBlack
import com.example.flowave.ui.theme.TextMuted
import com.example.flowave.ui.theme.TextPrimary

@Composable
fun PermissionsDialog(
    onPermissionsGranted: () -> Unit
) {
    PermissionManager(onPermissionsGranted = onPermissionsGranted)
}
