package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import coil3.compose.AsyncImage
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameDetailScreen(
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
    var showTools by remember { mutableStateOf(false) }
    val item = game
    var confirmUpdate by rememberSaveable(id) { mutableStateOf(false) }
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
        ) { padding ->
        if (item == null) CenterMessage("Chargement…", Modifier.padding(padding).fillMaxSize(), loading = true)
        else LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val coverWidth = (maxWidth * .3f).coerceIn(80.dp, 128.dp)
                    if (item.coverUri != null) {
                        AsyncImage(
                            model = item.coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().blur(30.dp)
                        )
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.85f to MaterialTheme.colorScheme.background.copy(alpha = .35f),
                                    1f to MaterialTheme.colorScheme.background
                                )
                            )
                        )
                    }
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 76.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GameCover(
                            item,
                            Modifier.width(coverWidth).aspectRatio(.72f).then(
                                if (item.coverUri != null) Modifier
                                    .semantics { contentDescription = AppLocalizer.text("Jaquette") + ", " + item.title }
                                    .clickable(onClickLabel = AppLocalizer.text("Ouvrir"), role = Role.Button) { previewCover = true }
                                else Modifier
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
                        if (latestUpdate != null && latestUpdate != item.version) {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Update, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Nouvelle version disponible : ${latestUpdate}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = { confirmUpdate = true }) {
                                        Icon(Icons.Default.CheckCircle, "Mise à jour effectuée", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.launchGame(item.id) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Jouer") }
                            FilledTonalButton(
                                onClick = { showTools = true }, modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(6.dp)); Text("Outils") }
                            FilledTonalIconToggleButton(
                                checked = item.favorite,
                                onCheckedChange = { vm.toggleFavorite(item.id) }, modifier = Modifier.size(48.dp)
                            ) {
                                Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, AppLocalizer.text("Favori"), Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    onClick = { showCompatibilityActions = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { pickTags = true }) { Icon(Icons.Default.Add, null); Text("Choisir") }
                }
                if (assignedTags.isEmpty()) Text("Aucun tag associé", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                else LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(assignedTags, key = { it.id }) { tag -> AssistChip(onClick = { onSearchTag(tag.id) }, label = { Text(tag.name) }) } }
            }
            item.f95Url?.let { f95Url ->
                item {
                    OutlinedButton(onClick = { uriHandler.openUri(f95Url) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir le thread F95Zone")
                    }
                }
            }
            if (!item.description.isNullOrBlank()) item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ExpandableDetailSection("Description", descriptionExpanded, { descriptionExpanded = !descriptionExpanded }) {
                        Text(item.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ExpandableDetailSection("Informations", informationExpanded, { informationExpanded = !informationExpanded }) {
                        InfoLine("Moteur", item.engine.readableEngine())
                        InfoLine("Lancements", item.playCount.toString())
                        InfoLine("Dernier lancement", item.lastPlayedAt?.asDateTime() ?: "Jamais lancé")
                        InfoLine("Temps de jeu", state.playStats[item.id]?.totalDurationMs.asDuration())
                        InfoLine("Source", item.physicalPath ?: item.documentUri)
                    }
                }
            }
            item { OutlinedButton(onClick = { pickFolder = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Default.FolderCopy, null); Spacer(Modifier.width(8.dp))
                Text(state.folders.firstOrNull { it.id == item.libraryFolderId }?.name ?: "Classer dans un dossier")
            } }
            if (item.missing) item { Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Ce jeu est introuvable. Rescannez sa source.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            } }
        }
    }
    GameDetailHeader(onBack = onBack, onEdit = { edit = true }, enabled = item != null)
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
    if (item != null && showTools) GameToolsSheet(item, vm) { showTools = false }
}

@Composable
internal fun GameDetailHeader(onBack: () -> Unit, onEdit: () -> Unit, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularHeaderButton(onBack, Icons.AutoMirrored.Filled.ArrowBack, "Retour")
        Spacer(Modifier.weight(1f))
        CircularHeaderButton(onEdit, Icons.Default.Edit, "Modifier", enabled)
    }
}

@Composable
internal fun CircularHeaderButton(
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
        modifier = Modifier.size(48.dp)
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, AppLocalizer.text(description), Modifier.size(22.dp)) } }
}

@Composable
internal fun ExpandableDetailSection(
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
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, AppLocalizer.text(if (expanded) "Refermer" else "Ouvrir"))
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
internal fun CompatibilityDialog(
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
internal fun LaunchProfileDialog(
    game: GameEntity,
    saved: LaunchProfileEntity?,
    onSave: (LaunchProfileEntity) -> Unit,
    onTest: (LaunchProfileEntity) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var launcherType by rememberSaveable(game.id, saved) { mutableStateOf(saved?.launcherType ?: "JOIPLAY") }
    var engineOverride by rememberSaveable(game.id, saved) { mutableStateOf(saved?.engineOverride) }
    var executable by rememberSaveable(game.id, saved) { mutableStateOf(saved?.executableName ?: game.executableName.orEmpty()) }
    var physicalPath by rememberSaveable(game.id, saved) { mutableStateOf(saved?.physicalPath ?: game.physicalPath.orEmpty()) }
    var customAction by rememberSaveable(game.id, saved) { mutableStateOf(saved?.customAction.orEmpty()) }
    var packageName by rememberSaveable(game.id, saved) { mutableStateOf(saved?.packageName.orEmpty()) }
    var arguments by rememberSaveable(game.id, saved) { mutableStateOf(saved?.arguments.orEmpty()) }
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
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
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
