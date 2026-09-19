package fr.astragames.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AstraColors, typography = AstraTypography, content = content)
}
