package fr.astragames.app.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI

/** Search pages may redirect across HTTPS sites, but never to app or local-file schemes. */
internal fun isAllowedWebUrl(url: String, loginOnly: Boolean = false): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
    uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (!loginOnly || host == "f95zone.to" || host.endsWith(".f95zone.to"))
}.getOrDefault(false)

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureNetworkOnly() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        userAgentString = SEARCH_WEBVIEW_USER_AGENT
    }
}

/** Covers main-frame POST requests as well as link/redirect navigation. */
internal open class NetworkOnlyWebViewClient(private val loginOnly: Boolean = false) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isAllowedWebUrl(request.url.toString(), loginOnly && request.isForMainFrame)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame && !isAllowedWebUrl(request.url.toString(), loginOnly)) {
            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                ByteArrayInputStream("Navigation blocked".toByteArray(Charsets.UTF_8)))
        }
        return super.shouldInterceptRequest(view, request)
    }
}
