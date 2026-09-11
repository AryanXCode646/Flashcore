package com.ashishsinghbora.flashcore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

val FlashCoreColorScheme = darkColorScheme(
    primary = ElegantBlueLight,
    onPrimary = TextHeadings,
    primaryContainer = ElegantBluePrimary,
    onPrimaryContainer = TextHeadings,
    secondary = ElegantEmeraldLight,
    onSecondary = ElegantDarkBackground,
    secondaryContainer = ElegantEmeraldBg,
    onSecondaryContainer = ElegantEmeraldLight,
    tertiary = ElegantBlueAccent,
    onTertiary = ElegantDarkBackground,
    background = ElegantDarkBackground,
    onBackground = TextPrimary,
    surface = ElegantDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElegantDarkSurface,
    onSurfaceVariant = TextSecondary,
    outline = ElegantDarkBorder,
    outlineVariant = ElegantDarkBorderSubtle,
    error = ElegantCoral,
    onError = TextHeadings
)

@Composable
fun FlashCoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FlashCoreColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    FlashCoreTheme(darkTheme, dynamicColor, content)
}


