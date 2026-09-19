package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*

@Composable
internal fun GameUpdatesScreen(state: AstraUiState, vm: AstraViewModel, onBack: (() -> Unit)? = null, onGame: (String) -> Unit) {
    val updates by vm.gameUpdates.collectAsStateWithLifecycle()
    val checking by vm.updatesChecking.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompactHeader("Mises à jour", onBack = onBack) {
            IconButton(onClick = { vm.checkGameUpdates() }, enabled = !checking) {
                Icon(Icons.Default.Refresh, AppLocalizer.text("Vérifier les mises à jour"))
            }
        } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            when {
                checking && updates.isEmpty() -> CenterMessage("Vérification en cours…", Modifier.fillMaxSize(), loading = true)
                updates.isEmpty() -> CenterMessage("Aucune mise à jour disponible", Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = PageBottomPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(updates, key = { it.game.id }) { update ->
                        Card(onClick = { onGame(update.game.id) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                GameCover(update.game, Modifier.width(36.dp).aspectRatio(.72f))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(update.game.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${update.currentVersion ?: "Version inconnue"} → ${update.latestVersion}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                update.game.f95Url?.let { url ->
                                    IconButton(onClick = { uriHandler.openUri(url) }) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, AppLocalizer.text("Ouvrir le thread F95Zone"))
                                    }
                                }
                                IconButton(onClick = { vm.acknowledgeGameUpdate(update.game.id) }) {
                                    Icon(Icons.Default.CheckCircle, AppLocalizer.text("Marquer comme vu"), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanProgressOverlay(progress: ScanProgressState, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("${progress.visitedFolders} dossiers")
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SportsEsports, null)
                            Spacer(Modifier.width(8.dp))
                            Text("${progress.foundGames} jeux")
                        }
                    }
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
}
