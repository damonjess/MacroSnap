package com.example.macrosnap

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal val BgDark = Color(0xFF0B0F17)
internal val CardDark = Color(0xFF151D2A)
internal val CardElevated = Color(0xFF1E293B)
internal val AccentEmerald = Color(0xFF10B981)
internal val AccentCyan = Color(0xFF06B6D4)
internal val ProteinBlue = Color(0xFF38BDF8)
internal val CarbsGreen = Color(0xFF34D399)
internal val FatAmber = Color(0xFFFBBF24)
internal val FiberPurple = Color(0xFFC084FC)
internal val WaterBlue = Color(0xFF0EA5E9)
internal val BurnOrange = Color(0xFFF97316)
internal val TextPrimary = Color(0xFFF8FAFC)
internal val TextSecondary = Color(0xFF94A3B8)

internal val MacroSnapColorScheme = darkColorScheme(
    primary = AccentEmerald,
    onPrimary = Color(0xFF042F2E),
    secondary = AccentCyan,
    background = BgDark,
    surface = CardDark,
    surfaceVariant = CardElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)
