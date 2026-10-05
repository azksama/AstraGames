package fr.astragames.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.LinearProgressIndicator
import fr.astragames.app.data.saves.SaveValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveEntry
import fr.astragames.app.data.saves.SaveEntryType
import fr.astragames.app.data.saves.SaveFieldClassifier
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SaveListSheet(game: GameEntity, vm: AstraViewModel, onEdit: (GameSave) -> Unit, onDismiss: () -> Unit) {
    val saves by vm.tools.gameSaves.collectAsStateWithLifecycle()
    val loading by vm.tools.savesLoading.collectAsStateWithLifecycle()
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    val error by vm.tools.savesError.collectAsStateWithLifecycle()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LaunchedEffect(game.id) { vm.tools.loadSaves(game.id) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text("Sauvegardes", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            item {
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.tools.requestSaveFolder(game.id) }) { Text("Choisir un dossier") }
                TextButton(onClick = { vm.tools.detectSaveLocations(game.id) }, enabled = !loading) { Text("Actualiser") }
            }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) }
            error?.let { message -> item { Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
            if (!loading && error == null && saves.isEmpty()) item { Text("Aucune sauvegarde détectée.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            items(saves, key = { it.uri }) { save ->
                ListItem(headlineContent = { MaterialText(save.name) }, supportingContent = { MaterialText((save.slot?.let { "Slot " + it + " - " } ?: "") + dateFormat.format(Date(save.lastModified))) }, trailingContent = { TextButton(enabled = !busy, onClick = { onEdit(save) }) { Text("Éditer") } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveBackupsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val backups by remember(game.id, vm) { vm.tools.observeSaveBackups(game.id) }.collectAsStateWithLifecycle(emptyList())
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    var restore by remember { mutableStateOf<SaveBackupEntity?>(null) }
    var delete by remember { mutableStateOf<SaveBackupEntity?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text("Sauvegardes de sécurité", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            if (backups.isEmpty()) item { Text("Aucune sauvegarde de sécurité.", Modifier.padding(horizontal = 20.dp)) }
            items(backups, key = { it.id }) { backup ->
                ListItem(headlineContent = { MaterialText(backup.sourceName) }, supportingContent = { MaterialText(dateFormat.format(Date(backup.createdAt))) }, trailingContent = { Row { IconButton(enabled = !busy, onClick = { restore = backup }) { Icon(Icons.Default.Restore, AppLocalizer.text("Restaurer")) }; IconButton(enabled = !busy, onClick = { delete = backup }) { Icon(Icons.Default.Delete, AppLocalizer.text("Supprimer")) } } })
            }
        }
    }
    restore?.let { backup -> AlertDialog(
        onDismissRequest = { restore = null },
        title = { Text("Restaurer cette sauvegarde ?") },
        text = { Column { MaterialText(backup.sourceName); Text("Fermez le jeu. La sauvegarde actuelle sera remplacée ; une copie de sécurité sera créée avant la restauration.") } },
        confirmButton = { TextButton(onClick = { restore = null; vm.tools.restoreSaveBackup(backup) }) { Text("Restaurer") } },
        dismissButton = { TextButton(onClick = { restore = null }) { Text("Annuler") } }
    ) }
    delete?.let { backup -> AlertDialog(
        onDismissRequest = { delete = null },
        title = { Text("Supprimer cette copie de sécurité ?") },
        text = { MaterialText(backup.sourceName) },
        confirmButton = { TextButton(onClick = { delete = null; vm.tools.deleteSaveBackup(backup) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Annuler") } }
    ) }
}

private val saveDraftsSaver = mapSaver<SnapshotStateMap<String, String>>(
    save = { it.toMap() },
    restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (key, value) -> put(key, value as String) } } }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveEditorDialog(game: GameEntity, save: GameSave, vm: AstraViewModel, onDismiss: () -> Unit) {
    val entries by vm.tools.saveEntries.collectAsStateWithLifecycle()
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    val error by vm.tools.toolsError.collectAsStateWithLifecycle()
    val saveRevision by vm.tools.saveRevision.collectAsStateWithLifecycle()
    var openedRevision by rememberSaveable(save.uri) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(save.uri) { mutableIntStateOf(0) }
    var query by rememberSaveable(save.uri) { mutableStateOf("") }
    var typeFilter by rememberSaveable(save.uri) { mutableStateOf<SaveEntryType?>(null) }
    val drafts = rememberSaveable(save.uri, saver = saveDraftsSaver) { mutableStateMapOf<String, String>() }
    var confirmDiscard by rememberSaveable(save.uri) { mutableStateOf(false) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var favorites by remember(uiState.settings.saveEditorFavorites) { mutableStateOf(uiState.settings.saveEditorFavorites.split("|").filter { it.isNotBlank() }.toSet()) }
    LaunchedEffect(save.uri) { vm.tools.loadSaveEntries(save, openedRevision) }
    LaunchedEffect(saveRevision) {
        val revision = vm.tools.saveRevision.value
        if (openedRevision == null && revision?.first == save.uri) openedRevision = revision.second
    }
    val simple = remember(entries) { SaveFieldClassifier.classify(entries) }
    val entriesByPath = remember(entries) { entries.associateBy { it.path } }
    val edits = drafts.mapNotNull { (path, value) ->
        entriesByPath[path]?.takeIf { it.editable && it.displayValue != value }?.let { SaveEdit(path, it.type, value) }
    }
    val validation = remember(edits) { edits.firstNotNullOfOrNull { edit -> runCatching { SaveValues.parse(edit) }.exceptionOrNull()?.message } }
    val requestClose = { if (!busy) { if (edits.isNotEmpty() || (entries.isEmpty() && drafts.isNotEmpty())) confirmDiscard = true else onDismiss() } }
    val filtered = remember(entries, query, typeFilter, favorites) {
        entries.filter { entry -> (typeFilter == null || entry.type == typeFilter) && (query.isBlank() || entry.path.contains(query, true) || entry.displayValue.contains(query, true)) }
            .sortedWith(compareByDescending<SaveEntry> { it.path in favorites }.thenBy { it.path })
    }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        ScrollingColumn(Modifier.fillMaxHeight().widthIn(max = 920.dp).fillMaxWidth().align(Alignment.Center).padding(horizontal = 16.dp), topBar = { CompactHeader("Éditer", save.name, onBack = if (busy) null else requestClose, localizeSubtitle = false) }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Fermez le jeu avant d’éditer une sauvegarde.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (game.engine == "RENPY") Text("Ren'Py peut demander de confirmer le chargement après modification.", style = MaterialTheme.typography.bodySmall)
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Simple") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Avancé") })
            }
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SimpleField("Argent", simple.money, drafts, !busy)
                    SimpleField("Niveau", simple.level, drafts, !busy)
                    SimpleField("Expérience", simple.experience, drafts, !busy)
                    SimpleField("PV", simple.hp, drafts, !busy)
                    SimpleField("PM", simple.mp, drafts, !busy)
                    if (simple.inventory.isNotEmpty()) Text("Inventaire", fontWeight = FontWeight.SemiBold)
                    simple.inventory.take(8).forEach { SimpleField(it.path.substringAfterLast(".").substringAfterLast("[").removeSuffix("]"), it, drafts, !busy, localizeLabel = false) }
                    if (simple.relations.isNotEmpty()) Text("Relations", fontWeight = FontWeight.SemiBold)
                    simple.relations.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts, !busy, localizeLabel = false) }
                    if (simple.progress.isNotEmpty()) Text("Progression", fontWeight = FontWeight.SemiBold)
                    simple.progress.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts, !busy, localizeLabel = false) }
                    if (simple.variables.isNotEmpty()) Text("Variables", fontWeight = FontWeight.SemiBold)
                    simple.variables.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts, !busy, localizeLabel = false) }
                    if (simple.switches.isNotEmpty()) Text("Switches", fontWeight = FontWeight.SemiBold)
                    simple.switches.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts, !busy, localizeLabel = false) }
                    if (!busy && listOf(simple.money, simple.level, simple.experience, simple.hp, simple.mp).all { it == null } && listOf(simple.inventory, simple.relations, simple.progress, simple.variables, simple.switches).all { it.isEmpty() }) Text("Aucune valeur reconnue automatiquement. Passez en mode avance.")
                }
            } else {
                OutlinedTextField(query, { query = it }, label = { Text("Recherche") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(typeFilter == null, { typeFilter = null }, { Text("Tous") })
                    listOf(SaveEntryType.INT, SaveEntryType.FLOAT, SaveEntryType.BOOLEAN, SaveEntryType.STRING).forEach { type -> FilterChip(typeFilter == type, { typeFilter = type }, { Text(type.name.lowercase(Locale.ROOT)) }) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!busy && filtered.isEmpty()) item { Text("Aucun champ ne correspond aux filtres.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(filtered, key = { it.path }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                favorites = if (entry.path in favorites) favorites - entry.path else favorites + entry.path
                                vm.tools.setSaveEditorFavorites(favorites.joinToString("|"))
                            }) { Icon(if (entry.path in favorites) Icons.Default.Star else Icons.Default.StarBorder, AppLocalizer.text("Favori")) }
                            Column(Modifier.weight(1f)) {
                                MaterialText(entry.path, style = MaterialTheme.typography.labelMedium)
                                if (entry.editable) OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { MaterialText(AppLocalizer.text("Original : ") + entry.displayValue) }) else MaterialText(entry.displayValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = requestClose, modifier = Modifier.weight(1f)) { Text("Annuler") }
                Button(enabled = !busy && edits.isNotEmpty() && validation == null, onClick = { vm.tools.applySaveEdits(game.id, save, edits, onDismiss) }, modifier = Modifier.weight(1f)) { Text("Enregistrer") }
            }
        }
        }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Abandonner les modifications ?") },
        text = { Text("Les modifications non enregistrées seront perdues.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Abandonner") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continuer à modifier") } }
    )
}

@Composable
private fun SimpleField(label: String, entry: SaveEntry?, drafts: MutableMap<String, String>, enabled: Boolean, localizeLabel: Boolean = true) {
    if (entry == null || !entry.editable) return
    OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, label = { MaterialText(if (localizeLabel) AppLocalizer.text(label) else label) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

