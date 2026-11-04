package fr.astragames.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.AstraSettings
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real VM, Room, SAF scanner and navigation; intended for the dedicated SDK fixture AVDs. */
@RunWith(AndroidJUnit4::class)
class GlobalCatalogueFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val fixtureId = "global-catalogue-${UUID.randomUUID()}"
    private val gameId = "$fixtureId-game"
    private val sourceName = "source-${UUID.randomUUID()}"
    private val title = "Parcours catalogue ${UUID.randomUUID().toString().take(8)}"
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val previousLanguage = AppLocalizer.language
    private val grants = mutableListOf<Uri>()
    private val observedMessages = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var previousSettings: AstraSettings
    private lateinit var sourceTree: Uri
    private lateinit var modsTree: Uri
    private lateinit var vm: AstraViewModel
    private var sourceId: String? = null

    @Before fun fixtures() {
        previousSettings = runBlocking { app.container.settings.settings.first() }
        // Do not disable or alter a pre-existing lock to run a UI test.
        check(!previousSettings.lockPinEnabled && !previousSettings.lockBiometricEnabled) {
            "This test requires the unlocked SDK fixture installation."
        }
        runBlocking {
            app.container.settings.setScanOnLaunch(false)
            app.container.settings.setLanguage(AppLanguage.FRENCH)
            app.container.settings.setViewMode(LibraryViewMode.LIST)
            app.container.settings.setModsRoot(null)
        }
        val result = requireNotNull(app.contentResolver.call(provider, "fixture", fixtureId, null))
        val root = requireNotNull(documentDir(app, Uri.parse(requireNotNull(result.getString("uri")))))
        val source = requireNotNull(root.createDirectory(sourceName))
        val www = requireNotNull(source.createDirectory("www"))
        val js = requireNotNull(www.createDirectory("js"))
        write(js, "rpg_core.js", "// Local MV detection fixture; never executed.")
        write(www, "index.html", "<!doctype html><title>Local fixture</title>")
        requireNotNull(root.createDirectory("denied-mods"))
        sourceTree = tree("$fixtureId/$sourceName")
        modsTree = tree("$fixtureId/denied-mods")
        compose.runOnUiThread {
            vm = AstraViewModel(app)
            store.put(fixtureId, vm)
            vm.viewModelScope.launch {
                vm.events.collect { event -> if (event is UiEvent.Message) observedMessages += event.text }
            }
        }
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        try {
            app.contentResolver.call(provider, "denyAccess", null, null)
            runBlocking {
                // All cleanup is scoped to this run's UUID-owned rows, never to the whole DB.
                app.container.dao.deleteGameCompletely(gameId)
                val ownedSource = sourceId ?: if (::sourceTree.isInitialized) {
                    app.container.dao.observeSources().first().singleOrNull { it.treeUri == sourceTree.toString() }?.id
                } else null
                ownedSource?.let { app.container.dao.deleteSourceAndGames(it) }
                if (::previousSettings.isInitialized) {
                    app.container.settings.setModsRoot(previousSettings.modsRootUri)
                    app.container.settings.setViewMode(previousSettings.viewMode)
                    app.container.settings.setLanguage(previousSettings.language)
                    app.container.settings.setScanOnLaunch(previousSettings.scanOnLaunch)
                }
            }
        } finally {
            grants.forEach { uri ->
                if (app.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                    app.contentResolver.releasePersistableUriPermission(uri, grantFlags)
                }
                app.revokeUriPermission(uri, grantFlags)
            }
            app.contentResolver.call(provider, "removeFixture", fixtureId, null)
            AppLocalizer.language = previousLanguage
        }
    }

    @Test fun sourceScanSearchProfileAndModsFailureKeepTheCatalogueUsable() {
        val originalGameIds = runBlocking { app.container.dao.getGameIds().toSet() }
        val originalMods = runBlocking { app.container.dao.getMods() }
        compose.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            // Only onboarding is bypassed in the harness, without persisting that preference.
            // Catalogue, sources, settings, filters and tools all come from the actual VM.
            AstraTheme {
                AstraApp(
                    state.copy(settings = state.settings.copy(onboardingCompleted = true)), vm,
                    onPickSource = { vm.addSource(sourceTree) },
                    onPickTags = { error("Unexpected tag picker") },
                    onPickCover = { error("Unexpected cover picker") },
                    onPickBackupFolder = { error("Unexpected backup picker") },
                    onRestoreBackup = { error("Unexpected restore picker") },
                    onOpenBackupFolder = { error("Unexpected backup folder") }
                )
            }
        }
        compose.waitUntil(10_000) { vm.uiState.value.settingsLoaded && !vm.isLocked.value }
        compose.onNodeWithContentDescription("Ajouter une source").performClick()
        compose.waitUntil(10_000) { vm.uiState.value.sources.any { it.treeUri == sourceTree.toString() } }
        val source = vm.uiState.value.sources.single { it.treeUri == sourceTree.toString() }
        sourceId = source.id

        // A new discovered ID starts automatic online metadata enrichment. Seed UNKNOWN first:
        // the real scanner must detect and update it, but no new ID/network enrichment is needed.
        runBlocking {
            app.container.dao.upsertGame(GameEntity(
                id = gameId, title = title,
                documentUri = requireNotNull(documentDir(app, sourceTree)).uri.toString(),
                physicalPath = null, executableName = null, engine = "UNKNOWN", launcher = "NONE",
                sourceId = source.id, dateAdded = 1, lastModified = 1, fingerprint = fixtureId
            ))
        }
        compose.onNodeWithContentDescription("Paramètres").performClick()
        compose.onNodeWithText("Sources et scan").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(sourceName))
        compose.onNodeWithText(sourceName).performScrollTo().assertIsDisplayed()
        val scanner = compose.onNode(hasText("Scanner") and hasAnyAncestor(hasAnyChild(hasText(sourceName))))
        scanner.performScrollTo().assertIsDisplayed().assertIsEnabled()
        // Record the actual UI before the touch. A visible confirmation may temporarily
        // cover content above the navigation; do not inject a tap through that message.
        captureEvidence("source-before-scan")
        if (compose.onAllNodesWithText("Source ajoutée").fetchSemanticsNodes().isNotEmpty()) {
            awaitCondition("source-message-not-dismissed", 10_000) {
                compose.onAllNodesWithText("Source ajoutée").fetchSemanticsNodes().isEmpty()
            }
        }
        scanner.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        awaitCondition("scan-report-timeout", 15_000) {
            vm.scanReports.value.any { it.sourceId == source.id } && !vm.uiState.value.scanning
        }
        val report = vm.scanReports.value.single { it.sourceId == source.id }
        assertEquals(0, report.added)
        assertEquals(1, report.updated)
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals(originalGameIds + gameId, runBlocking { app.container.dao.getGameIds().toSet() })
        assertEquals("RPG_MAKER_MV", runBlocking { app.container.dao.getGame(gameId) }?.engine)
        compose.onNodeWithText("Rapport de scan").assertIsDisplayed()
        capture("scan")
        compose.onNodeWithText("Fermer").performClick()

        compose.onNodeWithContentDescription("Jeux").performClick()
        compose.onNodeWithText("Rechercher un jeu").performClick()
        compose.onNode(hasSetTextAction() and hasText("Rechercher")).performTextReplacement(title)
        closeSoftKeyboard()
        compose.waitUntil(10_000) {
            !vm.uiState.value.searching && vm.uiState.value.filteredGames.map { it.id } == listOf(gameId)
        }
        compose.onNode(hasText(title) and !hasSetTextAction()).assertIsDisplayed()
        capture("search")
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
        // The diagnostic replaces a loading summary and can change the card's height.
        compose.waitUntil(10_000) { vm.compatibility.value[gameId] != null }
        val scanMessage = "${report.found} jeux trouvés, ${report.added} ajoutés"
        awaitCondition("scan-completion-message-not-observed", 10_000) { scanMessage in observedMessages }
        scrollTo("Compatibilité")
        val compatibility = compose.onNodeWithText("Compatibilité", useUnmergedTree = true)
        compatibility.performScrollTo().assertIsDisplayed()
        captureEvidence("compatibility-before-wait")
        // The scan's real snackbar can cover a card near the bottom of a short viewport.
        // Wait for its normal dismissal instead of tapping through it or opening via the VM.
        val visibleScanMessage = AppLocalizer.text(scanMessage)
        awaitCondition("scan-message-not-dismissed", 10_000) {
            compose.onAllNodesWithText(visibleScanMessage).fetchSemanticsNodes().isEmpty()
        }
        scrollTo("Compatibilité")
        compatibility.performScrollTo().assertIsDisplayed()
        var previousBounds = compatibility.fetchSemanticsNode().boundsInWindow
        var stableSince = android.os.SystemClock.elapsedRealtime()
        awaitCondition("compatibility-bounds-not-stable", 5_000) {
            val bounds = compatibility.fetchSemanticsNode().boundsInWindow
            if (bounds != previousBounds) {
                previousBounds = bounds
                stableSince = android.os.SystemClock.elapsedRealtime()
            }
            compose.onAllNodesWithText(visibleScanMessage).fetchSemanticsNodes().isEmpty() &&
                android.os.SystemClock.elapsedRealtime() - stableSince >= 300
        }
        captureEvidence("compatibility-before-tap")
        compatibility.assertIsDisplayed().performClick()
        // A platform Dialog installs a separate Compose root asynchronously.
        awaitText("Profil de lancement", "compatibility-not-opened")
        compose.onNode(hasText("Profil de lancement") and hasClickAction())
            .performScrollTo().assertIsDisplayed().performClick()
        awaitText("Ces réglages remplacent uniquement la détection automatique pour ce jeu.", "profile-not-opened")
        scrollTo("Arguments personnalisés")
        compose.onNode(hasSetTextAction() and hasText("Arguments personnalisés"))
            .performScrollTo().performTextReplacement("--global-ui-fixture")
        closeSoftKeyboard()
        capture("profile")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitUntil(10_000) {
            runBlocking { app.container.dao.getLaunchProfile(gameId) }?.arguments == "--global-ui-fixture"
        }

        scrollTo("Outils")
        compose.onNodeWithText("Outils").performScrollTo().performClick()
        scrollTo("Gérer les mods")
        compose.onNodeWithText("Gérer les mods").performScrollTo().performClick()
        compose.onNodeWithText("Choisir le dépôt de mods").assertIsDisplayed()
        compose.waitUntil(10_000) { !vm.tools.modsLoading.value }
        assertNull(vm.tools.modsError.value)

        // A successful empty repository scan prunes absent packages globally. Do not do that
        // to another fixture's catalogue: exercise unconfigured + denied SAF states instead.
        app.contentResolver.call(provider, "denyAccess", "$fixtureId/denied-mods", null)
        runBlocking { app.container.settings.setModsRoot(modsTree.toString()) }
        compose.waitUntil(10_000) { vm.uiState.value.settings.modsRootUri == modsTree.toString() }
        compose.onNodeWithText("Rescanner").performClick()
        compose.waitUntil(10_000) { !vm.tools.modsLoading.value && vm.tools.modsError.value != null }
        compose.onNodeWithText(AppLocalizer.text(requireNotNull(vm.tools.modsError.value))).assertIsDisplayed()
        compose.onNodeWithText("Rescanner").assertIsEnabled()
        capture("mods-error")
        assertEquals(originalMods, runBlocking { app.container.dao.getMods() })
        pressBack()
        app.contentResolver.call(provider, "denyAccess", null, null)
        runBlocking { app.container.settings.setModsRoot(null) }
        scrollTo("Gérer les mods")
        compose.onNodeWithText("Gérer les mods").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.tools.modsLoading.value && vm.tools.modsError.value == null }
        compose.onNodeWithText("Choisir le dépôt de mods").assertIsDisplayed()
        pressBack()
        pressBack()
        pressBack() // Return from the scrolled detail page through Android's actual back action.
        compose.onNode(hasSetTextAction() and hasText("Rechercher")).assertTextContains(title)
        compose.onNode(hasText(title) and !hasSetTextAction()).assertIsDisplayed()
        assertEquals(originalMods, runBlocking { app.container.dao.getMods() })
    }

    private fun tree(documentId: String): Uri = DocumentsContract.buildTreeDocumentUri(
        FixtureDocumentsProvider.AUTHORITY, documentId
    ).also { uri ->
        // Model the persistable permission supplied by ACTION_OPEN_DOCUMENT_TREE's result.
        app.grantUriPermission(app.packageName, uri,
            grantFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        grants += uri
    }

    private fun write(parent: DocumentFile, name: String, text: String) {
        val file = requireNotNull(parent.createFile("application/octet-stream", name))
        requireNotNull(app.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(text.toByteArray()) }
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText(text))
    }

    private fun awaitText(text: String, failureName: String) {
        awaitCondition(failureName, 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitCondition(failureName: String, timeoutMillis: Long, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (failure: Throwable) {
            runCatching { captureEvidence(failureName) }
            throw failure
        }
    }

    private fun captureEvidence(name: String) {
        val directory = File(app.cacheDir, "global-review-ui").apply { mkdirs() }
        val state = vm.uiState.value
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "catalogue-flow-$name.txt").writeText(buildString {
            appendLine(app.resources.configuration.toString())
            appendLine("scanning=${state.scanning}; progress=${state.scanProgress}")
            appendLine("sourceUi=${state.sources.singleOrNull { it.id == sourceId }}")
            appendLine("sourceDb=${runBlocking { sourceId?.let { app.container.dao.getSource(it) } }}")
            appendLine("game=${runBlocking { app.container.dao.getGame(gameId) }}")
            appendLine("reports=${vm.scanReports.value.filter { it.sourceId == sourceId }}")
            appendLine("observedMessages=$observedMessages")
            appendLine("compatibilityWindow=" + compose.onAllNodesWithText("Compatibilité", useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInWindow })
            observedMessages.distinct().forEach { message ->
                appendLine("messageWindow[$message]=" + compose.onAllNodesWithText(AppLocalizer.text(message))
                    .fetchSemanticsNodes().map { it.boundsInWindow })
            }
            roots.fetchSemanticsNodes().indices.forEach { appendLine(roots[it].printToString()) }
        })
        File(directory, "catalogue-flow-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400) // Native WindowManager fade is outside the Compose clock.
        val output = File(app.cacheDir, "global-review-ui").apply { mkdirs() }.resolve("catalogue-flow-$name.png")
        output.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
