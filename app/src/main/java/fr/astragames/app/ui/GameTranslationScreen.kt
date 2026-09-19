package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage
import fr.astragames.app.data.local.GameEntity
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameTranslationScreen(game: GameEntity, controller: GameTranslationController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    var source by rememberSaveable(game.id) { mutableStateOf("en") }
    var target by rememberSaveable(game.id) { mutableStateOf(if (language.code == "en") "fr" else language.code.take(2)) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, state.busy) {
        val previous = view.keepScreenOn
        if (state.busy) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(game.id) { controller.analyze(game) }
    Dialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column {
                CompactHeader("Traduire le jeu", onBack = if (!state.busy) onDismiss else null)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.Text(game.title, style = MaterialTheme.typography.titleMedium)
                    Text("Google Translate (ML Kit) traduit sur cet appareil, sans compte ni clé API.")
                    Text("Environ 30 Mo par langue au premier usage. Gardez Astra ouvert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TranslationLanguagePicker("Langue du jeu", source, !state.busy && state.analysis?.installed != true) { source = it }
                    TranslationLanguagePicker("Langue de traduction", target, !state.busy && state.analysis?.installed != true) { target = it }
                    if (state.analysis?.installed != true) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(wifiOnly, if (!state.busy) ({ wifiOnly = it }) else null, enabled = !state.busy)
                        Text("Télécharger les langues uniquement en Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    state.analysis?.let { analysis ->
                        if (analysis.installed) Text("Originaux sauvegardés. Restaurez-les avant de retraduire ou de modifier les mods.")
                        else androidx.compose.material3.Text(
                            listOf("Fichiers" to analysis.files, "Textes uniques" to analysis.texts, "Caractères" to analysis.characters)
                                .joinToString(" · ") { (label, count) -> "${AppLocalizer.text(label, language)} : $count" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (analysis.preservedFragments > 0) {
                            Text("Passages conservés dans la langue d’origine", style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Text(analysis.preservedFragments.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Les passages incompatibles restent inchangés pour préserver le jeu.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.progress?.let { progress ->
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(progress.phase, style = MaterialTheme.typography.titleSmall)
                                if (progress.total > 0 && progress.phase == "Traduction locale") {
                                    LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                                    Text("${progress.completed} / ${progress.total}", style = MaterialTheme.typography.labelMedium)
                                    TranslationRemainingTime(progress.remainingSeconds)
                                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("MV/MZ : dialogues, choix et menus standards. Images, plugins et sauvegardes exclus.", style = MaterialTheme.typography.bodySmall)
                    Text("Originaux : data/.astra-translation. Conservez ce dossier.", style = MaterialTheme.typography.bodySmall)
                    val uriHandler = LocalUriHandler.current
                    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/language/translation") }) { Text("À propos de Google Translate") }
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) {
                        TextButton(onClick = controller::cancel, enabled = state.progress?.phase !in setOf("Sauvegarde et application", "Restauration des originaux")) { Text("Arrêter") }
                    } else if (state.analysis?.installed == true) {
                        OutlinedButton(onClick = { controller.restore(game) }) { Text("Restaurer les originaux") }
                    } else {
                        TextButton(onClick = { controller.analyze(game) }) { Text("Actualiser") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { confirming = true }, enabled = state.analysis != null && state.analysis!!.texts > 0 && source != target) { Text("Traduire avec Google") }
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Traduire le jeu ?") },
        text = { Text("Fermez le jeu avant de continuer. Une traduction automatique sera appliquée avec sauvegarde des originaux. Vous pourrez la restaurer depuis cet écran.") },
        confirmButton = { TextButton(onClick = { confirming = false; controller.translate(game, source, target, wifiOnly) }) { Text("Traduire avec Google") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Annuler") } }
    )
}

@Composable
private fun TranslationRemainingTime(seconds: Long?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Temps restant estimé", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (seconds == null) Text("Calcul en cours…", style = MaterialTheme.typography.bodySmall)
        else androidx.compose.material3.Text(
            if (seconds < 60) "< 1 min" else if (seconds < 3600) "≈ ${(seconds + 59) / 60} min" else "≈ ${seconds / 3600} h ${(seconds % 3600 + 59) / 60} min",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranslationLanguagePicker(label: String, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.code)
    val languages = remember(locale) { TranslateLanguage.getAllLanguages().sortedBy { Locale.forLanguageTag(it).getDisplayLanguage(locale) } }
    PreferenceChoice(label, selected, languages.map { it to Locale.forLanguageTag(it).getDisplayLanguage(locale) }, onSelect, enabled = enabled)
}
