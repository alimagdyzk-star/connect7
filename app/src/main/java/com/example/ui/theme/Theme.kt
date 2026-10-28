package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ApexDarkColorScheme = darkColorScheme(
    primary = BreezeCyan,
    onPrimary = Slate950,
    primaryContainer = BreezeCyanContainer,
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = BreezeTeal,
    onSecondary = Slate950,
    secondaryContainer = BreezeTealContainer,
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = AmberAccent,
    onTertiary = Slate950,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    surfaceContainerLowest = Color(0xFF060A13),
    surfaceContainerLow = Color(0xFF0D1424),
    surfaceContainer = Slate900,
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,
    outline = Slate600,
    outlineVariant = Slate700,
    error = RoseError,
    onError = Slate950,
    errorContainer = RoseErrorContainer,
    onErrorContainer = Color(0xFFFFE4E6)
)

private val ApexLightColorScheme = lightColorScheme(
    primary = BreezeCyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = BreezeTealDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate600,
    outline = Slate400,
    outlineVariant = Slate200,
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337)
)

val ApexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to signature KDE Breeze Midnight Dark aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme || isSystemInDarkTheme()) {
        ApexDarkColorScheme
    } else {
        ApexLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ApexShapes,
        content = content
    )
}
