package com.zltm90plus.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = Color(0xFF00251A),
    secondary = UnknownGrey,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF14181A),
    surface = Color.White,
    onSurface = Color(0xFF14181A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF3E4A4D),
    error = DangerRed,
    onError = Color.White,
    outline = Color(0xFFB7C2C5),
)

private val DarkColors = darkColorScheme(
    primary = TealPrimaryLight,
    onPrimary = Color(0xFF00332B),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = UnknownGreyLight,
    onSecondary = Color(0xFF0B1416),
    background = DarkBackground,
    onBackground = Color(0xFFE1E6E7),
    surface = Color(0xFF182022),
    onSurface = Color(0xFFE1E6E7),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFBDC9CB),
    error = DangerRedLight,
    onError = Color(0xFF3B0907),
    outline = Color(0xFF5A6A6D),
)

/** Status colours resolved per theme so contrast stays valid in light and dark. */
data class StatusColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val unknown: Color,
)

@Composable
fun statusColors(): StatusColors = if (isSystemInDarkTheme()) {
    StatusColors(SuccessGreenLight, WarningAmberLight, DangerRedLight, UnknownGreyLight)
} else {
    StatusColors(SuccessGreen, WarningAmber, DangerRed, UnknownGrey)
}

@Composable
fun ZltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    LocalContext.current

    MaterialTheme(colorScheme = colors, content = content)
}