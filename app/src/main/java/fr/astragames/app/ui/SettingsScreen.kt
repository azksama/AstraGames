package fr.astragames.app.ui

import fr.astragames.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameSourceEntity

@Composable
internal fun AuditDialog(vm: AstraViewModel, onDismiss: () -> Unit) {
    val events by remember(vm) { vm.auditEvents() }.collectAsStateWithLifecycle(initialValue = emptyList())
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
internal fun SettingsScreen(
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
    var sourcesExpanded by rememberSaveable { mutableStateOf(state.sources.isEmpty()) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var joiplayExpanded by rememberSaveable { mutableStateOf(false) }
    var organizationExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var securityExpanded by rememberSaveable { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    var intervalMenu by remember { mutableStateOf(false) }
    var showRuntimes by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CompactHeader("Paramètres") }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = PageBottomPadding)) {
            item { SettingsSectionHeader("Sources et scan", sourcesExpanded) { sourcesExpanded = !sourcesExpanded } }
            if (sourcesExpanded) {
            items(state.sources, key = { it.id }) { source ->
                SourceSettingsCard(
                    source = source,
                    onToggle = { vm.toggleSource(source.id) },
                    onScan = { vm.scanSource(source.id) },
                    onReport = { vm.showLatestScanReport(source.id) },
                    onRemove = { sourceToDelete = source }
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
                    modifier = Modifier.toggleable(
                        value = state.settings.openSearchInExternalBrowser,
                        role = Role.Switch,
                        onValueChange = vm::setOpenSearchInExternalBrowser
                    ),
                    headlineContent = { Text("Rechercher dans le navigateur") },
                    supportingContent = { Text("Les recherches du moteur sélectionné s’ouvrent directement dans le navigateur du téléphone.") },
                    trailingContent = { Switch(state.settings.openSearchInExternalBrowser, onCheckedChange = null) }
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
            item { SettingsSectionHeader("Securite et historique", securityExpanded) { securityExpanded = !securityExpanded } }
            if (securityExpanded) {
            item { SettingsSwitch("Verrouillage biometrique", state.settings.lockBiometricEnabled, vm::setBiometricLock) }
            item { RoundedListItem(
                modifier = Modifier.clickable { if (state.settings.lockPinEnabled) vm.setPinLock(null) else showPinSetup = true },
                headlineContent = { Text(if (state.settings.lockPinEnabled) "Desactiver le code" else "Verrouillage par code") },
                supportingContent = { Text("Code chiffre de 4 a 8 chiffres, stocke chiffre") },
                leadingContent = { Icon(Icons.Default.Lock, null) }
            ) }
            item { SettingsSwitch("Verrouiller en arriere-plan", state.settings.lockOnBackground, vm::setLockOnBackground) }
            item { SettingsSwitch("Enregistrer l historique de jeu", state.settings.historyEnabled, vm::setHistoryEnabled) }
            item { RoundedListItem(
                modifier = Modifier.clickable(onClick = vm.tools::requestModsRoot),
                headlineContent = { Text(if (state.settings.modsRootUri == null) "Choisir le depot de mods" else "Changer le depot de mods") },
                supportingContent = { Text(if (state.settings.modsRootUri == null) "Dossier Astra/Mods" else "Depot configure") },
                leadingContent = { Icon(Icons.Default.Folder, null) }
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
    if (showPinSetup) PinSetupDialog({ vm.setPinLock(it); showPinSetup = false }, { showPinSetup = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceSettingsCard(
    source: GameSourceEntity,
    onToggle: () -> Unit,
    onScan: () -> Unit,
    onReport: () -> Unit,
    onRemove: () -> Unit
) {
    val running = source.lastScanStatus == "RUNNING"
    val status = when (source.lastScanStatus) {
        "RUNNING" -> "Scan en cours…"
        "SUCCESS" -> "Scan terminé"
        "PARTIAL" -> "Scan partiel"
        "FAILED" -> "Échec du scan"
        else -> "Jamais scannée"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Source, null, tint = MaterialTheme.colorScheme.primary)
                Text(source.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = source.enabled,
                    enabled = !running,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics { contentDescription = source.displayName }
                )
            }
            Text("${source.gamesCount} jeux", style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            source.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onScan, enabled = !running) { Text("Scanner") }
                TextButton(onClick = onReport, enabled = source.lastScanAt != null) { Text("Rapport") }
                TextButton(onClick = onRemove, enabled = !running) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Supprimer")
                }
            }
        }
    }
}
