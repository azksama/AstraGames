package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.CoverBlurMode
import fr.astragames.app.settings.SearchEngine

@Composable
internal fun OnboardingScreen(
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
internal fun LanguageSelector(
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
internal fun SearchEngineSelector(
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
internal fun CoverBlurSelector(
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
