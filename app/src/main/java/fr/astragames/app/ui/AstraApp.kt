package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import fr.astragames.app.core.metadata.CoverCandidate
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.repository.GameEdits
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import kotlin.math.abs

private enum class Destination(val route: String, val description: String, val visibleInMenu: Boolean = true) {
    LIBRARY("library", "Bibliothèque"), SEARCH("search", "Recherche", false),
    COLLECTIONS("collections", "Collections"), TAGS("tags", "Tags", false), SETTINGS("settings", "Paramètres")
}

private val menuDestinations = Destination.entries.filter { it.visibleInMenu }
private val topLevelRoutes = Destination.entries.map { it.route }.toSet()
private val PageBottomPadding = 32.dp

@Composable
fun AstraApp(
    state: AstraUiState,
    viewModel: AstraViewModel,
    onPickSource: () -> Unit,
    onPickTags: () -> Unit,
    onPickCover: (String) -> Unit,
    onPickBackupFolder: () -> Unit,
    onRestoreBackup: () -> Unit,
    onOpenBackupFolder: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val scanReports by viewModel.scanReports.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { if (it is UiEvent.Message) snackbar.showSnackbar(it.text) }
    }
    if (!state.settings.onboardingCompleted) {
        OnboardingScreen(state.sources.isNotEmpty(), onPickSource, viewModel::completeOnboarding)
        return
    }

    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val topLevel = route.isBlank() || route in topLevelRoutes
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            if (topLevel && wide) {
                Row(Modifier.padding(padding).fillMaxSize()) {
                    CompactNavigationRail(route, navController)
                    Box(Modifier.weight(1f).topLevelSwipe(route, navController)) { AppNavHost(navController, state, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder) }
                }
            } else Box(Modifier.padding(padding).fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(bottom = if (topLevel) 74.dp else 0.dp)
                        .then(if (topLevel) Modifier.topLevelSwipe(route, navController) else Modifier)
                ) { AppNavHost(navController, state, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder) }
                if (topLevel) {
                    CompactBottomNavigation(route, navController, Modifier.align(Alignment.BottomCenter))
                    if (route != Destination.SEARCH.route) FloatingActionButton(
                        onClick = { navigate(navController, Destination.SEARCH.route) },
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 72.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) { Icon(Icons.Default.Search, "Rechercher") }
                }
            }
        }
    }
    if (state.scanProgress.active) ScanProgressOverlay(state.scanProgress)
    if (scanReports.isNotEmpty()) ScanReportDialog(scanReports, viewModel::dismissScanReports)
    if (state.setupQueue.isNotEmpty() && scanReports.isEmpty()) {
        NewGamesSetupWizard(state, viewModel, onPickCover)
    }
}

@Composable
private fun CompactBottomNavigation(route: String, nav: NavHostController, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            Modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp, shadowElevation = 4.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) { menuDestinations.forEach { NavIcon(it, route == it.route || (route.isBlank() && it == Destination.LIBRARY)) { navigate(nav, it.route) } } }
        }
    }
}

private fun Modifier.topLevelSwipe(route: String, nav: NavHostController): Modifier = pointerInput(route) {
    var horizontal = 0f
    detectHorizontalDragGestures(
        onDragStart = { horizontal = 0f },
        onHorizontalDrag = { _, amount -> horizontal += amount },
        onDragCancel = { horizontal = 0f },
        onDragEnd = {
            if (abs(horizontal) >= 88.dp.toPx()) {
                val current = menuDestinations.indexOfFirst { it.route == route }.let { if (it < 0) 0 else it }
                val target = if (horizontal < 0) current + 1 else current - 1
                menuDestinations.getOrNull(target)?.let { navigate(nav, it.route) }
            }
            horizontal = 0f
        }
    )
}

@Composable
private fun CompactNavigationRail(route: String, nav: NavHostController) {
    Surface(
        Modifier.width(72.dp).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) { menuDestinations.forEach { NavIcon(it, route == it.route) { navigate(nav, it.route) }; Spacer(Modifier.height(8.dp)) } }
    }
}

@Composable
private fun NavIcon(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.size(46.dp).clickable(onClick = onClick), RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                destination.icon(), destination.description,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun navigate(nav: NavHostController, route: String) = nav.navigate(route) {
    popUpTo(Destination.LIBRARY.route) { saveState = true }; launchSingleTop = true; restoreState = true
}

private fun Destination.icon() = when (this) {
    Destination.LIBRARY -> Icons.Default.Home
    Destination.SEARCH -> Icons.Default.Search
    Destination.COLLECTIONS -> Icons.Default.CollectionsBookmark
    Destination.TAGS -> Icons.Default.Style
    Destination.SETTINGS -> Icons.Default.Settings
}

@Composable
private fun AppNavHost(
    nav: NavHostController, state: AstraUiState, vm: AstraViewModel,
    onPickSource: () -> Unit, onPickTags: () -> Unit, onPickCover: (String) -> Unit,
    onPickBackupFolder: () -> Unit, onRestoreBackup: () -> Unit, onOpenBackupFolder: () -> Unit
) {
    NavHost(nav, startDestination = Destination.LIBRARY.route) {
        composable(Destination.LIBRARY.route) { LibraryScreen(state, vm, { nav.navigate("game/$it") }, onPickSource) }
        composable(Destination.SEARCH.route) { SearchScreen(state, vm, { nav.navigate("game/$it") }) }
        composable(Destination.COLLECTIONS.route) { CollectionsScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.TAGS.route) { TagsScreen(state, vm, onPickTags, nav::popBackStack) }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(state, vm, onPickSource, { nav.navigate(Destination.TAGS.route) }, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder)
        }
        composable("game/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            GameDetailScreen(it.arguments?.getString("id").orEmpty(), state, vm, nav::popBackStack, onPickCover)
        }
    }
}

@Composable
private fun CompactHeader(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun OnboardingScreen(hasSource: Boolean, onPickSource: () -> Unit, onFinish: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer.copy(.55f)))).padding(32.dp)
    ) {
        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(24.dp)); Text("Toute votre bibliothèque. Un seul ciel.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp)); Text("Astra détecte, classe et lance vos jeux JoiPlay sans modifier leurs fichiers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp)); Button(onClick = onPickSource) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une source") }
            Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = onFinish, enabled = hasSource) { Text("Scanner ma bibliothèque") }
            if (!hasSource) TextButton(onClick = onFinish) { Text("Configurer plus tard") }
        }
    }
}

@Composable
private fun LibraryScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit, onPickSource: () -> Unit) {
    var columnsMenu by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader("Astra Games", "${state.games.size} jeux") {
                Box {
                    IconButton(onClick = { columnsMenu = true }) { Icon(Icons.Default.ViewColumn, "Nombre de colonnes") }
                    DropdownMenu(columnsMenu, { columnsMenu = false }) {
                        (2..4).forEach { columns ->
                            DropdownMenuItem(
                                text = { Text("$columns colonnes") },
                                leadingIcon = { if (state.settings.gridColumns == columns) Icon(Icons.Default.Check, null) },
                                onClick = { vm.setGridColumns(columns); columnsMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.setViewMode(if (state.settings.viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID) }) {
                    Icon(if (state.settings.viewMode == LibraryViewMode.GRID) Icons.AutoMirrored.Filled.List else Icons.Default.GridView, "Changer de vue")
                }
                IconButton(onClick = { vm.scanAll() }) {
                    if (state.scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Scanner")
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterStrip(state, vm)
            when {
                state.scanning && state.games.isEmpty() -> CenterMessage("Exploration de tous les sous-dossiers…", Modifier.fillMaxSize(), loading = true)
                state.games.isEmpty() -> EmptyLibrary(onPickSource) { vm.scanAll() }
                else -> GameCollection(state.filteredGames, state.settings.viewMode, state.settings.gridColumns, onGame, vm::toggleFavorite)
            }
        }
    }
}

@Composable
private fun FilterStrip(state: AstraUiState, vm: AstraViewModel) {
    var folderMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(state.filters.favoritesOnly, vm::toggleFavoriteFilter, { Text("Favoris") }, leadingIcon = { Icon(Icons.Default.Star, null) }) }
        item {
            Box {
                FilterChip(
                    selected = state.filters.folderId != null,
                    onClick = { folderMenu = true },
                    label = { Text(state.folders.firstOrNull { it.id == state.filters.folderId }?.name ?: "Tous les dossiers") },
                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                )
                DropdownMenu(folderMenu, { folderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Tous les dossiers") },
                        leadingIcon = { if (state.filters.folderId == null) Icon(Icons.Default.Check, null) },
                        onClick = { vm.filterFolder(null); folderMenu = false }
                    )
                    state.folders.forEach { folder -> DropdownMenuItem(
                        text = { Text(folder.name) },
                        leadingIcon = { if (state.filters.folderId == folder.id) Icon(Icons.Default.Check, null) },
                        onClick = { vm.filterFolder(folder.id); folderMenu = false }
                    ) }
                }
            }
        }
        item {
            Box {
                FilterChip(false, { sortMenu = true }, { Text(state.filters.sort.label()) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    LibrarySort.entries.forEach { sort -> DropdownMenuItem(
                        text = { Text(sort.label()) },
                        leadingIcon = { if (state.filters.sort == sort) Icon(Icons.Default.Check, null) },
                        onClick = { vm.setSort(sort); sortMenu = false }
                    ) }
                }
            }
        }
        items(state.sources, key = { it.id }) { source ->
            FilterChip(state.filters.sourceId == source.id, { vm.filterSource(if (state.filters.sourceId == source.id) null else source.id) }, { Text(source.displayName, maxLines = 1) })
        }
        if (state.filters.sourceId != null || state.filters.folderId != null || state.filters.engine != null || state.filters.favoritesOnly) item {
            TextButton(onClick = vm::clearFilters) { Text("Effacer") }
        }
    }
}

@Composable
private fun GameCollection(
    games: List<GameEntity>, viewMode: LibraryViewMode, columns: Int,
    onGame: (String) -> Unit, onFavorite: (String) -> Unit
) {
    if (games.isEmpty()) { CenterMessage("Aucun jeu ne correspond aux filtres", Modifier.fillMaxSize()); return }
    if (viewMode == LibraryViewMode.GRID) {
        LazyVerticalGrid(
            GridCells.Fixed(columns.coerceIn(2, 4)), contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = PageBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) { items(games, key = { it.id }) { GameGridCard(it, onGame, onFavorite) } }
    } else LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = PageBottomPadding)) {
        items(games, key = { it.id }) { game ->
            ListItem(
                modifier = Modifier.clickable { onGame(game.id) },
                headlineContent = { Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(game.engine.readableEngine() + if (game.missing) " • Manquant" else "") },
                leadingContent = { GameCover(game, Modifier.width(52.dp).aspectRatio(.72f)) },
                trailingContent = { IconButton(onClick = { onFavorite(game.id) }) { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (game.favorite) Color.Red else LocalContentColor.current) } }
            )
        }
    }
}

@Composable
private fun GameGridCard(game: GameEntity, onGame: (String) -> Unit, onFavorite: (String) -> Unit) {
    Column(Modifier.clickable { onGame(game.id) }) {
        Box {
            GameCover(game, Modifier.fillMaxWidth().aspectRatio(.72f))
            IconButton(
                onClick = { onFavorite(game.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(.88f), RoundedCornerShape(50))
            ) { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (game.favorite) Color.Red else LocalContentColor.current) }
        }
        Spacer(Modifier.height(7.dp)); Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(game.engine.readableEngine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GameCover(game: GameEntity, modifier: Modifier = Modifier) {
    Surface(modifier, RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (game.coverUri != null) AsyncImage(game.coverUri, game.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))),
            contentAlignment = Alignment.Center
        ) { Text(game.title.take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit, onScan: () -> Unit) = Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp)); Text("Votre bibliothèque attend ses jeux", style = MaterialTheme.typography.headlineSmall)
        Text("Astra parcourra récursivement tous les niveaux du dossier.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp)); Button(onClick = onAdd) { Text("Ajouter une source") }; TextButton(onClick = onScan) { Text("Scanner") }
    }
}

@Composable
private fun SearchScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    var engineMenu by remember { mutableStateOf(false) }
    var tagsMenu by remember { mutableStateOf(false) }
    val availableEngines = remember(state.games) {
        state.games.mapNotNull { runCatching { GameEngine.valueOf(it.engine) }.getOrNull() }.distinct()
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Recherche") }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            OutlinedTextField(
                state.filters.query, vm::updateQuery, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Titre, moteur, développeur…") }
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
            GameCollection(state.filteredGames, LibraryViewMode.LIST, state.settings.gridColumns, onGame, vm::toggleFavorite)
        }
    }
}

@Composable
private fun CollectionsScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var smartKey by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var deleting by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    val current = state.folders.firstOrNull { it.id == currentId }
    LaunchedEffect(currentId, state.folders) { if (currentId != null && current == null) currentId = null }
    val visibleFolders = state.folders.filter { it.parentId == currentId }
    val now = System.currentTimeMillis()
    val smartCollections = listOf(
        SmartCollection("favorites", "Favoris", Icons.Default.Favorite) { it.favorite },
        SmartCollection("recent", "Récemment ajoutés", Icons.Default.NewReleases) { now - it.dateAdded < 604_800_000L },
        SmartCollection("last_played", "Joués récemment", Icons.Default.History) { it.lastPlayedAt?.let { date -> now - date < 604_800_000L } == true },
        SmartCollection("never", "Jamais joués", Icons.Default.HourglassEmpty) { it.playCount == 0 },
        SmartCollection("missing", "Jeux manquants", Icons.Default.ErrorOutline) { it.missing },
        SmartCollection("no_cover", "Sans jaquette", Icons.Default.HideImage) { it.coverUri == null }
    )
    val selectedSmart = smartCollections.firstOrNull { it.key == smartKey }
    val displayedGames = when {
        selectedSmart != null -> state.games.filter(selectedSmart.predicate)
        currentId != null -> state.games.filter { it.libraryFolderId == currentId }
        else -> emptyList()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(
                selectedSmart?.name ?: current?.name ?: "Collections",
                subtitle = if (current != null) "${visibleFolders.size} sous-dossier(s)" else null,
                onBack = when {
                    selectedSmart != null -> ({ smartKey = null })
                    current != null -> ({ currentId = current.parentId })
                    else -> null
                }
            )
        }
    ) { padding ->
        if (selectedSmart != null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                GameCollection(displayedGames, state.settings.viewMode, state.settings.gridColumns, onGame, vm::toggleFavorite)
            }
        } else LazyColumn(Modifier.padding(padding).imePadding(), contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = PageBottomPadding)) {
            if (currentId == null) {
                item { SectionTitle("Collections intelligentes") }
                items(smartCollections, key = { it.key }) { smart ->
                    RoundedListItem(
                        modifier = Modifier.clickable { smartKey = smart.key },
                        headlineContent = { Text(smart.name) },
                        supportingContent = { Text("${state.games.count(smart.predicate)} jeux") },
                        leadingContent = { Icon(smart.icon, null, tint = if (smart.key == "favorites") Color.Red else LocalContentColor.current) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("Dossiers Astra") }
            }
            item {
                FilledTonalButton(onClick = { creating = true }, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Nouveau dossier")
                }
            }
            items(visibleFolders, key = { it.id }) { folder ->
                RoundedListItem(
                    modifier = Modifier.clickable { currentId = folder.id },
                    headlineContent = { Text(folder.name) },
                    supportingContent = { Text("${state.folders.count { it.parentId == folder.id }} sous-dossier(s)") },
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = folder }) { Icon(Icons.Default.Edit, "Modifier") }
                            IconButton(onClick = { deleting = folder }) { Icon(Icons.Default.Delete, "Supprimer") }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir", Modifier.align(Alignment.CenterVertically))
                        }
                    }
                )
            }
            if (displayedGames.isNotEmpty()) {
                item { SectionTitle("Jeux dans ce dossier") }
                items(displayedGames, key = { "game-${it.id}" }) { game -> RoundedListItem(
                    modifier = Modifier.clickable { onGame(game.id) },
                    headlineContent = { Text(game.title) },
                    supportingContent = { Text(game.engine.readableEngine()) },
                    leadingContent = { GameCover(game, Modifier.width(46.dp).aspectRatio(.72f)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
                ) }
            }
            if (visibleFolders.isEmpty() && displayedGames.isEmpty()) item { CenterMessage("Aucun dossier ni jeu ici", Modifier.fillMaxWidth().height(120.dp)) }
        }
    }
    if (creating) NameDialog("Nouveau dossier", "", { name -> vm.createFolder(name, currentId); creating = false }, { creating = false })
    editing?.let { folder -> NameDialog("Modifier le dossier", folder.name, { name -> vm.renameFolder(folder, name); editing = null }, { editing = null }) }
    deleting?.let { folder -> ConfirmDialog(
        "Supprimer ${folder.name} ?",
        "Les jeux ne seront pas supprimés. Ses sous-dossiers remonteront au niveau actuel.",
        { vm.deleteFolder(folder); deleting = null },
        { deleting = null }
    ) }
}

private data class SmartCollection(
    val key: String,
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val predicate: (GameEntity) -> Boolean
)

@Composable
private fun TagsScreen(state: AstraUiState, vm: AstraViewModel, onPickTags: () -> Unit, onBack: () -> Unit) {
    var categoriesMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<TagCategoryEntity?>(null) }
    var categoryDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteCategory by remember { mutableStateOf<TagCategoryEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(if (categoriesMode) "Catégories de tags" else "Tags", "${state.tags.size} tags", onBack = onBack) {
                IconButton(onClick = {
                    if (categoriesMode) { editingCategory = null; categoryDialog = true } else { editingTag = null; tagDialog = true }
                }) { Icon(Icons.Default.Add, "Ajouter") }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!categoriesMode, { categoriesMode = false }, { Text("Tags") })
                FilterChip(categoriesMode, { categoriesMode = true; selected = emptySet() }, { Text("Catégories") })
            }
            if (!categoriesMode) FilledTonalButton(
                onClick = onPickTags,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer des tags") }
            if (selected.isNotEmpty() && !categoriesMode) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} sélectionné(s)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { moveDialog = true }) { Text("Classer") }
                    IconButton(onClick = { vm.deleteTags(selected); selected = emptySet() }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                }
            }
            if (categoriesMode) CategoryManager(state, vm, { editingCategory = it; categoryDialog = true }, { deleteCategory = it })
            else TagManager(state, selected, { selected = selected.toggle(it) }, { editingTag = it; tagDialog = true })
        }
    }
    if (tagDialog) TagEditDialog(editingTag, state.tagCategories, {
        if (editingTag == null) vm.createTag(it.first, it.second) else vm.editTag(editingTag!!, it.first, it.second); tagDialog = false
    }, { tagDialog = false })
    if (categoryDialog) NameDialog(if (editingCategory == null) "Nouvelle catégorie" else "Renommer la catégorie", editingCategory?.name.orEmpty(), {
        if (editingCategory == null) vm.createTagCategory(it) else vm.renameTagCategory(editingCategory!!, it); categoryDialog = false
    }, { categoryDialog = false })
    if (moveDialog) CategoryChoiceDialog(state.tagCategories, { vm.moveTagsToCategory(selected, it); selected = emptySet(); moveDialog = false }, { moveDialog = false })
    deleteCategory?.let { category ->
        ConfirmDialog("Supprimer « ${category.name} » ?", "Les tags seront conservés sans catégorie.", {
            vm.deleteTagCategory(category); deleteCategory = null
        }, { deleteCategory = null })
    }
}

@Composable
private fun TagManager(state: AstraUiState, selected: Set<String>, onToggle: (String) -> Unit, onEdit: (TagEntity) -> Unit) {
    val ordered = state.tagCategories.map { it.name }
    val other = state.tags.mapNotNull { it.groupName }.filterNot(ordered.toSet()::contains).distinct().sorted()
    val groups = (ordered + other).map { it to state.tags.filter { tag -> tag.groupName == it } } +
        ("Sans catégorie" to state.tags.filter { it.groupName == null })
    if (state.tags.isEmpty()) CenterMessage("Aucun tag. Utilisez + pour en créer un directement.", Modifier.fillMaxSize())
    else LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
        groups.filter { it.second.isNotEmpty() }.forEach { (category, tags) ->
            item(key = "h-$category") { SectionTitle(category) }
            items(tags, key = { it.id }) { tag ->
                RoundedListItem(
                    modifier = Modifier.clickable { onToggle(tag.id) }, headlineContent = { Text(tag.name) },
                    leadingContent = { Checkbox(tag.id in selected, { onToggle(tag.id) }) },
                    trailingContent = { IconButton(onClick = { onEdit(tag) }) { Icon(Icons.Default.Edit, "Modifier") } }
                )
            }
        }
    }
}

@Composable
private fun CategoryManager(state: AstraUiState, vm: AstraViewModel, onEdit: (TagCategoryEntity) -> Unit, onDelete: (TagCategoryEntity) -> Unit) {
    if (state.tagCategories.isEmpty()) CenterMessage("Créez des catégories puis classez plusieurs tags en une fois.", Modifier.fillMaxSize())
    else LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
        items(state.tagCategories, key = { it.id }) { category ->
            RoundedListItem(
                headlineContent = { Text(category.name) }, supportingContent = { Text("${state.tags.count { it.groupName == category.name }} tag(s)") },
                leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = {
                    Row {
                        IconButton(onClick = { vm.moveTagCategory(category.id, -1) }) { Icon(Icons.Default.ArrowUpward, "Monter") }
                        IconButton(onClick = { vm.moveTagCategory(category.id, 1) }) { Icon(Icons.Default.ArrowDownward, "Descendre") }
                        IconButton(onClick = { onEdit(category) }) { Icon(Icons.Default.Edit, "Renommer") }
                        IconButton(onClick = { onDelete(category) }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AstraUiState,
    vm: AstraViewModel,
    onPickSource: () -> Unit,
    onOpenTags: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onRestoreBackup: () -> Unit,
    onOpenBackupFolder: () -> Unit
) {
    var sourceToDelete by remember { mutableStateOf<GameSourceEntity?>(null) }
    var showDuplicates by remember { mutableStateOf(false) }
    var showDeleted by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Paramètres") }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = PageBottomPadding)) {
            item { SectionTitle("Sources et scan") }
            items(state.sources, key = { it.id }) { source ->
                RoundedListItem(
                    headlineContent = { Text(source.displayName) }, leadingContent = { Icon(Icons.Default.Source, null) },
                    supportingContent = {
                        Column { Text("${source.gamesCount} jeux • ${source.lastScanStatus.lowercase()}"); Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.scanSource(source.id) }) { Text("Scanner") }
                            TextButton(onClick = { vm.showLatestScanReport(source.id) }, enabled = source.lastScanAt != null) { Text("Rapport") }
                            IconButton(onClick = { sourceToDelete = source }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                        } }
                    }, trailingContent = { Switch(source.enabled, { vm.toggleSource(source.id) }) }
                )
            }
            item { RoundedListItem(modifier = Modifier.clickable(onClick = onPickSource), headlineContent = { Text("Ajouter une source") }, leadingContent = { Icon(Icons.Default.Add, null) }) }
            item { SettingsSwitch("Scanner au lancement", state.settings.scanOnLaunch, vm::setScanOnLaunch) }
            item { SectionTitle("Apparence") }; item { SettingsSwitch("Couleurs dynamiques", state.settings.dynamicColor, vm::setDynamicColor) }
            item { LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ThemeMode.entries) { mode -> FilterChip(state.settings.themeMode == mode, { vm.setTheme(mode) }, { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            } }
            item { SectionTitle("JoiPlay") }
            item { RoundedListItem(headlineContent = { Text(if (state.joiPlayInstalled) "JoiPlay détecté" else "JoiPlay non détecté") }, supportingContent = { Text("RPG Maker et plugins Ren'Py pris en charge") }, leadingContent = { Icon(Icons.Default.PlayArrow, null) }) }
            item { SectionTitle("Organisation") }
            item { RoundedListItem(
                modifier = Modifier.clickable(onClick = onOpenTags), headlineContent = { Text("Tags et catégories") },
                supportingContent = { Text("Créer, importer, classer, modifier ou supprimer") },
                leadingContent = { Icon(Icons.Default.Style, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
            item { RoundedListItem(
                modifier = Modifier.clickable { showDuplicates = true }, headlineContent = { Text("Détection des doublons") },
                supportingContent = { Text("${state.duplicateGroups.size} groupe(s) détecté(s)") },
                leadingContent = { Icon(Icons.Default.ContentCopy, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
            item { RoundedListItem(
                modifier = Modifier.clickable { showDeleted = true }, headlineContent = { Text("Jeux supprimés") },
                supportingContent = { Text("${state.deletedGames.size} jeu(x) ignoré(s) pendant les scans") },
                leadingContent = { Icon(Icons.Default.DeleteSweep, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
            item { SectionTitle("Sauvegarde et restauration") }
            item { RoundedListItem(
                modifier = Modifier.clickable(onClick = onPickBackupFolder),
                headlineContent = { Text(if (state.settings.backupFolderUri == null) "Choisir le dossier de sauvegarde" else "Changer le dossier de sauvegarde") },
                supportingContent = { Text(if (state.settings.backupFolderUri == null) "Aucun dossier configuré" else "Dossier configuré et accessible par le bouton rapide ci-dessous") },
                leadingContent = { Icon(Icons.Default.FolderOpen, null) }
            ) }
            if (state.settings.backupFolderUri != null) {
                item { RoundedListItem(
                    modifier = Modifier.clickable { vm.createBackup() }, headlineContent = { Text("Sauvegarder maintenant") },
                    supportingContent = { Text("Catalogue, réglages de jeux et jaquettes") }, leadingContent = { Icon(Icons.Default.Backup, null) }
                ) }
                item { RoundedListItem(
                    modifier = Modifier.clickable(onClick = onOpenBackupFolder), headlineContent = { Text("Ouvrir le dossier de sauvegarde") },
                    leadingContent = { Icon(Icons.Default.FolderSpecial, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir") }
                ) }
            }
            item { RoundedListItem(
                modifier = Modifier.clickable { confirmRestore = true }, headlineContent = { Text("Restaurer une sauvegarde") },
                supportingContent = { Text("Remplace le catalogue par le contenu de l’archive") }, leadingContent = { Icon(Icons.Default.Restore, null) }
            ) }
        }
    }
    sourceToDelete?.let { source -> ConfirmDialog(
        "Retirer la source « ${source.displayName} » ?",
        "Les jeux de cette source seront retirés de la bibliothèque Astra. Aucun fichier ne sera supprimé du téléphone.",
        { vm.removeSource(source.id); sourceToDelete = null },
        { sourceToDelete = null }
    ) }
    if (showDuplicates) DuplicatesDialog(state.duplicateGroups) { showDuplicates = false }
    if (showDeleted) DeletedGamesDialog(state.deletedGames, vm::restoreDeletedGame) { showDeleted = false }
    if (confirmRestore) ConfirmDialog(
        "Restaurer une sauvegarde ?",
        "Le catalogue actuel sera remplacé par la sauvegarde sélectionnée. Les fichiers des jeux ne seront pas modifiés.",
        { confirmRestore = false; onRestoreBackup() },
        { confirmRestore = false }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameDetailScreen(id: String, state: AstraUiState, vm: AstraViewModel, onBack: () -> Unit, onPickCover: (String) -> Unit) {
    val game by remember(id) { vm.game(id) }.collectAsStateWithLifecycle(null)
    val assignedTags by remember(id) { vm.gameTags(id) }.collectAsStateWithLifecycle(emptyList())
    val launchProfile by remember(id) { vm.launchProfile(id) }.collectAsStateWithLifecycle(null)
    val compatibilityByGame by vm.compatibility.collectAsStateWithLifecycle()
    val coverState by vm.coverSearch.collectAsStateWithLifecycle()
    val f95State by vm.f95Import.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf(false) }
    var pickTags by remember { mutableStateOf(false) }
    var pickCover by remember { mutableStateOf(false) }
    var importF95 by remember { mutableStateOf(false) }
    var previewCover by remember { mutableStateOf(false) }
    var confirmCoverRemoval by remember { mutableStateOf(false) }
    var showDiagnostic by remember { mutableStateOf(false) }
    var showLaunchProfile by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var confirmGameRemoval by remember { mutableStateOf(false) }
    val item = game
    val diagnostic = compatibilityByGame[id]
    LaunchedEffect(id, item?.missing, item?.engine, item?.executableName, launchProfile) {
        if (item?.missing == true) vm.verifyGamePresence(id)
        if (item != null) vm.diagnoseGame(id)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompactHeader(item?.title ?: "Jeu", onBack = onBack) {
            IconButton(onClick = { edit = true }, enabled = item != null) { Icon(Icons.Default.Edit, "Modifier") }
            IconButton(onClick = { confirmGameRemoval = true }, enabled = item != null) { Icon(Icons.Default.DeleteOutline, "Supprimer le jeu") }
        } }
    ) { padding ->
        if (item == null) CenterMessage("Chargement…", Modifier.padding(padding).fillMaxSize(), loading = true)
        else LazyColumn(
            Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GameCover(
                        item,
                        Modifier.width(128.dp).aspectRatio(.72f).then(
                            if (item.coverUri != null) Modifier.clickable { previewCover = true } else Modifier
                        )
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp)); AssistChip(onClick = {}, label = { Text(item.engine.readableEngine()) })
                        item.developer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item.version?.let { Text("Version $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.launchGame(item.id) }, Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Jouer") }
                FilledTonalButton(onClick = { vm.toggleFavorite(item.id) }) { Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (item.favorite) Color.Red else LocalContentColor.current) }
            } }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            diagnostic == null -> MaterialTheme.colorScheme.surfaceContainer
                            diagnostic.canLaunch -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (diagnostic?.canLaunch == true) Icons.Default.CheckCircle else Icons.Default.BuildCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Compatibilité", fontWeight = FontWeight.SemiBold)
                                Text(diagnostic?.summary ?: "Vérification en cours…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showDiagnostic = true; vm.diagnoseGame(item.id) }) { Text("Diagnostic") }
                            TextButton(onClick = { showLaunchProfile = true }) { Text("Profil de lancement") }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickCover = true; vm.searchCovers(item.id) },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text("Changer la jaquette") }
                    if (item.coverUri != null) OutlinedButton(
                        onClick = { confirmCoverRemoval = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Icon(Icons.Default.DeleteOutline, "Supprimer la jaquette"); Spacer(Modifier.width(6.dp)); Text("Supprimer") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { pickTags = true }) { Icon(Icons.Default.Add, null); Text("Choisir") }
                }
                if (assignedTags.isEmpty()) Text("Aucun tag associé", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(assignedTags, key = { it.id }) { AssistChip(onClick = { pickTags = true }, label = { Text(it.name) }) } }
            }
            item { HorizontalDivider(); Text("Informations", style = MaterialTheme.typography.titleMedium) }
            item {
                InfoLine("Lancements", item.playCount.toString())
                InfoLine("Dernier lancement", item.lastPlayedAt?.asDateTime() ?: "Jamais lancé")
                InfoLine("Source", item.physicalPath ?: item.documentUri)
                item.productCode?.let { InfoLine("Code produit", it) }; item.language?.let { InfoLine("Langue", it) }; item.description?.let { InfoLine("Description", it) }
            }
            item { OutlinedButton(onClick = { pickFolder = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderCopy, null); Spacer(Modifier.width(8.dp))
                Text(state.folders.firstOrNull { it.id == item.libraryFolderId }?.name ?: "Classer dans un dossier")
            } }
            if (item.missing) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Ce jeu est introuvable. Rescannez sa source.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            } }
        }
    }
    if (item != null && edit) EditGameDialog(item, { vm.updateGame(item.id, it); edit = false }, {
        edit = false; importF95 = true
    }, { edit = false })
    if (item != null && pickTags) GameTagPickerSheet(state.tags, state.tagCategories, assignedTags.map { it.id }.toSet(), {
        vm.setGameTags(item.id, it); pickTags = false
    }, { pickTags = false })
    if (item != null && pickCover) CoverPickerSheet(item, coverState, { vm.chooseRemoteCover(item.id, it) }, {
        onPickCover(item.id); pickCover = false
    }, { vm.removeCover(item.id); pickCover = false }, { vm.searchCovers(item.id) }, { pickCover = false; vm.clearCoverSearch() })
    if (item != null && importF95) F95ImportSheet(item, state.tags, f95State, { vm.fetchF95Metadata(item.id, it) }, { tags, image ->
        vm.applyF95Tags(item.id, tags)
        image?.let { vm.chooseF95Cover(item.id, it) }
        importF95 = false
    }, { importF95 = false; vm.clearF95Import() })
    if (item?.coverUri != null && previewCover) CoverFullscreenDialog(item.coverUri, item.title) { previewCover = false }
    if (item != null && showDiagnostic) CompatibilityDialog(
        game = item, report = diagnostic,
        onRefresh = { vm.diagnoseGame(item.id) },
        onEditProfile = { showDiagnostic = false; showLaunchProfile = true },
        onRescan = { vm.scanSource(item.sourceId); showDiagnostic = false },
        onDismiss = { showDiagnostic = false }
    )
    if (item != null && showLaunchProfile) LaunchProfileDialog(
        game = item, saved = launchProfile,
        onSave = { vm.saveLaunchProfile(it); showLaunchProfile = false },
        onTest = vm::testLaunchProfile,
        onReset = { vm.resetLaunchProfile(item.id); showLaunchProfile = false },
        onDismiss = { showLaunchProfile = false }
    )
    if (item != null && pickFolder) GameFolderDialog(state.folders, item.libraryFolderId, {
        vm.setGameFolder(item.id, it); pickFolder = false
    }, { pickFolder = false })
    if (item != null && confirmGameRemoval) DeleteGameDialog(item, {
        vm.deleteGame(item.id, it, onBack); confirmGameRemoval = false
    }, { confirmGameRemoval = false })
    if (item != null && confirmCoverRemoval) AlertDialog(
        onDismissRequest = { confirmCoverRemoval = false },
        icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text("Supprimer la jaquette ?") },
        text = { Text("Le jeu restera dans la bibliothèque. Seule sa jaquette sera retirée.") },
        confirmButton = { TextButton(onClick = { vm.removeCover(item.id); confirmCoverRemoval = false }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmCoverRemoval = false }) { Text("Annuler") } }
    )
}

@Composable
private fun CompatibilityDialog(
    game: GameEntity,
    report: GameCompatibilityReport?,
    onRefresh: () -> Unit,
    onEditProfile: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 920.dp).align(Alignment.Center)) {
                    CompactHeader("Diagnostic", game.title, onBack = onDismiss) {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Vérifier à nouveau") }
                    }
                    if (report == null) CenterMessage("Vérification de la configuration…", Modifier.fillMaxSize(), loading = true)
                    else LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (report.canLaunch) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (report.canLaunch) Icons.Default.CheckCircle else Icons.Default.Error, null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(report.summary, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        items(report.checks) { check ->
                            ListItem(
                                headlineContent = { Text(check.label) },
                                supportingContent = { Text(check.detail) },
                                leadingContent = {
                                    Icon(
                                        when (check.severity) {
                                            CompatibilitySeverity.OK -> Icons.Default.CheckCircle
                                            CompatibilitySeverity.WARNING -> Icons.Default.Warning
                                            CompatibilitySeverity.ERROR -> Icons.Default.Error
                                        }, null,
                                        tint = when (check.severity) {
                                            CompatibilitySeverity.OK -> MaterialTheme.colorScheme.primary
                                            CompatibilitySeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                            CompatibilitySeverity.ERROR -> MaterialTheme.colorScheme.error
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onRescan, Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Rescanner") }
                        Button(onClick = onEditProfile, Modifier.weight(1f)) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text("Configurer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchProfileDialog(
    game: GameEntity,
    saved: LaunchProfileEntity?,
    onSave: (LaunchProfileEntity) -> Unit,
    onTest: (LaunchProfileEntity) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var launcherType by remember(game.id, saved) { mutableStateOf(saved?.launcherType ?: "JOIPLAY") }
    var engineOverride by remember(game.id, saved) { mutableStateOf(saved?.engineOverride) }
    var executable by remember(game.id, saved) { mutableStateOf(saved?.executableName ?: game.executableName.orEmpty()) }
    var physicalPath by remember(game.id, saved) { mutableStateOf(saved?.physicalPath ?: game.physicalPath.orEmpty()) }
    var customAction by remember(game.id, saved) { mutableStateOf(saved?.customAction.orEmpty()) }
    var packageName by remember(game.id, saved) { mutableStateOf(saved?.packageName.orEmpty()) }
    var arguments by remember(game.id, saved) { mutableStateOf(saved?.arguments.orEmpty()) }
    var launcherMenu by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }
    fun profile() = LaunchProfileEntity(
        gameId = game.id, launcherType = launcherType, engineOverride = engineOverride,
        executableName = executable.trim().ifBlank { null }, physicalPath = physicalPath.trim().ifBlank { null },
        customAction = customAction.trim().ifBlank { null }, packageName = packageName.trim().ifBlank { null },
        arguments = arguments.trim()
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 920.dp).align(Alignment.Center)) {
                    CompactHeader("Profil de lancement", game.title, onBack = onDismiss)
                    LazyColumn(
                        Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "Ces réglages remplacent uniquement la détection automatique pour ce jeu.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item {
                            Box {
                                OutlinedButton(onClick = { launcherMenu = true }, Modifier.fillMaxWidth()) {
                                    Text(if (launcherType == "EXTERNAL") "Application externe" else "JoiPlay")
                                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(launcherMenu, { launcherMenu = false }) {
                                    DropdownMenuItem({ Text("JoiPlay") }, { launcherType = "JOIPLAY"; launcherMenu = false })
                                    DropdownMenuItem({ Text("Application externe") }, { launcherType = "EXTERNAL"; launcherMenu = false })
                                }
                            }
                        }
                        item {
                            Box {
                                OutlinedButton(onClick = { engineMenu = true }, Modifier.fillMaxWidth()) {
                                    Text(engineOverride?.readableEngine() ?: "Automatique (${game.engine.readableEngine()})")
                                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(engineMenu, { engineMenu = false }) {
                                    DropdownMenuItem({ Text("Automatique (${game.engine.readableEngine()})") }, { engineOverride = null; engineMenu = false })
                                    GameEngine.entries.forEach { engine ->
                                        DropdownMenuItem({ Text(engine.name.readableEngine()) }, { engineOverride = engine.name; engineMenu = false })
                                    }
                                }
                            }
                        }
                        item { EditField(physicalPath, { physicalPath = it }, "Chemin du dossier") }
                        item { EditField(executable, { executable = it }, "Exécutable ou fichier d'entrée") }
                        if (launcherType == "EXTERNAL") {
                            item { EditField(packageName, { packageName = it }, "Package Android (facultatif)") }
                            item { EditField(customAction, { customAction = it }, "Action Android") }
                        }
                        item { EditField(arguments, { arguments = it }, "Arguments personnalisés") }
                    }
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onTest(profile()) }, Modifier.weight(1f)) { Icon(Icons.Default.PlayCircle, null); Spacer(Modifier.width(6.dp)); Text("Tester") }
                            Button(onClick = { onSave(profile()) }, Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Enregistrer") }
                        }
                        TextButton(onClick = onReset, Modifier.fillMaxWidth(), enabled = saved != null) { Text("Rétablir la détection automatique") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanReportDialog(reports: List<ScanReport>, onDismiss: () -> Unit) {
    var selectedIndex by remember(reports) { mutableIntStateOf(0) }
    var filter by remember(reports) { mutableStateOf<ScanReportItemStatus?>(null) }
    val report = reports.getOrNull(selectedIndex.coerceAtMost(reports.lastIndex)) ?: return
    val visibleItems = report.items.filter { filter == null || it.status == filter }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 1040.dp).align(Alignment.Center)) {
                    CompactHeader("Rapport de scan", report.sourceName, onBack = onDismiss)
                    if (reports.size > 1) LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(reports) { index, item ->
                            FilterChip(selectedIndex == index, { selectedIndex = index; filter = null }, { Text(item.sourceName) })
                        }
                    }
                    LazyColumn(
                        Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text("Terminé le ${report.finishedAt.asDateTime()} • ${report.visitedFolders} dossiers visités", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { ReportMetric("Trouvés", report.found, MaterialTheme.colorScheme.primaryContainer) }
                                item { ReportMetric("Ajoutés", report.added, MaterialTheme.colorScheme.secondaryContainer) }
                                item { ReportMetric("Actualisés", report.updated, MaterialTheme.colorScheme.tertiaryContainer) }
                                item { ReportMetric("Déjà connus", report.unchanged, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Déplacés", report.moved, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Manquants", report.missing, MaterialTheme.colorScheme.errorContainer) }
                                item { ReportMetric("Ignorés", report.ignored, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Erreurs", report.errors.size, MaterialTheme.colorScheme.errorContainer) }
                            }
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(filter == null, { filter = null }, { Text("Tout (${report.items.size})") }) }
                                items(ScanReportItemStatus.entries) { status ->
                                    val count = report.items.count { it.status == status }
                                    if (count > 0) FilterChip(filter == status, { filter = status }, { Text("${status.label()} ($count)") })
                                }
                            }
                        }
                        if (visibleItems.isEmpty()) item { CenterMessage("Aucun détail disponible pour ce rapport.", Modifier.fillMaxWidth().height(180.dp)) }
                        else items(visibleItems) { item ->
                            ListItem(
                                headlineContent = { Text(item.title ?: item.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Column {
                                        if (item.title != null) Text(item.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(item.reason)
                                    }
                                },
                                leadingContent = { Icon(item.status.icon(), item.status.label(), tint = item.status.tint()) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            )
                        }
                    }
                    Button(onClick = onDismiss, Modifier.fillMaxWidth().padding(16.dp)) { Text("Fermer") }
                }
            }
        }
    }
}

@Composable
private fun ReportMetric(label: String, value: Int, color: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = color) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun ScanReportItemStatus.label() = when (this) {
    ScanReportItemStatus.ADDED -> "Ajoutés"
    ScanReportItemStatus.UPDATED -> "Actualisés"
    ScanReportItemStatus.UNCHANGED -> "Déjà connus"
    ScanReportItemStatus.MOVED -> "Déplacés"
    ScanReportItemStatus.MISSING -> "Manquants"
    ScanReportItemStatus.IGNORED -> "Ignorés"
    ScanReportItemStatus.ERROR -> "Erreurs"
}

private fun ScanReportItemStatus.icon() = when (this) {
    ScanReportItemStatus.ADDED -> Icons.Default.AddCircle
    ScanReportItemStatus.UPDATED -> Icons.Default.Update
    ScanReportItemStatus.UNCHANGED -> Icons.Default.CheckCircle
    ScanReportItemStatus.MOVED -> Icons.AutoMirrored.Filled.DriveFileMove
    ScanReportItemStatus.MISSING -> Icons.Default.LinkOff
    ScanReportItemStatus.IGNORED -> Icons.Default.HideSource
    ScanReportItemStatus.ERROR -> Icons.Default.Error
}

@Composable
private fun ScanReportItemStatus.tint() = when (this) {
    ScanReportItemStatus.ADDED, ScanReportItemStatus.UNCHANGED -> MaterialTheme.colorScheme.primary
    ScanReportItemStatus.UPDATED, ScanReportItemStatus.MOVED -> MaterialTheme.colorScheme.tertiary
    ScanReportItemStatus.MISSING, ScanReportItemStatus.ERROR -> MaterialTheme.colorScheme.error
    ScanReportItemStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGamesSetupWizard(state: AstraUiState, vm: AstraViewModel, onPickCover: (String) -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    if (!accepted) {
        AlertDialog(
            onDismissRequest = vm::dismissGameSetup,
            icon = { Icon(Icons.Default.AutoAwesome, null) },
            title = { Text("${state.setupQueue.size} nouveau(x) jeu(x)") },
            text = { Text("Voulez-vous configurer chaque jeu maintenant ? Astra passera automatiquement au suivant.") },
            confirmButton = { TextButton(onClick = { accepted = true }) { Text("Configurer") } },
            dismissButton = { TextButton(onClick = vm::dismissGameSetup) { Text("Plus tard") } }
        )
        return
    }
    val game = state.setupQueue.firstOrNull() ?: return
    val assigned by remember(game.id) { vm.gameTags(game.id) }.collectAsStateWithLifecycle(emptyList())
    val coverState by vm.coverSearch.collectAsStateWithLifecycle()
    val f95State by vm.f95Import.collectAsStateWithLifecycle()
    var title by remember(game.id) { mutableStateOf(game.title) }
    var description by remember(game.id) { mutableStateOf(game.description.orEmpty()) }
    var developer by remember(game.id) { mutableStateOf(game.developer.orEmpty()) }
    var language by remember(game.id) { mutableStateOf(game.language.orEmpty()) }
    var selectedTags by remember(game.id, assigned) { mutableStateOf(assigned.map { it.id }.toSet()) }
    var showTags by remember { mutableStateOf(false) }
    var showCover by remember { mutableStateOf(false) }
    var showF95 by remember { mutableStateOf(false) }
    var importedF95TagNames by remember(game.id) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.tags, importedF95TagNames) {
        if (importedF95TagNames.isNotEmpty()) {
            selectedTags = selectedTags + state.tags.filter { it.name.trim().lowercase() in importedF95TagNames }.map { it.id }
        }
    }

    ModalBottomSheet(onDismissRequest = vm::dismissGameSetup) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 18.dp, vertical = 4.dp)) {
            Text("Configurer ${game.title}", style = MaterialTheme.typography.titleLarge)
            Text("${state.setupQueue.size} jeu(x) restant(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GameCover(game, Modifier.width(88.dp).aspectRatio(.72f))
                        Column {
                            AssistChip(onClick = {}, label = { Text(game.engine.readableEngine()) })
                            TextButton(onClick = { showCover = true; vm.searchCovers(game.id) }) { Icon(Icons.Default.ImageSearch, null); Text("Jaquette") }
                        }
                    }
                }
                item { EditField(title, { title = it }, "Nom") }
                item { EditField(developer, { developer = it }, "Développeur") }
                item { EditField(language, { language = it }, "Langue") }
                item { EditField(description, { description = it }, "Description", false) }
                item { OutlinedButton(onClick = { showTags = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Style, null); Spacer(Modifier.width(6.dp)); Text("Tags (${selectedTags.size})") } }
                item { OutlinedButton(onClick = { showF95 = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer depuis F95Zone") } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.completeGameSetup(game.id) }) { Text("Ignorer") }
                Button(
                    onClick = {
                        vm.updateGame(game.id, GameEdits(title, game.originalTitle, developer, game.version, game.productCode, language, description))
                        vm.setGameTags(game.id, selectedTags); vm.completeGameSetup(game.id)
                    }, modifier = Modifier.weight(1f), enabled = title.isNotBlank()
                ) { Text(if (state.setupQueue.size == 1) "Terminer" else "Enregistrer et suivant") }
            }
        }
    }
    if (showTags) GameTagPickerSheet(state.tags, state.tagCategories, selectedTags, { selectedTags = it; showTags = false }, { showTags = false })
    if (showCover) CoverPickerSheet(game, coverState, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { vm.searchCovers(game.id) }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(game, state.tags, f95State, { vm.fetchF95Metadata(game.id, it) }, { tags, image ->
        importedF95TagNames = tags.map { it.trim().lowercase() }.toSet()
        vm.applyF95Tags(game.id, tags)
        image?.let { vm.chooseF95Cover(game.id, it) }
        showF95 = false
    }, { showF95 = false; vm.clearF95Import() })
}

@Composable
private fun EditGameDialog(game: GameEntity, onSave: (GameEdits) -> Unit, onF95: () -> Unit, onDismiss: () -> Unit) {
    var title by remember(game) { mutableStateOf(game.title) }; var original by remember(game) { mutableStateOf(game.originalTitle.orEmpty()) }
    var developer by remember(game) { mutableStateOf(game.developer.orEmpty()) }; var version by remember(game) { mutableStateOf(game.version.orEmpty()) }
    var code by remember(game) { mutableStateOf(game.productCode.orEmpty()) }; var language by remember(game) { mutableStateOf(game.language.orEmpty()) }
    var description by remember(game) { mutableStateOf(game.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Modifier le jeu") },
        text = { LazyColumn(Modifier.heightIn(max = 520.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { OutlinedButton(onClick = onF95, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer depuis F95Zone") } }
            item { EditField(title, { title = it }, "Titre") }; item { EditField(original, { original = it }, "Titre original") }
            item { EditField(developer, { developer = it }, "Développeur") }; item { EditField(version, { version = it }, "Version") }
            item { EditField(code, { code = it }, "Code produit") }; item { EditField(language, { language = it }, "Langue") }
            item { EditField(description, { description = it }, "Description", false) }
        } },
        confirmButton = { TextButton(onClick = { onSave(GameEdits(title, original, developer, version, code, language, description)) }, enabled = title.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun EditField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameTagPickerSheet(
    tags: List<TagEntity>, categories: List<TagCategoryEntity>, initial: Set<String>,
    onSave: (Set<String>) -> Unit, onDismiss: () -> Unit
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    var category by remember { mutableStateOf("__all__") }
    var query by remember { mutableStateOf("") }
    val visible = tags.filter {
        (category == "__all__" || (category == "__none__" && it.groupName == null) || it.groupName == category) &&
            (query.isBlank() || it.name.contains(query, true))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Choisir les tags", style = MaterialTheme.typography.titleLarge); Text("${selected.size} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = { onSave(selected) }) { Icon(Icons.Default.Check, null); Text("Valider") }
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Rechercher un tag") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
            )
            LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category == "__all__", { category = "__all__" }, { Text("Tous") }) }
                items(categories, key = { it.id }) { item -> FilterChip(category == item.name, { category = item.name }, { Text(item.name) }) }
                item { FilterChip(category == "__none__", { category = "__none__" }, { Text("Sans catégorie") }) }
            }
            if (visible.isEmpty()) CenterMessage("Aucun tag dans cette catégorie", modifier = Modifier.height(180.dp))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                items(visible, key = { it.id }) { tag ->
                    ListItem(
                        modifier = Modifier.clickable { selected = selected.toggle(tag.id) }, headlineContent = { Text(tag.name) },
                        supportingContent = { tag.groupName?.let { Text(it) } },
                        leadingContent = { Checkbox(tag.id in selected, { selected = selected.toggle(tag.id) }) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun F95ImportSheet(
    game: GameEntity,
    existingTags: List<TagEntity>,
    state: F95ImportState,
    onFetch: (String) -> Unit,
    onComplete: (Set<String>, CoverCandidate?) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(game.id) { mutableStateOf("") }
    val metadata = state.metadata
    var step by remember(game.id) { mutableIntStateOf(0) }
    var selectedTags by remember(metadata?.sourceUrl) { mutableStateOf(metadata?.tags?.toSet().orEmpty()) }
    var selectedImage by remember(metadata?.sourceUrl) { mutableStateOf<CoverCandidate?>(null) }
    val existingNames = remember(existingTags) { existingTags.map { it.normalizedName }.toSet() }
    LaunchedEffect(metadata?.sourceUrl) {
        if (metadata != null) step = 1
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Fermer l'import F95Zone") }
                    Column(Modifier.weight(1f)) {
                        Text("Importer depuis F95Zone", style = MaterialTheme.typography.titleLarge)
                        Text("Étape ${step + 1} sur 3 · ${game.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val contentModifier = if (maxWidth >= 840.dp) {
                        Modifier.widthIn(max = 920.dp).fillMaxHeight().align(Alignment.Center)
                    } else Modifier.fillMaxSize()
                    Column(contentModifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            when (step) {
                0 -> Column(Modifier.fillMaxWidth().heightIn(min = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
                        label = { Text("Lien du thread") }, placeholder = { Text("https://f95zone.to/threads/…") },
                        leadingIcon = { Icon(Icons.Default.Link, null) }
                    )
                    when {
                        state.loading -> CenterMessage("Analyse de la page…", Modifier.weight(1f), true)
                        state.error != null -> CenterMessage(state.error, Modifier.weight(1f))
                        else -> CenterMessage("Chargez le thread pour récupérer ses tags et ses images.", Modifier.weight(1f))
                    }
                    Button(
                        onClick = { onFetch(url) }, enabled = url.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Analyser le lien") }
                }
                1 -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sélectionner les tags", style = MaterialTheme.typography.titleMedium)
                            Text("${selectedTags.size} sur ${metadata?.tags?.size ?: 0} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { selectedTags = metadata?.tags?.toSet().orEmpty() }) { Text("Tout") }
                        TextButton(onClick = { selectedTags = emptySet() }) { Text("Aucun") }
                    }
                    val detectedTags = metadata?.tags.orEmpty()
                    if (detectedTags.isEmpty()) CenterMessage("Aucun tag détecté sur ce thread.", Modifier.height(300.dp))
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                        items(detectedTags, key = { it.lowercase() }) { tag ->
                            val normalized = tag.normalizeTagName()
                            ListItem(
                                modifier = Modifier.clickable { selectedTags = selectedTags.toggle(tag) },
                                headlineContent = { Text(tag) },
                                supportingContent = { Text(if (normalized in existingNames) "Tag existant" else "Nouveau tag · F95Zone") },
                                leadingContent = { Checkbox(tag in selectedTags, { selectedTags = selectedTags.toggle(tag) }) }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 0 }) { Text("Retour") }
                        Button(onClick = { step = 2 }, Modifier.weight(1f)) { Text("Continuer vers les images") }
                    }
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    Text("Choisir une image", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                    Text("L’image choisie pourra être recadrée avant enregistrement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val images = metadata?.images.orEmpty()
                    if (images.isEmpty()) CenterMessage("Aucune image détectée sur ce thread.", Modifier.height(300.dp))
                    else LazyVerticalGrid(
                        GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(images, key = { it.imageUrl }) { image ->
                            CoverCandidateCard(image, selected = selectedImage?.imageUrl == image.imageUrl) { selectedImage = image }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 1 }) { Text("Retour") }
                        if (selectedImage == null) TextButton(onClick = { onComplete(selectedTags, null) }) { Text("Sans image") }
                        Button(
                            onClick = { selectedImage?.let { onComplete(selectedTags, it) } },
                            enabled = selectedImage != null, modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Importer") }
                    }
                }
            }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverPickerSheet(
    game: GameEntity, state: CoverSearchState, onCandidate: (CoverCandidate) -> Unit,
    onLocal: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Jaquette de ${game.title}", style = MaterialTheme.typography.titleLarge)
            Text("10 premiers résultats Google Images : nom + moteur + « game ».", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Image locale") }
                if (game.coverUri != null) TextButton(onClick = onRemove) { Text("Supprimer") }
            }
            when {
                state.loading || state.downloading -> CenterMessage(if (state.downloading) "Préparation du recadrage…" else "Recherche Google…", Modifier.height(320.dp), true)
                state.error != null -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        if (state.configured) TextButton(onClick = onRetry) { Text("Réessayer") }
                        Text("Une image locale reste disponible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.fillMaxWidth().height(430.dp), contentPadding = PaddingValues(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { items(state.results, key = { it.imageUrl }) { candidate -> CoverCandidateCard(candidate) { onCandidate(candidate) } } }
            }
        }
    }
}

@Composable
private fun CoverCandidateCard(candidate: CoverCandidate, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column {
            Box {
                AsyncImage(candidate.imageUrl, candidate.matchedTitle, Modifier.fillMaxWidth().aspectRatio(1.15f), contentScale = ContentScale.Crop)
                if (selected) Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.Check, "Image sélectionnée", Modifier.padding(5.dp).size(18.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.source.ifBlank { "Image" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CoverFullscreenDialog(uri: String, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val imageModifier = if (maxWidth >= 840.dp) Modifier.fillMaxWidth(.75f).fillMaxHeight(.9f) else Modifier.fillMaxSize()
            Surface(imageModifier, color = Color.Black) {
                Box {
                    AsyncImage(
                        uri, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(50))
                    ) { Icon(Icons.Default.Close, "Fermer la jaquette", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun TagEditDialog(tag: TagEntity?, categories: List<TagCategoryEntity>, onSave: (Pair<String, String?>) -> Unit, onDismiss: () -> Unit) {
    var name by remember(tag) { mutableStateOf(tag?.name.orEmpty()) }; var category by remember(tag) { mutableStateOf(tag?.groupName) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (tag == null) "Nouveau tag" else "Modifier le tag") },
        text = { Column {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true)
            Spacer(Modifier.height(12.dp)); Text("Catégorie", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category == null, { category = null }, { Text("Aucune") }) }
                items(categories, key = { it.id }) { item -> FilterChip(category == item.name, { category = item.name }, { Text(item.name) }) }
            }
        } },
        confirmButton = { TextButton(onClick = { onSave(name to category) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun CategoryChoiceDialog(categories: List<TagCategoryEntity>, onChoice: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Classer les tags") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { ListItem(modifier = Modifier.clickable { onChoice(null) }, headlineContent = { Text("Sans catégorie") }, leadingContent = { Icon(Icons.Default.Style, null) }) }
            items(categories, key = { it.id }) { category -> ListItem(modifier = Modifier.clickable { onChoice(category.name) }, headlineContent = { Text(category.name) }, leadingContent = { Icon(Icons.Default.Folder, null) }) }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
)

@Composable
private fun GameFolderDialog(
    folders: List<LibraryFolderEntity>,
    selectedId: String?,
    onChoice: (String?) -> Unit,
    onDismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Classer le jeu") },
    text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { ListItem(
                modifier = Modifier.clickable { onChoice(null) }, headlineContent = { Text("Sans dossier") },
                leadingContent = { RadioButton(selectedId == null, null) }
            ) }
            items(folders, key = { it.id }) { folder -> ListItem(
                modifier = Modifier.clickable { onChoice(folder.id) }, headlineContent = { Text(folder.name) },
                leadingContent = { RadioButton(selectedId == folder.id, null) }
            ) }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
)

@Composable
private fun DeleteGameDialog(game: GameEntity, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, null) },
        title = { Text("Supprimer ${game.title} ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (deleteFiles) "Le dossier complet du jeu sera supprimé définitivement du téléphone." else "Le jeu sera retiré et ignoré lors des prochains scans. Vous pourrez le réautoriser dans Paramètres > Jeux supprimés.")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { deleteFiles = !deleteFiles }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(deleteFiles, { deleteFiles = it })
                    Spacer(Modifier.width(8.dp)); Text("Supprimer aussi les fichiers associés")
                }
                if (deleteFiles) Text("Cette action est irréversible et le jeu ne figurera pas dans l’historique des jeux supprimés.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun DuplicatesDialog(groups: List<List<GameEntity>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                CompactHeader("Doublons détectés", "${groups.size} groupe(s)", onBack = onDismiss)
                if (groups.isEmpty()) CenterMessage("Aucun doublon détecté", Modifier.fillMaxSize())
                else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    groups.forEachIndexed { index, group ->
                        item(key = "duplicate-$index") { SectionTitle("Groupe ${index + 1}") }
                        items(group, key = { it.id }) { game -> RoundedListItem(
                            headlineContent = { Text(game.title) },
                            supportingContent = { Text(game.physicalPath ?: game.documentUri, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.ContentCopy, null) }
                        ) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletedGamesDialog(games: List<DeletedGameEntity>, onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                CompactHeader("Jeux supprimés", "${games.size} jeu(x)", onBack = onDismiss)
                if (games.isEmpty()) CenterMessage("Aucun jeu supprimé", Modifier.fillMaxSize())
                else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(games, key = { it.id }) { game -> RoundedListItem(
                        headlineContent = { Text(game.title) },
                        supportingContent = { Text("Supprimé le ${game.deletedAt.asDateTime()}\n${game.physicalPath ?: game.documentUri}", maxLines = 3) },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, null) },
                        trailingContent = { TextButton(onClick = { onRestore(game.id) }) { Text("Réautoriser") } }
                    ) }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, modifier: Modifier = Modifier, loading: Boolean = false) = Box(modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { if (loading) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)) }; Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ScanProgressOverlay(progress: ScanProgressState) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(Modifier.size(58.dp), strokeWidth = 5.dp)
                Spacer(Modifier.height(24.dp))
                Text("Synchronisation en cours", style = MaterialTheme.typography.headlineSmall)
                if (progress.sourceName.isNotBlank()) Text(
                    progress.sourceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Text(progress.phase.ifBlank { "Préparation…" }, style = MaterialTheme.typography.bodyLarge)
                if (progress.currentPath.isNotBlank()) Text(
                    progress.currentPath,
                    Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SuggestionChip(onClick = {}, label = { Text("${progress.visitedFolders} dossiers") }, icon = { Icon(Icons.Default.FolderOpen, null) })
                    SuggestionChip(onClick = {}, label = { Text("${progress.foundGames} jeux") }, icon = { Icon(Icons.Default.SportsEsports, null) })
                }
                if (progress.depth > 0) Text(
                    "Sous-dossier — niveau ${progress.depth}",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun RoundedListItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ListItem(
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) = RoundedListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })

private fun LibrarySort.label() = when (this) {
    LibrarySort.TITLE -> "Titre"
    LibrarySort.RECENTLY_ADDED -> "Ajouts récents"
    LibrarySort.LAST_PLAYED -> "Dernier lancement"
    LibrarySort.MOST_PLAYED -> "Plus joués"
}

@Composable
private fun InfoLine(label: String, value: String) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(value); Spacer(Modifier.height(9.dp)) }

private fun Set<String>.toggle(id: String): Set<String> = toMutableSet().apply { if (!add(id)) remove(id) }
private fun String.normalizeTagName(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase()
private fun String.readableEngine() = lowercase().replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
private fun Long.asDateTime(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))
