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
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFF7FAFC),
    surfaceVariant = Color(0xFFDCE4E8),
    onSurface = Color(0xFF111D22)
)

private val AstraDarkColors = darkColorScheme(
    primary = Color(0xFF61D6FF),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D63),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFB5CAD4),
    background = Color(0xFF091216),
    surface = Color(0xFF091216),
    surfaceVariant = Color(0xFF253238),
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
