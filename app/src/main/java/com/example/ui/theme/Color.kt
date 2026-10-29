package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Apple Human Interface Guidelines (iOS 18 / macOS Sequoia) System Colors

// Light Mode Grouped Surfaces & Backgrounds
val AppleGroupedBgLight = Color(0xFFF2F2F7)      // systemGroupedBackground
val AppleSecondaryBgLight = Color(0xFFFFFFFF)    // secondarySystemGroupedBackground (pure white cards)
val AppleTertiaryBgLight = Color(0xFFE5E5EA)     // tertiarySystemFill / segmented track
val AppleLabelPrimaryLight = Color(0xFF1C1C1E)
val AppleLabelSecondaryLight = Color(0xFF8E8E93)
val AppleSeparatorLight = Color(0xFFD1D1D6)

// Dark Mode Elevated Grouped Surfaces
val AppleGroupedBgDark = Color(0xFF000000)       // pure OLED black grouped background
val AppleSecondaryBgDark = Color(0xFF1C1C1E)     // elevated card surface
val AppleTertiaryBgDark = Color(0xFF2C2C2E)      // secondary elevated / pill track
val AppleLabelPrimaryDark = Color(0xFFFFFFFF)
val AppleLabelSecondaryDark = Color(0xFF8E8E93)
val AppleSeparatorDark = Color(0xFF38383A)

// Signature Apple System Tints
val AppleBlue = Color(0xFF007AFF)
val AppleBlueDark = Color(0xFF0A84FF)
val AppleGreen = Color(0xFF34C759)
val AppleIndigo = Color(0xFF5856D6)
val AppleOrange = Color(0xFFFF9500)
val ApplePink = Color(0xFFFF2D55)
val AppleRed = Color(0xFFFF3B30)
val AppleTeal = Color(0xFF30B0C7)
val AppleYellow = Color(0xFFFFCC00)

// Backward-compatible semantic aliases used across screens
val BreezeCyan = AppleBlue
val BreezeTeal = AppleTeal
val AmberAccent = AppleOrange
val EmeraldSuccess = AppleGreen
val RoseError = AppleRed
