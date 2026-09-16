package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*

@Composable
internal fun SearchScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit, onBack: () -> Unit) {
    var engineMenu by remember { mutableStateOf(false) }
    var tagsMenu by remember { mutableStateOf(false) }
    var systemFolderMenu by remember { mutableStateOf(false) }
    val availableEngines = remember(state.games) {
        state.games.mapNotNull { runCatching { GameEngine.valueOf(it.engine) }.getOrNull() }.distinct()
    }
    DisposableEffect(Unit) { onDispose { vm.updateQuery("") } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Recherche", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            OutlinedTextField(
                state.filters.query, vm::updateQuery, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.filters.query.isNotBlank()) IconButton(onClick = { vm.updateQuery("") }) { Icon(Icons.Default.Close, "Effacer la sélection") }
                },
                placeholder = { Text("Titre, moteur, développeur…") }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { engineMenu = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Extension, null); Spacer(Modifier.width(6.dp))
                        Text(state.filters.engine?.name?.readableEngine() ?: "Tous les moteurs", Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(engineMenu, { engineMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Tous les moteurs") },
                            leadingIcon = { if (state.filters.engine == null) Icon(Icons.Default.Check, null) },
                            onClick = { vm.filterEngine(null); engineMenu = false }
                        )
                        availableEngines.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.name.readableEngine()) },
                                leadingIcon = { if (state.filters.engine == engine) Icon(Icons.Default.Check, null) },
                                onClick = { vm.filterEngine(engine); engineMenu = false }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { tagsMenu = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Style, null); Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.filters.tagIds.isEmpty()) "Tous les tags" else "${state.filters.tagIds.size} tag(s)",
                            Modifier.weight(1f), maxLines = 1
                        )
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = tagsMenu,
                        onDismissRequest = { tagsMenu = false },
                        modifier = Modifier.heightIn(max = 480.dp)
                    ) {
                        if (state.filters.tagIds.isNotEmpty()) DropdownMenuItem(
                            text = { Text("Effacer la sélection") },
                            leadingIcon = { Icon(Icons.Default.Clear, null) },
                            onClick = { state.filters.tagIds.forEach(vm::toggleTagFilter) }
                        )
                        val categoryNames = state.tagCategories.map { it.name }
                        val grouped = state.tags.groupBy { it.groupName }
                        (categoryNames.map { it as String? } + listOf(null)).distinct().forEach { category ->
                            val tags = grouped[category].orEmpty()
                            if (tags.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(category ?: "Sans collection", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                    enabled = false,
                                    onClick = {}
                                )
                                tags.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(tag.name) },
                                        leadingIcon = { Checkbox(tag.id in state.filters.tagIds, null) },
                                        onClick = { vm.toggleTagFilter(tag.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val selectedFolder = state.systemFolders.firstOrNull { it.id == state.filters.systemFolderId }
                OutlinedButton(onClick = { systemFolderMenu = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp))
                    Text(selectedFolder?.label ?: "Tous les dossiers système", Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(systemFolderMenu, { systemFolderMenu = false }, Modifier.heightIn(max = 520.dp)) {
                    DropdownMenuItem(
                        text = { Text("Tous les dossiers système") },
                        leadingIcon = { if (state.filters.systemFolderId == null) Icon(Icons.Default.Check, null) },
                        onClick = { vm.filterSystemFolder(null); systemFolderMenu = false }
                    )
                    state.systemFolders.forEach { folder -> DropdownMenuItem(
                        text = { Text(folder.label, maxLines = 2) },
                        leadingIcon = { if (state.filters.systemFolderId == folder.id) Icon(Icons.Default.Check, null) },
                        onClick = { vm.filterSystemFolder(folder.id); systemFolderMenu = false }
                    ) }
                }
            }
            GameCollection(state.filteredGames, LibraryViewMode.LIST, state.settings.gridColumns, onGame, vm::toggleFavorite)
        }
    }
}
