package fr.astragames.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.windows.WolfGameStorage
import fr.astragames.app.windows.WolfLegacySaves
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File
import java.util.UUID

class WolfSavesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val id = UUID.randomUUID().toString()
    private val source = File(context.cacheDir, "wolf-saves-ui-$id").apply { mkdirs() }
    private val storage = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
    private val game = GameEntity(id = id, title = "Wolf test", documentUri = source.toURI().toString(), physicalPath = source.path,
        executableName = "Game.exe", engine = "WOLF_RPG", launcher = "native", sourceId = "fixture", dateAdded = 0, lastModified = 0, fingerprint = id)
    @After fun cleanup() {
        source.deleteRecursively(); storage.directory.deleteRecursively()
        val native = WolfLegacySaves.directoryFor(context, id)
        check(native.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
        native.parentFile!!.deleteRecursively()
    }

    @Test fun privateSavesAndTheirRealPathsCanBeFoundFromGameTools() = runBlocking {
        File(source, "Game.exe").writeText("fixture")
        File(source, "SaveData").mkdirs(); File(source, "SaveData/Save01.sav").writeText("progress")
        storage.prepare("Game.exe") { }
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(LocalAppLanguage provides fr.astragames.app.settings.AppLanguage.FRENCH) {
            MaterialTheme { WolfSavesSheet(game) {} }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SaveData/Save01.sav", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Exporter les sauvegardes").assertIsEnabled()
        compose.onNodeWithText("Copie privée dans Astra", substring = true).assertIsDisplayed()
        compose.onNodeWithText("SaveData/Save01.sav", substring = true).assertIsDisplayed()
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "wolf-saves-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun emptyGameDoesNotOfferAnEmptySaveExport(): Unit = runBlocking {
        File(source, "Game.exe").writeText("fixture")
        storage.prepare("Game.exe") { }
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(LocalAppLanguage provides fr.astragames.app.settings.AppLanguage.FRENCH) {
            MaterialTheme { WolfSavesSheet(game) {} }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Aucune sauvegarde repérée.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Exporter les sauvegardes").assertIsNotEnabled()
    }

    @Test fun nativeSavesAreListedAndExportableWithoutAnyWindowsSave(): Unit = runBlocking {
        File(source, "Game.exe").writeText("fixture")
        val native = WolfLegacySaves(context, id)
        native.directory.mkdirs()
        File(native.directory, "slot-0.astrawolf").writeBytes(byteArrayOf(1, 2, 3))
        compose.setContent { MaterialTheme { WolfSavesSheet(game) {} } }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Anciennes sauvegardes natives").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Exporter les sauvegardes").assertIsNotEnabled()
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToNode(hasText("Exporter les sauvegardes natives"))
            compose.onNodeWithText("Exporter les sauvegardes natives").assertIsDisplayed().assertIsEnabled()
            // A prefetched item can already have semantics without being placed. Scroll the
            // LazyColumn to materialize it instead of trusting that item's temporary bounds.
            list.performScrollToNode(hasText("slot-0.astrawolf", substring = true))
            compose.onNodeWithText("slot-0.astrawolf", substring = true).assertIsDisplayed()
            compose.onNodeWithText("slot-0.astrawolf", substring = true).assertTextContains(native.directory.path, substring = true)
            captureNativeEvidence("success")
        } catch (failure: Throwable) {
            runCatching { captureNativeEvidence("failure") }
            throw failure
        }
    }

    private fun captureNativeEvidence(result: String) {
        val directory = File(context.cacheDir, "global-review-ui").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "wolf-saves-native-$result.txt").writeText(buildString {
            appendLine(context.resources.configuration.toString())
            roots.fetchSemanticsNodes().indices.forEach { appendLine(roots[it].printToString()) }
        })
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "wolf-saves-native-$result.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
