package fr.astragames.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import fr.astragames.app.windows.*
import fr.astragames.app.wolfnative.*
import fr.astragames.wolf.WolfCompatibilityAuditor
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WolfCompatibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = "compatibility-${UUID.randomUUID()}"
    private val root = File(app.cacheDir, id).apply { mkdirs() }
    private val reportRoot = File(root, "astra-files").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = reportRoot
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = WolfCompatibilityController(context, scope)
    private fun game(directory: File) = GameEntity(id = id, title = "Audit Android", documentUri = Uri.fromFile(directory).toString(),
        physicalPath = directory.path, executableName = "Game.exe", engine = "WOLF_RPG", launcher = "ASTRA_WINDOWS",
        sourceId = "fixture", dateAdded = 0, lastModified = 0, fingerprint = "fixture")
    @After fun cleanup() {
        controller.cancel(); scope.cancel()
        assertEquals(app.cacheDir.canonicalFile, root.canonicalFile.parentFile); root.deleteRecursively()
        app.deleteSharedPreferences("$id-wolf_game_options")
    }
    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val owner = LocalContext.current as ActivityResultRegistryOwner
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner,
                LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme { block() } }
        }
    }
    @Test fun hiddenEntryRequiresLongPressAndRuntimeChoicesPreserveOtherGameSettings() {
        var opened = 0
        val store = WolfGameOptions(context, id)
        val original = WolfOptions(opacity = 37, pinchZoom = true, maxFps = 30, imageMode = WolfImageMode.FILL)
        store.save(original); store.position("confirm", false, .25f, .75f)
        content { androidx.compose.foundation.layout.Column {
            WolfCompatibilityEntry("Wolf RPG", { opened++ }); WolfRuntimePicker(id)
        } }
        compose.onNodeWithTag("wolf-compatibility-entry").performTouchInput { click() }
        assertEquals(0, opened)
        compose.onNodeWithTag("wolf-compatibility-entry").performTouchInput { longClick() }
        assertEquals(1, opened)
        for (mode in listOf(WolfRuntimeMode.NATIVE, WolfRuntimeMode.WINLATOR, WolfRuntimeMode.AUTO)) {
            compose.onNodeWithTag("wolf-runtime-${mode.name}").performClick().assertIsSelected()
            assertEquals(original.copy(runtime = mode), WolfGameOptions(context, id).read())
            assertEquals(.25f to .75f, store.position("confirm", false))
            assertEquals(WolfRuntimeMode.AUTO, WolfGameOptions(context, "$id-other").read().runtime)
        }
    }
    @Test fun independentAnalysisDisplaysInventoryPersistsAndExportsWithoutRunningOrChangingTheGame() {
        val directory = File(root, "game").apply { mkdirs() }; NativeGameFixture.write(directory)
        val originals = directory.walkTopDown().filter(File::isFile).associate { it.relativeTo(directory).path to it.readBytes().toList() }
        val entity = game(directory)
        content { WolfCompatibilityDialog(entity, controller, onDismiss = {}) }
        compose.onNodeWithText("Analyser le jeu").performClick()
        compose.waitUntil(20_000) { !controller.state.value.running && controller.state.value.report != null }
        val record = requireNotNull(controller.state.value.report)
        assertTrue(record.audit.complete); assertTrue(record.audit.commands > 0)
        assertEquals(originals, directory.walkTopDown().filter(File::isFile).associate { it.relativeTo(directory).path to it.readBytes().toList() })
        assertEquals(record, WolfCompatibilityReports(context).latest(id))
        compose.onNodeWithText("Fonctions").performScrollTo().performClick()
        compose.onNodeWithText("Filtrer par fonction, code ou fichier").performScrollTo().performTextInput("commande:150")
        compose.onNodeWithText("Images et textes").assertExists()
        compose.onNodeWithText("Partager").assertIsEnabled()
        compose.onNodeWithText("Enregistrer").assertIsEnabled()
        for (json in listOf(false, true)) {
            val shared = WolfCompatibilityReports(context).shareIntent(record, json)
            assertEquals(if (json) "application/json" else "text/plain", shared.type)
            assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            if (json) assertEquals(record, WolfCompatibilityReports.decode(text)) else assertTrue(text.contains("FONCTIONS UTILISÉES"))
            val destination = File(app.cacheDir, "wolf-reports/$id-export.${if (json) "json" else "txt"}").apply { writeText("") }
            try {
                WolfCompatibilityReports(context).export(record, FileProvider.getUriForFile(app, "${app.packageName}.files", destination), json)
                assertEquals(text, destination.readText())
            } finally { destination.delete() }
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithText("Bilan").performScrollTo().performClick()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.getExternalFilesDir(null), "wolf-compatibility-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun metadataErrorCanBeExportedAndCancellationCannotOverwritePreviousReport() {
        val empty = File(root, "empty").apply { mkdirs() }
        val entity = game(empty)
        compose.runOnIdle { controller.open(id); controller.start(entity) }
        compose.waitUntil(10_000) { !controller.state.value.running && controller.state.value.report != null }
        val previous = requireNotNull(controller.state.value.report)
        assertFalse(previous.audit.complete)
        assertTrue(previous.textReport().contains("Données non inspectables"))
        compose.runOnIdle { controller.start(entity); controller.cancel() }
        compose.waitUntil { !controller.state.value.running }
        assertEquals(previous, WolfCompatibilityReports(context).latest(id))
        assertFalse(File(reportRoot, "wolf-native").exists())
        assertFalse(File(context.noBackupFilesDir, "wolf-runtime/$id").exists())
    }
}
