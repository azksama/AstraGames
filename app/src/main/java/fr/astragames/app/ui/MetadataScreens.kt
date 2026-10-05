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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.settings.SearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun F95ImportSheet(
    game: GameEntity,
    existingTags: List<TagEntity>,
    state: F95ImportState,
    openInExternalBrowser: Boolean,
    f95SessionUser: String?,
    onFetch: (String) -> Unit,
    onPrepareSearch: (MetadataSource) -> Unit,
    onComplete: (Set<String>, CoverCandidate?) -> Unit,
    onDismiss: () -> Unit,
    onConnectSession: (cookies: String, username: String?) -> Unit,
    onDisconnectSession: () -> Unit
) {
    val source = state.source
    var url by rememberSaveable(game.id, source) { mutableStateOf(if (source == MetadataSource.F95ZONE) game.f95Url.orEmpty() else game.ryuugamesUrl.orEmpty()) }
    val metadata = state.metadata
    var step by rememberSaveable(game.id) { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(metadata?.sourceUrl) { mutableStateOf(metadata?.tags.orEmpty()) }
    var selectedImageUrl by rememberSaveable(metadata?.sourceUrl) { mutableStateOf<String?>(null) }
    var showSearch by remember(game.id) { mutableStateOf(false) }
    var showLogin by remember(game.id) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val existingNames = remember(existingTags) { existingTags.map { it.normalizedName }.toSet() }
    LaunchedEffect(metadata?.sourceUrl) {
        if (metadata != null && step == 0) step = 1
    }
    LaunchedEffect(game.id) { onPrepareSearch(source) }
    Dialog(
        onDismissRequest = { if (step > 0) step-- else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (step > 0) step-- else onDismiss() }) { Icon(Icons.Default.Close, "Fermer") }
                    Column(Modifier.weight(1f)) {
                        Text("Importer des métadonnées", style = MaterialTheme.typography.titleLarge)
                        Text("Étape ${step + 1} sur 3 · ${game.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                if (source == MetadataSource.F95ZONE) Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (f95SessionUser != null) "Connecté en tant que ${f95SessionUser}"
                        else "Session F95Zone : non connectée",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (f95SessionUser != null) {
                        TextButton(onClick = onDisconnectSession) { Text("Se déconnecter") }
                    } else {
                        Button(onClick = { showLogin = true }) { Text("Se connecter") }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val contentModifier = if (maxWidth >= 840.dp) {
                        Modifier.widthIn(max = 920.dp).fillMaxHeight().align(Alignment.Center)
                    } else Modifier.fillMaxSize()
                    Column(contentModifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            when (step) {
                0 -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataSource.entries.forEach { option ->
                            FilterChip(selected = source == option, onClick = { showSearch = false; onPrepareSearch(option) }, enabled = !state.loading, label = { MaterialText(option.label) })
                        }
                    }
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
                        label = { Text("Lien de la fiche") }, placeholder = { MaterialText(if (source == MetadataSource.F95ZONE) "https://f95zone.to/threads/…" else "https://www.ryuugames.com/…/") },
                        leadingIcon = { Icon(Icons.Default.Link, null) }
                    )
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { onFetch(url) }, enabled = url.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Analyser le lien") }
                    Button(
                        onClick = {
                            state.browserUrl?.let { url ->
                                if (openInExternalBrowser) uriHandler.openUri(url) else showSearch = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.browserUrl != null && !state.loading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (openInExternalBrowser) "Ouvrir dans le navigateur" else "Rechercher automatiquement")
                    }
                    OutlinedButton(
                        onClick = {
                            state.browserUrl?.let { url ->
                                if (openInExternalBrowser) showSearch = true else uriHandler.openUri(url)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        enabled = state.browserUrl != null && !state.loading
                    ) {
                        Icon(if (openInExternalBrowser) Icons.Default.Search else Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (openInExternalBrowser) "Afficher dans Astra" else "Ouvrir dans le navigateur")
                    }
                }
                1 -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sélectionner les tags", style = MaterialTheme.typography.titleMedium)
                            Text("${selectedTags.size} sur ${metadata?.tags?.size ?: 0} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { selectedTags = metadata?.tags.orEmpty() }) { Text("Tout") }
                        TextButton(onClick = { selectedTags = emptyList() }) { Text("Aucun") }
                    }
                    val detectedTags = metadata?.tags.orEmpty()
                    if (detectedTags.isEmpty()) CenterMessage("Aucun tag détecté sur cette fiche.", Modifier.fillMaxWidth().weight(1f))
                    else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(detectedTags, key = { it.lowercase(java.util.Locale.ROOT) }) { tag ->
                            val normalized = tag.normalizeTagName()
                            ListItem(
                                modifier = Modifier.clickable { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag },
                                headlineContent = { MaterialText(tag) },
                                supportingContent = { Text(if (normalized in existingNames) "Tag existant" else "Nouveau tag") },
                                leadingContent = { Checkbox(tag in selectedTags, { selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag }) }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 0 }, Modifier.weight(1f)) { Text("Retour") }
                        TextButton(onClick = { selectedTags = emptyList(); step = 2 }, Modifier.weight(1f)) { Text("Ignorer") }
                        Button(onClick = { step = 2 }, Modifier.weight(1f)) { Text("Continuer") }
                    }
                }
                else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Text("Choisir une image", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                    Text("L’image choisie pourra être recadrée avant enregistrement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val images = metadata?.images.orEmpty()
                    val selectedImage = images.firstOrNull { it.imageUrl == selectedImageUrl }
                    if (images.isEmpty()) CenterMessage("Aucune image détectée sur cette fiche.", Modifier.fillMaxWidth().weight(1f))
                    else LazyVerticalGrid(
                        GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(images, key = { it.imageUrl }) { image ->
                            CoverCandidateCard(image, selected = selectedImageUrl == image.imageUrl) { selectedImageUrl = image.imageUrl }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 1 }, Modifier.weight(1f)) { Text("Retour") }
                        TextButton(onClick = { onComplete(selectedTags.toSet(), null) }, Modifier.weight(1f)) { Text("Ignorer") }
                        Button(
                            onClick = { selectedImage?.let { onComplete(selectedTags.toSet(), it) } },
                            enabled = selectedImage != null, modifier = Modifier.weight(1f)
                        ) { Text("Importer") }
                    }
                }
            }
                    }
                }
            }
        }
    }
    if (showSearch && state.browserUrl != null) SearchF95PickerDialog(
        source = source,
        searchUrl = state.browserUrl,
        searchEngine = state.searchEngine,
        onThreadSelected = { selectedUrl ->
            showSearch = false
            url = selectedUrl
            onFetch(selectedUrl)
        },
        onDismiss = { showSearch = false }
    )
    if (showLogin) F95LoginDialog(
        onSessionReady = { cookies, user ->
            showLogin = false
            onConnectSession(cookies, user)
        },
        onDismiss = { showLogin = false }
    )
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
    var error by remember { mutableStateOf<String?>(null) }
    val latestCallback by rememberUpdatedState(onSessionReady)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
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
                                        "(function(){var e=document.querySelector('.p-navgroup-linkText');return e?e.textContent.trim():'';})()"
                                    ) { result ->
                                        val clean = result.trim('"').ifBlank { null }
                                        if (clean != null && clean != "null") username = clean
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
                        if (cookies.isNullOrBlank() || !cookies.contains("xf_user=")) {
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
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Jaquette de ${game.title}", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Image locale") }
                if (game.coverUri != null) TextButton(onClick = onRemove) { Text("Supprimer") }
            }
            Button(
                onClick = {
                    state.browserUrl?.let { url ->
                        if (openInExternalBrowser) uriHandler.openUri(url) else showSearch = true
                    }
                },
                enabled = state.browserUrl != null && !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text(if (openInExternalBrowser) "Ouvrir dans le navigateur" else "Rechercher automatiquement") }
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
            when {
                state.loading -> CenterMessage("Recherche d’images…", Modifier.height(180.dp), true)
                state.downloading -> CenterMessage("Préparation du recadrage…", Modifier.height(220.dp), true)
                state.error != null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
                state.results.isNotEmpty() -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.results, key = { it.imageUrl }) { candidate ->
                        CoverCandidateCard(candidate, onClick = { onCandidate(candidate) })
                    }
                }
                else -> Spacer(Modifier.navigationBarsPadding().height(18.dp))
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
    val uriHandler = LocalUriHandler.current
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
      document.images.forEach(function(img) {
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
      const top = ranked[0];
      const best = ranked[0];
      return best ? JSON.stringify({url: best.url, title: best.title || ''}) : '';
    })();
""".trimIndent()

@Composable
internal fun CoverCandidateCard(candidate: CoverCandidate, selected: Boolean = false, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coverName = candidate.matchedTitle?.takeIf(String::isNotBlank)
        ?: runCatching { android.net.Uri.parse(candidate.imageUrl).lastPathSegment }.getOrNull()?.takeIf(String::isNotBlank)
        ?: candidate.source.ifBlank { "Image" }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column {
            Box {
                AsyncImage(candidate.imageUrl, candidate.matchedTitle, Modifier.fillMaxWidth().aspectRatio(1.15f), contentScale = ContentScale.Crop)
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
