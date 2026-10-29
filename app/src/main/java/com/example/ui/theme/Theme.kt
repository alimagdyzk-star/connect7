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

private val AppleLightColorScheme = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF0040DD),
    secondary = AppleIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEFF),
    onSecondaryContainer = Color(0xFF3634A3),
    tertiary = AppleOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF4E5),
    onTertiaryContainer = Color(0xFF995700),
    background = AppleGroupedBgLight,
    onBackground = AppleLabelPrimaryLight,
    surface = AppleSecondaryBgLight,
    onSurface = AppleLabelPrimaryLight,
    surfaceVariant = AppleTertiaryBgLight,
    onSurfaceVariant = AppleLabelSecondaryLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9FB),
    surfaceContainer = AppleSecondaryBgLight,
    surfaceContainerHigh = Color(0xFFEBEBF0),
    surfaceContainerHighest = AppleTertiaryBgLight,
    outline = AppleLabelSecondaryLight,
    outlineVariant = AppleSeparatorLight,
    error = AppleRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEA),
    onErrorContainer = Color(0xFF990900)
)

private val AppleDarkColorScheme = darkColorScheme(
    primary = AppleBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A2A4A),
    onPrimaryContainer = Color(0xFF84C2FF),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF24234D),
    onSecondaryContainer = Color(0xFFC7C6FF),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3D2800),
    onTertiaryContainer = Color(0xFFFFD48A),
    background = AppleGroupedBgDark,
    onBackground = AppleLabelPrimaryDark,
    surface = AppleSecondaryBgDark,
    onSurface = AppleLabelPrimaryDark,
    surfaceVariant = AppleTertiaryBgDark,
    onSurfaceVariant = AppleLabelSecondaryDark,
    surfaceContainerLowest = Color(0xFF0D0D0F),
    surfaceContainerLow = AppleSecondaryBgDark,
    surfaceContainer = AppleSecondaryBgDark,
    surfaceContainerHigh = AppleTertiaryBgDark,
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = AppleLabelSecondaryDark,
    outlineVariant = AppleSeparatorDark,
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF421111),
    onErrorContainer = Color(0xFFFFB3AE)
)

// Apple Continuous Squircle-inspired Radii
val ApexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AppleDarkColorScheme else AppleLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ApexShapes,
        content = content
    )
}
