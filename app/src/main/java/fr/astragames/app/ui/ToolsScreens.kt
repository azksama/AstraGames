package fr.astragames.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LockScreen(state: AstraUiState, vm: AstraViewModel) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(state.settings.lockBiometricEnabled) {
        if (state.settings.lockBiometricEnabled) vm.requestBiometricUnlock()
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Astra est verrouille", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Utilisez votre empreinte ou votre code pour continuer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (state.settings.lockBiometricEnabled) {
            FilledTonalButton(onClick = vm::requestBiometricUnlock, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Deverrouiller par biometrie")
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.settings.lockPinEnabled) {
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
                label = { Text("Code") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (pin.length >= 4) vm.verifyLockPin(pin) { if (!it) pin = "" } }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.verifyLockPin(pin) { if (!it) pin = "" } }, modifier = Modifier.fillMaxWidth(), enabled = pin.length >= 4) { Text("Deverrouiller") }
            Spacer(Modifier.height(16.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        OutlinedButton(onClick = { when (key) { "<" -> pin = pin.dropLast(1); "" -> Unit; else -> pin = (pin + key).take(8) } }, enabled = key.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text(if (key == "<") "Effacer" else key) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }
}

@Composable
internal fun HistoryScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    val sessions by remember(vm) { vm.playHistory() }.collectAsStateWithLifecycle(emptyList())
    var confirmClear by remember { mutableStateOf(false) }
    val games = remember(state.games) { state.games.associateBy { it.id } }
    val language = LocalAppLanguage.current
    val locale = remember(language) { Locale.forLanguageTag(language.code) }
    val grouped = remember(sessions, locale) {
        val dateFormat = DateFormat.getDateInstance(DateFormat.FULL, locale)
        sessions.groupBy { dateFormat.format(Date(it.startedAt)) }
    }
    Scaffold(topBar = {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Historique", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { confirmClear = true }, enabled = sessions.isNotEmpty()) { Text("Effacer") }
            }
        }
    }) { padding ->
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, null, Modifier.size(40.dp)); Spacer(Modifier.height(12.dp)); Text("Aucune session enregistree")
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = PageBottomPadding)) {
                grouped.forEach { (day, daySessions) ->
                    item(key = "day:$day") { Text(day, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    items(daySessions, key = { it.id }) { session ->
                        val game = games[session.gameId]
                        ListItem(
                            modifier = Modifier.clickable(enabled = game != null) { game?.let { onGame(it.id) } },
                            headlineContent = { Text(game?.title ?: "Jeu inconnu") },
                            supportingContent = { Text(sessionLabel(session)) },
                            leadingContent = {
                                if (game != null) GameCover(game, Modifier.width(40.dp).aspectRatio(.72f))
                                else Box(Modifier.width(40.dp).height(56.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.History, null) }
                            },
                            trailingContent = { IconButton(onClick = { vm.deletePlaySession(session.id) }) { Icon(Icons.Default.Delete, "Supprimer") } }
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Effacer l historique ?") }, text = { Text("Toutes les sessions terminees seront supprimees.") }, confirmButton = { TextButton(onClick = { vm.clearPlayHistory(); confirmClear = false }) { Text("Effacer") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } })
}

private fun sessionLabel(session: PlaySessionEntity): String {
    val start = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(session.startedAt))
    val duration = session.durationMs?.let { ms ->
        val minutes = (ms / 60000L).toInt(); val seconds = ((ms % 60000L) / 1000L).toInt()
        if (minutes > 0) minutes.toString() + " min " + seconds.toString() + " s" else seconds.toString() + " s"
    } ?: "en cours"
    return start + " - " + duration
}

@Composable
internal fun PinSetupDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Definir un code") }, text = { Column { OutlinedTextField(first, { first = it.filter(Char::isDigit).take(8) }, label = { Text("Code (4 a 8 chiffres)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); OutlinedTextField(second, { second = it.filter(Char::isDigit).take(8) }, label = { Text("Confirmer") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { onSave(first) }, enabled = first.length in 4..8 && first == second) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
