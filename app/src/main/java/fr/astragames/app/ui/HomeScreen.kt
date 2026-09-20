package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.astragames.app.data.local.GameEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun homeRecentGames(state: AstraUiState): List<GameEntity> = state.games
    .filter { !it.hidden && (it.lastPlayedAt ?: state.playStats[it.id]?.lastSessionAt) != null }
    .sortedByDescending { it.lastPlayedAt ?: state.playStats[it.id]?.lastSessionAt }
    .take(10)

@Composable
internal fun HomeScreen(
    state: AstraUiState, onGame: (String) -> Unit, onResume: (String) -> Unit,
    onAllGames: () -> Unit, onPickSource: () -> Unit,
    onSearch: () -> Unit = onAllGames, onHistory: (() -> Unit)? = null, onUpdates: (() -> Unit)? = null
) {
    val recent = remember(state.games, state.playStats) { homeRecentGames(state) }
    val landscape = LandscapeLayout
    val recentGridState = rememberLazyGridState()
    val resume: @Composable () -> Unit = {
        val latest = recent.firstOrNull()
        if (latest != null) {
            Text("Reprendre", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ResumeGameCard(latest, latest.lastPlayedAt ?: state.playStats[latest.id]?.lastSessionAt, onGame, onResume)
        } else Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ta prochaine aventure commence ici", style = MaterialTheme.typography.headlineMedium)
            Text("Lance un jeu pour le retrouver sur ton accueil.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = if (state.games.isEmpty()) onPickSource else onAllGames) { Text(if (state.games.isEmpty()) "Ajouter une source" else "Découvrir tes jeux") }
        }
    }
    val activity: @Composable () -> Unit = {
        if (onHistory != null || onUpdates != null) {
            Text("Activité", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onHistory != null) ActivityShortcut("Historique", Icons.Default.History, onHistory, Modifier.weight(1f))
                if (onUpdates != null) ActivityShortcut("Mises à jour", Icons.Default.Update, onUpdates, Modifier.weight(1f))
            }
        }
    }
    ScrollingScaffold(topBar = { CompactHeader(if (landscape) "Bibliothèque" else "Astra") {
        IconButton(onClick = onPickSource) { Icon(Icons.Default.Add, AppLocalizer.text("Ajouter une source")) }
    } }) { padding ->
        if (landscape) Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            SearchEntry(onSearch)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("home-page"), contentPadding = PaddingValues(bottom = PageBottomPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item { resume() }
                    item { activity() }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    RecentGamesHeading(onAllGames)
                    RecentGamesGrid(recent, onGame, recentGridState, Modifier.weight(1f))
                }
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.padding(padding).fillMaxHeight().widthIn(max = 900.dp).fillMaxWidth().testTag("home-page"),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = PageBottomPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { PageHeading("Bibliothèque", "${state.games.count { !it.hidden }} jeux") }
                item { SearchEntry(onSearch) }
                item { resume() }
                item {
                    RecentGamesHeading(onAllGames)
                    RecentGamesGrid(recent, onGame, recentGridState, Modifier.height(320.dp))
                }
                item { activity() }
            }
        }
    }
}

@Composable
private fun RecentGamesHeading(onAllGames: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Tes jeux", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAllGames) { Text("Tout voir") }
    }
}

@Composable
private fun RecentGamesGrid(games: List<GameEntity>, onGame: (String) -> Unit, gridState: LazyGridState, modifier: Modifier) {
    if (games.isEmpty()) Text("Tes autres parties récentes apparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else LazyVerticalGrid(GridCells.Fixed(2), modifier.fillMaxWidth().testTag("home-recent-games"), state = gridState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(games, key = { it.id }) { game ->
            Column(Modifier.clickable { onGame(game.id) }) {
                GameCover(game, Modifier.fillMaxWidth().aspectRatio(1.5f))
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(game.engine.readableEngine(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ActivityShortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ResumeGameCard(game: GameEntity, lastSession: Long?, onGame: (String) -> Unit, onResume: (String) -> Unit) {
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.code)
    val time = remember(lastSession, locale) { lastSession?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverHeight = if (LandscapeLayout) 132.dp else (maxWidth * .65f).coerceIn(200.dp, 244.dp)
        Box(Modifier.fillMaxWidth().padding(bottom = 26.dp).heightIn(min = coverHeight).clip(RoundedCornerShape(18.dp)).clickable { onGame(game.id) }) {
            GameCover(game, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE609090F)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text(game.title, style = if (LandscapeLayout) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!LandscapeLayout) Text("Dernière session", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (time != null) androidx.compose.material3.Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { onResume(game.id) }, enabled = !game.missing, modifier = Modifier.align(Alignment.BottomCenter).heightIn(min = 52.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (game.missing) "Manquant" else "Reprendre la partie")
        }
    }
}
