package com.duobao.proxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Scale of the whole UI relative to the system's dp / sp. */
const val UI_SCALE = 0.88f

val LocalDbpColors = staticCompositionLocalOf { dbpColors(dark = false) }

object DbpTheme {
    val colors: DbpColors
        @Composable @ReadOnlyComposable get() = LocalDbpColors.current
}

private val DbpShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DbpTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = remember(mode, systemDark) { dbpColors(mode.isDark(systemDark)) }
    val scheme = remember(colors) { colors.toColorScheme() }
    // Everything (dp and sp) at UI_SCALE of the system size: the design's sizes read large on phones.
    val d = LocalDensity.current
    val scaled = remember(d) { Density(d.density * UI_SCALE, d.fontScale) }
    CompositionLocalProvider(LocalDbpColors provides colors, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, shapes = DbpShapes, content = content)
    }
}

private fun DbpColors.toColorScheme(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = accentFg,
        primaryContainer = accentSoft,
        onPrimaryContainer = fg,
        secondary = fg2,
        onSecondary = panel,
        secondaryContainer = panel2,
        onSecondaryContainer = fg,
        tertiary = warn,
        background = bg,
        onBackground = fg,
        surface = panel,
        onSurface = fg,
        surfaceVariant = panel2,
        onSurfaceVariant = fg2,
        surfaceContainerLowest = panel,
        surfaceContainerLow = panel,
        surfaceContainer = panel,
        surfaceContainerHigh = panel2,
        surfaceContainerHighest = panel2,
        inverseSurface = fg,
        inverseOnSurface = panel,
        outline = line,
        outlineVariant = line,
        error = bad,
        onError = panel,
    )
}
