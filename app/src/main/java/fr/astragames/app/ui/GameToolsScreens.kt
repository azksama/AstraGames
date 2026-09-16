package fr.astragames.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import fr.astragames.app.data.saves.SaveValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.mods.ModStatus
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveEntry
import fr.astragames.app.data.saves.SaveEntryType
import fr.astragames.app.data.saves.SaveFieldClassifier
import fr.astragames.app.tools.GameToolsRegistry
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameToolsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    var showSaves by remember { mutableStateOf(false) }
    var showBackups by remember { mutableStateOf(false) }
    var showMods by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GameSave?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Outils", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        GameToolsRegistry.sectionsFor(game).forEach { (section, tools) ->
            Text(section, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            tools.forEach { tool ->
                ListItem(
                    headlineContent = { Text(tool.title) },
                    supportingContent = tool.description?.let { desc -> { Text(desc) } },
                    leadingContent = { Icon(tool.icon, null) },
                    modifier = Modifier.clickable {
                        when (tool.id) {
                            "save_folder" -> vm.openGameSaveFolder(game.id)
                            "save_editor" -> { vm.tools.loadSaves(game.id); showSaves = true }
                            "save_backups" -> showBackups = true
                            "mods" -> { vm.tools.loadMods(game.id); showMods = true }
                            "open_folder" -> vm.openGameFolder(game.id)
                            "rescan" -> vm.rescanGame(game.id)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showSaves) SaveListSheet(game, vm, { editing = it; showSaves = false }, { showSaves = false })
    if (showBackups) SaveBackupsSheet(game, vm) { showBackups = false }
    if (showMods) ModsSheet(game, vm) { showMods = false }
    editing?.let { save -> SaveEditorDialog(game, save, vm) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveListSheet(game: GameEntity, vm: AstraViewModel, onEdit: (GameSave) -> Unit, onDismiss: () -> Unit) {
    val saves by vm.tools.gameSaves.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.tools.requestSaveFolder(game.id) }) { Text("Choisir un dossier") }
                TextButton(onClick = { vm.tools.detectSaveLocations(game.id) }) { Text("Actualiser") }
            }
            Text("Sauvegardes", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if (saves.isEmpty()) Text("Aucune sauvegarde détectée.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            saves.forEach { save ->
                ListItem(headlineContent = { Text(save.name) }, supportingContent = { Text((save.slot?.let { "Slot " + it + " - " } ?: "") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(save.lastModified))) }, trailingContent = { TextButton(onClick = { onEdit(save) }) { Text("Éditer") } })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveBackupsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val backups by vm.tools.observeSaveBackups(game.id).collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Sauvegardes de sécurité", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if (backups.isEmpty()) Text("Aucune sauvegarde de sécurité.", Modifier.padding(horizontal = 20.dp))
            backups.forEach { backup ->
                ListItem(headlineContent = { Text(backup.sourceName) }, supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt))) }, trailingContent = { Row { IconButton(onClick = { vm.tools.restoreSaveBackup(backup) }) { Icon(Icons.Default.Restore, "Restaurer") }; IconButton(onClick = { vm.tools.deleteSaveBackup(backup) }) { Icon(Icons.Default.Delete, "Supprimer") } } })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveEditorDialog(game: GameEntity, save: GameSave, vm: AstraViewModel, onDismiss: () -> Unit) {
    val entries by vm.tools.saveEntries.collectAsStateWithLifecycle()
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    val error by vm.tools.toolsError.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<SaveEntryType?>(null) }
    val drafts = remember(save.uri) { mutableStateMapOf<String, String>() }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val favorites = remember(uiState.settings.saveEditorFavorites) { uiState.settings.saveEditorFavorites.split("|").filter { it.isNotBlank() }.toMutableSet() }
    LaunchedEffect(save.uri) { vm.tools.loadSaveEntries(save) }
    val simple = remember(entries) { SaveFieldClassifier.classify(entries) }
    val edits = drafts.mapNotNull { (path, value) ->
        entries.firstOrNull { it.path == path && it.editable && it.displayValue != value }?.let { SaveEdit(path, it.type, value) }
    }
    val validation = edits.firstNotNullOfOrNull { edit -> runCatching { SaveValues.parse(edit) }.exceptionOrNull()?.message }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Éditer " + save.name) }, text = {
        Column(Modifier.fillMaxWidth()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (game.engine == "RENPY") Text("Ren'Py peut demander de confirmer le chargement après modification.", style = MaterialTheme.typography.bodySmall)
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Simple") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Avancé") })
            }
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    SimpleField("Argent", simple.money, drafts)
                    SimpleField("Niveau", simple.level, drafts)
                    SimpleField("Expérience", simple.experience, drafts)
                    SimpleField("PV", simple.hp, drafts)
                    SimpleField("PM", simple.mp, drafts)
                    if (simple.inventory.isNotEmpty()) Text("Inventaire", fontWeight = FontWeight.SemiBold)
                    simple.inventory.take(8).forEach { SimpleField(it.path.substringAfterLast(".").substringAfterLast("[").removeSuffix("]"), it, drafts) }
                    if (simple.relations.isNotEmpty()) Text("Relations", fontWeight = FontWeight.SemiBold)
                    simple.relations.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts) }
                    if (simple.progress.isNotEmpty()) Text("Progression", fontWeight = FontWeight.SemiBold)
                    simple.progress.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts) }
                    if (simple.variables.isNotEmpty()) Text("Variables", fontWeight = FontWeight.SemiBold)
                    simple.variables.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts) }
                    if (simple.switches.isNotEmpty()) Text("Switches", fontWeight = FontWeight.SemiBold)
                    simple.switches.take(8).forEach { SimpleField(it.path.substringAfterLast("."), it, drafts) }
                    if (entries.none { it.editable }) Text("Aucune valeur reconnue automatiquement. Passez en mode avance.")
                }
            } else {
                OutlinedTextField(query, { query = it }, label = { Text("Recherche") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(typeFilter == null, { typeFilter = null }, { Text("Tous") })
                    listOf(SaveEntryType.INT, SaveEntryType.FLOAT, SaveEntryType.BOOLEAN, SaveEntryType.STRING).forEach { type -> FilterChip(typeFilter == type, { typeFilter = type }, { Text(type.name.lowercase(Locale.ROOT)) }) }
                }
                val filtered = entries.filter { entry -> (typeFilter == null || entry.type == typeFilter) && (query.isBlank() || entry.path.contains(query, true) || entry.displayValue.contains(query, true)) }.sortedWith(compareByDescending<SaveEntry> { it.path in favorites }.thenBy { it.path })
                LazyColumn(Modifier.height(320.dp)) {
                    items(filtered, key = { it.path }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (!favorites.add(entry.path)) favorites.remove(entry.path); vm.tools.setSaveEditorFavorites(favorites.joinToString("|")) }) { Icon(if (entry.path in favorites) Icons.Default.Star else Icons.Default.StarBorder, null) }
                            Column(Modifier.weight(1f)) {
                                Text(entry.path, style = MaterialTheme.typography.labelMedium)
                                if (entry.editable) OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Original : " + entry.displayValue) }) else Text(entry.displayValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && edits.isNotEmpty() && validation == null, onClick = { vm.tools.applySaveEdits(game.id, save, edits, onDismiss) }) { Text("Enregistrer") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun SimpleField(label: String, entry: SaveEntry?, drafts: MutableMap<String, String>) {
    if (entry == null) return
    OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val mods by vm.tools.modsCatalog.collectAsStateWithLifecycle()
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val root = uiState.settings.modsRootUri
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Mods", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if (root == null) {
                Text("Choisissez le dossier Astra/Mods pour scanner et importer des mods.", Modifier.padding(horizontal = 20.dp))
                Button(onClick = vm.tools::requestModsRoot, enabled = !busy, modifier = Modifier.padding(20.dp)) {
                    Text("Choisir le dépôt de mods")
                }
            } else {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.tools.loadMods(game.id) }, enabled = !busy) { Text("Rescanner") }
                    OutlinedButton(onClick = { vm.tools.requestModZipImport(game.id) }, enabled = !busy) { Text("Importer un ZIP") }
                }
                if (mods.isEmpty()) Text("Aucun mod dans ce dépôt.", Modifier.padding(20.dp))
                mods.forEach { item ->
                    val status = when (item.status) {
                        ModStatus.AVAILABLE -> "Disponible"
                        ModStatus.INSTALLED -> "Installé"
                        ModStatus.INCOMPATIBLE -> "Incompatible"
                        ModStatus.ERROR -> "Restauration nécessaire"
                    }
                    ListItem(
                        headlineContent = { Text(item.entity.name) },
                        supportingContent = { Text(listOfNotNull(status, item.entity.version, item.entity.author).joinToString(" · ")) },
                        leadingContent = { Icon(Icons.Default.Build, null) },
                        trailingContent = {
                            when (item.status) {
                                ModStatus.AVAILABLE -> TextButton(enabled = !busy, onClick = { vm.tools.installMod(game.id, item.entity.id) }) { Text("Installer") }
                                ModStatus.INSTALLED -> TextButton(enabled = !busy, onClick = { item.installation?.let { vm.tools.uninstallMod(game.id, it.id) } }) { Text("Désinstaller") }
                                ModStatus.ERROR -> TextButton(enabled = !busy, onClick = { item.installation?.let { vm.tools.uninstallMod(game.id, it.id, true) } }) { Text("Restaurer") }
                                ModStatus.INCOMPATIBLE -> Unit
                            }
                        }
                    )
                    item.entity.description?.let {
                        Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
