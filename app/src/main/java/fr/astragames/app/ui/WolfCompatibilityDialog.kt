package fr.astragames.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.app.wolfnative.*
import fr.astragames.wolf.WolfAuditVerdict
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Discreet entry in Astra's game sheet, never an in-game control. */
@Composable
internal fun WolfCompatibilityEntry(value: String, onAnalyze: () -> Unit, modifier: Modifier = Modifier) {
    SummaryStat("Moteur", value, modifier.heightIn(min = 48.dp).testTag("wolf-compatibility-entry")
        .combinedClickable(onClick = {}, onLongClickLabel = AppLocalizer.text("Analyser la compatibilité Wolf"), onLongClick = onAnalyze))
}

@Composable
internal fun WolfRuntimePicker(gameId: String) {
    val context = LocalContext.current
    val store = remember(context, gameId) { WolfGameOptions(context, gameId) }
    var selected by remember(gameId) { mutableStateOf(store.read().runtime) }
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text("Moteur de lancement", style = MaterialTheme.typography.titleSmall)
        WolfRuntimeMode.entries.forEach { mode ->
            val label = when (mode) { WolfRuntimeMode.AUTO -> "Automatique"; WolfRuntimeMode.NATIVE -> "Natif Android"; WolfRuntimeMode.WINLATOR -> "Winlator" }
            val detail = when (mode) {
                WolfRuntimeMode.AUTO -> "Natif si compatible. Conserve Winlator si une session Windows existe déjà."
                WolfRuntimeMode.NATIVE -> "Moteur expérimental. Une fonction incompatible interrompt le lancement avec un rapport."
                WolfRuntimeMode.WINLATOR -> "Moteur Windows avec Wine et Box64."
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("wolf-runtime-${mode.name}")
                .selectable(selected == mode, role = Role.RadioButton, onClick = { store.runtime(mode); selected = mode })
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == mode, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(label); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Choix enregistré pour ce jeu. Les sauvegardes natives et Windows restent séparées.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WolfCompatibilityDialog(game: GameEntity, controller: WolfCompatibilityController,
    beforeExternal: () -> Unit = {}, onDismiss: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = if (observed.gameId == game.id) observed else WolfAuditState(game.id)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(game.id) { mutableIntStateOf(0) }
    var query by rememberSaveable(game.id) { mutableStateOf("") }
    var json by rememberSaveable(game.id) { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<WolfAuditRecord, Boolean>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val pending = pendingExport; pendingExport = null
        if (uri != null && pending != null) scope.launch {
            exporting = true
            try { controller.export(pending.first, uri, pending.second) }
            catch (error: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); exportError = error.message }
            finally { exporting = false }
        }
    }
    LaunchedEffect(game.id) { controller.open(game.id) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 920.dp)) {
                CompactHeader("Analyse de compatibilité Wolf", game.title, onBack = onDismiss, localizeSubtitle = false)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text("Analyse du moteur natif depuis Astra, sans lancer le jeu. Le dossier d’origine et les sauvegardes sont conservés.") }
                    if (state.running) item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(state.progress.stage, style = MaterialTheme.typography.titleMedium)
                                if (state.progress.totalMaps > 0) {
                                    LinearProgressIndicator(progress = { state.progress.completedMaps.toFloat() / state.progress.totalMaps }, modifier = Modifier.fillMaxWidth())
                                    Text("${state.progress.completedMaps} / ${state.progress.totalMaps} cartes · ${state.progress.commands} commandes")
                                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.progress.currentFile.isNotBlank()) MaterialText(state.progress.currentFile, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.cancelled) item { Text("Analyse annulée. Le dernier rapport enregistré est conservé.") }
                    state.error?.let { error -> item { MaterialText(error, color = MaterialTheme.colorScheme.error) } }
                    exportError?.let { error -> item { MaterialText(error, color = MaterialTheme.colorScheme.error) } }
                    val record = state.report
                    if (record == null && !state.running) item {
                        Text("Le scan inspecte les réglages, les bases de données, les événements communs et toutes les pages des cartes déclarées, même inactives.")
                        Spacer(Modifier.height(10.dp))
                        Text("Le rapport reste sur cet appareil. Vous pourrez l’enregistrer ou le partager au format texte ou JSON.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (record != null) {
                        val report = record.audit
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(report.verdictLabel, style = MaterialTheme.typography.titleMedium,
                                        color = if (report.verdict == WolfAuditVerdict.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    MaterialText("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.createdAt))} · Astra ${record.astraVersion}", style = MaterialTheme.typography.bodySmall)
                                    Text("${report.visitedMaps}/${report.totalMaps} cartes · ${report.commonEvents} événements communs · ${report.pages} pages")
                                    Text("${report.commands} commandes · ${report.findings.size} incompatibilités distinctes")
                                    Text("${report.features.count { it.runtimeChecks > 0 }} fonctions nécessitent une vérification à l’exécution")
                                    Text("Un contrôle statique ne garantit ni une partie complète, ni le décodage des médias, ni les FPS.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Bilan", "Fonctions", "Blocages").forEachIndexed { index, label ->
                                    FilterChip(tab == index, onClick = { tab = index; query = "" }, label = { Text(label) })
                                }
                            }
                        }
                        if (tab == 0) item {
                            Text("Profil Wolf : ${report.declaredEngineVersion ?: "inconnu"} · ${report.width}×${report.height} · ${report.fps} FPS configurés")
                            Spacer(Modifier.height(10.dp))
                            Text("${report.readableMaps} cartes lisibles. Durée : ${report.elapsedMs} ms.")
                            if (!report.complete) Text("Couverture incomplète : carte illisible ou inventaire limité. Aucun bilan compatible ne peut être établi.", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(10.dp))
                            Text("Les chemins calculés, valeurs dynamiques et services Android doivent être vérifiés pendant le jeu. Les cartes non déclarées dans SysDB sont hors du périmètre.")
                            Spacer(Modifier.height(10.dp))
                            Text("Relancez l’analyse après une mise à jour d’Astra ou du jeu : le rapport enregistré décrit la version indiquée ci-dessus.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (tab != 0) item {
                            OutlinedTextField(query, { query = it.take(120) }, label = { Text("Filtrer par fonction, code ou fichier") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        if (tab == 1) {
                            val features = report.features.filter { it.label.contains(query, true) || it.key.contains(query, true) }
                            if (features.isEmpty()) item { Text("Aucune fonction ne correspond au filtre.") }
                            items(features, key = { it.key }) { feature ->
                                ListItem(headlineContent = { MaterialText(feature.label) },
                                    supportingContent = { Text("${feature.occurrences} utilisations · ${feature.blocked} bloquées · ${feature.runtimeChecks} à vérifier à l’exécution") },
                                    leadingContent = { Icon(if (feature.blocked > 0) Icons.Default.ErrorOutline else if (feature.runtimeChecks > 0) Icons.Default.Info else Icons.Default.CheckCircle, null) })
                            }
                        }
                        if (tab == 2) {
                            val findings = report.findings.filter { it.issue.reason.contains(query, true) || it.issue.file.contains(query, true) || it.issue.opcode.toString().contains(query) }
                            if (findings.isEmpty()) item { Text("Aucun blocage ne correspond au filtre.") }
                            items(findings, key = { "${it.issue.file}:${it.mapId}:${it.issue.eventId}:${it.pageIndex}:${it.issue.opcode}:${it.issue.offset}:${it.issue.reason}" }) { finding ->
                                val i = finding.issue
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MaterialText(i.reason, color = MaterialTheme.colorScheme.error)
                                        MaterialText("${i.file} · événement ${i.eventId} · page ${finding.pageIndex ?: "—"} · commande ${i.opcode} @0x${i.offset.toString(16)}", style = MaterialTheme.typography.bodySmall)
                                        i.operands?.let { MaterialText(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.report != null && !state.running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Format JSON", Modifier.weight(1f)); Switch(json, { json = it }, enabled = !exporting)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !exporting, modifier = Modifier.weight(1f), onClick = {
                                val snapshot = state.report ?: return@OutlinedButton
                                pendingExport = snapshot to json
                                beforeExternal(); save.launch("Astra-Wolf-Compatibilite-${snapshot.createdAt}.${if (json) "json" else "txt"}")
                            }) { Text("Enregistrer") }
                            OutlinedButton(enabled = !exporting, modifier = Modifier.weight(1f), onClick = {
                                val snapshot = state.report ?: return@OutlinedButton
                                scope.launch {
                                    exporting = true
                                    try {
                                        val intent = controller.share(snapshot, json)
                                        beforeExternal(); context.startActivity(Intent.createChooser(intent, AppLocalizer.text("Partager la compatibilité Wolf")))
                                    } catch (error: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); exportError = error.message }
                                    finally { exporting = false }
                                }
                            }) { Text("Partager") }
                        }
                    }
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !exporting, onClick = {
                        if (state.running) controller.cancel() else { exportError = null; controller.start(game) }
                    }) { Text(if (state.running) "Annuler l’analyse" else if (state.report == null) "Analyser le jeu" else "Relancer l’analyse") }
                }
            }
        }
    }
}
