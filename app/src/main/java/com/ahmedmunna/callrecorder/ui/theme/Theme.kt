package com.ahmedmunna.callrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahmedmunna.callrecorder.data.prefs.AccentTheme
import com.ahmedmunna.callrecorder.data.prefs.ThemeMode

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(mode: ThemeMode, accent: AccentTheme, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = Color(accent.argb)
    val scheme = if (dark) darkColorScheme(
        primary = lerp(base, Color.White, 0.35f),
        onPrimary = Color(0xFF101114),
        primaryContainer = lerp(base, Color.Black, 0.45f),
        onPrimaryContainer = Color.White,
        secondaryContainer = lerp(base, Color(0xFF1B1D22), 0.7f),
        onSecondaryContainer = Color.White,
        background = Color(0xFF0F1115),
        onBackground = Color(0xFFE8EAF0),
        surface = Color(0xFF16181D),
        onSurface = Color(0xFFE8EAF0),
        surfaceVariant = Color(0xFF22252C),
        onSurfaceVariant = Color(0xFFB4B8C4),
        error = Color(0xFFFF8A80),
    ) else lightColorScheme(
        primary = base,
        onPrimary = Color.White,
        primaryContainer = lerp(base, Color.White, 0.86f),
        onPrimaryContainer = lerp(base, Color.Black, 0.55f),
        secondaryContainer = lerp(base, Color.White, 0.88f),
        onSecondaryContainer = lerp(base, Color.Black, 0.5f),
        background = Color(0xFFF8F9FC),
        onBackground = Color(0xFF15171C),
        surface = Color.White,
        onSurface = Color(0xFF15171C),
        surfaceVariant = Color(0xFFEDEFF5),
        onSurfaceVariant = Color(0xFF545A68),
        error = Color(0xFFC62828),
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

object StatusColors {
    val ok = Color(0xFF2E9E6B)
    val warn = Color(0xFFE0922F)
    val bad = Color(0xFFD64545)
    val neutral = Color(0xFF8A8F9C)
}
