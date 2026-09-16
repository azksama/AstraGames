package fr.astragames.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LIBRARY("library", "Bibliothèque"), SEARCH("search", "Recherche", false),
    COLLECTIONS("collections", "Collections"), UPDATES("updates", "Mises à jour"), HISTORY("history", "Historique"), TAGS("tags", "Tags", false), SETTINGS("settings", "Paramètres")
}

internal val menuDestinations = Destination.entries.filter { it.visibleInMenu }
internal val topLevelRoutes = Destination.entries.map { it.route }.toSet()
internal val PageBottomPadding = 32.dp
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
    val updateBadgeCount by viewModel.updateBadgeCount.collectAsStateWithLifecycle()
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
                        val wide = maxWidth >= 840.dp
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(snackbar) }
                        ) { padding ->
                            if (topLevel && wide) {
                                Row(Modifier.padding(padding).fillMaxSize()) {
                                    CompactNavigationRail(route, navController, updateBadgeCount)
                                    Box(Modifier.weight(1f).topLevelSwipe(route, navController)) { AppNavHost(navController, visibleState, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder) }
                                }
                            } else Box(Modifier.padding(padding).fillMaxSize()) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .padding(bottom = if (topLevel) 74.dp else 0.dp)
                                        .then(if (topLevel) Modifier.topLevelSwipe(route, navController) else Modifier)
                                ) { AppNavHost(navController, visibleState, viewModel, onPickSource, onPickTags, onPickCover, onPickBackupFolder, onRestoreBackup, onOpenBackupFolder) }
                                if (topLevel) {
                                    CompactBottomNavigation(route, navController, Modifier.align(Alignment.BottomCenter), updateBadgeCount)
                                    if (route != Destination.SEARCH.route) FloatingActionButton(
                                        onClick = { navigate(navController, Destination.SEARCH.route) },
                                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 72.dp),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ) { Icon(Icons.Default.Search, AppLocalizer.text("Rechercher", visibleLanguage)) }
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
internal fun CompactBottomNavigation(route: String, nav: NavHostController, modifier: Modifier = Modifier, badgeCount: Int = 0) {
    Surface(
        modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) { menuDestinations.forEach { NavIcon(it, route == it.route || (route.isBlank() && it == Destination.LIBRARY), { navigate(nav, it.route) }, badge = if (it == Destination.UPDATES) badgeCount else 0) } }
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
internal fun CompactNavigationRail(route: String, nav: NavHostController, badgeCount: Int = 0) {
    Surface(
        Modifier.width(72.dp).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) { menuDestinations.forEach { NavIcon(it, route == it.route, { navigate(nav, it.route) }, badge = if (it == Destination.UPDATES) badgeCount else 0); Spacer(Modifier.height(8.dp)) } }
    }
}

@Composable
internal fun NavIcon(destination: Destination, selected: Boolean, onClick: () -> Unit, badge: Int = 0) {
    Box(
        Modifier.size(56.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            destination.icon(), AppLocalizer.text(destination.description),
            modifier = Modifier.size(if (selected) 27.dp else 23.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
        )
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
    popUpTo(Destination.LIBRARY.route) { saveState = false }; launchSingleTop = true; restoreState = false
}

internal fun Destination.icon() = when (this) {
    Destination.LIBRARY -> Icons.Default.Home
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
        startDestination = Destination.LIBRARY.route,
        enterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        exitTransition = { fadeOut(tween(PageTransitionDurationMillis)) },
        popEnterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        popExitTransition = { fadeOut(tween(PageTransitionDurationMillis)) }
    ) {
        composable(Destination.LIBRARY.route) { LibraryScreen(state, vm, { nav.navigate("game/$it") }, onPickSource) }
        composable(Destination.SEARCH.route) { SearchScreen(state, vm, { nav.navigate("game/$it") }, nav::popBackStack) }
        composable(Destination.COLLECTIONS.route) { CollectionsScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.UPDATES.route) { GameUpdatesScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.HISTORY.route) { HistoryScreen(state, vm) }
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
        Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, AppLocalizer.text("Retour")) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
