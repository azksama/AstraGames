package fr.astragames.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import fr.astragames.app.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import fr.astragames.app.core.model.*
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.CoverBlurMode
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

internal enum class Destination(val route: String, val description: String, val visibleInMenu: Boolean = true) {
    HOME("home", "Accueil"), LIBRARY("library", "Jeux"), SEARCH("search", "Recherche", false),
    COLLECTIONS("collections", "Collections"), UPDATES("updates", "Mises à jour", false), HISTORY("history", "Historique", false), TAGS("tags", "Tags", false), SETTINGS("settings", "Paramètres")
}

internal val menuDestinations = Destination.entries.filter { it.visibleInMenu }
internal val topLevelRoutes = Destination.entries.map { it.route }.toSet()
internal val LocalPageBottomPadding = staticCompositionLocalOf { 32.dp }
internal val PageBottomPadding: androidx.compose.ui.unit.Dp @Composable get() = LocalPageBottomPadding.current
internal val NavigationContentHeight: androidx.compose.ui.unit.Dp
    @Composable get() = with(LocalDensity.current) { (28.sp.toDp() + 38.dp).coerceAtLeast(68.dp) }
internal const val PageTransitionDurationMillis = 150
internal const val SEARCH_WEBVIEW_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/127.0 Mobile Safari/537.36"

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
    AppLocalizer.language = state.settings.language
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { if (it is UiEvent.Message) snackbar.showSnackbar(AppLocalizer.text(it.text)) }
    }
    if (!state.settingsLoaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val locked by viewModel.isLocked.collectAsStateWithLifecycle()
    if (locked) {
        LockScreen(state, viewModel)
        return
    }
    val navController = rememberNavController()
    var coverBlurred by remember(state.settings.coverBlurMode) {
        mutableStateOf(state.settings.coverBlurMode == CoverBlurMode.STARTUP)
    }
    val coverBlurState = CoverBlurState(
        blurred = coverBlurred,
        enabled = state.settings.coverBlurMode != CoverBlurMode.OFF,
        toggle = { coverBlurred = !coverBlurred }
    )
    CompositionLocalProvider(
        LocalAppLanguage provides state.settings.language,
        LocalCoverBlurState provides coverBlurState
    ) {
        if (!state.settings.onboardingCompleted) {
            LanguageTransition(state.settings.language) { visibleLanguage ->
                CompositionLocalProvider(LocalAppLanguage provides visibleLanguage) {
                    val visibleState = state.copy(settings = state.settings.copy(language = visibleLanguage))
                    OnboardingScreen(visibleState, onPickSource, onPickTags, viewModel::setLanguage, viewModel::completeOnboarding, viewModel::connectF95Session, viewModel::disconnectF95)
                }
            }
        } else {
            LanguageTransition(state.settings.language) { visibleLanguage ->
                CompositionLocalProvider(LocalAppLanguage provides visibleLanguage) {
                    val visibleState = state.copy(settings = state.settings.copy(language = visibleLanguage))
                    val entry by navController.currentBackStackEntryAsState()
                    val route = entry?.destination?.route.orEmpty()
                    val topLevel = route.isBlank() || route in topLevelRoutes
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxHeight < 480.dp)
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(snackbar) }
                        ) { padding ->
                            if (topLevel && wide) {
                                Row(Modifier.padding(padding).fillMaxSize()) {
                                    CompactNavigationRail(route, navController)
                                    Box(Modifier.weight(1f).navigationBarsPadding().then(if (route in menuDestinations.map { it.route }) Modifier.pullDownSearch(route, navController).topLevelSwipe(route, navController) else Modifier)) { AppNavHost(navController, visibleState, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder) }
                                }
                            } else Box(Modifier.padding(padding).fillMaxSize()) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .then(if (topLevel) Modifier.navigationBarsPadding() else Modifier)
                                        .then(if (route in menuDestinations.map { it.route }) Modifier.pullDownSearch(route, navController).topLevelSwipe(route, navController) else Modifier)
                                ) { CompositionLocalProvider(LocalPageBottomPadding provides if (topLevel) NavigationContentHeight + 40.dp else 24.dp) {
                                    AppNavHost(navController, visibleState, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder)
                                } }
                                if (topLevel) {
                                    CompactBottomNavigation(route, navController, Modifier.align(Alignment.BottomCenter))

                                }
                            }
                        }
                    }
                    if (visibleState.scanProgress.active) ScanProgressOverlay(visibleState.scanProgress, viewModel::cancelSyncs)
                    if (scanReports.isNotEmpty()) ScanReportDialog(scanReports, viewModel::dismissScanReports)
                    if (visibleState.setupQueue.isNotEmpty() && scanReports.isEmpty()) {
                        NewGamesSetupWizard(visibleState, viewModel, onPickCover)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LanguageTransition(language: AppLanguage, content: @Composable (AppLanguage) -> Unit) {
    AnimatedContent(
        targetState = language,
        transitionSpec = {
            fadeIn(tween(140, delayMillis = PageTransitionDurationMillis)) togetherWith
                fadeOut(tween(PageTransitionDurationMillis))
        },
        label = "language transition"
    ) { visibleLanguage -> content(visibleLanguage) }
}

@Composable
internal fun CompactBottomNavigation(route: String, nav: NavHostController, modifier: Modifier = Modifier) {
    Surface(
        modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.heightIn(min = NavigationContentHeight).padding(horizontal = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) { menuDestinations.forEach { NavIcon(it, route == it.route || (route.isBlank() && it == Destination.HOME), { navigate(nav, it.route) }, modifier = Modifier.weight(1f)) } }
    }
}

@Composable
internal fun Modifier.pullDownSearch(route: String, nav: NavHostController): Modifier {
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val pull = remember(route, threshold, nav) { SearchPullGesture(threshold) { nav.navigate(Destination.SEARCH.route) { launchSingleTop = true } } }
    val connection = remember(pull) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (consumed.y != 0f) pull.reset()
                if (available.y != 0f) pull.drag(available.y)
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                pull.reset()
                return Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection).pointerInput(pull) {
        detectVerticalDragGestures(
            onDragStart = { pull.reset() },
            onDragEnd = { pull.reset() },
            onDragCancel = { pull.reset() },
            onVerticalDrag = { _, amount -> pull.drag(amount) }
        )
    }.semantics {
        customActions = listOf(CustomAccessibilityAction(AppLocalizer.text("Rechercher")) {
            nav.navigate(Destination.SEARCH.route) { launchSingleTop = true }
            true
        })
    }
}

internal class SearchPullGesture(private val threshold: Float, private val onSearch: () -> Unit) {
    private var distance = 0f
    private var opened = false
    fun reset() { distance = 0f; opened = false }
    fun drag(amount: Float) {
        distance = (distance + amount).coerceAtLeast(0f)
        if (!opened && distance >= threshold) { opened = true; onSearch() }
    }
}

internal fun Modifier.topLevelSwipe(route: String, nav: NavHostController): Modifier = pointerInput(route) {
    var horizontal = 0f
    detectHorizontalDragGestures(
        onDragStart = { horizontal = 0f },
        onHorizontalDrag = { _, amount -> horizontal += amount },
        onDragCancel = { horizontal = 0f },
        onDragEnd = {
            if (abs(horizontal) >= 88.dp.toPx()) {
                val current = menuDestinations.indexOfFirst { it.route == route }.let { if (it < 0) 0 else it }
                val target = if (horizontal < 0) {
                    (current + 1) % menuDestinations.size
                } else {
                    (current - 1 + menuDestinations.size) % menuDestinations.size
                }
                navigate(nav, menuDestinations[target].route)
            }
            horizontal = 0f
        }
    )
}

@Composable
internal fun CompactNavigationRail(route: String, nav: NavHostController) {
    Surface(
        Modifier.width(104.dp).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            menuDestinations.forEach { NavIcon(it, route == it.route, { navigate(nav, it.route) }); Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
internal fun NavIcon(destination: Destination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    Box(
        modifier.widthIn(min = 48.dp, max = 96.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(26.dp))
            .background(if (selected || hovered || focused || pressed) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interactions)
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(destination.icon(), AppLocalizer.text(destination.description), modifier = Modifier.size(21.dp),
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(destination.description, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 0.dp)
            ) { Text(badge.toString(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
    }
}

internal fun navigate(nav: NavHostController, route: String) = nav.navigate(route) {
    popUpTo(Destination.HOME.route) { saveState = false }; launchSingleTop = true; restoreState = false
}

internal fun Destination.icon() = when (this) {
    Destination.HOME -> Icons.Default.Home
    Destination.LIBRARY -> Icons.Default.SportsEsports
    Destination.SEARCH -> Icons.Default.Search
    Destination.COLLECTIONS -> Icons.Default.CollectionsBookmark
    Destination.UPDATES -> Icons.Default.Update
    Destination.HISTORY -> Icons.Default.History
    Destination.TAGS -> Icons.Default.Style
    Destination.SETTINGS -> Icons.Default.Settings
}

@Composable
internal fun AppNavHost(
    nav: NavHostController, state: AstraUiState, vm: AstraViewModel,
    onPickSource: () -> Unit, onPickTags: () -> Unit, onPickCover: (String) -> Unit,
    onPickBackupFolder: () -> Unit, onRestoreBackup: () -> Unit, onOpenBackupFolder: () -> Unit
) {
    NavHost(
        navController = nav,
        startDestination = Destination.HOME.route,
        enterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        exitTransition = { fadeOut(tween(PageTransitionDurationMillis)) },
        popEnterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        popExitTransition = { fadeOut(tween(PageTransitionDurationMillis)) }
    ) {
        composable(Destination.HOME.route) {
            HomeScreen(state, onGame = { nav.navigate("game/$it") }, onResume = vm::launchGame,
                onAllGames = { vm.clearFilters(); navigate(nav, Destination.LIBRARY.route) }, onPickSource = onPickSource, onSearch = { nav.navigate(Destination.SEARCH.route) },
                onHistory = { nav.navigate(Destination.HISTORY.route) }, onUpdates = { nav.navigate(Destination.UPDATES.route) })
        }
        composable(Destination.LIBRARY.route) { LibraryScreen(state, vm, { nav.navigate("game/$it") }, onPickSource, { nav.navigate(Destination.SEARCH.route) }) }
        composable(Destination.SEARCH.route) { SearchScreen(state, vm, { nav.navigate("game/$it") }, nav::popBackStack) }
        composable(Destination.COLLECTIONS.route) { CollectionsScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.UPDATES.route) { GameUpdatesScreen(state, vm, nav::popBackStack) { nav.navigate("game/$it") } }
        composable(Destination.HISTORY.route) { HistoryScreen(state, vm, nav::popBackStack) { nav.navigate("game/$it") } }
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
internal fun CompactHeader(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, AppLocalizer.text("Retour")) }
        if (title == "Astra") {
            Image(painterResource(R.mipmap.astra_icon), null, Modifier.padding(end = 10.dp).size(36.dp).clip(RoundedCornerShape(12.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (title == "Astra") androidx.compose.material3.Text("GAMES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
