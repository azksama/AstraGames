package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.LibraryViewMode

@Composable
internal fun SearchScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit, onBack: () -> Unit) {
    var engineMenu by remember { mutableStateOf(false) }
    var tagsMenu by remember { mutableStateOf(false) }
    var systemFolderMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    val availableEngines = remember(state.games) {
        state.games.mapNotNull { runCatching { GameEngine.valueOf(it.engine) }.getOrNull() }.distinct().sortedBy { it.name }
    }
    LaunchedEffect(Unit) {
        if (!focusRequested) {
            focusRequester.requestFocus()
            focusRequested = true
        }
    }
    ScrollingScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Recherche", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = vm::updateQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focusRequester),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.filters.query.isNotEmpty()) IconButton(onClick = { vm.updateQuery("") }) {
                        Icon(Icons.Default.Close, AppLocalizer.text("Effacer la recherche"))
                    }
                },
                label = { Text("Rechercher") },
                placeholder = { Text("Titre, moteur, développeur…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Box {
                        FilterChip(
                            selected = state.filters.engine != null,
                            onClick = { keyboard?.hide(); engineMenu = true },
                            label = { Text(state.filters.engine?.name?.readableEngine() ?: "Tous les moteurs") },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                        )
                        DropdownMenu(engineMenu, { engineMenu = false }) {
                            DropdownMenuItem(text = { Text("Tous les moteurs") }, onClick = { vm.filterEngine(null); engineMenu = false })
                            availableEngines.forEach { engine ->
                                DropdownMenuItem(
                                    text = { Text(engine.name.readableEngine()) },
                                    leadingIcon = { if (state.filters.engine == engine) Icon(Icons.Default.Check, null) },
                                    onClick = { vm.filterEngine(engine); engineMenu = false }
                                )
                            }
                        }
                    }
                }
                item {
                    val tagCount = state.filters.tagIds.size + state.filters.excludedTagIds.size
                    FilterChip(
                        selected = tagCount > 0,
                        onClick = { keyboard?.hide(); tagsMenu = true },
                        label = { Text(if (tagCount == 0) "Tous les tags" else "Tags ($tagCount)") },
                        leadingIcon = { Icon(Icons.Default.Style, null) }
                    )
                }
                item {
                    Box {
                        val selectedFolder = state.systemFolders.firstOrNull { it.id == state.filters.systemFolderId }
                        FilterChip(
                            selected = selectedFolder != null,
                            onClick = { keyboard?.hide(); systemFolderMenu = true },
                            label = { MaterialText(selectedFolder?.label ?: AppLocalizer.text("Tous les dossiers système", LocalAppLanguage.current), maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                        DropdownMenu(systemFolderMenu, { systemFolderMenu = false }, Modifier.heightIn(max = 480.dp)) {
                            DropdownMenuItem(text = { Text("Tous les dossiers système") }, onClick = { vm.filterSystemFolder(null); systemFolderMenu = false })
                            state.systemFolders.forEach { folder ->
                                DropdownMenuItem(text = { MaterialText(folder.label, maxLines = 2) }, onClick = { vm.filterSystemFolder(folder.id); systemFolderMenu = false })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.searching) "Recherche en cours…" else "${state.filteredGames.size} jeux", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.filters != LibraryFilters()) TextButton(onClick = vm::clearFilters) { Text("Réinitialiser") }
            }
            Box(Modifier.weight(1f)) {
                if (state.searching) CenterMessage("Recherche en cours…", Modifier.fillMaxSize(), loading = true)
                else GameCollection(state.filteredGames, LibraryViewMode.LIST, state.settings.gridColumns, onGame, vm::toggleFavorite, onResetFilters = vm::clearFilters)
            }
        }
    }
    if (tagsMenu) GameTagPickerSheet(
        state.tags, state.tagCategories, state.filters.tagIds,
        { vm.setTagFilters(it); tagsMenu = false },
        { tagsMenu = false },
        initialExcluded = state.filters.excludedTagIds,
        onSaveExcluded = vm::setExcludedTagFilters,
        title = "Filtrer les tags"
    )
}
