package fr.astragames.app.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/** A missing/disabled browser is a recoverable UI failure, including inside modal dialogs. */
internal class SafeExternalUriHandler(
    private val delegate: UriHandler,
    private val onUnavailable: () -> Unit
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            delegate.openUri(uri)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        } catch (_: IllegalArgumentException) {
            // Compose wraps ActivityNotFoundException on Android in IllegalArgumentException.
            onUnavailable()
        } catch (_: SecurityException) {
            onUnavailable()
        }
    }
}

@Composable
internal fun rememberExternalUriHandler(): UriHandler {
    val delegate = LocalUriHandler.current
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    return remember(delegate, context, language) {
        SafeExternalUriHandler(delegate) {
            Toast.makeText(context, AppLocalizer.text(
                "Impossible d’ouvrir ce lien. Vérifiez le lien et qu’un navigateur est disponible.", language),
                Toast.LENGTH_LONG).show()
        }
    }
}
