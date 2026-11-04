package fr.astragames.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Keep a full-screen dialog's system icons legible against its own Astra background. */
@Composable
internal fun AstraDialogSystemBars() {
    val view = LocalView.current
    val darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    if (view.isInEditMode) return
    SideEffect {
        // A Compose Dialog owns a separate window. Never fall back to the Activity:
        // MainActivity already configures its bars, and changing it leaves this bug intact.
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
