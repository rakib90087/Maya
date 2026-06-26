package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EspressoPrimary,
    secondary = EspressoSecondary,
    tertiary = AquaGreen,
    background = EspressoBg,
    surface = EspressoSurface,
    onPrimary = CharcoalText,
    onSecondary = CharcoalText,
    onTertiary = CharcoalText,
    onBackground = LatteText,
    onSurface = LatteText,
    surfaceVariant = EspressoSurface,
    onSurfaceVariant = LatteText
)

private val LightColorScheme = lightColorScheme(
    primary = CreamPrimary,
    secondary = CreamSecondary,
    tertiary = AquaGreen,
    background = CreamBg,
    surface = CreamSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = CharcoalText,
    onSurface = CharcoalText,
    surfaceVariant = SoftCreamVariant,
    onSurfaceVariant = CharcoalText
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Set to false to keep our gorgeous custom branding consistent
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme // Default to light/cream theme for Maya's cozy, warm feeling!
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
