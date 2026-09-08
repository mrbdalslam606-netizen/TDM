package com.tdm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Minimal, restrained palette (spec §76: "لا تستخدم ألوانًا كثيرة").
 * One blue accent, neutral greys, proper dark/light theme.
 */
private val Blue = Color(0xFF1A73C9)
private val BlueDark = Color(0xFF8AB4F8)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFB26A00)

val StatusGreen = Green
val StatusRed = Red
val StatusAmber = Amber
val Accent = Blue
val AccentDark = BlueDark

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),
    surface = Color(0xFFFAFAFA),
    background = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFFECEFF1),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF062E5C),
    secondary = Color(0xFF90A4AE),
    surface = Color(0xFF1C1B1F),
    background = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2930),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

@Composable
fun TdmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
