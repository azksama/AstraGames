package fr.astragames.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.tools.GameToolsRegistry

private val gameSaveSaver = listSaver<GameSave?, Any>(
    save = { save -> save?.let { listOf(it.uri, it.name, it.slot ?: -1, it.engine, it.sizeBytes, it.lastModified) } ?: emptyList() },
    restore = { values -> if (values.isEmpty()) null else GameSave(
        uri = values[0] as String, name = values[1] as String,
        slot = (values[2] as Int).takeIf { it >= 0 }, engine = values[3] as String,
        sizeBytes = values[4] as Long, lastModified = values[5] as Long
    ) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameToolsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    var showSaves by rememberSaveable(game.id) { mutableStateOf(false) }
    var showBackups by rememberSaveable(game.id) { mutableStateOf(false) }
    var showTranslation by rememberSaveable(game.id) { mutableStateOf(false) }
    var showMods by rememberSaveable(game.id) { mutableStateOf(false) }
    var editing by rememberSaveable(game.id, stateSaver = gameSaveSaver) { mutableStateOf<GameSave?>(null) }
    val sections = remember(game) { GameToolsRegistry.sectionsFor(game) }
    if (!showSaves && !showBackups && !showMods && !showTranslation && editing == null) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
        Text("Outils", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        sections.forEach { (section, tools) ->
            item {
            Text(section, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            items(tools, key = { it.id }) { tool ->
                ListItem(
                    headlineContent = { Text(tool.title) },
                    supportingContent = tool.description?.let { desc -> { Text(desc) } },
                    leadingContent = { Icon(tool.icon, null) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        when (tool.id) {
                            "save_folder" -> vm.openGameSaveFolder(game.id)
                            "save_editor" -> showSaves = true
                            "save_backups" -> showBackups = true
                            "mods" -> showMods = true
                            "translate" -> showTranslation = true
                            "open_folder" -> vm.openGameFolder(game.id)
                            "rescan" -> vm.rescanGame(game.id)
                            "wolf_refresh" -> { vm.refreshWolfFiles(game.id); onDismiss() }
                        }
                    }
                )
            }
        }
        }
    }
    if (showSaves) SaveListSheet(game, vm, { editing = it; showSaves = false }, { showSaves = false })
    if (showBackups) SaveBackupsSheet(game, vm) { showBackups = false }
    if (showTranslation) GameTranslationScreen(game, vm.translation) { showTranslation = false }
    if (showMods) ModsSheet(game, vm) { showMods = false }
    editing?.let { save -> SaveEditorDialog(game, save, vm) { editing = null } }
}
