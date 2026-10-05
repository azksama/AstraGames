package fr.astragames.app.ui

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.BuildConfig
import fr.astragames.app.updates.AppUpdateManager
import fr.astragames.app.updates.UPDATE_REPOSITORY
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateScreen(manager: AppUpdateManager, beforeExternal: () -> Unit, onDismiss: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val launchInstall: () -> Unit = {
        scope.launch {
            runCatching { manager.installerIntent() }.onSuccess { intent ->
                beforeExternal(); installer.launch(intent)
            }.onFailure { localError = it.message }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) launchInstall()
    }
    val requestInstall: () -> Unit = {
        if (context.packageManager.canRequestPackageInstalls()) launchInstall()
        else {
            beforeExternal()
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                CompactHeader("Mises à jour d’Astra", onBack = onDismiss)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MaterialText("Astra ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                    MaterialText(UPDATE_REPOSITORY, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Les mises à jour sont téléchargées depuis GitHub. Android demande votre confirmation avant l’installation.")
                    SettingsSwitch("Vérification automatique", state.automatic, { value -> scope.launch { manager.configure(value, state.autoDownload, state.wifiOnly) } })
                    SettingsSwitch("Téléchargement automatique", state.autoDownload, { value -> scope.launch { manager.configure(state.automatic, value, state.wifiOnly) } })
                    SettingsSwitch("Télécharger uniquement en Wi-Fi", state.wifiOnly, { value -> scope.launch { manager.configure(state.automatic, state.autoDownload, value) } })
                    state.release?.let { release ->
                        HorizontalDivider()
                        MaterialText("Astra ${release.version}", style = MaterialTheme.typography.titleLarge)
                        MaterialText("${release.bytes / (1024 * 1024)} MiB · ${release.assetName}", style = MaterialTheme.typography.bodySmall)
                        if (release.assetName.contains("debug", true)) Text("APK signé avec une clé de développement.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { uriHandler.openUri(release.pageUrl) }) { Text("Notes de version") }
                    }
                    if (state.busy) {
                        state.progress?.let { percent ->
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            MaterialText("$percent %")
                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { localError = null; scope.launch { manager.check() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Vérifier les mises à jour") }
                    if (state.release != null) Button(onClick = {
                        localError = null
                        if (state.ready) requestInstall()
                        else scope.launch {
                            manager.download()
                            if (manager.state.value.ready) requestInstall()
                        }
                    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.ready) "Installer la mise à jour" else "Télécharger et installer") }
                }
            }
        }
    }
}
