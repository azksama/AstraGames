package fr.astragames.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

internal val AstraBackground = Color(0xFF09090F)
internal val AstraSurface = Color(0xFF191E29)
internal val AstraAction = Color(0xFF302147)
internal val AstraCaption = Color(0xFFC9B7FF)

private val AstraColors = darkColorScheme(
    primary = AstraCaption, onPrimary = AstraBackground,
    primaryContainer = AstraAction, onPrimaryContainer = Color.White,
    secondary = AstraCaption, onSecondary = AstraBackground,
    secondaryContainer = AstraAction, onSecondaryContainer = Color.White,
    tertiary = AstraCaption, onTertiary = AstraBackground,
    tertiaryContainer = AstraAction, onTertiaryContainer = Color.White,
    background = AstraBackground, onBackground = Color.White,
    surface = AstraBackground, onSurface = Color.White,
    surfaceVariant = AstraSurface, onSurfaceVariant = AstraCaption,
    surfaceContainerLowest = AstraBackground,
    surfaceContainerLow = AstraSurface, surfaceContainer = AstraSurface,
    surfaceContainerHigh = AstraSurface, surfaceContainerHighest = AstraSurface,
    surfaceTint = Color.Transparent,
    outline = Color(0xFF687083), outlineVariant = Color(0xFF2B3242)
)

@Composable
fun AstraTheme(hue: Int = 255, content: @Composable () -> Unit) {
    val colors = remember(hue) { astraColors(hue) }
    MaterialTheme(colorScheme = colors, typography = AstraTypography, content = content)
}

internal fun astraColors(hue: Int): androidx.compose.material3.ColorScheme {
    val shift = hue.coerceIn(0, 359) - 255f
    if (shift == 0f) return AstraColors
    fun tint(color: Color): Color {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        if (delta == 0f) return color
        val base = when (max) {
            color.red -> 60f * ((color.green - color.blue) / delta % 6f)
            color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }
        return Color.hsv(((base + shift) % 360f + 360f) % 360f, delta / max, max, color.alpha)
    }
    return AstraColors.copy(
        primary = tint(AstraColors.primary), onPrimary = tint(AstraColors.onPrimary),
        primaryContainer = tint(AstraColors.primaryContainer),
        secondary = tint(AstraColors.secondary), onSecondary = tint(AstraColors.onSecondary),
        secondaryContainer = tint(AstraColors.secondaryContainer),
        tertiary = tint(AstraColors.tertiary), onTertiary = tint(AstraColors.onTertiary),
        tertiaryContainer = tint(AstraColors.tertiaryContainer),
        background = tint(AstraColors.background), surface = tint(AstraColors.surface),
        surfaceVariant = tint(AstraColors.surfaceVariant), onSurfaceVariant = tint(AstraColors.onSurfaceVariant),
        surfaceContainerLowest = tint(AstraColors.surfaceContainerLowest), surfaceContainerLow = tint(AstraColors.surfaceContainerLow),
        surfaceContainer = tint(AstraColors.surfaceContainer), surfaceContainerHigh = tint(AstraColors.surfaceContainerHigh),
        surfaceContainerHighest = tint(AstraColors.surfaceContainerHighest),
        outline = tint(AstraColors.outline), outlineVariant = tint(AstraColors.outlineVariant)
    )
}
