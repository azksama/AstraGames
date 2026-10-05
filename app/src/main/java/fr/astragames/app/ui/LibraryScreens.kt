package fr.astragames.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.*
import coil3.compose.AsyncImage
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity

@Composable
internal fun LibraryScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit, onPickSource: () -> Unit, onSearch: () -> Unit = {}) {
    var columnsMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var selectedGames by remember { mutableStateOf(emptySet<String>()) }
    var quickGame by remember { mutableStateOf<GameEntity?>(null) }
    var bulkFolder by remember { mutableStateOf(false) }
    var bulkTags by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    val coverBlur = LocalCoverBlurState.current
    BackHandler(selectedGames.isNotEmpty()) { selectedGames = emptySet() }
    LaunchedEffect(state.games) {
        selectedGames = selectedGames.intersect(state.games.mapTo(mutableSetOf()) { it.id })
    }
    ScrollingScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
            CompactHeader(if (selectedGames.isEmpty()) { if (LandscapeLayout) "Tous les jeux" else "Astra" } else "${selectedGames.size} sélectionné(s)", if (LandscapeLayout) "${state.filteredGames.size} jeux" else null) {
                if (selectedGames.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Default.MoreVert, AppLocalizer.text("Actions rapides")) }
                        DropdownMenu(selectionMenu, { selectionMenu = false }) {
                            DropdownMenuItem(text = { Text("Ajouter aux favoris") }, leadingIcon = { Icon(Icons.Default.FavoriteBorder, null) }, onClick = { vm.setGamesFavorite(selectedGames); selectionMenu = false })
                            DropdownMenuItem(text = { Text("Ajouter des tags") }, leadingIcon = { Icon(Icons.Default.Style, null) }, onClick = { bulkTags = true; selectionMenu = false })
                            DropdownMenuItem(text = { Text("Classer") }, leadingIcon = { Icon(Icons.Default.FolderCopy, null) }, onClick = { bulkFolder = true; selectionMenu = false })
                            DropdownMenuItem(text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { bulkDelete = true; selectionMenu = false })
                        }
                    }
                    IconButton(onClick = { selectedGames = emptySet() }) { Icon(Icons.Default.Close, AppLocalizer.text("Quitter la sélection")) }
                } else {
                Box {
                    IconButton(onClick = { columnsMenu = true }) { Icon(Icons.Default.Tune, AppLocalizer.text("Affichage")) }
                    DropdownMenu(columnsMenu, { columnsMenu = false }) {
                        DropdownMenuItem(text = { Text("Liste") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }, trailingIcon = { if (state.settings.viewMode == LibraryViewMode.LIST) Icon(Icons.Default.Check, null) }, onClick = { vm.setViewMode(LibraryViewMode.LIST); columnsMenu = false })
                        (2..4).forEach { columns ->
                            DropdownMenuItem(
                                text = { Text("$columns colonnes") },
                                leadingIcon = { Icon(Icons.Default.GridView, null) },
                                trailingIcon = { if (state.settings.viewMode == LibraryViewMode.GRID && state.settings.gridColumns == columns) Icon(Icons.Default.Check, null) },
                                onClick = { vm.setViewMode(LibraryViewMode.GRID); vm.setGridColumns(columns); columnsMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.scanAll() }, enabled = !state.scanning && state.sources.any { it.enabled }) {
                    if (state.scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, AppLocalizer.text("Scanner"))
                }
                if (coverBlur.enabled) IconButton(onClick = coverBlur.toggle) {
                    Icon(
                        if (coverBlur.blurred) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        AppLocalizer.text(if (coverBlur.blurred) "Afficher les jaquettes" else "Flouter les jaquettes")
                    )
                }
                }
            }
            if (!LandscapeLayout) Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { PageHeading("Tous les jeux", "${state.filteredGames.size} jeux") }
            FilterStrip(state, vm)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SearchEntry(onSearch)
            }
            Spacer(Modifier.height(6.dp))
            when {
                state.scanning && state.games.isEmpty() -> CenterMessage("Exploration de tous les sous-dossiers…", Modifier.fillMaxSize(), loading = true)
                state.games.isEmpty() -> EmptyLibrary(onPickSource, { vm.scanAll() }, state.sources.any { it.enabled })
                state.searching -> CenterMessage("Recherche en cours…", Modifier.fillMaxSize(), loading = true)
                else -> GameCollection(
                    state.filteredGames, state.settings.viewMode, state.settings.gridColumns,
                    onGame = { id -> if (selectedGames.isEmpty()) onGame(id) else selectedGames = selectedGames.toggle(id) },
                    onFavorite = vm::toggleFavorite,
                    selected = selectedGames,
                    onLongGame = { selectedGames = selectedGames.toggle(it) },
                    onQuickGame = { id -> quickGame = state.games.firstOrNull { it.id == id } },
                    onResetFilters = vm::clearFilters
                )
            }
        }
    }
    quickGame?.let { game -> QuickGameActionsSheet(
        game = game,
        onPlay = { vm.launchGame(game.id); quickGame = null },
        onFavorite = { vm.toggleFavorite(game.id); quickGame = null },
        onOpen = { onGame(game.id); quickGame = null },
        onSelect = { selectedGames = selectedGames + game.id; quickGame = null },
        onDismiss = { quickGame = null }
    ) }
    if (bulkFolder) GameFolderDialog(state.folders, null, {
        vm.setGamesFolder(selectedGames, it); selectedGames = emptySet(); bulkFolder = false
    }, { bulkFolder = false })
    if (bulkTags) GameTagPickerSheet(state.tags, state.tagCategories, emptySet(), {
        vm.addTagsToGames(selectedGames, it); selectedGames = emptySet(); bulkTags = false
    }, { bulkTags = false })
    if (bulkDelete) ConfirmDialog(
        "Retirer ${selectedGames.size} jeu(x) ?", "Ils seront ignorés lors des prochains scans. Aucun fichier ne sera supprimé.",
        { vm.deleteGames(selectedGames); selectedGames = emptySet(); bulkDelete = false }, { bulkDelete = false }
    )
}

@Composable
internal fun FilterStrip(state: AstraUiState, vm: AstraViewModel) {
    var showFilters by remember { mutableStateOf(false) }
    val activeCount = with(state.filters) {
        listOfNotNull(sourceId, folderId, collectionId, systemFolderId, query.takeIf { it.isNotBlank() }, engine, (tagIds + excludedTagIds).takeIf { it.isNotEmpty() }, missingOnly.takeIf { it }, sort.takeIf { it != LibrarySort.TITLE }).size
    }
    val hasActiveFilters = activeCount > 0 || state.filters.favoritesOnly
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(!hasActiveFilters, vm::clearFilters, { Text("Tous") }) }
        item { FilterChip(state.filters.favoritesOnly, vm::toggleFavoriteFilter, { Text("Favoris") }, leadingIcon = { Icon(Icons.Default.Star, null) }) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = { showFilters = true },
                    label = { Text(if (activeCount == 0) "Filtres" else "Filtres ($activeCount)") },
                    leadingIcon = { Icon(Icons.Default.Tune, null) }
                )
                if (hasActiveFilters) IconButton(onClick = vm::clearFilters, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, AppLocalizer.text("Réinitialiser les filtres"), Modifier.size(18.dp))
                }
            }
        }
    }
    if (showFilters) LibraryFiltersDialog(state, vm) { showFilters = false }
}

@Composable
internal fun LibraryFiltersDialog(state: AstraUiState, vm: AstraViewModel, onDismiss: () -> Unit) {
    var pickTags by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 760.dp).align(Alignment.Center)) {
                    CompactHeader("Filtres", "${state.filteredGames.size} jeu(x)", onBack = onDismiss) {
                        TextButton(onClick = vm::clearFilters) { Text("Réinitialiser") }
                    }
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Tags", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { pickTags = true }, Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Style, null); Spacer(Modifier.width(8.dp))
                                    val excludedCount = state.filters.excludedTagIds.size
                                    Text(
                                        if (excludedCount > 0) "${state.filters.tagIds.size} inclus · $excludedCount exclus"
                                        else "${state.filters.tagIds.size} tag(s) sélectionné(s)",
                                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (state.filters.tagIds.isNotEmpty()) FilterDropdown(
                                    "Correspondance",
                                    listOf(TagMatchMode.ALL to "Tous", TagMatchMode.ANY to "Au moins un"),
                                    state.filters.tagMode,
                                    vm::setTagMode
                                )
                            }
                        }
                        item { FilterSwitch("Favoris uniquement", state.filters.favoritesOnly, vm::toggleFavoriteFilter) }
                        item { FilterSwitch("Jeux introuvables", state.filters.missingOnly, vm::toggleMissingFilter) }
                        item { FilterDropdown("Moteur", listOf(null to "Tous") + GameEngine.entries.map { it to it.name.readableEngine() }, state.filters.engine, vm::filterEngine) }
                        item { FilterDropdown("Source", listOf(null to "Toutes") + state.sources.map { it.id to it.displayName }, state.filters.sourceId, vm::filterSource, localizeChoices = { it == null }) }
                        item {
                            val choices = listOf(null to "Toutes") +
                                state.folders.map { it.id to it.name } +
                                state.collections.map { it.id to it.name }
                            FilterDropdown(
                                "Collections",
                                choices,
                                state.filters.collectionId ?: state.filters.folderId,
                                { id -> if (id == null) vm.filterFolder(null) else if (state.folders.any { it.id == id }) vm.filterFolder(id) else vm.filterCollection(id) },
                                localizeChoices = { it == null }
                            )
                        }
                        item { FilterDropdown("Tri", LibrarySort.entries.map { it to it.label() }, state.filters.sort, vm::setSort) }
                    }
                    Button(onClick = onDismiss, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Afficher les jeux") }
                }
            }
        }
    }
    if (pickTags) GameTagPickerSheet(
        state.tags, state.tagCategories, state.filters.tagIds,
        { vm.setTagFilters(it); pickTags = false },
        { pickTags = false },
        initialExcluded = state.filters.excludedTagIds,
        onSaveExcluded = { vm.setExcludedTagFilters(it) },
        title = "Filtrer les tags"
    )
}
internal fun topLevelSlideDirection(
    fromRoute: String?,
    toRoute: String?,
    fallback: AnimatedContentTransitionScope.SlideDirection
): AnimatedContentTransitionScope.SlideDirection {
    val from = menuDestinations.indexOfFirst { it.route == fromRoute }
    val to = menuDestinations.indexOfFirst { it.route == toRoute }
    if (from < 0 || to < 0 || from == to) return fallback
    if ((fromRoute == Destination.LIBRARY.route && toRoute == Destination.SETTINGS.route) ||
        (fromRoute == Destination.SETTINGS.route && toRoute == Destination.LIBRARY.route)
    ) {
        // Home and Settings are opposite anchors: keep both directions visually consistent.
        return if (fromRoute == Destination.LIBRARY.route) {
            AnimatedContentTransitionScope.SlideDirection.Left
        } else {
            AnimatedContentTransitionScope.SlideDirection.Right
        }
    }
    return if (to > from) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
}

@Composable
internal fun FilterSwitch(label: String, checked: Boolean, onToggle: () -> Unit) = ListItem(
    headlineContent = { Text(label) },
    trailingContent = { Switch(checked, null) },
    modifier = Modifier.clip(RoundedCornerShape(16.dp)).toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
)

@Composable
internal fun <T> FilterDropdown(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    localizeChoices: (T) -> Boolean = { true }
) {
    var expanded by remember { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    fun choiceLabel(value: T, label: String) = if (localizeChoices(value)) AppLocalizer.text(label, language) else label
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box {
            ListItem(
                headlineContent = {
                    androidx.compose.material3.Text(
                        choiceLabel(selected, choices.firstOrNull { it.first == selected }?.second ?: ""),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { expanded = true },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
            DropdownMenu(expanded, { expanded = false }, Modifier.width(320.dp).heightIn(max = 480.dp)) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { androidx.compose.material3.Text(choiceLabel(choice.first, choice.second), Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { if (choice.first == selected) Icon(Icons.Default.Check, null) },
                        onClick = { onSelect(choice.first); expanded = false }
                    )
                }
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCollection(
    games: List<GameEntity>, viewMode: LibraryViewMode, columns: Int,
    onGame: (String) -> Unit, onFavorite: (String) -> Unit,
    selected: Set<String> = emptySet(), onLongGame: ((String) -> Unit)? = null,
    onQuickGame: ((String) -> Unit)? = null,
    onResetFilters: (() -> Unit)? = null
) {
    if (games.isEmpty()) {
        LibraryEmptyResults(onResetFilters)
        return
    }
    if (viewMode == LibraryViewMode.GRID) {
        BoxWithConstraints {
            val gridCells = if (maxWidth >= 600.dp) GridCells.Adaptive(180.dp) else GridCells.Fixed(columns.coerceIn(2, 4))
            LazyVerticalGrid(
                gridCells, contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = PageBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) { items(games, key = { it.id }) { GameGridCard(it, onGame, onFavorite, it.id in selected, onLongGame, onQuickGame) } }
        }
    } else LazyVerticalGrid(GridCells.Fixed(if (LandscapeLayout) 2 else 1), contentPadding = PaddingValues(top = 6.dp, bottom = PageBottomPadding)) {
        items(games, key = { it.id }) { game ->
            Column { ListItem(
                modifier = Modifier.semantics { this.selected = game.id in selected }.combinedClickable(onClick = { onGame(game.id) }, onLongClick = onLongGame?.let { { it(game.id) } }),
                headlineContent = { MaterialText(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { MaterialText(game.engine.readableEngine() + if (game.missing) " • ${AppLocalizer.text("Manquant", LocalAppLanguage.current)}" else "", style = MaterialTheme.typography.bodySmall) },
                leadingContent = { if (game.id in selected) Checkbox(true, { onLongGame?.invoke(game.id) }) else GameCover(game, Modifier.width(56.dp).height(68.dp)) },
                trailingContent = { Row {
                    IconButton(onClick = { onFavorite(game.id) }, Modifier.size(48.dp)) {
                        Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, AppLocalizer.text(if (game.favorite) "Retirer des favoris" else "Ajouter aux favoris"), Modifier.size(20.dp), tint = if (game.favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    if (onQuickGame != null) IconButton(onClick = { onQuickGame(game.id) }, Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, AppLocalizer.text("Actions rapides"), Modifier.size(20.dp)) }
                } },
                colors = ListItemDefaults.colors(containerColor = if (game.id in selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameGridCard(
    game: GameEntity, onGame: (String) -> Unit, onFavorite: (String) -> Unit,
    selected: Boolean, onLongGame: ((String) -> Unit)?, onQuickGame: ((String) -> Unit)?
) {
    Column(Modifier.semantics { this.selected = selected }.combinedClickable(onClick = { onGame(game.id) }, onLongClick = onLongGame?.let { { it(game.id) } })) {
        Box {
            GameCover(game, Modifier.fillMaxWidth().aspectRatio(.72f))
            if (selected) Surface(Modifier.matchParentSize(), RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .28f), border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary)) {}
            CoverActionButton(
                onClick = { onFavorite(game.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                description = if (game.favorite) "Retirer des favoris" else "Ajouter aux favoris"
            ) { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(20.dp), tint = if (game.favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            if (onQuickGame != null) CoverActionButton(
                onClick = { onQuickGame(game.id) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp),
                description = "Actions rapides"
            ) { Icon(Icons.Default.MoreVert, null, Modifier.size(17.dp)) }
        }
        Spacer(Modifier.height(7.dp)); MaterialText(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (game.missing) "Manquant" else game.engine.readableEngine(), style = MaterialTheme.typography.bodySmall, color = if (game.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CoverActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    icon: @Composable () -> Unit
) {
    Box(modifier.size(48.dp).semantics { contentDescription = AppLocalizer.text(description) }.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(32.dp), shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), tonalElevation = 2.dp
        ) { Box(contentAlignment = Alignment.Center) { icon() } }
    }
}

@Composable
internal fun GameCover(game: GameEntity, modifier: Modifier = Modifier) {
    val coverBlur = LocalCoverBlurState.current
    Surface(modifier, RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SportsEsports, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            if (game.coverUri != null) AsyncImage(
                game.coverUri,
                null,
                Modifier.fillMaxSize().then(if (coverBlur.blurred) Modifier.blur(24.dp) else Modifier),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
internal fun EmptyLibrary(onAdd: () -> Unit, onScan: () -> Unit, canScan: Boolean = true) = Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp)); Text("Votre bibliothèque attend ses jeux", style = MaterialTheme.typography.headlineSmall)
        Text("Astra parcourra récursivement tous les niveaux du dossier.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp)); Button(onClick = onAdd) { Text("Ajouter une source") }; if (canScan) TextButton(onClick = onScan) { Text("Scanner") }
    }
}

@Composable
internal fun LibraryEmptyResults(onResetFilters: (() -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 180.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            if (!compact) Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Aucun jeu ne correspond aux filtres", style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
            if (onResetFilters != null) FilledTonalButton(onClick = onResetFilters) { Text("Réinitialiser les filtres") }
        }
    }
}
