package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.*
import coil3.compose.AsyncImage
import fr.astragames.app.core.metadata.CoverCandidate
import fr.astragames.app.core.metadata.extractF95ThreadUrl
import fr.astragames.app.core.metadata.MetadataSource
import fr.astragames.app.core.metadata.MetadataField
import fr.astragames.app.core.metadata.MetadataImportSelection
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import coil3.request.ImageRequest
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.settings.SearchEngine

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun F95ImportSheet(
    game: GameEntity,
    existingTags: List<TagEntity>,
    state: F95ImportState,
    openInExternalBrowser: Boolean,
    f95SessionUser: String?,
    onFetch: (String) -> Unit,
    onPrepareSearch: (MetadataSource) -> Unit,
    onFind: (String) -> Unit,
    onComplete: (MetadataImportSelection) -> Unit,
    onDismiss: () -> Unit,
    onConnectSession: (cookies: String, username: String?) -> Unit,
    onDisconnectSession: () -> Unit
) {
    val source = state.source
    val metadata = state.metadata
    val busy = state.loading || state.saving
    var url by rememberSaveable(game.id, source) { mutableStateOf(if (source == MetadataSource.F95ZONE) game.f95Url.orEmpty() else game.ryuugamesUrl.orEmpty()) }
    var query by rememberSaveable(game.id) { mutableStateOf(game.title) }
    var review by rememberSaveable(game.id) { mutableStateOf(metadata != null) }
    var selectedTags by rememberSaveable(metadata) { mutableStateOf(metadata?.tags.orEmpty()) }
    var selectedFields by rememberSaveable(metadata) {
        mutableStateOf(MetadataField.entries.filter { metadata != null && it.incoming(metadata) != null && it.current(game).isNullOrBlank() }.map { it.name })
    }
    var includeLink by rememberSaveable(metadata) { mutableStateOf(true) }
    var selectedImageUrl by rememberSaveable(metadata) { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var reloadAfterLogin by remember { mutableStateOf(false) }
    var showLinkError by rememberSaveable { mutableStateOf(false) }
    val uriHandler = rememberExternalUriHandler()
    val existingNames = remember(existingTags) { existingTags.map { it.normalizedName }.toSet() }
    val validLink = source.pageUrl(url)
    LaunchedEffect(metadata) { if (metadata != null) review = true }
    LaunchedEffect(game.id) { if (state.gameId != game.id) onPrepareSearch(source) }
    LaunchedEffect(f95SessionUser, reloadAfterLogin) {
        if (reloadAfterLogin && f95SessionUser != null) {
            reloadAfterLogin = false
            metadata?.sourceUrl?.let(onFetch)
        }
    }
    Dialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Column(Modifier.widthIn(max = 920.dp).fillMaxSize().align(Alignment.TopCenter)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, enabled = !state.saving) { Icon(Icons.Default.Close, "Fermer") }
                        Column(Modifier.weight(1f)) {
                            Text(if (review && metadata != null) "Vérifier l’import" else "Importer des métadonnées", style = MaterialTheme.typography.titleLarge)
                            MaterialText(game.title, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                    if (!review || metadata == null) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Trouver la fiche du jeu", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetadataSource.entries.forEach { option ->
                                    FilterChip(source == option, { showLinkError = false; onPrepareSearch(option) }, enabled = !busy, label = { MaterialText(option.label) })
                                }
                            }
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Nom du jeu à rechercher") }, singleLine = true, enabled = !busy)
                            Button(onClick = { review = true; onFind(query.trim()) }, enabled = query.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Trouver automatiquement")
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Vous avez déjà un lien ?", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                url, { url = it; showLinkError = false }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                                label = { Text("Lien de la fiche") },
                                placeholder = { MaterialText(if (source == MetadataSource.F95ZONE) "https://f95zone.to/threads/…" else "https://www.ryuugames.com/…/") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                isError = showLinkError && validLink == null,
                                supportingText = { if (showLinkError && validLink == null) Text("Collez un lien valide pour la source sélectionnée.") }
                            )
                            OutlinedButton(onClick = { if (validLink == null) showLinkError = true else { review = true; onFetch(validLink) } }, enabled = url.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Lire cette fiche")
                            }
                            TextButton(onClick = {
                                val target = source.searchUrl(query, state.searchEngine)
                                if (openInExternalBrowser) uriHandler.openUri(target) else showSearch = true
                            }, enabled = query.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Choisir dans le navigateur")
                            }
                            if (source == MetadataSource.F95ZONE) {
                                HorizontalDivider()
                                Text(if (metadata?.sessionExpired == true) "La session F95Zone a expiré. Reconnectez-vous pour récupérer les images."
                                    else if (f95SessionUser != null) "Session F95Zone enregistrée · vérifiée à la lecture de la fiche"
                                    else "Certaines fiches et images nécessitent une connexion à F95Zone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val needsLogin = f95SessionUser == null || metadata?.sessionExpired == true
                                TextButton(onClick = { if (needsLogin) showLogin = true else onDisconnectSession() }, enabled = !busy) {
                                    Text(if (needsLogin) "Se connecter à F95Zone" else "Se déconnecter")
                                }
                            }
                            Text("Vous pourrez vérifier la sélection avant de l’enregistrer.", style = MaterialTheme.typography.bodySmall)
                            if (state.loading) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Lecture des métadonnées…")
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        val availableFields = MetadataField.entries.filter { it.incoming(metadata) != null }
                        LazyColumn(Modifier.weight(1f).testTag("metadata-import-review"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                MaterialText(metadata.title ?: source.label, style = MaterialTheme.typography.titleMedium)
                                MaterialText(metadata.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item {
                                MetadataChoiceRow("Enregistrer le lien source", includeLink, !busy, { includeLink = it })
                                Text("Seuls les éléments cochés seront importés. Les tags actuels sont conservés.", style = MaterialTheme.typography.bodySmall)
                            }
                            item {
                                Text("Informations", style = MaterialTheme.typography.titleMedium)
                                if (availableFields.isEmpty()) Text("Aucune information supplémentaire détectée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Text("Les champs vides sont présélectionnés. Cochez une valeur existante pour la remplacer.", style = MaterialTheme.typography.bodySmall)
                            }
                            items(availableFields, key = { it.name }) { field ->
                                Column {
                                    MetadataChoiceRow(field.label, field.name in selectedFields, !busy, { checked ->
                                        selectedFields = if (checked) selectedFields + field.name else selectedFields - field.name
                                    })
                                    field.current(game)?.takeIf(String::isNotBlank)?.let { current ->
                                        Text("Valeur actuelle", style = MaterialTheme.typography.labelSmall)
                                        MaterialText(current, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text("Valeur trouvée", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                                    }
                                    MaterialText(field.incoming(metadata).orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            item {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Text("Tags", style = MaterialTheme.typography.titleMedium)
                                if (metadata.tags.isEmpty()) Text("Aucun tag détecté sur cette fiche.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else {
                                    Row {
                                        TextButton(onClick = { selectedTags = metadata.tags }, enabled = !busy) { Text("Tout sélectionner") }
                                        TextButton(onClick = { selectedTags = emptyList() }, enabled = !busy) { Text("Aucun") }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        metadata.tags.forEach { tag ->
                                            FilterChip(selected = tag in selectedTags, onClick = { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag }, enabled = !busy,
                                                label = { MaterialText(tag) },
                                                leadingIcon = if (tag.normalizeTagName() in existingNames) ({ Icon(Icons.Default.Label, null, Modifier.size(16.dp)) }) else null)
                                        }
                                    }
                                }
                            }
                            item {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Text("Jaquette", style = MaterialTheme.typography.titleMedium)
                                Text("Facultatif · l’image choisie sera ensuite proposée au recadrage.", style = MaterialTheme.typography.bodySmall)
                                MetadataChoiceRow(if (game.coverUri != null) "Conserver la jaquette actuelle" else "Ne pas ajouter de jaquette", selectedImageUrl == null, !busy, { selectedImageUrl = null })
                                if (metadata.imagesRequireLogin || metadata.sessionExpired) {
                                    Text(if (metadata.sessionExpired) "La session F95Zone a expiré. Reconnectez-vous pour récupérer les images."
                                        else "F95Zone masque les images sans connexion. Connectez-vous pour les récupérer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick = { showLogin = true }, enabled = !busy) { Text("Se connecter à F95Zone") }
                                    TextButton(onClick = { onFetch(metadata.sourceUrl) }, enabled = !busy) { Text("Relire la fiche") }
                                } else if (metadata.images.isEmpty()) Text("Aucune image détectée sur cette fiche.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(metadata.images.chunked(2), key = { it.first().imageUrl }) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { candidate ->
                                        Box(Modifier.weight(1f)) {
                                            CoverCandidateCard(candidate, selectedImageUrl == candidate.imageUrl, enabled = !busy) {
                                                selectedImageUrl = if (selectedImageUrl == candidate.imageUrl) null else candidate.imageUrl
                                            }
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Import en cours…") }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { review = false }, enabled = !busy) { Text("Retour") }
                                Button(onClick = {
                                    onComplete(MetadataImportSelection(selectedFields.map(MetadataField::valueOf).toSet(), selectedTags.toSet(), metadata.images.firstOrNull { it.imageUrl == selectedImageUrl }, includeLink))
                                }, enabled = !busy && (includeLink || selectedFields.isNotEmpty() || selectedTags.isNotEmpty() || selectedImageUrl != null), modifier = Modifier.weight(1f)) { Text("Importer la sélection") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showSearch) SearchF95PickerDialog(
        source = source, searchUrl = source.searchUrl(query, state.searchEngine), searchEngine = state.searchEngine,
        onThreadSelected = { selectedUrl -> showSearch = false; url = selectedUrl; review = true; onFetch(selectedUrl) },
        onDismiss = { showSearch = false }
    )
    if (showLogin) F95LoginDialog(
        onSessionReady = { cookies, user -> showLogin = false; reloadAfterLogin = metadata != null; onConnectSession(cookies, user) },
        onDismiss = { showLogin = false }
    )
}

@Composable
private fun MetadataChoiceRow(label: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SearchF95PickerDialog(
    searchUrl: String,
    searchEngine: SearchEngine,
    onThreadSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    source: MetadataSource = MetadataSource.F95ZONE
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val latestSelection by rememberUpdatedState(onThreadSelected)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader(
                    "Rechercher sur ${searchEngine.displayName}",
                    "Appui long sur la fiche du jeu",
                    onBack = onDismiss
                )
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            configureNetworkOnly()
                            webViewClient = object : NetworkOnlyWebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    return !isAllowedWebUrl(request.url.toString())
                                }
                            }
                            setOnLongClickListener {
                                val hit = hitTestResult
                                if (hit.type !in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                                    return@setOnLongClickListener false
                                }
                                val threadUrl = hit.extra?.let(source::pageUrl)
                                if (threadUrl == null) {
                                    selectionError = "Ce lien ne correspond pas à la source sélectionnée."
                                } else {
                                    selectionError = null
                                    latestSelection(threadUrl)
                                }
                                true
                            }
                            if (isAllowedWebUrl(searchUrl)) loadUrl(searchUrl)
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
                        val threadUrl = webView?.url?.let(source::pageUrl)
                        if (threadUrl == null) {
                            selectionError = "Ce lien ne correspond pas à la source sélectionnée."
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
internal fun F95LoginDialog(
    onSessionReady: (cookies: String, username: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var authenticated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestCallback by rememberUpdatedState(onSessionReady)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
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
                            configureNetworkOnly()
                            webViewClient = object : NetworkOnlyWebViewClient(loginOnly = true) {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    if (!isAllowedWebUrl(url, loginOnly = true)) return
                                    view.evaluateJavascript(
                                        "(function(){var e=document.querySelector('.p-navgroup-link--user .p-navgroup-linkText');return JSON.stringify({authenticated:document.documentElement.getAttribute('data-logged-in')==='true',user:e?e.textContent.trim():''});})()"
                                    ) { result ->
                                        val payload = runCatching { org.json.JSONObject(org.json.JSONTokener(result).nextValue() as String) }.getOrNull()
                                        authenticated = payload?.optBoolean("authenticated") == true
                                        username = payload?.optString("user")?.takeIf(String::isNotBlank)
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
                        if (!authenticated || cookies.isNullOrBlank() || !cookies.split(';').any { it.trim().startsWith("xf_session=") && it.substringAfter('=').isNotBlank() }) {
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
internal fun CoverPickerSheet(
    game: GameEntity, state: CoverSearchState, openInExternalBrowser: Boolean, onCandidate: (CoverCandidate) -> Unit,
    onLocal: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit
) {
    var showSearch by remember { mutableStateOf(false) }
    val uriHandler = rememberExternalUriHandler()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().testTag("cover-picker-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Jaquette de ${game.title}", style = MaterialTheme.typography.titleLarge) }
            item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, Modifier.weight(1f), enabled = !state.downloading) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Image locale") }
                if (game.coverUri != null) TextButton(onClick = onRemove, enabled = !state.downloading) { Text("Supprimer") }
            }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
            Button(
                onClick = {
                    state.browserUrl?.let { url ->
                        if (openInExternalBrowser) uriHandler.openUri(url) else showSearch = true
                    }
                },
                enabled = state.browserUrl != null && !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text(if (openInExternalBrowser) "Ouvrir dans le navigateur" else "Choisir dans le navigateur") }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
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
            }
            when {
                state.loading -> item(span = { GridItemSpan(maxLineSpan) }) { CenterMessage("Recherche d’images…", Modifier.heightIn(min = 120.dp), true) }
                state.downloading -> item(span = { GridItemSpan(maxLineSpan) }) { CenterMessage("Préparation du recadrage…", Modifier.heightIn(min = 120.dp), true) }
                state.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
                }
                else -> items(state.results, key = { it.imageUrl }) { candidate ->
                    CoverCandidateCard(candidate, onClick = { onCandidate(candidate) })
                }
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
internal fun SearchImagePickerDialog(
    searchUrl: String,
    searchEngine: SearchEngine,
    onCandidate: (CoverCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var contextImageUrl by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val latestCandidate by rememberUpdatedState(onCandidate)
    val uriHandler = rememberExternalUriHandler()
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
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompactHeader("${searchEngine.displayName} Images", "Touchez une image pour afficher son aperçu", onBack = onDismiss)
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            configureNetworkOnly()
                            webViewClient = object : NetworkOnlyWebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    view.evaluateJavascript(IMAGE_SELECTION_BOOTSTRAP_SCRIPT, null)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    return !isAllowedWebUrl(request.url.toString())
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
                            if (isAllowedWebUrl(searchUrl)) loadUrl(searchUrl)
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

internal val IMAGE_SELECTION_BOOTSTRAP_SCRIPT = """
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

internal val IMAGE_SELECTION_SCRIPT = """
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
      Array.from(document.images).forEach(function(img) {
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
      const best = ranked[0];
      return best ? JSON.stringify({url: best.url, title: best.title || ''}) : '';
    })();
""".trimIndent()

@Composable
internal fun CoverCandidateCard(candidate: CoverCandidate, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coverName = candidate.matchedTitle?.takeIf(String::isNotBlank)
        ?: runCatching { android.net.Uri.parse(candidate.imageUrl).lastPathSegment }.getOrNull()?.takeIf(String::isNotBlank)
        ?: candidate.source.ifBlank { "Image" }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column {
            Box {
                var failed by remember(candidate.thumbnailUrl) { mutableStateOf(false) }
                val request = remember(candidate, context) {
                    ImageRequest.Builder(context).data(candidate.thumbnailUrl).httpHeaders(
                        NetworkHeaders.Builder().apply { candidate.contextUrl?.let { set("Referer", it) } }.build()
                    ).build()
                }
                AsyncImage(request, candidate.matchedTitle, Modifier.fillMaxWidth().aspectRatio(1.15f), contentScale = ContentScale.Crop,
                    onSuccess = { failed = false }, onError = { failed = true })
                if (failed) Column(Modifier.fillMaxWidth().aspectRatio(1.15f).padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BrokenImage, null)
                    Text("Aperçu indisponible", style = MaterialTheme.typography.labelMedium)
                }
                if (selected) Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.Check, "Image sélectionnée", Modifier.padding(5.dp).size(18.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                MaterialText(
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
internal fun CoverFullscreenDialog(uri: String, title: String, onDismiss: () -> Unit) {
    val coverBlur = LocalCoverBlurState.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AstraDialogSystemBars()
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
