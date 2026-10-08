package fr.astragames.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.astragames.app.windows.WolfDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun WolfDiagnosticsDialog(initialReport: File? = null, recovered: Boolean = false, beforeExternal: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = remember(context) { WolfDiagnostics(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(diagnostics.enabled) }
    var reports by remember { mutableStateOf(emptyList<File>()) }
    var selected by remember { mutableStateOf(initialReport) }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        reports = withContext(Dispatchers.IO) { diagnostics.reports() }
        if (selected == null) selected = reports.firstOrNull()
    }
    LaunchedEffect(selected) {
        content = ""
        selected?.let { file ->
            runCatching { withContext(Dispatchers.IO) { diagnostics.report(file) } }
                .onSuccess { content = it }.onFailure { error = it.message }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                CompactHeader("Diagnostic Wolf RPG", onBack = onDismiss)
                if (recovered) Text("Le lancement Wolf précédent a été interrompu. Voici le rapport conservé.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                SettingsSwitch("Mode debug Wolf RPG", enabled, { value ->
                    scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.IO) { diagnostics.enabled = value } }
                            .onSuccess { enabled = value }.onFailure { error = it.message }
                        busy = false
                    }
                }, !busy)
                Text("Activez ce mode puis relancez le jeu. Les détails de Box64, Wine et Android seront conservés après un crash.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Text("Les 5 derniers rapports restent sur cet appareil. Ils peuvent contenir le nom du jeu et des chemins de fichiers. Rien n’est envoyé automatiquement.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                if (reports.isEmpty()) Text("Aucun rapport. Lancez un jeu Wolf RPG pour en créer un.", Modifier.padding(16.dp))
                else {
                    var expanded by remember { mutableStateOf(false) }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { expanded = true }) {
                            MaterialText(selected?.name?.substringBefore('-')?.toLongOrNull()?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Rapport")
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            reports.forEach { file -> DropdownMenuItem(text = {
                                MaterialText(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.name.substringBefore('-').toLong())))
                            }, onClick = { selected = file; expanded = false }) }
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        TextButton(enabled = selected != null && !busy && content.isNotEmpty(), onClick = {
                            val file = selected ?: return@TextButton
                            scope.launch {
                                busy = true
                                runCatching {
                                    val intent = withContext(Dispatchers.IO) { diagnostics.shareIntent(file) }
                                    beforeExternal(); context.startActivity(Intent.createChooser(intent, AppLocalizer.text("Partager le rapport")))
                                }.onFailure { error = it.message }
                                busy = false
                            }
                        }) { Text("Partager le rapport") }
                    }
                    SelectionContainer(Modifier.heightIn(min = 220.dp, max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                        // Display a bounded tail; the shared report includes all retained streams.
                        MaterialText(if (content.length > 48_000) content.take(4000) + "\n[… Aperçu abrégé ; partagez le rapport complet …]\n" + content.takeLast(44_000) else content,
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { MaterialText(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
