package fr.astragames.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage
import fr.astragames.app.data.local.GameEntity
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GameTranslationScreen(game: GameEntity, controller: GameTranslationController, onDismiss: () -> Unit) {
    val controllerState by controller.state.collectAsStateWithLifecycle()
    val state = controllerState.takeIf { it.gameId == game.id } ?: GameTranslationState(gameId = game.id)
    val language = LocalAppLanguage.current
    var source by rememberSaveable(game.id) { mutableStateOf("en") }
    var target by rememberSaveable(game.id) { mutableStateOf(if (language.code == "en") "fr" else language.code.take(2)) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    var manual by rememberSaveable(game.id) { mutableStateOf(false) }
    var pendingImport by rememberSaveable(game.id) { mutableStateOf<String?>(null) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) controller.exportManual(game, source, target, uri)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri?.toString()
    }
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
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            ScrollingColumn(Modifier.widthIn(max = 920.dp).fillMaxSize(), topBar = { CompactHeader("Traduire le jeu", onBack = if (!state.busy) onDismiss else null) }) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.Text(game.title, style = MaterialTheme.typography.titleMedium)
                    PrimaryTabRow(selectedTabIndex = if (manual) 1 else 0) {
                        Tab(selected = !manual, onClick = { manual = false }, enabled = !state.busy, text = { Text("Sur cet appareil") })
                        Tab(selected = manual, onClick = { manual = true }, enabled = !state.busy, text = { Text("Fichier pour IA") })
                    }
                    if (manual) {
                        Text("Exportez les textes, faites traduire le fichier JSON par l’IA de votre choix, puis rechargez-le dans Astra.")
                        Text("Modifiez uniquement les champs translation. Conservez les identifiants, les textes sources et les marqueurs ASTRA. Les instructions sont incluses dans le fichier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                    Text("Google Translate (ML Kit) traduit sur cet appareil, sans compte ni clé API.")
                    Text("Environ 30 Mo par langue au premier usage. Gardez Astra ouvert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TranslationLanguagePicker("Langue du jeu", source, !state.busy && state.analysis?.installed != true) { source = it }
                    TranslationLanguagePicker("Langue de traduction", target, !state.busy && state.analysis?.installed != true) { target = it }
                    if (!manual && state.analysis?.installed != true) Row(Modifier.fillMaxWidth().toggleable(value = wifiOnly, enabled = !state.busy, role = Role.Checkbox, onValueChange = { wifiOnly = it }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(wifiOnly, onCheckedChange = null, enabled = !state.busy)
                        Spacer(Modifier.width(8.dp))
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
                    state.message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary) }
                    state.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) }
                    if (!state.busy && state.analysis?.texts == 0 && state.analysis?.installed != true) Text("Aucun texte compatible trouvé. Vérifiez que le dossier data du jeu MV/MZ est accessible.")
                    Text("MV/MZ : dialogues, choix et menus standards. Images, plugins et sauvegardes exclus.", style = MaterialTheme.typography.bodySmall)
                    Text("Les noms utilisés par les conditions du jeu sont conservés pour rester compatibles avec vos sauvegardes.", style = MaterialTheme.typography.bodySmall)
                    Text("Originaux : data/.astra-translation. Conservez ce dossier.", style = MaterialTheme.typography.bodySmall)
                    if (!manual) {
                        val uriHandler = LocalUriHandler.current
                        TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/language/translation") }) { Text("À propos de Google Translate") }
                    }
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
                        if (manual) {
                            OutlinedButton(onClick = {
                                controller.prepareExternalPicker()
                                importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }, enabled = state.analysis != null) { Text("Importer la traduction") }
                            Button(onClick = {
                                controller.prepareExternalPicker()
                                exportFile.launch("astra-${game.title.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(70)}-$target.json")
                            }, enabled = state.analysis != null && state.analysis!!.texts > 0 && source != target) { Text("Exporter les textes") }
                        } else Button(onClick = { confirming = true }, enabled = state.analysis != null && state.analysis!!.texts > 0 && source != target) { Text("Traduire avec Google") }
                    }
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
    pendingImport?.let { sourceUri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Appliquer le fichier traduit ?") },
            text = { Text("Fermez le jeu. Astra vérifiera les textes et les commandes avant d’appliquer la traduction. Les originaux seront sauvegardés ; les champs translation vides resteront inchangés.") },
            confirmButton = { TextButton(onClick = { pendingImport = null; controller.importManual(game, Uri.parse(sourceUri)) }) { Text("Vérifier et appliquer") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Annuler") } }
        )
    }
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
