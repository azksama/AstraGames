package fr.astragames.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.PlaySessionEntity
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

@Composable
internal fun LockScreen(state: AstraUiState, vm: AstraViewModel) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(state.settings.lockBiometricEnabled) {
        if (state.settings.lockBiometricEnabled) vm.requestBiometricUnlock()
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Astra est verrouille", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Utilisez votre empreinte ou votre code pour continuer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (state.settings.lockBiometricEnabled) {
            FilledTonalButton(onClick = vm::requestBiometricUnlock, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Deverrouiller par biometrie")
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.settings.lockPinEnabled) {
            OutlinedTextField(value = pin, onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) }, label = { Text("Code") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.verifyLockPin(pin) { if (!it) pin = "" } }, modifier = Modifier.fillMaxWidth(), enabled = pin.length >= 4) { Text("Deverrouiller") }
            Spacer(Modifier.height(16.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        OutlinedButton(onClick = { when (key) { "<" -> pin = pin.dropLast(1); "" -> Unit; else -> pin = (pin + key).take(8) } }, enabled = key.isNotBlank(), modifier = Modifier.weight(1f).height(48.dp)) { Text(if (key == "<") "Effacer" else key) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun HistoryScreen(state: AstraUiState, vm: AstraViewModel) {
    val sessions by vm.playHistory().collectAsStateWithLifecycle(emptyList())
    var confirmClear by remember { mutableStateOf(false) }
    val games = remember(state.games) { state.games.associateBy { it.id } }
    val grouped = remember(sessions) { sessions.groupBy { DateFormat.getDateInstance(DateFormat.FULL, Locale.FRANCE).format(Date(it.startedAt)) } }
    Scaffold(topBar = {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Historique", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { confirmClear = true }, enabled = sessions.isNotEmpty()) { Text("Effacer") }
            }
        }
    }) { padding ->
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, null, Modifier.size(40.dp)); Spacer(Modifier.height(12.dp)); Text("Aucune session enregistree")
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
                grouped.forEach { (day, daySessions) ->
                    item { Text(day, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    items(daySessions, key = { it.id }) { session ->
                        val game = games[session.gameId]
                        ListItem(headlineContent = { Text(game?.title ?: "Jeu inconnu") }, supportingContent = { Text(sessionLabel(session)) }, trailingContent = { IconButton(onClick = { vm.deletePlaySession(session.id) }) { Icon(Icons.Default.Delete, "Supprimer") } })
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Effacer l historique ?") }, text = { Text("Toutes les sessions terminees seront supprimees.") }, confirmButton = { TextButton(onClick = { vm.clearPlayHistory(); confirmClear = false }) { Text("Effacer") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } })
}

private fun sessionLabel(session: PlaySessionEntity): String {
    val start = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(session.startedAt))
    val duration = session.durationMs?.let { ms ->
        val minutes = (ms / 60000L).toInt(); val seconds = ((ms % 60000L) / 1000L).toInt()
        if (minutes > 0) minutes.toString() + " min " + seconds.toString() + " s" else seconds.toString() + " s"
    } ?: "en cours"
    return start + " - " + duration
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameToolsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    var showLocations by remember { mutableStateOf(false) }
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
                            "save_folder" -> { vm.detectSaveLocations(game.id); showLocations = true }
                            "save_editor" -> { vm.loadSaves(game.id); showSaves = true }
                            "save_backups" -> showBackups = true
                            "mods" -> { vm.loadMods(game.id); showMods = true }
                            "open_folder" -> vm.openGameFolder(game.id)
                            "rescan" -> vm.rescanGame(game.id)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showLocations) SaveLocationsSheet(game, vm) { showLocations = false }
    if (showSaves) SaveListSheet(game, vm, { editing = it; showSaves = false }, { showSaves = false })
    if (showBackups) SaveBackupsSheet(game, vm) { showBackups = false }
    if (showMods) ModsSheet(game, vm) { showMods = false }
    editing?.let { save -> SaveEditorDialog(game, save, vm) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveLocationsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val locations by vm.observeSaveLocations(game.id).collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Dossier des sauvegardes", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if (locations.isEmpty()) Text("Aucun emplacement connu. Detectez-en un ou ajoutez-en un manuellement.", Modifier.padding(horizontal = 20.dp))
        locations.forEach { location ->
            ListItem(headlineContent = { Text(location.displayName.ifBlank { location.uri }) }, supportingContent = { Text((if (location.autoDetected) "Detecte" else "Manuel") + " - " + location.type) }, leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { TextButton(onClick = { vm.removeSaveLocation(location.id) }) { Text("Retirer") } })
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.detectSaveLocations(game.id) }) { Text("Detecter") }
            OutlinedButton(onClick = { vm.requestSaveFolder(game.id) }) { Text("Ajouter un dossier") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveListSheet(game: GameEntity, vm: AstraViewModel, onEdit: (GameSave) -> Unit, onDismiss: () -> Unit) {
    val saves by vm.gameSaves.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Sauvegardes", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if (saves.isEmpty()) Text("Aucune sauvegarde detectee.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        saves.forEach { save ->
            ListItem(headlineContent = { Text(save.name) }, supportingContent = { Text((save.slot?.let { "Slot " + it + " - " } ?: "") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(save.lastModified))) }, trailingContent = { TextButton(onClick = { onEdit(save) }) { Text("Editer") } })
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveBackupsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val backups by vm.observeSaveBackups(game.id).collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Backups", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if (backups.isEmpty()) Text("Aucun backup pour l instant.", Modifier.padding(horizontal = 20.dp))
        backups.forEach { backup ->
            ListItem(headlineContent = { Text(backup.sourceName) }, supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt))) }, trailingContent = { Row { IconButton(onClick = { vm.restoreSaveBackup(backup) }) { Icon(Icons.Default.Restore, "Restaurer") }; IconButton(onClick = { vm.deleteSaveBackup(backup) }) { Icon(Icons.Default.Delete, "Supprimer") } } })
        }
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun SaveEditorDialog(game: GameEntity, save: GameSave, vm: AstraViewModel, onDismiss: () -> Unit) {
    val entries by vm.saveEntries.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<SaveEntryType?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val favorites = remember(uiState.settings.saveEditorFavorites) { uiState.settings.saveEditorFavorites.split("|").filter { it.isNotBlank() }.toMutableSet() }
    LaunchedEffect(save.uri) { vm.loadSaveEntries(game.id, save) }
    val simple = remember(entries) { SaveFieldClassifier.classify(entries) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editer " + save.name) }, text = {
        Column(Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Simple") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Avance") })
            }
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                simpleField("Argent", simple.money, drafts)
                simpleField("Niveau", simple.level, drafts)
                simpleField("Experience", simple.experience, drafts)
                simpleField("PV", simple.hp, drafts)
                simpleField("PM", simple.mp, drafts)
                if (simple.inventory.isNotEmpty()) Text("Inventaire", fontWeight = FontWeight.SemiBold)
                simple.inventory.take(8).forEach { simpleField(it.path.substringAfterLast(".").substringAfterLast("[").removeSuffix("]"), it, drafts) }
                if (simple.relations.isNotEmpty()) Text("Relations", fontWeight = FontWeight.SemiBold)
                simple.relations.take(8).forEach { simpleField(it.path.substringAfterLast("."), it, drafts) }
                if (simple.progress.isNotEmpty()) Text("Progression", fontWeight = FontWeight.SemiBold)
                simple.progress.take(8).forEach { simpleField(it.path.substringAfterLast("."), it, drafts) }
                if (simple.variables.isNotEmpty()) Text("Variables", fontWeight = FontWeight.SemiBold)
                simple.variables.take(8).forEach { simpleField(it.path.substringAfterLast("."), it, drafts) }
                if (simple.switches.isNotEmpty()) Text("Switches", fontWeight = FontWeight.SemiBold)
                simple.switches.take(8).forEach { simpleField(it.path.substringAfterLast("."), it, drafts) }
                if (entries.none { it.editable }) Text("Aucune valeur reconnue automatiquement. Passez en mode avance.")
            } else {
                OutlinedTextField(query, { query = it }, label = { Text("Recherche") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(typeFilter == null, { typeFilter = null }, { Text("Tous") })
                    listOf(SaveEntryType.INT, SaveEntryType.FLOAT, SaveEntryType.BOOLEAN, SaveEntryType.STRING).forEach { type -> FilterChip(typeFilter == type, { typeFilter = type }, { Text(type.name.lowercase(Locale.ROOT)) }) }
                }
                val filtered = entries.filter { entry -> (typeFilter == null || entry.type == typeFilter) && (query.isBlank() || entry.path.contains(query, true) || entry.displayValue.contains(query, true)) }.sortedWith(compareByDescending<SaveEntry> { it.path in favorites }.thenBy { it.path })
                LazyColumn(Modifier.height(320.dp)) {
                    items(filtered, key = { it.path }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (!favorites.add(entry.path)) favorites.remove(entry.path); vm.setSaveEditorFavorites(favorites.joinToString("|")) }) { Icon(if (entry.path in favorites) Icons.Default.Star else Icons.Default.StarBorder, null) }
                            Column(Modifier.weight(1f)) {
                                Text(entry.path, style = MaterialTheme.typography.labelMedium)
                                if (entry.editable) OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Original : " + entry.displayValue) }) else Text(entry.displayValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { val edits = drafts.mapNotNull { (path, value) -> val entry = entries.firstOrNull { it.path == path } ?: return@mapNotNull null; if (!entry.editable || value == entry.displayValue) null else SaveEdit(path, entry.type, value) }; if (edits.isNotEmpty()) vm.applySaveEdits(game.id, save, edits); onDismiss() }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun simpleField(label: String, entry: SaveEntry?, drafts: MutableMap<String, String>) {
    if (entry == null) return
    OutlinedTextField(value = drafts[entry.path] ?: entry.displayValue, onValueChange = { drafts[entry.path] = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val mods by vm.modsCatalog.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val root = uiState.settings.modsRootUri
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Mods", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if (root == null) {
            Text("Choisissez le dossier Astra/Mods pour scanner et importer des mods.", Modifier.padding(horizontal = 20.dp))
            Button(onClick = vm::requestModsRoot, modifier = Modifier.padding(20.dp)) { Text("Choisir le depot de mods") }
        } else {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.scanMods(); vm.loadMods(game.id) }) { Text("Rescanner") }
                OutlinedButton(onClick = vm::requestModZipImport) { Text("Importer un ZIP") }
            }
            if (mods.isEmpty()) Text("Aucun mod compatible pour l instant.", Modifier.padding(20.dp))
            mods.forEach { item ->
                ListItem(headlineContent = { Text(item.entity.name) }, supportingContent = { Text(item.status.name + " - " + (item.entity.version ?: "sans version") + " - " + (item.entity.author ?: "auteur inconnu")) }, leadingContent = { Icon(Icons.Default.Build, null) }, trailingContent = { when (item.status) { ModStatus.AVAILABLE -> TextButton(onClick = { vm.installMod(game.id, item.entity.id) }) { Text("Installer") }; ModStatus.INSTALLED -> TextButton(onClick = { item.installation?.let { vm.uninstallMod(game.id, it.id) } }) { Text("Desinstaller") }; ModStatus.INCOMPATIBLE -> Text("Incompatible"); ModStatus.ERROR -> Text("Erreur") } })
                item.entity.description?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun PinSetupDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Definir un code") }, text = { Column { OutlinedTextField(first, { first = it.filter(Char::isDigit).take(8) }, label = { Text("Code (4 a 8 chiffres)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); OutlinedTextField(second, { second = it.filter(Char::isDigit).take(8) }, label = { Text("Confirmer") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { onSave(first) }, enabled = first.length in 4..8 && first == second) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}


