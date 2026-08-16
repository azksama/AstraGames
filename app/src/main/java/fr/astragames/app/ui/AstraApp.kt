package fr.astragames.app.ui

import fr.astragames.app.BuildConfig
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.CoverBlurMode
import fr.astragames.app.settings.SearchEngine
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import kotlin.math.abs

private enum class Destination(val route: String, val description: String, val visibleInMenu: Boolean = true) {
    LIBRARY("library", "Bibliothèque"), SEARCH("search", "Recherche", false),
    COLLECTIONS("collections", "Collections"), UPDATES("updates", "Mises à jour"), TAGS("tags", "Tags", false), SETTINGS("settings", "Paramètres")
}

private val menuDestinations = Destination.entries.filter { it.visibleInMenu }
private val topLevelRoutes = Destination.entries.map { it.route }.toSet()
private val PageBottomPadding = 32.dp
private const val PageTransitionDurationMillis = 150
private const val SEARCH_WEBVIEW_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/127.0 Mobile Safari/537.36"

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
private fun LanguageTransition(language: AppLanguage, content: @Composable (AppLanguage) -> Unit) {
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
private fun CompactBottomNavigation(route: String, nav: NavHostController, modifier: Modifier = Modifier, badgeCount: Int = 0) {
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

private fun Modifier.topLevelSwipe(route: String, nav: NavHostController): Modifier = pointerInput(route) {
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
private fun CompactNavigationRail(route: String, nav: NavHostController, badgeCount: Int = 0) {
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
private fun NavIcon(destination: Destination, selected: Boolean, onClick: () -> Unit, badge: Int = 0) {
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            destination.icon(), AppLocalizer.text(destination.description),
            modifier = Modifier.size(if (selected) 27.dp else 23.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
        )
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = 0.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) { Text(badge.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun navigate(nav: NavHostController, route: String) = nav.navigate(route) {
    popUpTo(Destination.LIBRARY.route) { saveState = false }; launchSingleTop = true; restoreState = false
}

private fun Destination.icon() = when (this) {
    Destination.LIBRARY -> Icons.Default.Home
    Destination.SEARCH -> Icons.Default.Search
    Destination.COLLECTIONS -> Icons.Default.CollectionsBookmark
    Destination.UPDATES -> Icons.Default.Update
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
        enterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        exitTransition = { fadeOut(tween(PageTransitionDurationMillis)) },
        popEnterTransition = { fadeIn(tween(PageTransitionDurationMillis)) },
        popExitTransition = { fadeOut(tween(PageTransitionDurationMillis)) }
    ) {
        composable(Destination.LIBRARY.route) { LibraryScreen(state, vm, { nav.navigate("game/$it") }, onPickSource) }
        composable(Destination.SEARCH.route) { SearchScreen(state, vm, { nav.navigate("game/$it") }, nav::popBackStack) }
        composable(Destination.COLLECTIONS.route) { CollectionsScreen(state, vm) { nav.navigate("game/$it") } }
        composable(Destination.UPDATES.route) { GameUpdatesScreen(state, vm) { nav.navigate("game/$it") } }
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
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, AppLocalizer.text("Retour")) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun OnboardingScreen(
    state: AstraUiState,
    onPickSource: () -> Unit,
    onPickTags: () -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    onFinish: () -> Unit,
    onConnectSession: (cookies: String, username: String?) -> Unit,
    onDisconnectSession: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    val hasSource = state.sources.isNotEmpty()
    val hasTags = state.tags.isNotEmpty()
    val uriHandler = LocalUriHandler.current
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer.copy(.55f))))
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(18.dp))
            Text("Toute votre bibliothèque. Un seul ciel.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Astra détecte, classe et lance vos jeux JoiPlay sans modifier leurs fichiers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(5) { index ->
                    Box(
                        Modifier.width(if (index == step) 34.dp else 10.dp).height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        0 -> {
                            Text("Choisissez la langue d’Astra", style = MaterialTheme.typography.titleLarge)
                            LanguageSelector(state.settings.language, onSetLanguage)
                            Text("La langue est modifiable à tout moment dans Paramètres.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> {
                            Text("Choisissez votre dossier de jeux", style = MaterialTheme.typography.titleLarge)
                            Text("Astra parcourra récursivement tous les niveaux du dossier.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onPickSource, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une source")
                            }
                            if (hasSource) {
                                state.sources.take(2).forEach { source ->
                                    ListItem(
                                        headlineContent = { Text(source.displayName) },
                                        supportingContent = { Text("Dossier sélectionné") },
                                        leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                            } else Text("Aucun dossier sélectionné", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        2 -> {
                            Text("Importer des tags", style = MaterialTheme.typography.titleLarge)
                            Text("Importez un fichier texte, CSV ou JSON, ou passez cette étape.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onPickTags, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer des tags")
                            }
                            Text(
                                if (hasTags) "${state.tags.size} tags importés" else "Aucun tag importé",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        3 -> {
                            Text("Vérifier les runtimes", style = MaterialTheme.typography.titleLarge)
                            Text("Astra vérifie les composants JoiPlay présents sur le téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.runtimes.isEmpty()) Text("Aucun runtime détecté", color = MaterialTheme.colorScheme.error)
                            else state.runtimes.take(5).forEach { runtime ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (runtime.installed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        null,
                                        tint = if (runtime.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(runtime.name)
                                        Text(
                                            when {
                                                runtime.installed -> "Installé${runtime.versionName?.let { " • $it" }.orEmpty()}"
                                                runtime.required -> "Requis par votre bibliothèque • non installé"
                                                else -> "Non installé"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!runtime.installed) TextButton(onClick = { uriHandler.openUri(runtime.downloadUrl) }) { Text("Télécharger") }
                                }
                            }
                        }
                        else -> {
                            Text("Compte F95Zone (optionnel)", style = MaterialTheme.typography.titleLarge)
                            Text("Connectez-vous pour accéder au contenu réservé aux membres et récupérer les versions des jeux.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (state.settings.f95SessionUser != null) "Connecté en tant que ${state.settings.f95SessionUser}"
                                    else "Session F95Zone : non connectée",
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (state.settings.f95SessionUser != null) {
                                    TextButton(onClick = onDisconnectSession) { Text("Se déconnecter") }
                                } else {
                                    Button(onClick = { showLogin = true }) { Text("Se connecter") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step > 0) TextButton(onClick = { step-- }, Modifier.weight(1f)) { Text("Retour") }
                        else Spacer(Modifier.weight(1f))
                        if (step < 4) {
                            Button(onClick = { step++ }, Modifier.weight(1f)) { Text("Continuer") }
                        } else {
                            Button(onClick = onFinish, Modifier.weight(1f)) { Text("Terminer") }
                        }
                    }
                    if (step == 1 && !hasSource || step == 2 && !hasTags) {
                        TextButton(onClick = { step++ }, Modifier.fillMaxWidth()) { Text("Configurer plus tard") }
                    }
                }
            }
        }
    }
    if (showLogin) F95LoginDialog(
        onSessionReady = { cookies, user ->
            showLogin = false
            onConnectSession(cookies, user)
        },
        onDismiss = { showLogin = false }
    )
}

@Composable
private fun LanguageSelector(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Langue", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Text("${selected.flag}  ${selected.nativeName}", Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 200.dp, max = 320.dp)) {
                AppLanguage.entries.forEach { language ->
                    DropdownMenuItem(
                        text = { Text("${language.flag}  ${language.nativeName}") },
                        leadingIcon = { if (language == selected) Icon(Icons.Default.Check, null) },
                        onClick = { onSelect(language); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchEngineSelector(
    selected: SearchEngine,
    onSelect: (SearchEngine) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Moteur de recherche", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Text(selected.displayName, Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 200.dp, max = 320.dp)) {
                SearchEngine.entries.forEach { engine ->
                    DropdownMenuItem(
                        text = { Text(engine.displayName) },
                        leadingIcon = { if (engine == selected) Icon(Icons.Default.Check, null) },
                        onClick = { onSelect(engine); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverBlurSelector(
    selected: CoverBlurMode,
    onSelect: (CoverBlurMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Flou des jaquettes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Text(selected.label(), Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 220.dp, max = 320.dp)) {
                CoverBlurMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label()) },
                        leadingIcon = { if (mode == selected) Icon(Icons.Default.Check, null) },
                        onClick = { onSelect(mode); expanded = false }
                    )
                }
            }
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
    val coverBlur = LocalCoverBlurState.current
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
                if (state.settings.viewMode == LibraryViewMode.GRID) Box {
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
                if (coverBlur.enabled) IconButton(onClick = coverBlur.toggle) {
                    Icon(
                        if (coverBlur.blurred) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        AppLocalizer.text(if (coverBlur.blurred) "Afficher les jaquettes" else "Flouter les jaquettes")
                    )
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
        listOfNotNull(sourceId, folderId, collectionId, systemFolderId, engine, tagIds.takeIf { it.isNotEmpty() }, missingOnly.takeIf { it }, sort.takeIf { it != LibrarySort.TITLE }).size
    }
    val hasActiveFilters = activeCount > 0 || state.filters.favoritesOnly
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(state.filters.favoritesOnly, vm::toggleFavoriteFilter, { Text("Favoris") }, leadingIcon = { Icon(Icons.Default.Star, null) }) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = { showFilters = true },
                    label = { Text(if (activeCount == 0) "Filtres" else "Filtres ($activeCount)") },
                    leadingIcon = { Icon(Icons.Default.Tune, null) }
                )
                if (hasActiveFilters) IconButton(onClick = vm::clearFilters, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Réinitialiser les filtres", Modifier.size(18.dp))
                }
            }
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
                        item { AstraCollectionsFilterSection(state, vm) }
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

private fun topLevelSlideDirection(
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
private fun AstraCollectionsFilterSection(state: AstraUiState, vm: AstraViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Collections Astra", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.filters.folderId == null && state.filters.collectionId == null,
                    onClick = { vm.filterFolder(null) },
                    label = { Text("Toutes") }
                )
            }
            items(state.folders, key = { "folder-${it.id}" }) { folder ->
                FilterChip(
                    selected = state.filters.folderId == folder.id,
                    onClick = { vm.filterFolder(folder.id) },
                    label = { Text(folder.name, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(17.dp)) }
                )
            }
            items(state.collections, key = { "smart-${it.id}" }) { collection ->
                FilterChip(
                    selected = state.filters.collectionId == collection.id,
                    onClick = { vm.filterCollection(collection.id) },
                    label = { Text(collection.name, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, "Collection intelligente personnelle", Modifier.size(17.dp)) }
                )
            }
        }
    }
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
    Box(modifier.size(40.dp).semantics { contentDescription = AppLocalizer.text(description) }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(28.dp), shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), tonalElevation = 2.dp
        ) { Box(contentAlignment = Alignment.Center) { icon() } }
    }
}

@Composable
private fun GameCover(game: GameEntity, modifier: Modifier = Modifier) {
    val coverBlur = LocalCoverBlurState.current
    Surface(modifier, RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (game.coverUri != null) AsyncImage(
            game.coverUri,
            game.title,
            Modifier.fillMaxSize().then(if (coverBlur.blurred) Modifier.blur(24.dp) else Modifier),
            contentScale = ContentScale.Crop
        )
        else Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))),
            contentAlignment = Alignment.Center
        ) { Text(game.title.take(1).uppercase(java.util.Locale.ROOT), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary) }
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
private fun SearchScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit, onBack: () -> Unit) {
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
                item {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        Modifier.fillMaxWidth().height((((smartCollections.size + 2) / 3) * 118).dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(smartCollections, key = { it.key }) { smart ->
                            Card(
                                Modifier.fillMaxWidth().height(108.dp).clickable { smartKey = smart.key },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(smart.icon, null, Modifier.size(28.dp), tint = if (smart.key == "favorites") Color.Red else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(7.dp))
                                    Text(smart.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${state.games.count(smart.predicate)} jeux", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
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
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("Collections Astra") }
            }
            item {
                FilledTonalButton(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("Nouvelle collection")
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
    if (creating) NameDialog("Nouvelle collection", "", { name -> vm.createFolder(name, currentId); creating = false }, { creating = false })
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
    var mergesMode by remember { mutableStateOf(false) }
    var tagQuery by remember { mutableStateOf("") }
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
            CompactHeader(if (mergesMode) "Fusions de tags" else if (categoriesMode) "Catégories de tags" else "Tags", "${state.tags.size} tags", onBack = onBack) {
                if (!mergesMode) IconButton(onClick = {
                    if (categoriesMode) { editingCategory = null; categoryDialog = true } else { editingTag = null; tagDialog = true }
                }) { Icon(Icons.Default.Add, "Ajouter") }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!categoriesMode && !mergesMode, { categoriesMode = false; mergesMode = false }, { Text("Tags") })
                FilterChip(categoriesMode && !mergesMode, { categoriesMode = true; mergesMode = false; selected = emptySet() }, { Text("Catégories") })
                FilterChip(mergesMode, { mergesMode = true; categoriesMode = false; selected = emptySet() }, { Text("Fusions") })
            }
            if (!categoriesMode && !mergesMode) FilledTonalButton(
                onClick = onPickTags,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer des tags") }
            if (selected.isNotEmpty() && !categoriesMode && !mergesMode) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} sélectionné(s)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { moveDialog = true }) { Text("Classer") }
                    IconButton(onClick = { vm.deleteTags(selected); selected = emptySet() }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                }
            }
            if (mergesMode) TagMergesManager(state, vm)
            else if (categoriesMode) CategoryManager(state, vm, { editingCategory = it; categoryDialog = true }, { deleteCategory = it })
            else {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Rechercher un tag") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true
                )
                TagManager(state, selected, tagQuery, { selected = selected.toggle(it) }, { editingTag = it; tagDialog = true })
            }
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
private fun TagMergesManager(state: AstraUiState, vm: AstraViewModel) {
    val merges by vm.tagMerges.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshTagMerges() }
    if (merges.isEmpty()) {
        CenterMessage("Aucune fusion à vérifier", Modifier.fillMaxSize())
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = PageBottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(merges, key = { "${it.first.id}|${it.second.id}" }) { suggestion ->
                MergeSuggestionCard(suggestion, onMerge = { keep, removed -> vm.mergeTagPair(keep, removed) }, onIgnore = { vm.ignoreTagMerge(suggestion.first, suggestion.second) })
            }
        }
    }
}

@Composable
private fun MergeSuggestionCard(suggestion: TagMergeSuggestion, onMerge: (TagEntity, TagEntity) -> Unit, onIgnore: () -> Unit) {
    var keepFirst by remember(suggestion.first.id, suggestion.second.id) { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ressemblance : ${(suggestion.similarity * 100).toInt()} %", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onIgnore) { Text("Ignorer") }
                TextButton(onClick = { onMerge(if (keepFirst) suggestion.first else suggestion.second, if (keepFirst) suggestion.second else suggestion.first) }) { Text("Fusionner") }
            }
            listOf(suggestion.first to true, suggestion.second to false).forEach { (tag, isFirst) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable { keepFirst = isFirst }
                        .background(if (keepFirst == isFirst) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(keepFirst == isFirst, { keepFirst = isFirst })
                    Spacer(Modifier.width(6.dp))
                    Text(tag.name, Modifier.weight(1f), fontWeight = if (keepFirst == isFirst) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
@Composable
private fun TagManager(state: AstraUiState, selected: Set<String>, query: String, onToggle: (String) -> Unit, onEdit: (TagEntity) -> Unit) {
    val filteredTags = state.tags.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val ordered = state.tagCategories.map { it.name }
    val other = filteredTags.mapNotNull { it.groupName }.filterNot(ordered.toSet()::contains).distinct().sorted()
    val groups = (ordered + other).map { it to filteredTags.filter { tag -> tag.groupName == it } } +
        ("Sans catégorie" to filteredTags.filter { it.groupName == null })
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
private fun AuditDialog(vm: AstraViewModel, onDismiss: () -> Unit) {
    val events by vm.auditEvents().collectAsStateWithLifecycle(initialValue = emptyList())
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                CompactHeader("Historique des modifications", onBack = onDismiss)
                if (events.isEmpty()) CenterMessage("Aucun événement enregistré", Modifier.fillMaxSize())
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = PageBottomPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(events, key = { it.id }) { event ->
                        RoundedListItem(
                            headlineContent = { Text(event.detail) },
                            supportingContent = { Text(event.timestamp.asDateTime()) },
                            leadingContent = { Icon(Icons.Default.History, null) }
                        )
                    }
                }
            }
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
    var showAudit by remember { mutableStateOf(false) }
    var sourcesExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var joiplayExpanded by rememberSaveable { mutableStateOf(false) }
    var organizationExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var intervalMenu by remember { mutableStateOf(false) }
    var showRuntimes by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Paramètres") }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = PageBottomPadding)) {
            item { SettingsSectionHeader("Sources et scan", sourcesExpanded) { sourcesExpanded = !sourcesExpanded } }
            if (sourcesExpanded) {
            items(state.sources, key = { it.id }) { source ->
                RoundedListItem(
                    headlineContent = { Text(source.displayName) }, leadingContent = { Icon(Icons.Default.Source, null) },
                    supportingContent = {
                        Column { Text("${source.gamesCount} jeux • ${source.lastScanStatus.lowercase(java.util.Locale.ROOT)}"); Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.scanSource(source.id) }) { Text("Scanner") }
                            TextButton(onClick = { vm.showLatestScanReport(source.id) }, enabled = source.lastScanAt != null) { Text("Rapport") }
                            IconButton(onClick = { sourceToDelete = source }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                        } }
                    }, trailingContent = { Switch(source.enabled, { vm.toggleSource(source.id) }) }
                )
            }
            item { RoundedListItem(modifier = Modifier.clickable(onClick = onPickSource), headlineContent = { Text("Ajouter une source") }, leadingContent = { Icon(Icons.Default.Add, null) }) }
            item { SettingsSwitch("Scanner au lancement", state.settings.scanOnLaunch, vm::setScanOnLaunch) }
            item { RoundedListItem(
                modifier = Modifier.clickable(enabled = !state.metadataRefresh.running, onClick = vm::refreshIncompleteMetadata),
                headlineContent = { Text("Actualiser les métadonnées manquantes") },
                supportingContent = {
                    val refresh = state.metadataRefresh
                    Text(if (refresh.running) "${refresh.completed}/${refresh.total} jeu(x) • VNDB et ${state.settings.searchEngine.displayName}" else "Jaquettes, descriptions et développeurs • VNDB, sans tags")
                },
                leadingContent = {
                    if (state.metadataRefresh.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                },
                trailingContent = {
                    if (state.metadataRefresh.running) IconButton(onClick = { vm.cancelSyncs() }) { Icon(Icons.Default.Close, "Arrêter") }
                }
            ) }
            item {
                Box {
                    RoundedListItem(
                        modifier = Modifier.clickable { intervalMenu = true },
                        headlineContent = { Text("Vérifier les mises à jour") },
                        supportingContent = { Text(updateIntervalLabel(state.settings.updateCheckInterval)) },
                        trailingContent = { Icon(Icons.Default.ArrowDropDown, null) }
                    )
                    DropdownMenu(intervalMenu, { intervalMenu = false }) {
                        listOf("LAUNCH", "DAY_1", "DAY_3", "DAY_7", "DAY_15", "DAY_30").forEach { code ->
                            DropdownMenuItem(
                                text = { Text(updateIntervalLabel(code)) },
                                leadingIcon = { if (state.settings.updateCheckInterval == code) Icon(Icons.Default.Check, null) },
                                onClick = { vm.setUpdateCheckInterval(code); intervalMenu = false }
                            )
                        }
                    }
                }
            }
            item {
                SearchEngineSelector(
                    state.settings.searchEngine,
                    vm::setSearchEngine,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                RoundedListItem(
                    headlineContent = { Text("Rechercher dans le navigateur") },
                    supportingContent = { Text("Les recherches du moteur sélectionné s’ouvrent directement dans le navigateur du téléphone.") },
                    trailingContent = { Switch(state.settings.openSearchInExternalBrowser, vm::setOpenSearchInExternalBrowser) }
                )
            }
            }
            item { SettingsSectionHeader("Apparence", appearanceExpanded) { appearanceExpanded = !appearanceExpanded } }
            if (appearanceExpanded) {
            item {
                LanguageSelector(
                    state.settings.language,
                    vm::setLanguage,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                CoverBlurSelector(
                    state.settings.coverBlurMode,
                    vm::setCoverBlurMode,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item { SettingsSwitch("Couleurs dynamiques", state.settings.dynamicColor, vm::setDynamicColor) }

            item { LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ThemeMode.entries) { mode -> FilterChip(state.settings.themeMode == mode, { vm.setTheme(mode) }, { Text(mode.label()) }) }
            } }
            }
            item { SettingsSectionHeader("JoiPlay", joiplayExpanded) { joiplayExpanded = !joiplayExpanded } }
            if (joiplayExpanded) {
            item { RoundedListItem(
                modifier = Modifier.clickable { vm.requestNotificationPermission(); showRuntimes = true },
                headlineContent = { Text(if (state.joiPlayInstalled) "Gestionnaire de runtimes" else "JoiPlay non détecté") },
                supportingContent = { Text("${state.runtimes.count { it.installed }}/${state.runtimes.size} composants détectés") },
                leadingContent = { Icon(Icons.Default.Extension, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
            }
            item { SettingsSectionHeader("Organisation", organizationExpanded) { organizationExpanded = !organizationExpanded } }
            if (organizationExpanded) {
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
            item { RoundedListItem(
                modifier = Modifier.clickable { showAudit = true }, headlineContent = { Text("Historique des modifications") },
                supportingContent = { Text("Fusions de tags, suppressions et modifications") },
                leadingContent = { Icon(Icons.Default.History, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
            ) }
            }
            item { SettingsSectionHeader("Sauvegarde et restauration", backupExpanded) { backupExpanded = !backupExpanded } }
            if (backupExpanded) {
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
            item {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
    if (showAudit) AuditDialog(vm) { showAudit = false }
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
    val latestVersions by vm.latestGameVersions.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var edit by remember { mutableStateOf(false) }
    var pickTags by remember { mutableStateOf(false) }
    var pickCover by remember { mutableStateOf(false) }
    var importF95 by rememberSaveable(id) { mutableStateOf(false) }
    var previewCover by remember { mutableStateOf(false) }
    var confirmCoverRemoval by remember { mutableStateOf(false) }
    var showDiagnostic by remember { mutableStateOf(false) }
    var showLaunchProfile by remember { mutableStateOf(false) }
    var showCompatibilityActions by remember { mutableStateOf(false) }
    var descriptionExpanded by rememberSaveable(id) { mutableStateOf(true) }
    var informationExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var confirmGameRemoval by remember { mutableStateOf(false) }
    val item = game
    var confirmUpdate by rememberSaveable { mutableStateOf(false) }
    val latestUpdate = latestVersions[id]
    if (confirmUpdate && latestUpdate != null && item != null) {
        AlertDialog(
            onDismissRequest = { confirmUpdate = false },
            title = { Text("Mise à jour effectuée ?") },
            text = { Text("Avez-vous effectué la mise à jour vers la version ${latestUpdate} ?") },
            confirmButton = { TextButton(onClick = { vm.confirmGameUpdate(item.id, latestUpdate); confirmUpdate = false }) { Text("Oui") } },
            dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("Non") } }
        )
    }
    val diagnostic = compatibilityByGame[id]
    LaunchedEffect(id, item?.missing, item?.engine, item?.executableName, launchProfile) {
        if (item?.missing == true) vm.verifyGamePresence(id)
        if (item != null) vm.diagnoseGame(id)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { GameDetailHeader(onBack = onBack, onEdit = { edit = true }, enabled = item != null) }
        ) { padding ->
        if (item == null) CenterMessage("Chargement…", Modifier.padding(padding).fillMaxSize(), loading = true)
        else LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (item.coverUri != null) {
                item {
                    Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .5f).dp)) {
                        AsyncImage(
                            model = item.coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(30.dp)
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.85f to MaterialTheme.colorScheme.background.copy(alpha = .35f),
                                    1f to MaterialTheme.colorScheme.background
                                )
                            )
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GameCover(
                        item,
                        Modifier.width(128.dp).aspectRatio(.72f).then(
                            if (item.coverUri != null) Modifier.clickable { previewCover = true } else Modifier
                        )
                    )
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                item.title,
                                Modifier.clickable {
                                    clipboard.setText(AnnotatedString(item.title))
                                    android.widget.Toast.makeText(context, "Nom du jeu copié", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.height(6.dp))
                            item.developer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            item.version?.let { Text("Version $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                if (latestUpdate != null && latestUpdate != item.version) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Update, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text("Nouvelle version disponible : ${latestUpdate}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { confirmUpdate = true }, Modifier.size(30.dp)) {
                                Icon(Icons.Default.CheckCircle, "Mise à jour effectuée", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
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
                    onClick = { showCompatibilityActions = true },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            diagnostic == null -> MaterialTheme.colorScheme.surfaceContainer
                            diagnostic.canLaunch -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (diagnostic?.canLaunch == true) Icons.Default.CheckCircle else Icons.Default.BuildCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Compatibilité", fontWeight = FontWeight.SemiBold)
                                Text(diagnostic?.summary ?: "Vérification en cours…", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir")
                        }
                    }
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
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir le thread F95Zone")
                    }
                }
            }
            if (!item.description.isNullOrBlank()) item {
                ExpandableDetailSection("Description", descriptionExpanded, { descriptionExpanded = !descriptionExpanded }) {
                    Text(item.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                ExpandableDetailSection("Informations", informationExpanded, { informationExpanded = !informationExpanded }) {
                    InfoLine("Moteur", item.engine.readableEngine())
                    InfoLine("Lancements", item.playCount.toString())
                    InfoLine("Dernier lancement", item.lastPlayedAt?.asDateTime() ?: "Jamais lancé")
                    InfoLine("Temps de jeu", state.playStats[item.id]?.totalDurationMs.asDuration())
                    InfoLine("Source", item.physicalPath ?: item.documentUri)
                }
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
    }
    if (item != null && edit) EditGameDialog(
        game = item,
        onSave = { edits, textTags -> vm.updateGame(item.id, edits, textTags); edit = false },
        onF95 = { edit = false; importF95 = true },
        onChangeCover = { edit = false; pickCover = true; vm.prepareCoverPicker(item.id) },
        onRemoveCover = { edit = false; confirmCoverRemoval = true },
        onDeleteGame = { edit = false; confirmGameRemoval = true },
        onDismiss = { edit = false }
    )
    if (item != null && pickTags) GameTagPickerSheet(state.tags, state.tagCategories, assignedTags.map { it.id }.toSet(), {
        vm.setGameTags(item.id, it); pickTags = false
    }, { pickTags = false })
    if (item != null && pickCover) CoverPickerSheet(item, coverState, state.settings.openSearchInExternalBrowser, { vm.chooseRemoteCover(item.id, it) }, {
        onPickCover(item.id); pickCover = false
    }, { vm.removeCover(item.id); pickCover = false }, { pickCover = false; vm.clearCoverSearch() })
    if (item != null && importF95) F95ImportSheet(item, state.tags, f95State, state.settings.openSearchInExternalBrowser, state.settings.f95SessionUser, { vm.fetchF95Metadata(item.id, it) }, {
        vm.prepareF95Search(item.id, item.title)
    }, { tags, image ->
        vm.applyF95Tags(item.id, tags)
        image?.let { vm.chooseF95Cover(item.id, it) }
        importF95 = false
    }, { importF95 = false; vm.clearF95Import() }, vm::connectF95Session, vm::disconnectF95)
    if (item?.coverUri != null && previewCover) CoverFullscreenDialog(item.coverUri, item.title) { previewCover = false }
    if (item != null && showCompatibilityActions) AlertDialog(
        onDismissRequest = { showCompatibilityActions = false },
        icon = { Icon(Icons.Default.BuildCircle, null) },
        title = { Text("Compatibilité") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(diagnostic?.summary ?: "Vérification en cours…")
                FilledTonalButton(
                    onClick = { showCompatibilityActions = false; showDiagnostic = true; vm.diagnoseGame(item.id) },
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Troubleshoot, null); Spacer(Modifier.width(8.dp)); Text("Diagnostic") }
                OutlinedButton(
                    onClick = { showCompatibilityActions = false; showLaunchProfile = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(8.dp)); Text("Profil de lancement") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showCompatibilityActions = false }) { Text("Fermer") } }
    )
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
private fun GameDetailHeader(onBack: () -> Unit, onEdit: () -> Unit, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularHeaderButton(onBack, Icons.AutoMirrored.Filled.ArrowBack, "Retour")
        Spacer(Modifier.weight(1f))
        CircularHeaderButton(onEdit, Icons.Default.Edit, "Modifier", enabled)
    }
}

@Composable
private fun CircularHeaderButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .9f),
        tonalElevation = 3.dp,
        modifier = Modifier.size(42.dp)
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(22.dp)) } }
}

@Composable
private fun ExpandableDetailSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .92f)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Refermer" else "Ouvrir")
            }
            if (expanded) Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
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
    val latestVersions by vm.latestGameVersions.collectAsStateWithLifecycle()
    var title by remember(game.id) { mutableStateOf(game.title) }
    var description by remember(game.id) { mutableStateOf(game.description.orEmpty()) }
    var developer by remember(game.id) { mutableStateOf(game.developer.orEmpty()) }
    var version by remember(game.id) { mutableStateOf(game.version.orEmpty()) }
    var textTags by remember(game.id) { mutableStateOf("") }
    var selectedTags by remember(game.id, assigned) { mutableStateOf(assigned.map { it.id }.toSet()) }
    var showTags by remember { mutableStateOf(false) }
    var showCover by remember { mutableStateOf(false) }
    var showF95 by rememberSaveable(game.id) { mutableStateOf(false) }
    var importedF95TagNames by remember(game.id) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.tags, importedF95TagNames) {
        if (importedF95TagNames.isNotEmpty()) {
            selectedTags = selectedTags + state.tags.filter { it.name.trim().lowercase(java.util.Locale.ROOT) in importedF95TagNames }.map { it.id }
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
                            GameEdits(title, game.originalTitle, developer, version, game.productCode, game.language, description, game.f95Url),
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
    if (showCover) CoverPickerSheet(game, coverState, state.settings.openSearchInExternalBrowser, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(game, state.tags, f95State, state.settings.openSearchInExternalBrowser, state.settings.f95SessionUser, { vm.fetchF95Metadata(game.id, it) }, {
        vm.prepareF95Search(game.id, game.title)
    }, { tags, image ->
        importedF95TagNames = tags.map { it.trim().lowercase(java.util.Locale.ROOT) }.toSet()
        vm.applyF95Tags(game.id, tags)
        image?.let { vm.chooseF95Cover(game.id, it) }
        showF95 = false
    }, { showF95 = false; vm.clearF95Import() }, vm::connectF95Session, vm::disconnectF95)
}

@Composable
private fun EditGameDialog(
    game: GameEntity,
    onSave: (GameEdits, String) -> Unit,
    onF95: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDeleteGame: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(game) { mutableStateOf(game.title) }; var original by remember(game) { mutableStateOf(game.originalTitle.orEmpty()) }
    var developer by remember(game) { mutableStateOf(game.developer.orEmpty()) }; var version by remember(game) { mutableStateOf(game.version.orEmpty()) }
    var description by remember(game) { mutableStateOf(game.description.orEmpty()) }
    var f95Url by remember(game) { mutableStateOf(game.f95Url.orEmpty()) }
    var textTags by remember(game) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Modifier le jeu") },
        text = { LazyColumn(Modifier.heightIn(max = 520.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Jaquette", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChangeCover, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(6.dp)); Text("Changer")
                    }
                    if (game.coverUri != null) OutlinedButton(
                        onClick = onRemoveCover,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Icon(Icons.Default.DeleteOutline, "Supprimer la jaquette") }
                }
            }
            item { HorizontalDivider(); Text("Métadonnées", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
            item { OutlinedButton(onClick = onF95, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer depuis F95Zone") } }
            item { EditField(title, { title = it }, "Titre") }; item { EditField(original, { original = it }, "Titre original") }
            item { EditField(developer, { developer = it }, "Développeur") }; item { EditField(version, { version = it }, "Version") }
            item { EditField(f95Url, { f95Url = it }, "Lien F95Zone") }
            item { EditField(description, { description = it }, "Description", false) }
            item { TextTagInput(textTags, { textTags = it }) }
            item { HorizontalDivider(); Text("Zone sensible", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
            item { OutlinedButton(
                onClick = onDeleteGame,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(6.dp)); Text("Supprimer le jeu") } }
        } },
        confirmButton = { TextButton(onClick = { onSave(GameEdits(title, original, developer, version, game.productCode, game.language, description, f95Url), textTags) }, enabled = title.isNotBlank()) { Text("Enregistrer") } },
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
    openInExternalBrowser: Boolean,
    f95SessionUser: String?,
    onFetch: (String) -> Unit,
    onPrepareSearch: () -> Unit,
    onComplete: (Set<String>, CoverCandidate?) -> Unit,
    onDismiss: () -> Unit,
    onConnectSession: (cookies: String, username: String?) -> Unit,
    onDisconnectSession: () -> Unit
) {
    var url by remember(game.id) { mutableStateOf(game.f95Url.orEmpty()) }
    val metadata = state.metadata
    var step by rememberSaveable(game.id) { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(metadata?.sourceUrl) { mutableStateOf(metadata?.tags.orEmpty()) }
    var selectedImageUrl by rememberSaveable(metadata?.sourceUrl) { mutableStateOf<String?>(null) }
    var showSearch by remember(game.id) { mutableStateOf(false) }
    var showLogin by remember(game.id) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val existingNames = remember(existingTags) { existingTags.map { it.normalizedName }.toSet() }
    LaunchedEffect(metadata?.sourceUrl) {
        if (metadata != null && step == 0) step = 1
    }
    LaunchedEffect(game.id) { onPrepareSearch() }
    Dialog(
        onDismissRequest = { if (step > 0) step-- else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (step > 0) step-- else onDismiss() }) { Icon(Icons.Default.Close, "Fermer l'import F95Zone") }
                    Column(Modifier.weight(1f)) {
                        Text("Importer depuis F95Zone", style = MaterialTheme.typography.titleLarge)
                        Text("Étape ${step + 1} sur 3 · ${game.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (f95SessionUser != null) "Connecté en tant que ${f95SessionUser}"
                        else "Session F95Zone : non connectée",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (f95SessionUser != null) {
                        TextButton(onClick = onDisconnectSession) { Text("Se déconnecter") }
                    } else {
                        Button(onClick = { showLogin = true }) { Text("Se connecter") }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val contentModifier = if (maxWidth >= 840.dp) {
                        Modifier.widthIn(max = 920.dp).fillMaxHeight().align(Alignment.Center)
                    } else Modifier.fillMaxSize()
                    Column(contentModifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            when (step) {
                0 -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Button(
                        onClick = {
                            state.browserUrl?.let { url ->
                                if (openInExternalBrowser) uriHandler.openUri(url) else showSearch = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.browserUrl != null && !state.loading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (openInExternalBrowser) "Ouvrir dans le navigateur" else "Rechercher automatiquement")
                    }
                    OutlinedButton(
                        onClick = {
                            state.browserUrl?.let { url ->
                                if (openInExternalBrowser) showSearch = true else uriHandler.openUri(url)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        enabled = state.browserUrl != null && !state.loading
                    ) {
                        Icon(if (openInExternalBrowser) Icons.Default.Search else Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (openInExternalBrowser) "Afficher dans Astra" else "Ouvrir dans le navigateur")
                    }
                }
                1 -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sélectionner les tags", style = MaterialTheme.typography.titleMedium)
                            Text("${selectedTags.size} sur ${metadata?.tags?.size ?: 0} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { selectedTags = metadata?.tags.orEmpty() }) { Text("Tout") }
                        TextButton(onClick = { selectedTags = emptyList() }) { Text("Aucun") }
                    }
                    val detectedTags = metadata?.tags.orEmpty()
                    if (detectedTags.isEmpty()) CenterMessage("Aucun tag détecté sur ce thread.", Modifier.fillMaxWidth().weight(1f))
                    else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(detectedTags, key = { it.lowercase(java.util.Locale.ROOT) }) { tag ->
                            val normalized = tag.normalizeTagName()
                            ListItem(
                                modifier = Modifier.clickable { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag },
                                headlineContent = { Text(tag) },
                                supportingContent = { Text(if (normalized in existingNames) "Tag existant" else "Nouveau tag · F95Zone") },
                                leadingContent = { Checkbox(tag in selectedTags, { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag }) }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 0 }, Modifier.weight(1f)) { Text("Retour") }
                        TextButton(onClick = { selectedTags = emptyList(); step = 2 }, Modifier.weight(1f)) { Text("Ignorer") }
                        Button(onClick = { step = 2 }, Modifier.weight(1f)) { Text("Continuer") }
                    }
                }
                else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Text("Choisir une image", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                    Text("L’image choisie pourra être recadrée avant enregistrement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val images = metadata?.images.orEmpty()
                    val selectedImage = images.firstOrNull { it.imageUrl == selectedImageUrl }
                    if (images.isEmpty()) CenterMessage("Aucune image détectée sur ce thread.", Modifier.fillMaxWidth().weight(1f))
                    else LazyVerticalGrid(
                        GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(images, key = { it.imageUrl }) { image ->
                            CoverCandidateCard(image, selected = selectedImageUrl == image.imageUrl) { selectedImageUrl = image.imageUrl }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 1 }, Modifier.weight(1f)) { Text("Retour") }
                        TextButton(onClick = { onComplete(selectedTags.toSet(), null) }, Modifier.weight(1f)) { Text("Ignorer") }
                        Button(
                            onClick = { selectedImage?.let { onComplete(selectedTags.toSet(), it) } },
                            enabled = selectedImage != null, modifier = Modifier.weight(1f)
                        ) { Text("Importer") }
                    }
                }
            }
                    }
                }
            }
        }
    }
    if (showSearch && state.browserUrl != null) SearchF95PickerDialog(
        searchUrl = state.browserUrl,
        searchEngine = state.searchEngine,
        onThreadSelected = { selectedUrl ->
            showSearch = false
            url = selectedUrl
            onFetch(selectedUrl)
        },
        onDismiss = { showSearch = false }
    )
    if (showLogin) F95LoginDialog(
        onSessionReady = { cookies, user ->
            showLogin = false
            onConnectSession(cookies, user)
        },
        onDismiss = { showLogin = false }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SearchF95PickerDialog(
    searchUrl: String,
    searchEngine: SearchEngine,
    onThreadSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val latestSelection by rememberUpdatedState(onThreadSelected)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader(
                    "Rechercher sur ${searchEngine.displayName}",
                    "Appui long sur le bon résultat F95Zone",
                    onBack = onDismiss
                )
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.userAgentString = SEARCH_WEBVIEW_USER_AGENT
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    // Let the selected engine follow its own result redirects.
                                    return false
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = {
                        val threadUrl = webView?.url?.let(::extractF95ThreadUrl)
                        if (threadUrl == null) {
                            selectionError = "Ce lien n’est pas un thread F95Zone valide. Maintenez le titre d’un résultat F95Zone."
                        } else {
                            selectionError = null
                            latestSelection(threadUrl)
                        }
                    }) { Text("Utiliser le lien") }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun F95LoginDialog(
    onSessionReady: (cookies: String, username: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestCallback by rememberUpdatedState(onSessionReady)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader(
                    "Se connecter à F95Zone",
                    "Connectez-vous sur le site puis utilisez le bouton ci-dessous.",
                    onBack = onDismiss
                )
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.userAgentString = SEARCH_WEBVIEW_USER_AGENT
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    view.evaluateJavascript(
                                        "(function(){var e=document.querySelector('.p-navgroup-linkText');return e?e.textContent.trim():'';})()"
                                    ) { result ->
                                        val clean = result.trim('"').ifBlank { null }
                                        if (clean != null && clean != "null") username = clean
                                    }
                                }
                            }
                            loadUrl("https://f95zone.to/login")
                            webView = this
                        }
                    },
                    update = { webView = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = {
                        val cookies = CookieManager.getInstance().getCookie("https://f95zone.to")
                        if (cookies.isNullOrBlank() || !cookies.contains("xf_user=")) {
                            error = "Aucune session F95Zone détectée. Connectez-vous d’abord sur le site."
                        } else {
                            error = null
                            latestCallback(cookies, username)
                        }
                    }) { Text("Utiliser cette session") }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverPickerSheet(
    game: GameEntity, state: CoverSearchState, openInExternalBrowser: Boolean, onCandidate: (CoverCandidate) -> Unit,
    onLocal: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit
) {
    var showSearch by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Jaquette de ${game.title}", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Image locale") }
                if (game.coverUri != null) TextButton(onClick = onRemove) { Text("Supprimer") }
            }
            Button(
                onClick = {
                    state.browserUrl?.let { url ->
                        if (openInExternalBrowser) uriHandler.openUri(url) else showSearch = true
                    }
                },
                enabled = state.browserUrl != null && !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text(if (openInExternalBrowser) "Ouvrir dans le navigateur" else "Rechercher automatiquement") }
            OutlinedButton(
                onClick = {
                    state.browserUrl?.let { url ->
                        if (openInExternalBrowser) showSearch = true else uriHandler.openUri(url)
                    }
                },
                enabled = state.browserUrl != null && !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(if (openInExternalBrowser) Icons.Default.ImageSearch else Icons.AutoMirrored.Filled.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text(if (openInExternalBrowser) "Afficher dans Astra" else "Ouvrir dans le navigateur")
            }
            when {
                state.loading -> CenterMessage("Recherche d’images…", Modifier.height(180.dp), true)
                state.downloading -> CenterMessage("Préparation du recadrage…", Modifier.height(220.dp), true)
                state.error != null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
                state.results.isNotEmpty() -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.results, key = { it.imageUrl }) { candidate ->
                        CoverCandidateCard(candidate, onClick = { onCandidate(candidate) })
                    }
                }
                else -> Spacer(Modifier.navigationBarsPadding().height(18.dp))
            }
        }
    }
    if (showSearch && state.browserUrl != null) SearchImagePickerDialog(
        searchUrl = state.browserUrl,
        searchEngine = state.searchEngine,
        onCandidate = { showSearch = false; onCandidate(it) },
        onDismiss = { showSearch = false }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SearchImagePickerDialog(
    searchUrl: String,
    searchEngine: SearchEngine,
    onCandidate: (CoverCandidate) -> Unit,
    onDismiss: () -> Unit
) {
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
        target.evaluateJavascript(IMAGE_SELECTION_SCRIPT) { rawResult ->
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
                CompactHeader("${searchEngine.displayName} Images", "Touchez une image pour afficher son aperçu", onBack = onDismiss)
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.userAgentString = SEARCH_WEBVIEW_USER_AGENT
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    view.evaluateJavascript(IMAGE_SELECTION_BOOTSTRAP_SCRIPT, null)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    // Keep navigation unrestricted so the user can use the selected engine normally.
                                    return false
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

private val IMAGE_SELECTION_BOOTSTRAP_SCRIPT = """
    (function() {
      function imageUrls(img) {
        const raw = [img.getAttribute('data-iurl'), img.getAttribute('data-original'), img.getAttribute('data-src'), img.getAttribute('data-mediaurl'), img.getAttribute('data-murl'), img.getAttribute('data-full-url'), img.getAttribute('data-image-url'), img.getAttribute('data-bem'), img.getAttribute('data-state')].filter(Boolean).join(' ');
        const normalized = raw.replace(/\\\//g, '/').replace(/\\u003A/gi, ':').replace(/\\u002F/gi, '/').replace(/\\u003F/gi, '?').replace(/\\u003D/gi, '=').replace(/\\u0026/gi, '&');
        return (normalized.match(/https?:\/\/[^\s"'<>]+/g) || []).map(function(url) {
          return url;
        });
      }
      document.addEventListener('click', function(event) {
        const img = event.target && event.target.closest ? event.target.closest('img') : null;
        if (!img) return;
        const urls = imageUrls(img);
        window.__astraSelectedImage = {url: urls[0] || img.currentSrc || img.src || '', title: img.alt || ''};
      }, true);
    })();
""".trimIndent()

private val IMAGE_SELECTION_SCRIPT = """
    (function() {
      if (window.__astraSelectedImage && window.__astraSelectedImage.url) {
        return JSON.stringify(window.__astraSelectedImage);
      }
      const candidates = [];
      document.querySelectorAll('[data-iurl], [data-original], [data-src], [data-mediaurl], [data-murl], [data-full-url], [data-image-url], [data-bem], [data-state]').forEach(function(node) {
        const raw = [node.getAttribute('data-iurl'), node.getAttribute('data-original'), node.getAttribute('data-src'), node.getAttribute('data-mediaurl'), node.getAttribute('data-murl'), node.getAttribute('data-full-url'), node.getAttribute('data-image-url'), node.getAttribute('data-bem'), node.getAttribute('data-state')].filter(Boolean).join(' ');
        const normalized = raw.replace(/\\\//g, '/').replace(/\\u003A/gi, ':').replace(/\\u002F/gi, '/').replace(/\\u003F/gi, '?').replace(/\\u003D/gi, '=').replace(/\\u0026/gi, '&');
        const urls = normalized.match(/https?:\/\/[^\s"'<>]+/g) || [];
        urls.forEach(function(url) {
          candidates.push({url: url, title: node.getAttribute('aria-label') || node.getAttribute('alt') || '', score: 2000000000000});
        });
      });
      document.images.forEach(function(img) {
        const rect = img.getBoundingClientRect();
        const visible = rect.width > 80 && rect.height > 80 && rect.bottom > 0 && rect.top < window.innerHeight;
        const preview = img.closest('[aria-selected="true"], [aria-current="true"], [role="dialog"], [role="main"]') || visible;
        const score = (preview ? 1000000000000 : 0) + (visible ? 10000000000 : 0) + (img.naturalWidth || 0) * (img.naturalHeight || 0);
        [img.currentSrc, img.src, img.getAttribute('data-src'), img.getAttribute('data-original'), img.getAttribute('data-iurl')].forEach(function(url) {
          if (url) candidates.push({url: url, title: img.alt || '', score: score});
        });
      });
      const valid = candidates.filter(function(item) { return item.url && item.url.indexOf('https://') === 0; });
      const ranked = valid.sort(function(a, b) { return b.score - a.score; });
      const top = ranked[0];
      const best = ranked[0];
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
    val coverBlur = LocalCoverBlurState.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val imageModifier = if (maxWidth >= 840.dp) Modifier.fillMaxWidth(.75f).fillMaxHeight(.9f) else Modifier.fillMaxSize()
            Surface(imageModifier, color = Color.Black) {
                Box {
                    AsyncImage(
                        uri,
                        title,
                        Modifier.fillMaxSize().then(if (coverBlur.blurred) Modifier.blur(24.dp) else Modifier),
                        contentScale = ContentScale.Fit
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
            modifier = Modifier.fillMaxWidth().height(46.dp),
            label = { Text("Rechercher un tag") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true
        )
        if (sortedTags.isEmpty()) Text("Aucun tag trouvé", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sortedTags, key = { it.id }) { tag ->
                ListItem(
                    modifier = Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(tag.id) },
                    headlineContent = { Text(tag.name, style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { RadioButton(selected = tag.id == selectedId, onClick = { onSelect(tag.id) }, modifier = Modifier.size(30.dp)) },
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
                                runtime.updateAvailable -> "Installé${runtime.versionName?.let { " • version $it" }.orEmpty()} • mise à jour ${runtime.latestVersion} disponible"
                                runtime.installed -> "Installé${runtime.versionName?.let { " • version $it" }.orEmpty()}"
                                runtime.required -> "Requis par votre bibliothèque • non installé"
                                else -> "Version ${runtime.latestVersion.orEmpty()} • non installé"
                            })
                        },
                        leadingContent = { Icon(
                            if (runtime.updateAvailable) Icons.Default.Update else if (runtime.installed) Icons.Default.CheckCircle else Icons.Default.Download,
                            null, tint = if (runtime.updateAvailable) MaterialTheme.colorScheme.tertiary else if (runtime.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        ) },
                        trailingContent = { if (!runtime.installed || runtime.updateAvailable) TextButton(onClick = { uriHandler.openUri(runtime.downloadUrl) }) { Text(if (runtime.updateAvailable) "Mettre à jour" else "Télécharger") } }
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
private fun GameUpdatesScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    val updates by vm.gameUpdates.collectAsStateWithLifecycle()
    val checking by vm.updatesChecking.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompactHeader("Mises à jour de jeux") }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Button(
                onClick = { vm.checkGameUpdates() },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Icon(Icons.Default.Update, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Vérification en cours…" else "Vérifier les mises à jour") }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            when {
                checking && updates.isEmpty() -> CenterMessage("Vérification en cours…", Modifier.fillMaxSize(), loading = true)
                updates.isEmpty() -> CenterMessage("Aucune mise à jour disponible", Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = PageBottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(updates, key = { it.game.id }) { update ->
                        Card(Modifier.fillMaxWidth().clickable { onGame(update.game.id) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                GameCover(update.game, Modifier.width(56.dp).aspectRatio(.72f))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(update.game.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${update.currentVersion ?: "Version inconnue"} → ${update.latestVersion}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                update.game.f95Url?.let { url ->
                                    IconButton(onClick = { uriHandler.openUri(url) }) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir le thread F95Zone")
                                    }
                                }
                                IconButton(onClick = { vm.acknowledgeGameUpdate(update.game.id) }) {
                                    Icon(Icons.Default.CheckCircle, "Marquer comme vu", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ScanProgressOverlay(progress: ScanProgressState, onCancel: () -> Unit) {
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
                Spacer(Modifier.height(28.dp))
                OutlinedButton(onClick = onCancel) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Arrêter la synchronisation") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier.padding(start = 16.dp, top = 14.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SettingsSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}

@Composable
private fun CompactActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String
) = IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(icon, AppLocalizer.text(description), Modifier.size(18.dp))
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

private fun updateIntervalLabel(code: String) = when (code) {
    "LAUNCH" -> "Chaque lancement"
    "DAY_1" -> "1 jour"
    "DAY_3" -> "3 jours"
    "DAY_15" -> "15 jours"
    "DAY_30" -> "30 jours"
    else -> "7 jours"
}

private fun LibrarySort.label() = when (this) {
    LibrarySort.TITLE -> "Titre"
    LibrarySort.RECENTLY_ADDED -> "Ajouts récents"
    LibrarySort.LAST_PLAYED -> "Dernier lancement"
    LibrarySort.MOST_PLAYED -> "Plus joués"
    LibrarySort.UPDATE_AVAILABLE -> "Mises à jour disponibles"
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Système"
    ThemeMode.LIGHT -> "Clair"
    ThemeMode.DARK -> "Sombre"
}

private fun CoverBlurMode.label() = when (this) {
    CoverBlurMode.OFF -> "Désactivé"
    CoverBlurMode.STARTUP -> "Automatique au démarrage"
    CoverBlurMode.MANUAL -> "Manuel"
}

@Composable
private fun InfoLine(label: String, value: String) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(value); Spacer(Modifier.height(9.dp)) }

private fun Set<String>.toggle(id: String): Set<String> = toMutableSet().apply { if (!add(id)) remove(id) }
private fun String.normalizeTagName(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(java.util.Locale.ROOT)
private fun String.readableEngine() = lowercase(java.util.Locale.ROOT).replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
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
