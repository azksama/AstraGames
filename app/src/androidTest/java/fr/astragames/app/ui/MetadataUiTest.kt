package fr.astragames.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.metadata.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.repository.GameEdits
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataUiTest {
    @get:Rule val compose = createComposeRule()
    private val game = GameEntity("metadata-ui", "Mossbound", documentUri = "content://metadata/ui", physicalPath = null,
        executableName = "index.html", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "metadata-test",
        dateAdded = 1, lastModified = 1, fingerprint = "metadata-ui", developer = "Studio Aurora", version = "1.0")
    private val url = "https://f95zone.to/threads/mossbound.123/"

    @Test fun editorPreservesTypedTitleWhenAnImportUpdatesOtherFields() {
        var displayed by mutableStateOf(game)
        var saved: GameEdits? = null
        content {
            EditGameDialog(displayed, { edits, _ -> saved = edits },
                onF95 = { displayed = displayed.copy(developer = "Imported studio") }, onChangeCover = {}, onRemoveCover = {}, onDeleteGame = {}, onDismiss = {})
        }
        fields().performScrollToNode(hasText("Titre") and hasSetTextAction())
        compose.onNode(hasText("Titre") and hasSetTextAction()).performTextReplacement("My title")
        fields().performScrollToNode(hasText("Importer des métadonnées"))
        compose.onNodeWithText("Importer des métadonnées").performClick()
        compose.onNodeWithText("Enregistrer").performClick()
        compose.runOnIdle {
            assertEquals("My title", saved!!.title)
            assertEquals("Imported studio", saved!!.developer)
        }
    }

    @Test fun invalidSourceLinkDisablesSavingUntilCorrected() {
        content { EditGameDialog(game, { _, _ -> fail("Must not save") }, {}, {}, {}, {}, {}) }
        fields().performScrollToNode(hasText("Liens et identifiant"))
        compose.onNodeWithText("Liens et identifiant").performClick()
        fields().performScrollToNode(hasText("Lien F95Zone") and hasSetTextAction())
        compose.onNode(hasText("Lien F95Zone") and hasSetTextAction()).performTextReplacement("https://example.com/")
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
        compose.onNode(hasText("Lien F95Zone") and hasSetTextAction()).performTextReplacement(url)
        compose.onNodeWithText("Enregistrer").assertIsEnabled()
    }

    @Test fun saveFailureKeepsDraftAndShowsError() {
        var dismissed = false
        content { EditGameDialog(game, { _, _ -> error("Stockage indisponible") }, {}, {}, {}, {}, { dismissed = true }) }
        compose.onNodeWithText("Enregistrer").performClick()
        compose.onNodeWithText("Stockage indisponible").assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun closingADirtyEditorRequiresDiscardAndCanResumeEditing() {
        var dismissed = false
        content { EditGameDialog(game, { _, _ -> }, {}, {}, {}, {}, { dismissed = true }) }
        fields().performScrollToNode(hasText("Titre") and hasSetTextAction())
        compose.onNode(hasText("Titre") and hasSetTextAction()).performTextReplacement("Unsaved")
        compose.onNodeWithContentDescription("Fermer").performClick()
        compose.onNodeWithText("Quitter sans enregistrer ?").assertIsDisplayed()
        compose.onNodeWithText("Continuer à modifier").performClick()
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithContentDescription("Fermer").performClick()
        compose.onNodeWithText("Abandonner les modifications").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun reviewDefaultsToMissingFieldsAndAllowsImportWithoutCover() {
        var selection: MetadataImportSelection? = null
        val metadata = F95ZoneMetadata(url, emptyList(), emptyList(), developer = "Another studio", language = "English")
        content { importer(F95ImportState(gameId = game.id, metadata = metadata), onComplete = { selection = it }) }
        compose.onNodeWithText("Importer la sélection").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(setOf(MetadataField.LANGUAGE), selection!!.fields)
            assertNull(selection!!.image)
            assertTrue(selection!!.includeSourceLink)
        }
    }

    @Test fun guestAttachmentGateExplainsHowToRecoverAndCloseDoesNotImport() {
        var dismissed = false
        val metadata = F95ZoneMetadata(url, emptyList(), emptyList(), imagesRequireLogin = true)
        content { importer(F95ImportState(gameId = game.id, metadata = metadata), onComplete = { fail("Closing must not import") }, onDismiss = { dismissed = true }) }
        review().performScrollToNode(hasText("F95Zone masque les images sans connexion. Connectez-vous pour les récupérer."))
        compose.onNodeWithText("Se connecter à F95Zone").performScrollTo().assertIsDisplayed()
        screenshot("import-login")
        compose.onNodeWithContentDescription("Fermer").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun sourceScreenExposesRealAutomaticSearchAndManualLink() {
        var query: String? = null
        content { importer(F95ImportState(gameId = game.id), onFind = { query = it }) }
        compose.onNodeWithText("Trouver automatiquement").assertIsDisplayed()
        screenshot("import-source")
        compose.onNodeWithText("Trouver automatiquement").performClick()
        compose.runOnIdle { assertEquals("Mossbound", query) }
    }

    @Test fun editorShowsCoverImportAndSaveOnCompactScreens() {
        content { EditGameDialog(game.copy(coverUri = coverFixture()), { _, _ -> }, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Enregistrer").assertIsDisplayed()
        compose.onNodeWithText("Importer des métadonnées").assertExists()
        screenshot("editor")
    }

    @Test fun browserImageSelectionWorksWithAndroidHtmlCollection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        lateinit var webView: android.webkit.WebView
        instrumentation.runOnMainSync {
            webView = android.webkit.WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                layout(0, 0, 400, 600)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) =
                        android.webkit.WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(byteArrayOf()))
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript(IMAGE_SELECTION_SCRIPT) { result = it; ready.countDown() }
                    }
                }
                loadDataWithBaseURL("https://example.com/", "<html><body><img width='200' height='300' src='https://example.com/cover.png'></body></html>", "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val json = org.json.JSONTokener(result).nextValue() as String
            assertEquals("https://example.com/cover.png", org.json.JSONObject(json).getString("url"))
        } finally { instrumentation.runOnMainSync { webView.destroy() } }
    }

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme { body() } } }
    }

    @Composable private fun importer(state: F95ImportState, onComplete: (MetadataImportSelection) -> Unit = {}, onDismiss: () -> Unit = {}, onFind: (String) -> Unit = {}) {
        F95ImportSheet(game, emptyList(), state, false, null, {}, {}, onFind, onComplete, onDismiss, { _, _ -> }, {})
    }

    private fun fields() = compose.onNodeWithTag("metadata-editor-fields")
    private fun review() = compose.onNodeWithTag("metadata-import-review")

    private fun coverFixture(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "metadata-ui-cover.png")
        instrumentation.context.assets.open("design/mossbound.png").use { input -> file.outputStream().use(input::copyTo) }
        return android.net.Uri.fromFile(file).toString()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idle does not include the Android dialog window enter animation.
        android.os.SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
        val variant = InstrumentationRegistry.getArguments().getString("metadataVariant") ?: "portrait"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "metadata-evidence/$variant-$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
