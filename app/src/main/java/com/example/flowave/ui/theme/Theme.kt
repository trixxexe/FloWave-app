package com.example.flowave.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    secondary = BlueNeon,
    tertiary = PurpleNeon,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = PureBlack,
    onSecondary = PureBlack,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = TextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = BlueNeon,
    secondary = PurpleNeon,
    tertiary = CyanNeon,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A)
)

@Composable
fun FloWaveTheme(
    darkTheme: Boolean = true, // Default to AMOLED Dark Theme for audio aesthetic
    dynamicColor: Boolean = false, // Set to false to prioritize custom neon colors
    accentColor: Color? = null,
    fontScaleFactor: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (accentColor != null) {
        baseScheme.copy(primary = accentColor)
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val customDensity = androidx.compose.ui.unit.Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * fontScaleFactor
    )

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides customDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}
