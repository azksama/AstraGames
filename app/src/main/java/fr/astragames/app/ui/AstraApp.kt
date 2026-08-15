package fr.astragames.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.automirrored.filled.Rule
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
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
import fr.astragames.app.core.metadata.extractF95ThreadUrl
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.repository.GameEdits
import fr.astragames.app.data.repository.CollectionRuleDraft
import fr.astragames.app.data.repository.DuplicateMergePreview
import fr.astragames.app.data.repository.SaveConflictStrategy
import fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup
import fr.astragames.app.launcher.JoiPlayRuntimeInfo
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
private const val PageTransitionDurationMillis = 150

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
        onClick = onClick,
        modifier = Modifier.size(46.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
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
    NavHost(
        navController = nav,
        startDestination = Destination.LIBRARY.route,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(PageTransitionDurationMillis))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(PageTransitionDurationMillis))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(PageTransitionDurationMillis))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(PageTransitionDurationMillis))
        }
    ) {
        composable(Destination.LIBRARY.route) { LibraryScreen(state, vm, { nav.navigate("game/$it") }, onPickSource) }
        composable(Destination.SEARCH.route) { SearchScreen(state, vm, { nav.navigate("game/$it") }) }
        composable(Destination.COLLECTIONS.route) { CollectionsScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.TAGS.route) { TagsScreen(state, vm, onPickTags, nav::popBackStack) }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(state, vm, onPickSource, { nav.navigate(Destination.TAGS.route) }, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder)
        }
        composable("game/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            GameDetailScreen(
                it.arguments?.getString("id").orEmpty(), state, vm, nav::popBackStack, onPickCover,
                onSearchTag = { tagId -> vm.searchByTag(tagId); navigate(nav, Destination.SEARCH.route) }
            )
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
    var selectedGames by remember { mutableStateOf(emptySet<String>()) }
    var quickGame by remember { mutableStateOf<GameEntity?>(null) }
    var bulkFolder by remember { mutableStateOf(false) }
    var bulkTags by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(if (selectedGames.isEmpty()) "Astra" else "${selectedGames.size} sélectionné(s)", if (selectedGames.isEmpty()) "${state.games.size} jeux" else null) {
                if (selectedGames.isNotEmpty()) {
                    IconButton(onClick = { vm.setGamesFavorite(selectedGames) }) { Icon(Icons.Default.Favorite, "Ajouter aux favoris", tint = Color.Red) }
                    IconButton(onClick = { bulkTags = true }) { Icon(Icons.Default.Style, "Ajouter des tags") }
                    IconButton(onClick = { bulkFolder = true }) { Icon(Icons.Default.FolderCopy, "Classer") }
                    IconButton(onClick = { bulkDelete = true }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                    IconButton(onClick = { selectedGames = emptySet() }) { Icon(Icons.Default.Close, "Quitter la sélection") }
                } else {
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
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterStrip(state, vm)
            when {
                state.scanning && state.games.isEmpty() -> CenterMessage("Exploration de tous les sous-dossiers…", Modifier.fillMaxSize(), loading = true)
                state.games.isEmpty() -> EmptyLibrary(onPickSource) { vm.scanAll() }
                else -> GameCollection(
                    state.filteredGames, state.settings.viewMode, state.settings.gridColumns,
                    onGame = { id -> if (selectedGames.isEmpty()) onGame(id) else selectedGames = selectedGames.toggle(id) },
                    onFavorite = vm::toggleFavorite,
                    selected = selectedGames,
                    onLongGame = { selectedGames = selectedGames.toggle(it) },
                    onQuickGame = { id -> quickGame = state.games.firstOrNull { it.id == id } }
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
private fun FilterStrip(state: AstraUiState, vm: AstraViewModel) {
    var showFilters by remember { mutableStateOf(false) }
    val activeCount = with(state.filters) {
        listOfNotNull(sourceId, folderId, systemFolderId, engine, tagIds.takeIf { it.isNotEmpty() }, missingOnly.takeIf { it }, sort.takeIf { it != LibrarySort.TITLE }).size
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(state.filters.favoritesOnly, vm::toggleFavoriteFilter, { Text("Favoris") }, leadingIcon = { Icon(Icons.Default.Star, null) }) }
        item {
            FilterChip(
                selected = activeCount > 0,
                onClick = { showFilters = true },
                label = { Text(if (activeCount == 0) "Filtres" else "Filtres ($activeCount)") },
                leadingIcon = { Icon(Icons.Default.Tune, null) }
            )
        }
    }
    if (showFilters) LibraryFiltersDialog(state, vm) { showFilters = false }
}

@Composable
private fun LibraryFiltersDialog(state: AstraUiState, vm: AstraViewModel, onDismiss: () -> Unit) {
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
                        item { FilterSwitch("Favoris uniquement", state.filters.favoritesOnly, vm::toggleFavoriteFilter) }
                        item { FilterSwitch("Jeux introuvables", state.filters.missingOnly, vm::toggleMissingFilter) }
                        item { FilterChoiceSection("Moteur", listOf(null to "Tous") + GameEngine.entries.map { it to it.name.readableEngine() }, state.filters.engine, vm::filterEngine) }
                        item { FilterChoiceSection("Source", listOf(null to "Toutes") + state.sources.map { it.id to it.displayName }, state.filters.sourceId, vm::filterSource) }
                        item { FilterChoiceSection("Dossier Astra", listOf(null to "Tous") + state.folders.map { it.id to it.name }, state.filters.folderId, vm::filterFolder) }
                        item { FilterChoiceSection("Dossier système", listOf(null to "Tous") + state.systemFolders.map { it.id to it.label }, state.filters.systemFolderId, vm::filterSystemFolder) }
                        item { FilterChoiceSection("Tri", LibrarySort.entries.map { it to it.label() }, state.filters.sort, vm::setSort) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Tags", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { pickTags = true }, Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Style, null); Spacer(Modifier.width(8.dp)); Text("${state.filters.tagIds.size} tag(s) sélectionné(s)")
                                }
                                if (state.filters.tagIds.isNotEmpty()) FilterChoiceSection(
                                    "Correspondance",
                                    listOf(TagMatchMode.ALL to "Tous", TagMatchMode.ANY to "Au moins un", TagMatchMode.EXCLUDE to "Exclure"),
                                    state.filters.tagMode,
                                    vm::setTagMode
                                )
                            }
                        }
                    }
                    Button(onClick = onDismiss, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Afficher les jeux") }
                }
            }
        }
    }
    if (pickTags) GameTagPickerSheet(state.tags, state.tagCategories, state.filters.tagIds, {
        vm.setTagFilters(it); pickTags = false
    }, { pickTags = false })
}

@Composable
private fun FilterSwitch(label: String, checked: Boolean, onToggle: () -> Unit) = ListItem(
    headlineContent = { Text(label) },
    trailingContent = { Switch(checked, { onToggle() }) },
    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onToggle),
    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
)

@Composable
private fun <T> FilterChoiceSection(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(choices) { choice -> FilterChip(choice.first == selected, { onSelect(choice.first) }, { Text(choice.second, maxLines = 1) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCollection(
    games: List<GameEntity>, viewMode: LibraryViewMode, columns: Int,
    onGame: (String) -> Unit, onFavorite: (String) -> Unit,
    selected: Set<String> = emptySet(), onLongGame: ((String) -> Unit)? = null,
    onQuickGame: ((String) -> Unit)? = null
) {
    if (games.isEmpty()) { CenterMessage("Aucun jeu ne correspond aux filtres", Modifier.fillMaxSize()); return }
    if (viewMode == LibraryViewMode.GRID) {
        BoxWithConstraints {
            val gridCells = if (maxWidth >= 600.dp) GridCells.Adaptive(180.dp) else GridCells.Fixed(columns.coerceIn(2, 4))
            LazyVerticalGrid(
                gridCells, contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = PageBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) { items(games, key = { it.id }) { GameGridCard(it, onGame, onFavorite, it.id in selected, onLongGame, onQuickGame) } }
        }
    } else LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = PageBottomPadding)) {
        items(games, key = { it.id }) { game ->
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onGame(game.id) }, onLongClick = { onLongGame?.invoke(game.id) }),
                headlineContent = { Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(game.engine.readableEngine() + if (game.missing) " • Manquant" else "") },
                leadingContent = { if (game.id in selected) Checkbox(true, { onLongGame?.invoke(game.id) }) else GameCover(game, Modifier.width(52.dp).aspectRatio(.72f)) },
                trailingContent = { Row {
                    IconButton(onClick = { onFavorite(game.id) }, Modifier.size(36.dp)) {
                        Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", Modifier.size(20.dp), tint = if (game.favorite) Color.Red else LocalContentColor.current)
                    }
                    if (onQuickGame != null) IconButton(onClick = { onQuickGame(game.id) }, Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Actions rapides", Modifier.size(20.dp)) }
                } },
                colors = ListItemDefaults.colors(containerColor = if (game.id in selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameGridCard(
    game: GameEntity, onGame: (String) -> Unit, onFavorite: (String) -> Unit,
    selected: Boolean, onLongGame: ((String) -> Unit)?, onQuickGame: ((String) -> Unit)?
) {
    Column(Modifier.combinedClickable(onClick = { onGame(game.id) }, onLongClick = { onLongGame?.invoke(game.id) })) {
        Box {
            GameCover(game, Modifier.fillMaxWidth().aspectRatio(.72f))
            if (selected) Surface(Modifier.matchParentSize(), RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .28f), border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary)) {}
            CoverActionButton(
                onClick = { onFavorite(game.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                description = "Favori"
            ) { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(16.dp), tint = if (game.favorite) Color.Red else LocalContentColor.current) }
            if (onQuickGame != null) CoverActionButton(
                onClick = { onQuickGame(game.id) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp),
                description = "Actions rapides"
            ) { Icon(Icons.Default.MoreVert, null, Modifier.size(17.dp)) }
        }
        Spacer(Modifier.height(7.dp)); Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(game.engine.readableEngine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoverActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    icon: @Composable () -> Unit
) {
    Box(modifier.size(40.dp).semantics { contentDescription = description }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(28.dp), shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), tonalElevation = 2.dp
        ) { Box(contentAlignment = Alignment.Center) { icon() } }
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
    var systemFolderMenu by remember { mutableStateOf(false) }
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

@Composable
private fun CollectionsScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var smartKey by rememberSaveable { mutableStateOf<String?>(null) }
    var customCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var deleting by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var editingCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var collectionEditor by remember { mutableStateOf(false) }
    var deletingCollection by remember { mutableStateOf<CollectionEntity?>(null) }
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
    val selectedCustom = state.collections.firstOrNull { it.id == customCollectionId }
    val displayedGames = when {
        selectedSmart != null -> state.games.filter(selectedSmart.predicate)
        selectedCustom != null -> state.customCollectionGames[selectedCustom.id].orEmpty()
        currentId != null -> state.games.filter { it.libraryFolderId == currentId }
        else -> emptyList()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(
                selectedSmart?.name ?: selectedCustom?.name ?: current?.name ?: "Collections",
                subtitle = if (current != null) "${visibleFolders.size} sous-dossier(s)" else null,
                onBack = when {
                    selectedSmart != null -> ({ smartKey = null })
                    selectedCustom != null -> ({ customCollectionId = null })
                    current != null -> ({ currentId = current.parentId })
                    else -> null
                }
            )
        }
    ) { padding ->
        if (selectedSmart != null || selectedCustom != null) {
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
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Mes collections intelligentes")
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { editingCollection = null; collectionEditor = true },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
                        ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("Créer") }
                    }
                }
                if (state.collections.isEmpty()) item { Text(
                    "Créez une collection avec des règles ET/OU.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                ) }
                items(state.collections, key = { "collection-${it.id}" }) { collection ->
                    RoundedListItem(
                        modifier = Modifier.clickable { customCollectionId = collection.id },
                        headlineContent = { Text(collection.name) },
                        supportingContent = {
                            val rules = state.collectionRules.count { it.collectionId == collection.id }
                            Text("${state.customCollectionGames[collection.id].orEmpty().size} jeux • $rules règle(s) ${if (collection.matchMode == "ANY") "OU" else "ET"}")
                        },
                        leadingContent = { Icon(Icons.Default.FilterAlt, null) },
                        trailingContent = { Row {
                            CompactActionButton({ editingCollection = collection; collectionEditor = true }, Icons.Default.Edit, "Modifier")
                            CompactActionButton({ deletingCollection = collection }, Icons.Default.DeleteOutline, "Supprimer")
                        } }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("Dossiers Astra") }
            }
            item {
                FilledTonalButton(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("Nouveau dossier")
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
                            CompactActionButton({ editing = folder }, Icons.Default.Edit, "Modifier")
                            CompactActionButton({ deleting = folder }, Icons.Default.Delete, "Supprimer")
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
    if (collectionEditor) SmartCollectionEditorDialog(
        collection = editingCollection,
        existingRules = editingCollection?.let { selected -> state.collectionRules.filter { it.collectionId == selected.id } }.orEmpty(),
        state = state,
        onSave = { id, name, mode, rules -> vm.saveCollection(id, name, mode, rules); collectionEditor = false },
        onDismiss = { collectionEditor = false }
    )
    deletingCollection?.let { collection -> ConfirmDialog(
        "Supprimer ${collection.name} ?", "Les jeux et leurs fichiers seront conservés.",
        { vm.deleteCollection(collection.id); deletingCollection = null }, { deletingCollection = null }
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
    var showRuntimes by remember { mutableStateOf(false) }
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
            item { RoundedListItem(
                modifier = Modifier.clickable { showRuntimes = true },
                headlineContent = { Text(if (state.joiPlayInstalled) "Gestionnaire de runtimes" else "JoiPlay non détecté") },
                supportingContent = { Text("${state.runtimes.count { it.installed }}/${state.runtimes.size} composants détectés") },
                leadingContent = { Icon(Icons.Default.Extension, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
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
    if (showDuplicates) DuplicatesDialog(state, vm) { showDuplicates = false }
    if (showDeleted) DeletedGamesDialog(state.deletedGames, vm::restoreDeletedGame) { showDeleted = false }
    if (showRuntimes) RuntimeManagerDialog(state.runtimes) { showRuntimes = false }
    if (confirmRestore) ConfirmDialog(
        "Restaurer une sauvegarde ?",
        "Le catalogue actuel sera remplacé par la sauvegarde sélectionnée. Les fichiers des jeux ne seront pas modifiés.",
        { confirmRestore = false; onRestoreBackup() },
        { confirmRestore = false }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameDetailScreen(
    id: String, state: AstraUiState, vm: AstraViewModel, onBack: () -> Unit,
    onPickCover: (String) -> Unit, onSearchTag: (String) -> Unit
) {
    val game by remember(id) { vm.game(id) }.collectAsStateWithLifecycle(null)
    val assignedTags by remember(id) { vm.gameTags(id) }.collectAsStateWithLifecycle(emptyList())
    val launchProfile by remember(id) { vm.launchProfile(id) }.collectAsStateWithLifecycle(null)
    val compatibilityByGame by vm.compatibility.collectAsStateWithLifecycle()
    val coverState by vm.coverSearch.collectAsStateWithLifecycle()
    val f95State by vm.f95Import.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
            Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        Text(
                            item.title,
                            Modifier.clickable {
                                clipboard.setText(AnnotatedString(item.title))
                                android.widget.Toast.makeText(context, "Nom du jeu copié", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(6.dp)); AssistChip(onClick = {}, label = { Text(item.engine.readableEngine()) })
                        item.developer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item.version?.let { Text("Version $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.launchGame(item.id) }, Modifier.weight(1f).height(40.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Jouer") }
                FilledTonalButton(
                    onClick = { vm.toggleFavorite(item.id) }, modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", Modifier.size(20.dp), tint = if (item.favorite) Color.Red else LocalContentColor.current)
                }
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
                        onClick = { pickCover = true; vm.prepareCoverPicker(item.id) },
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
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(assignedTags, key = { it.id }) { tag -> AssistChip(onClick = { onSearchTag(tag.id) }, label = { Text(tag.name) }) } }
            }
            item.f95Url?.let { f95Url ->
                item {
                    OutlinedButton(onClick = { uriHandler.openUri(f95Url) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir la fiche F95Zone")
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text("Informations", style = MaterialTheme.typography.titleMedium)
            }
            item {
                InfoLine("Lancements", item.playCount.toString())
                InfoLine("Dernier lancement", item.lastPlayedAt?.asDateTime() ?: "Jamais lancé")
                InfoLine("Temps de jeu", state.playStats[item.id]?.totalDurationMs.asDuration())
                InfoLine("Source", item.physicalPath ?: item.documentUri)
                item.productCode?.let { InfoLine("Code produit", it) }; item.language?.let { InfoLine("Langue", it) }; item.description?.let { InfoLine("Description", it) }
            }
            item { OutlinedButton(onClick = { pickFolder = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderCopy, null); Spacer(Modifier.width(8.dp))
                Text(state.folders.firstOrNull { it.id == item.libraryFolderId }?.name ?: "Classer dans un dossier")
            } }
            item { OutlinedButton(onClick = { vm.openGameSaveFolder(item.id) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir le dossier des sauvegardes")
            } }
            if (item.missing) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Ce jeu est introuvable. Rescannez sa source.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            } }
        }
    }
    if (item != null && edit) EditGameDialog(item, { edits, textTags -> vm.updateGame(item.id, edits, textTags); edit = false }, {
        edit = false; importF95 = true
    }, { edit = false })
    if (item != null && pickTags) GameTagPickerSheet(state.tags, state.tagCategories, assignedTags.map { it.id }.toSet(), {
        vm.setGameTags(item.id, it); pickTags = false
    }, { pickTags = false })
    if (item != null && pickCover) CoverPickerSheet(item, coverState, { vm.chooseRemoteCover(item.id, it) }, {
        onPickCover(item.id); pickCover = false
    }, { vm.removeCover(item.id); pickCover = false }, { pickCover = false; vm.clearCoverSearch() })
    if (item != null && importF95) F95ImportSheet(item, state.tags, f95State, { vm.fetchF95Metadata(item.id, it) }, {
        vm.prepareF95Search(item.id, item.title)
    }, { tags, image ->
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
    var version by remember(game.id) { mutableStateOf(game.version.orEmpty()) }
    var language by remember(game.id) { mutableStateOf(game.language.orEmpty()) }
    var textTags by remember(game.id) { mutableStateOf("") }
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
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            Text("Configurer ${game.title}", style = MaterialTheme.typography.titleLarge)
            Text("${state.setupQueue.size} jeu(x) restant(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GameCover(game, Modifier.width(88.dp).aspectRatio(.72f))
                        Column {
                            AssistChip(onClick = {}, label = { Text(game.engine.readableEngine()) })
                            TextButton(onClick = { showCover = true; vm.prepareCoverPicker(game.id) }) { Icon(Icons.Default.ImageSearch, null); Text("Jaquette") }
                        }
                    }
                }
                item { EditField(title, { title = it }, "Nom") }
                item { EditField(developer, { developer = it }, "Développeur") }
                item { EditField(version, { version = it }, "Version") }
                item { EditField(language, { language = it }, "Langue") }
                item { EditField(description, { description = it }, "Description", false) }
                item { TextTagInput(textTags, { textTags = it }) }
                item { OutlinedButton(onClick = { showTags = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Style, null); Spacer(Modifier.width(6.dp)); Text("Tags (${selectedTags.size})") } }
                item { OutlinedButton(onClick = { showF95 = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer depuis F95Zone") } }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = {
                        vm.configureScannedGame(
                            game.id,
                            GameEdits(title, game.originalTitle, developer, version, game.productCode, language, description, game.f95Url),
                            selectedTags,
                            textTags
                        )
                    }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank()
                ) { Text(if (state.setupQueue.size == 1) "Terminer" else "Enregistrer et suivant") }
                TextButton(onClick = { vm.completeGameSetup(game.id) }, Modifier.fillMaxWidth()) { Text("Ignorer ce jeu") }
            }
        }
    }
    if (showTags) GameTagPickerSheet(state.tags, state.tagCategories, selectedTags, { selectedTags = it; showTags = false }, { showTags = false })
    if (showCover) CoverPickerSheet(game, coverState, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(game, state.tags, f95State, { vm.fetchF95Metadata(game.id, it) }, {
        vm.prepareF95Search(game.id, game.title)
    }, { tags, image ->
        importedF95TagNames = tags.map { it.trim().lowercase() }.toSet()
        vm.applyF95Tags(game.id, tags)
        image?.let { vm.chooseF95Cover(game.id, it) }
        showF95 = false
    }, { showF95 = false; vm.clearF95Import() })
}

@Composable
private fun EditGameDialog(game: GameEntity, onSave: (GameEdits, String) -> Unit, onF95: () -> Unit, onDismiss: () -> Unit) {
    var title by remember(game) { mutableStateOf(game.title) }; var original by remember(game) { mutableStateOf(game.originalTitle.orEmpty()) }
    var developer by remember(game) { mutableStateOf(game.developer.orEmpty()) }; var version by remember(game) { mutableStateOf(game.version.orEmpty()) }
    var code by remember(game) { mutableStateOf(game.productCode.orEmpty()) }; var language by remember(game) { mutableStateOf(game.language.orEmpty()) }
    var description by remember(game) { mutableStateOf(game.description.orEmpty()) }
    var f95Url by remember(game) { mutableStateOf(game.f95Url.orEmpty()) }
    var textTags by remember(game) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Modifier le jeu") },
        text = { LazyColumn(Modifier.heightIn(max = 520.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { OutlinedButton(onClick = onF95, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer depuis F95Zone") } }
            item { EditField(title, { title = it }, "Titre") }; item { EditField(original, { original = it }, "Titre original") }
            item { EditField(developer, { developer = it }, "Développeur") }; item { EditField(version, { version = it }, "Version") }
            item { EditField(code, { code = it }, "Code produit") }; item { EditField(language, { language = it }, "Langue") }
            item { EditField(f95Url, { f95Url = it }, "Lien F95Zone") }
            item { EditField(description, { description = it }, "Description", false) }
            item { TextTagInput(textTags, { textTags = it }) }
        } },
        confirmButton = { TextButton(onClick = { onSave(GameEdits(title, original, developer, version, code, language, description, f95Url), textTags) }, enabled = title.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun EditField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
)

@Composable
private fun TextTagInput(value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text("Ajouter des tags") },
    placeholder = { Text("female protagonist, adventure, fantasy") },
    supportingText = { Text("Séparez-les par des virgules ou écrivez [tag] [tag]. Les tags existants seront réutilisés.") },
    minLines = 2,
    maxLines = 4,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
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
    onPrepareSearch: () -> Unit,
    onComplete: (Set<String>, CoverCandidate?) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(game.id) { mutableStateOf(game.f95Url.orEmpty()) }
    val metadata = state.metadata
    var step by remember(game.id) { mutableIntStateOf(0) }
    var selectedTags by remember(metadata?.sourceUrl) { mutableStateOf(metadata?.tags?.toSet().orEmpty()) }
    var selectedImage by remember(metadata?.sourceUrl) { mutableStateOf<CoverCandidate?>(null) }
    var showGoogleSearch by remember(game.id) { mutableStateOf(false) }
    val existingNames = remember(existingTags) { existingTags.map { it.normalizedName }.toSet() }
    LaunchedEffect(metadata?.sourceUrl) {
        if (metadata != null) step = 1
    }
    LaunchedEffect(game.id) { onPrepareSearch() }
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
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { onFetch(url) }, enabled = url.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Analyser le lien") }
                    OutlinedButton(
                        onClick = { showGoogleSearch = true },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        enabled = state.browserUrl != null && !state.loading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Rechercher la fiche sur Google")
                    }
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
    if (showGoogleSearch && state.browserUrl != null) GoogleF95PickerDialog(
        searchUrl = state.browserUrl,
        onThreadSelected = { selectedUrl ->
            showGoogleSearch = false
            url = selectedUrl
            onFetch(selectedUrl)
        },
        onDismiss = { showGoogleSearch = false }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleF95PickerDialog(searchUrl: String, onThreadSelected: (String) -> Unit, onDismiss: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val latestSelection by rememberUpdatedState(onThreadSelected)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader(
                    "Rechercher sur Google",
                    "Appui long sur le bon résultat F95Zone",
                    onBack = onDismiss
                )
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host.orEmpty().lowercase()
                                    return host != "google.com" && !host.endsWith(".google.com")
                                }
                            }
                            setOnLongClickListener {
                                val hit = hitTestResult
                                if (hit.type !in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                                    return@setOnLongClickListener false
                                }
                                val threadUrl = hit.extra?.let(::extractF95ThreadUrl)
                                if (threadUrl == null) {
                                    selectionError = "Ce lien n’est pas un thread F95Zone valide. Maintenez le titre d’un résultat F95Zone."
                                } else {
                                    selectionError = null
                                    latestSelection(threadUrl)
                                }
                                true
                            }
                            loadUrl(searchUrl)
                            webView = this
                        }
                    },
                    update = { webView = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                selectionError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverPickerSheet(
    game: GameEntity, state: CoverSearchState, onCandidate: (CoverCandidate) -> Unit,
    onLocal: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit
) {
    var showGoogleBrowser by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Jaquette de ${game.title}", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Image locale") }
                if (game.coverUri != null) TextButton(onClick = onRemove) { Text("Supprimer") }
            }
            Button(
                onClick = { showGoogleBrowser = true },
                enabled = state.browserUrl != null && !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text("Google Images") }
            when {
                state.downloading -> CenterMessage("Préparation du recadrage…", Modifier.height(220.dp), true)
                state.error != null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> Spacer(Modifier.navigationBarsPadding().height(18.dp))
            }
        }
    }
    if (showGoogleBrowser && state.browserUrl != null) GoogleImagePickerDialog(
        searchUrl = state.browserUrl,
        onCandidate = { showGoogleBrowser = false; onCandidate(it) },
        onDismiss = { showGoogleBrowser = false }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleImagePickerDialog(searchUrl: String, onCandidate: (CoverCandidate) -> Unit, onDismiss: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var contextImageUrl by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val latestCandidate by rememberUpdatedState(onCandidate)
    val uriHandler = LocalUriHandler.current
    fun useImage(url: String, title: String? = null) {
        if (!url.startsWith("https://")) {
            selectionError = "Cette image ne possède pas d’adresse HTTPS exploitable."
            return
        }
        latestCandidate(
            CoverCandidate(
                imageUrl = url,
                thumbnailUrl = url,
                source = android.net.Uri.parse(url).host?.removePrefix("www.").orEmpty(),
                contextUrl = searchUrl,
                matchedTitle = title,
                confidence = 1f
            )
        )
    }
    fun useDisplayedImage() {
        val target = webView ?: return
        selectionError = null
        target.evaluateJavascript(GOOGLE_IMAGE_SELECTION_SCRIPT) { rawResult ->
            val payload = runCatching {
                val jsonText = org.json.JSONTokener(rawResult).nextValue() as? String
                jsonText?.takeIf(String::isNotBlank)?.let { org.json.JSONObject(it) }
            }.getOrNull()
            val url = payload?.optString("url").orEmpty()
            if (url.isBlank()) selectionError = "Touchez d’abord une image pour afficher son aperçu."
            else useImage(url, payload?.optString("title")?.ifBlank { null })
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader("Google Images", "Touchez une image pour afficher son aperçu", onBack = onDismiss)
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host.orEmpty().lowercase()
                                    return host != "google.com" && !host.endsWith(".google.com")
                                }
                            }
                            setOnLongClickListener {
                                val hit = hitTestResult
                                val imageUrl = hit.extra?.takeIf { it.startsWith("https://") }
                                if (imageUrl != null && hit.type in setOf(WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                                    contextImageUrl = imageUrl
                                    true
                                } else false
                            }
                            loadUrl(searchUrl)
                            webView = this
                        }
                    },
                    update = { webView = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Button(
                    onClick = ::useDisplayedImage,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Icon(Icons.Default.Crop, null); Spacer(Modifier.width(8.dp)); Text("Utiliser l’image affichée") }
                selectionError?.let { Text(
                    it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) }
            }
        }
    }
    contextImageUrl?.let { imageUrl ->
        AlertDialog(
            onDismissRequest = { contextImageUrl = null },
            title = { Text("Image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choisissez l’action à effectuer avec cette image.")
                    OutlinedButton(onClick = { uriHandler.openUri(imageUrl); contextImageUrl = null }, Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir directement l’image")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { contextImageUrl = null; useImage(imageUrl) }) { Text("Utiliser cette image") } },
            dismissButton = { TextButton(onClick = { contextImageUrl = null }) { Text("Annuler") } }
        )
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}

private val GOOGLE_IMAGE_SELECTION_SCRIPT = """
    (function() {
      const candidates = [];
      document.querySelectorAll('[aria-selected="true"] [data-iurl], [data-iurl][aria-selected="true"]').forEach(function(node) {
        candidates.push({url: node.getAttribute('data-iurl'), title: node.getAttribute('aria-label') || '', score: 2000000000000});
      });
      document.images.forEach(function(img) {
        const rect = img.getBoundingClientRect();
        const visible = rect.width > 80 && rect.height > 80 && rect.bottom > 0 && rect.top < window.innerHeight;
        const preview = img.matches('.iPVvYb, .sFlh5c, .n3VNCb') || img.closest('[aria-selected="true"]');
        const score = (preview ? 1000000000000 : 0) + (visible ? 10000000000 : 0) + (img.naturalWidth || 0) * (img.naturalHeight || 0);
        [img.currentSrc, img.src, img.getAttribute('data-src')].forEach(function(url) {
          if (url) candidates.push({url: url, title: img.alt || '', score: score});
        });
      });
      const valid = candidates.filter(function(item) { return item.url && item.url.indexOf('https://') === 0; });
      const ranked = valid.sort(function(a, b) { return b.score - a.score; });
      const top = ranked[0];
      const bestOriginal = top && ranked.find(function(item) {
        return item.score >= top.score * 0.9 && item.url.indexOf('google.') < 0 && item.url.indexOf('gstatic.') < 0;
      });
      const best = bestOriginal || top;
      return best ? JSON.stringify({url: best.url, title: best.title || ''}) : '';
    })();
""".trimIndent()

@Composable
private fun CoverCandidateCard(candidate: CoverCandidate, selected: Boolean = false, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coverName = candidate.matchedTitle?.takeIf(String::isNotBlank)
        ?: runCatching { android.net.Uri.parse(candidate.imageUrl).lastPathSegment }.getOrNull()?.takeIf(String::isNotBlank)
        ?: candidate.source.ifBlank { "Image" }
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
                Text(
                    coverName,
                    Modifier.weight(1f).clickable {
                        clipboard.setText(AnnotatedString(coverName))
                        android.widget.Toast.makeText(context, "Nom copié", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickGameActionsSheet(
    game: GameEntity,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(game.title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        ListItem(modifier = Modifier.clickable(onClick = onPlay), headlineContent = { Text("Jouer") }, leadingContent = { Icon(Icons.Default.PlayArrow, null) })
        ListItem(modifier = Modifier.clickable(onClick = onOpen), headlineContent = { Text("Ouvrir la fiche") }, leadingContent = { Icon(Icons.Default.Info, null) })
        ListItem(
            modifier = Modifier.clickable(onClick = onFavorite),
            headlineContent = { Text(if (game.favorite) "Retirer des favoris" else "Ajouter aux favoris") },
            leadingContent = { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (game.favorite) Color.Red else LocalContentColor.current) }
        )
        ListItem(modifier = Modifier.clickable(onClick = onSelect), headlineContent = { Text("Démarrer une sélection multiple") }, leadingContent = { Icon(Icons.Default.Checklist, null) })
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable
private fun SmartCollectionEditorDialog(
    collection: CollectionEntity?,
    existingRules: List<CollectionRuleEntity>,
    state: AstraUiState,
    onSave: (String?, String, String, List<CollectionRuleDraft>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(collection?.id) { mutableStateOf(collection?.name.orEmpty()) }
    var mode by remember(collection?.id) { mutableStateOf(collection?.matchMode ?: "ALL") }
    val rules = remember(collection?.id, existingRules) {
        mutableStateListOf<CollectionRuleDraft>().apply {
            addAll(existingRules.map { CollectionRuleDraft(it.field, it.operator, it.value) })
        }
    }
    var addingRule by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                CompactHeader(if (collection == null) "Nouvelle collection" else "Modifier la collection", onBack = onDismiss) {
                    TextButton(onClick = { onSave(collection?.id, name, mode, rules.toList()) }, enabled = name.isNotBlank() && rules.isNotEmpty()) { Text("Enregistrer") }
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true) }
                    item {
                        Text("Correspondance", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(mode == "ALL", { mode = "ALL" }, { Text("Toutes les règles (ET)") })
                            FilterChip(mode == "ANY", { mode = "ANY" }, { Text("Au moins une (OU)") })
                        }
                    }
                    item { FilledTonalButton(onClick = { editingIndex = null; addingRule = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une règle")
                    } }
                    itemsIndexed(rules, key = { index, _ -> "rule-$index" }) { index, rule ->
                        RoundedListItem(
                            modifier = Modifier.clickable { editingIndex = index; addingRule = true },
                            headlineContent = { Text(rule.summary(state)) },
                            supportingContent = { Text(rule.operatorLabel()) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Rule, null) },
                            trailingContent = { IconButton(onClick = { rules.removeAt(index) }) { Icon(Icons.Default.Close, "Retirer") } }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
    if (addingRule) RuleEditorDialog(
        initial = editingIndex?.let(rules::get), state = state,
        onSave = { rule ->
            val index = editingIndex
            if (index == null) rules += rule else rules[index] = rule
            addingRule = false
        },
        onDismiss = { addingRule = false }
    )
}

@Composable
private fun RuleEditorDialog(
    initial: CollectionRuleDraft?, state: AstraUiState,
    onSave: (CollectionRuleDraft) -> Unit, onDismiss: () -> Unit
) {
    val fields = listOf("ENGINE", "TAG", "FOLDER", "FAVORITE", "COVER", "DATE_ADDED", "LAST_PLAYED", "PLAY_TIME")
    var field by remember { mutableStateOf(initial?.field ?: "ENGINE") }
    var operator by remember(field) { mutableStateOf(initial?.takeIf { it.field == field }?.operator ?: defaultOperator(field)) }
    var value by remember(field) { mutableStateOf(initial?.takeIf { it.field == field }?.value ?: defaultRuleValue(field, state)) }
    val operators = operatorsFor(field)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Règle de collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownSelector("Champ", fields, field, { it.fieldLabel() }) { field = it }
                DropdownSelector("Condition", operators, operator, { it.operatorLabelRaw() }) { operator = it }
                when (field) {
                    "ENGINE" -> DropdownSelector("Moteur", GameEngine.entries.map { it.name }, value, { it.readableEngine() }) { value = it }
                    "TAG" -> SearchableTagSelector(state.tags, value) { value = it }
                    "FOLDER" -> DropdownSelector("Dossier", state.folders.map { it.id }, value, { id -> state.folders.firstOrNull { it.id == id }?.name ?: "Dossier" }) { value = it }
                    "FAVORITE", "COVER" -> DropdownSelector("Valeur", listOf("true", "false"), value, { if (it == "true") "Oui" else "Non" }) { value = it }
                    "LAST_PLAYED" -> if (operator != "NEVER") OutlinedTextField(value, { value = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Nombre de jours") }, singleLine = true)
                    "DATE_ADDED" -> OutlinedTextField(value, { value = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Nombre de jours") }, singleLine = true)
                    "PLAY_TIME" -> OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), label = { Text("Durée en heures") }, singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(CollectionRuleDraft(field, operator, value)) }, enabled = operator == "NEVER" || value.isNotBlank()) { Text("Ajouter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun SearchableTagSelector(tags: List<TagEntity>, selectedId: String, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val sortedTags = remember(tags, query) {
        val collator = java.text.Collator.getInstance(java.util.Locale.FRENCH)
        tags.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith { first, second -> collator.compare(first.name, second.name) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Rechercher un tag") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        if (sortedTags.isEmpty()) Text("Aucun tag trouvé", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sortedTags, key = { it.id }) { tag ->
                ListItem(
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(tag.id) },
                    headlineContent = { Text(tag.name) },
                    supportingContent = tag.groupName?.let { category -> @Composable { Text(category) } },
                    leadingContent = { RadioButton(selected = tag.id == selectedId, onClick = { onSelect(tag.id) }) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (tag.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        }
    }
}

@Composable
private fun <T> DropdownSelector(label: String, values: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Text(text(selected), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp)) {
                values.forEach { value -> DropdownMenuItem(
                    text = { Text(text(value)) },
                    leadingIcon = { if (value == selected) Icon(Icons.Default.Check, null) },
                    onClick = { onSelect(value); expanded = false }
                ) }
            }
        }
    }
}

private fun defaultOperator(field: String) = when (field) {
    "DATE_ADDED", "LAST_PLAYED" -> "WITHIN_DAYS"
    "PLAY_TIME" -> "GREATER_THAN"
    else -> "IS"
}

private fun defaultRuleValue(field: String, state: AstraUiState) = when (field) {
    "ENGINE" -> GameEngine.entries.first().name
    "TAG" -> state.tags.minWithOrNull { first, second ->
        java.text.Collator.getInstance(java.util.Locale.FRENCH).compare(first.name, second.name)
    }?.id.orEmpty()
    "FOLDER" -> state.folders.firstOrNull()?.id.orEmpty()
    "FAVORITE", "COVER" -> "true"
    else -> "7"
}

private fun operatorsFor(field: String) = when (field) {
    "DATE_ADDED" -> listOf("WITHIN_DAYS", "OLDER_THAN_DAYS")
    "LAST_PLAYED" -> listOf("WITHIN_DAYS", "OLDER_THAN_DAYS", "NEVER")
    "PLAY_TIME" -> listOf("GREATER_THAN", "LESS_THAN")
    else -> listOf("IS", "NOT")
}

private fun String.fieldLabel() = when (this) {
    "ENGINE" -> "Moteur"; "TAG" -> "Tag"; "FOLDER" -> "Dossier"; "FAVORITE" -> "Favori"
    "COVER" -> "Jaquette"; "DATE_ADDED" -> "Date d’ajout"; "LAST_PLAYED" -> "Dernier lancement"; "PLAY_TIME" -> "Temps de jeu"
    else -> this
}

private fun String.operatorLabelRaw() = when (this) {
    "IS" -> "Est"; "NOT" -> "N’est pas"; "WITHIN_DAYS" -> "Dans les derniers jours"
    "OLDER_THAN_DAYS" -> "Il y a plus de jours"; "NEVER" -> "Jamais lancé"
    "GREATER_THAN" -> "Plus de"; "LESS_THAN" -> "Moins de"; else -> this
}

private fun CollectionRuleDraft.operatorLabel() = operator.operatorLabelRaw()

private fun CollectionRuleDraft.summary(state: AstraUiState): String {
    val shownValue = when (field) {
        "ENGINE" -> value.readableEngine()
        "TAG" -> state.tags.firstOrNull { it.id == value }?.name ?: "Tag supprimé"
        "FOLDER" -> state.folders.firstOrNull { it.id == value }?.name ?: "Dossier supprimé"
        "FAVORITE", "COVER" -> if (value == "true") "Oui" else "Non"
        "PLAY_TIME" -> "$value h"
        "LAST_PLAYED" -> if (operator == "NEVER") "Jamais" else "$value jours"
        else -> "$value jours"
    }
    return "${field.fieldLabel()} • $shownValue"
}

@Composable
private fun DuplicatesDialog(state: AstraUiState, vm: AstraViewModel, onDismiss: () -> Unit) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup = state.duplicateGroups.firstOrNull { it.key == selectedKey }
    var primaryId by remember(selectedKey) { mutableStateOf(selectedGroup?.games?.firstOrNull()?.id) }
    val secondary = selectedGroup?.games?.firstOrNull { it.id != primaryId }
    val preview by vm.duplicatePreview.collectAsStateWithLifecycle()
    var migrateSaves by remember(selectedKey) { mutableStateOf(true) }
    var strategy by remember(selectedKey) { mutableStateOf(SaveConflictStrategy.KEEP_BOTH) }
    var deleteFiles by remember(selectedKey) { mutableStateOf(false) }
    var confirmPhysical by remember { mutableStateOf(false) }
    LaunchedEffect(primaryId, secondary?.id) {
        if (primaryId != null && secondary != null) vm.previewDuplicateMerge(primaryId!!, secondary.id)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                CompactHeader(
                    selectedGroup?.let { "Comparer les doublons" } ?: "Doublons détectés",
                    selectedGroup?.let { "Choisissez l’exemplaire principal" } ?: "${state.duplicateGroups.size} groupe(s)",
                    onBack = if (selectedGroup != null) ({ selectedKey = null; vm.clearDuplicatePreview() }) else onDismiss
                )
                if (selectedGroup != null && primaryId != null && secondary != null) {
                    DuplicateComparisonContent(
                        group = selectedGroup, state = state, primaryId = primaryId!!, preview = preview,
                        migrateSaves = migrateSaves, onMigrateSaves = { migrateSaves = it },
                        strategy = strategy, onStrategy = { strategy = it }, deleteFiles = deleteFiles,
                        onDeleteFiles = { deleteFiles = it }, onPrimary = { primaryId = it },
                        onIgnore = { vm.ignoreDuplicateGroup(selectedGroup.key); selectedKey = null },
                        onMerge = {
                            if (deleteFiles) confirmPhysical = true else vm.mergeDuplicate(primaryId!!, secondary.id, migrateSaves, strategy, false) { selectedKey = null }
                        }
                    )
                } else if (state.duplicateGroups.isEmpty()) CenterMessage("Aucun doublon détecté", Modifier.fillMaxSize())
                else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(state.duplicateGroups, key = { _, group -> group.key }) { index, group ->
                        RoundedListItem(
                            modifier = Modifier.clickable { selectedKey = group.key; primaryId = group.games.first().id },
                            headlineContent = { Text("${group.games.first().title} • ${group.games.size} exemplaires") },
                            supportingContent = { Text(group.games.joinToString("\n") { it.physicalPath ?: it.documentUri }, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Comparer") }
                        )
                    }
                }
            }
        }
    }
    if (confirmPhysical && primaryId != null && secondary != null) ConfirmDialog(
        "Supprimer le dossier secondaire ?",
        "Les sauvegardes sont traitées avant la suppression. Le dossier ${secondary.title} sera ensuite supprimé définitivement du téléphone.",
        {
            confirmPhysical = false
            vm.mergeDuplicate(primaryId!!, secondary.id, migrateSaves, strategy, true) { selectedKey = null }
        },
        { confirmPhysical = false }
    )
}

@Composable
private fun DuplicateComparisonContent(
    group: DuplicateGroup,
    state: AstraUiState,
    primaryId: String,
    preview: DuplicateMergePreview?,
    migrateSaves: Boolean,
    onMigrateSaves: (Boolean) -> Unit,
    strategy: SaveConflictStrategy,
    onStrategy: (SaveConflictStrategy) -> Unit,
    deleteFiles: Boolean,
    onDeleteFiles: (Boolean) -> Unit,
    onPrimary: (String) -> Unit,
    onIgnore: () -> Unit,
    onMerge: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BoxWithConstraints {
                if (maxWidth >= 700.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.games.forEach { game -> DuplicateGameCard(game, state, game.id == primaryId, { onPrimary(game.id) }, Modifier.weight(1f)) }
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(group.games, key = { it.id }) { game -> DuplicateGameCard(game, state, game.id == primaryId, { onPrimary(game.id) }, Modifier.width(290.dp)) }
                }
            }
        }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sauvegardes", style = MaterialTheme.typography.titleMedium)
            Text(
                if (preview == null) "Recherche des sauvegardes…"
                else "${preview.primarySaves.size} dans le jeu principal • ${preview.secondarySaves.size} à récupérer • ${preview.conflictingSaves.size} conflit(s)"
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(migrateSaves, onMigrateSaves); Text("Récupérer les sauvegardes du doublon")
            }
            if (migrateSaves && (preview?.conflictingSaves?.isNotEmpty() == true)) {
                Text("En cas de même nom", style = MaterialTheme.typography.labelLarge)
                SaveConflictStrategy.entries.forEach { choice -> FilterChip(
                    strategy == choice, { onStrategy(choice) }, { Text(choice.label()) }, Modifier.padding(end = 6.dp)
                ) }
            }
        } } }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Après la fusion", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().clickable { onDeleteFiles(!deleteFiles) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteFiles, onDeleteFiles)
                Column { Text("Supprimer le dossier secondaire"); Text(
                    if (deleteFiles) "Suppression physique irréversible après confirmation" else "Conserver les fichiers et ignorer définitivement ce doublon",
                    style = MaterialTheme.typography.bodySmall, color = if (deleteFiles) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                ) }
            }
        } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onIgnore, Modifier.weight(1f)) { Text("Ignorer ce groupe") }
            Button(onClick = onMerge, Modifier.weight(1f), enabled = preview != null) { Text("Fusionner") }
        } }
        if (group.games.size > 2) item { Text("Après cette fusion, les exemplaires restants seront reproposés un par un.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DuplicateGameCard(game: GameEntity, state: AstraUiState, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Card(
        modifier.clickable(onClick = onSelect),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, { onSelect() }); Text(if (selected) "Exemplaire principal" else "Choisir comme principal", fontWeight = FontWeight.SemiBold) }
        GameCover(game, Modifier.fillMaxWidth().height(150.dp))
        Text(game.title, style = MaterialTheme.typography.titleMedium)
        Text(game.engine.readableEngine())
        Text(game.physicalPath ?: game.documentUri, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Text("${game.playCount} lancements • ${state.playStats[game.id]?.totalDurationMs.asDuration()}", style = MaterialTheme.typography.bodySmall)
        if (game.favorite) Text("Favori", color = Color.Red)
    } }
}

private fun SaveConflictStrategy.label() = when (this) {
    SaveConflictStrategy.KEEP_PRIMARY -> "Garder le principal"
    SaveConflictStrategy.REPLACE_WITH_SECONDARY -> "Remplacer"
    SaveConflictStrategy.KEEP_BOTH -> "Conserver les deux"
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
private fun RuntimeManagerDialog(runtimes: List<JoiPlayRuntimeInfo>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                CompactHeader("Runtimes JoiPlay", "Détection locale des composants", onBack = onDismiss)
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(runtimes, key = { it.key }) { runtime -> RoundedListItem(
                        headlineContent = { Text(runtime.name) },
                        supportingContent = {
                            Text(when {
                                runtime.installed -> "Installé${runtime.versionName?.let { " • version $it" }.orEmpty()}"
                                runtime.required -> "Requis par votre bibliothèque • non installé"
                                else -> "Non requis actuellement • non installé"
                            })
                        },
                        leadingContent = { Icon(
                            if (runtime.installed) Icons.Default.CheckCircle else Icons.Default.Download,
                            null, tint = if (runtime.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        ) },
                        trailingContent = { if (!runtime.installed) TextButton(onClick = { uriHandler.openUri(runtime.downloadUrl) }) { Text("Télécharger") } }
                    ) }
                    item { Text(
                        "Après installation ou mise à jour d’un plugin, fermez puis rouvrez ce gestionnaire pour relancer la détection.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun CompactActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String
) = IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(icon, description, Modifier.size(18.dp))
}

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

private fun Long?.asDuration(): String {
    val totalMinutes = ((this ?: 0L) / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours} h ${minutes} min"
        minutes > 0 -> "$minutes min"
        else -> "0 min"
    }
}
