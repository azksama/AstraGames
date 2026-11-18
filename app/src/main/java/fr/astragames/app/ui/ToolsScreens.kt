package fr.astragames.app.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.material3.Text as MaterialText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.PlaySessionEntity
import fr.astragames.app.data.local.MAX_SESSION_DURATION_MS
import fr.astragames.app.data.local.GameEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LockScreen(state: AstraUiState, vm: AstraViewModel) {
    LaunchedEffect(state.settings.lockBiometricEnabled) {
        if (state.settings.lockBiometricEnabled) vm.requestBiometricUnlock()
    }
    LockContent(state.settings.lockBiometricEnabled, state.settings.lockPinEnabled, vm::requestBiometricUnlock) { pin, result -> vm.verifyLockPin(pin, result) }
}

@Composable
internal fun HistoryScreen(state: AstraUiState, vm: AstraViewModel, onBack: (() -> Unit)? = null, onGame: (String) -> Unit) {
    val sessions by remember(vm) { vm.playHistory() }.collectAsStateWithLifecycle<List<PlaySessionEntity>?>(null)
    HistoryContent(state.games, sessions, { vm.deletePlaySession(it) }, vm::clearPlayHistory, onBack, onGame)
}

@Composable
internal fun HistoryContent(
    library: List<GameEntity>, sessions: List<PlaySessionEntity>?, onDelete: (String) -> Unit,
    onClear: suspend () -> Unit, onBack: (() -> Unit)?, onGame: (String) -> Unit
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val games = remember(library) { library.associateBy { it.id } }
    val language = LocalAppLanguage.current
    val locale = remember(language) { Locale.forLanguageTag(language.code) }
    val grouped = remember(sessions, locale) {
        val dateFormat = DateFormat.getDateInstance(DateFormat.FULL, locale)
        sessions.orEmpty().groupBy { dateFormat.format(Date(it.startedAt)) }
    }
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val durationFormat = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    ScrollingScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        CompactHeader("Historique", onBack = onBack) {
            TextButton(onClick = { confirmClear = true }, enabled = !sessions.isNullOrEmpty()) { Text("Effacer") }
        }
    }) { padding ->
        if (sessions == null) CenterMessage("Chargement…", Modifier.fillMaxSize().padding(padding), loading = true)
        else if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, null, Modifier.size(40.dp)); Spacer(Modifier.height(12.dp)); Text("Aucune session enregistrée")
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = PageBottomPadding)) {
                grouped.forEach { (day, daySessions) ->
                    item(key = "day:$day") { MaterialText(day, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
                    items(daySessions, key = { "session:" + it.id }) { session ->
                        val game = games[session.gameId]
                        ListItem(
                            modifier = Modifier.clickable(enabled = game != null) { game?.let { onGame(it.id) } },
                            headlineContent = { MaterialText(game?.title ?: AppLocalizer.text("Jeu inconnu", language)) },
                            supportingContent = { MaterialText(sessionLabel(session, timeFormat, durationFormat)) },
                            leadingContent = {
                                if (game != null) GameCover(game, Modifier.width(40.dp).aspectRatio(.72f))
                                else Box(Modifier.width(40.dp).height(56.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.History, null) }
                            },
                            trailingContent = { IconButton(onClick = { onDelete(session.id) }) { Icon(Icons.Default.Delete, AppLocalizer.text("Supprimer", language)) } }
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog("Effacer l’historique ?", "Toutes les sessions terminées seront supprimées.", {
        onClear()
        confirmClear = false
    }, { confirmClear = false }, confirmLabel = "Effacer")
}

private fun sessionLabel(session: PlaySessionEntity, time: DateFormat, format: MeasureFormat): String {
    val start = time.format(Date(session.startedAt))
    val duration = session.durationMs ?: session.endedAt?.let { (it - session.startedAt).coerceIn(0L, MAX_SESSION_DURATION_MS) } ?: return start
    val seconds = duration.coerceAtLeast(0) / 1_000L
    val remainder = Measure(seconds % 60, MeasureUnit.SECOND)
    val label = if (seconds < 60) format.formatMeasures(remainder)
        else format.formatMeasures(Measure(seconds / 60, MeasureUnit.MINUTE), remainder)
    return "$start – $label"
}

@Composable
internal fun PinSetupDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Definir un code") }, text = { Column { OutlinedTextField(first, { first = it.filter(Char::isDigit).take(8) }, label = { Text("Code (4 a 8 chiffres)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); OutlinedTextField(second, { second = it.filter(Char::isDigit).take(8) }, label = { Text("Confirmer") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { onSave(first) }, enabled = first.length in 4..8 && first == second) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
