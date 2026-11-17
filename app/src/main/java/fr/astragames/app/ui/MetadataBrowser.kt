package fr.astragames.app.ui

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.astragames.app.core.metadata.MetadataSource
import fr.astragames.app.settings.SearchEngine

@Composable
internal fun SearchF95PickerDialog(
    searchUrl: String,
    searchEngine: SearchEngine,
    onThreadSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    source: MetadataSource = MetadataSource.F95ZONE
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(searchUrl) }
    var editingAddress by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(searchUrl) }
    var currentUrl by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf(searchEngine) }
    var engineMenu by remember { mutableStateOf(false) }
    var lastQuery by remember {
        mutableStateOf(android.net.Uri.parse(searchUrl).let { it.getQueryParameter("text") ?: it.getQueryParameter("q") } ?: source.domain)
    }
    val latestSelection by rememberUpdatedState(onThreadSelected)
    val keyboard = LocalSoftwareKeyboardController.current
    val selectedUrl = currentUrl.takeIf { isAllowedWebUrl(it) }?.let(source::pageUrl)
    fun load(target: String) {
        pendingUrl = target; editingAddress = false
        error = null; inputError = null; currentUrl = ""; address = target
        webView?.stopLoading()
        webView?.loadUrl(target)
    }
    fun belongsToNavigation(view: WebView, url: String): Boolean =
        pendingUrl == null || pendingUrl == url || pendingUrl == view.originalUrl
    fun navigate() {
        val input = address.trim()
        if (input.isBlank()) return
        val target = when {
            input.contains("://") -> input
            input.startsWith("f95zone.to/") || input.startsWith("www.ryuugames.com/") || input.startsWith("ryuugames.com/") -> "https://$input"
            else -> source.searchUrl(input, engine).also { lastQuery = "$input ${source.domain}" }
        }
        if (!isAllowedWebUrl(target)) {
            webView?.stopLoading(); currentUrl = ""
            inputError = "Utilisez une adresse HTTPS ou le nom du jeu."; return
        }
        keyboard?.hide()
        load(target)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
        BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onDismiss() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                CompactHeader("Navigateur Astra", source.label, onBack = onDismiss)
                OutlinedTextField(address, { address = it; editingAddress = true }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("metadata-browser-address"),
                    label = { Text("Rechercher ou saisir une adresse") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate() }),
                    trailingIcon = { IconButton(onClick = { navigate() }) { Icon(Icons.Default.Search, "Rechercher") } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        IconButton(onClick = { pendingUrl = null; editingAddress = false; inputError = null; webView?.goBack() }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Page précédente") }
                        IconButton(onClick = { pendingUrl = null; editingAddress = false; inputError = null; webView?.goForward() }, enabled = canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Page suivante") }
                        IconButton(onClick = { pendingUrl = null; editingAddress = false; error = null; inputError = null; webView?.reload() }) { Icon(Icons.Default.Refresh, "Recharger la page") }
                    }
                    Box {
                        TextButton(onClick = { engineMenu = true }) {
                            Text(engine.displayName); Icon(Icons.Default.ArrowDropDown, "Choisir le moteur de recherche")
                        }
                        DropdownMenu(engineMenu, { engineMenu = false }) {
                            SearchEngine.entries.forEach { option -> DropdownMenuItem(
                                text = { Text(option.displayName) }, onClick = {
                                    engine = option; engineMenu = false
                                    load(option.webSearchUrl(lastQuery))
                                }, trailingIcon = { if (option == engine) Icon(Icons.Default.Check, null) }
                            ) }
                        }
                    }
                }
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                AndroidView(factory = { context ->
                    WebView(context).apply {
                        configureNetworkOnly()
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : NetworkOnlyWebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (!belongsToNavigation(view, url)) return
                                currentUrl = ""; error = null
                                if (!editingAddress) address = url
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!belongsToNavigation(view, url) || view.url != url) return
                                pendingUrl = null; currentUrl = url
                                if (!editingAddress) address = url
                                canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame && belongsToNavigation(view, request.url.toString())) { error = "La page n’a pas pu être chargée. Vérifiez la connexion puis rechargez."; currentUrl = "" }
                            }
                        }
                        if (isAllowedWebUrl(searchUrl)) loadUrl(searchUrl)
                        webView = this
                    }
                }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.stopLoading(); it.destroy(); webView = null })
                (inputError ?: error)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    if (selectedUrl == null) Text("Ouvrez une fiche ${source.label} pour utiliser son lien.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { selectedUrl?.let(latestSelection) }, enabled = selectedUrl != null && error == null && inputError == null,
                        modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Utiliser le lien") }
                }
            }
        }
    }
}
