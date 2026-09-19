package fr.astragames.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import fr.astragames.app.core.model.ThemeMode

private val AstraLightColors = lightColorScheme(
    primary = Color(0xFF006581),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDE9FF),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF4D616B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E6F0),
    onSecondaryContainer = Color(0xFF0A2029),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFF7FAFC),
    surfaceVariant = Color(0xFFDCE4E8),
    onSurfaceVariant = Color(0xFF405159),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5F8),
    surfaceContainer = Color(0xFFEAF0F4),
    surfaceContainerHigh = Color(0xFFE4EBF0),
    surfaceContainerHighest = Color(0xFFDEE5EA),
    outline = Color(0xFF707E86),
    outlineVariant = Color(0xFFBECAD1),
    onSurface = Color(0xFF111D22)
)

private val AstraDarkColors = darkColorScheme(
    primary = Color(0xFF61D6FF),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D63),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFB5CAD4),
    onSecondary = Color(0xFF20353F),
    secondaryContainer = Color(0xFF354B55),
    onSecondaryContainer = Color(0xFFD0E6F0),
    background = Color(0xFF091216),
    surface = Color(0xFF091216),
    surfaceVariant = Color(0xFF253238),
    onSurfaceVariant = Color(0xFFBDC9D0),
    surfaceContainerLowest = Color(0xFF050D11),
    surfaceContainerLow = Color(0xFF111D22),
    surfaceContainer = Color(0xFF152228),
    surfaceContainerHigh = Color(0xFF202D33),
    surfaceContainerHighest = Color(0xFF2B383E),
    outline = Color(0xFF87969F),
    outlineVariant = Color(0xFF3E4E57),
    onSurface = Color(0xFFDDE4E8)
)

@Composable
fun AstraTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> AstraDarkColors
        else -> AstraLightColors
    }
    MaterialTheme(colorScheme = colors, typography = AstraTypography, content = content)
}
