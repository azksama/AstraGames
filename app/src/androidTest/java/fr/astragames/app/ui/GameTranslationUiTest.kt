package fr.astragames.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.translation.GameTranslationManager
import fr.astragames.app.translation.TextTranslator
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directory = File(app.cacheDir, "translation-ui-${UUID.randomUUID()}")
    private val previousLanguage = AppLocalizer.language

    @After fun cleanup() {
        scope.cancel()
        directory.deleteRecursively()
        AppLocalizer.language = previousLanguage
    }

    @Test fun userCanAnalyzeConfirmTranslateAndRestoreWithoutLeavingTheScreen() {
        val data = File(directory, "game/data").apply { mkdirs() }
        val original = """{"displayName":"Village","events":[null,{"pages":[{"list":[{"code":401,"parameters":["Hello, traveler!"]}]}]}]}""".toByteArray()
        val file = File(data, "Map001.json").apply { writeBytes(original) }
        val game = GameEntity(
            id = "translation-ui", title = "The Clockwork Garden", documentUri = Uri.fromFile(data.parentFile).toString(),
            physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MZ", launcher = "JOIPLAY",
            sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "test"
        )
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        }
        val manager = GameTranslationManager(context) { _, _ -> object : TextTranslator {
            override suspend fun prepare(wifiOnly: Boolean) = Unit
            override suspend fun translate(text: String) = if (text == "Village") "Bourg" else "Bonjour, voyageur !"
            override fun close() = Unit
        } }
        val controller = GameTranslationController(app, scope, manager)
        AppLocalizer.language = AppLanguage.FRENCH
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { GameTranslationScreen(game, controller) {} }
            }
        }
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.analysis != null }
        compose.onNodeWithText("The Clockwork Garden").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Traduire avec Google").assertIsDisplayed().assertIsEnabled()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(app.cacheDir, "ui-review").apply { mkdirs() }.resolve("translation-light.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("Traduire avec Google").assertIsDisplayed().performClick()
        compose.onNodeWithText("Traduire le jeu ?").assertIsDisplayed()
        compose.onNodeWithText("Supprimer").assertDoesNotExist()
        compose.onAllNodesWithText("Traduire avec Google").onLast().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { controller.state.value.analysis?.installed == true && !controller.state.value.busy }
        compose.onNodeWithText("Restaurer les originaux").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { controller.state.value.message == "Originaux restaurés" && !controller.state.value.busy }
        assertArrayEquals(original, file.readBytes())
        compose.onNodeWithText("Traduire avec Google").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun remainingTimeAndPartialTranslationReportAreShown() {
        val data = File(directory, "timing/data").apply { mkdirs() }
        val original = """{"displayName":"Village","events":[null,{"pages":[{"list":[{"code":401,"parameters":["First line."]},{"code":401,"parameters":["Second line."]},{"code":401,"parameters":["Third line."]}]}]}]}""".toByteArray()
        val file = File(data, "Map001.json").apply { writeBytes(original) }
        val game = GameEntity(id = "translation-timing", title = "Translation timing", documentUri = Uri.fromFile(data.parentFile).toString(), physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "test")
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() } }
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val manager = GameTranslationManager(context) { _, _ -> object : TextTranslator {
            override suspend fun prepare(wifiOnly: Boolean) = Unit
            override suspend fun translate(text: String): String {
                calls++
                if (calls == 4) { finish.await(); return "\\V[9]" }
                kotlinx.coroutines.delay(20)
                return "Texte traduit."
            }
            override fun close() = Unit
        } }
        val controller = GameTranslationController(app, scope, manager)
        AppLocalizer.language = AppLanguage.FRENCH
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme {
            GameTranslationScreen(game, controller) {}
        } } }
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.analysis != null }
        compose.onNodeWithText("Traduire avec Google").performClick()
        compose.onAllNodesWithText("Traduire avec Google").onLast().performClick()
        compose.waitUntil(10_000) { controller.state.value.progress?.remainingSeconds != null }
        compose.onNodeWithText("Temps restant estimé").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("< 1 min").assertIsDisplayed()
        finish.complete(Unit)
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.analysis?.installed == true }
        compose.onNodeWithText("Traduction partielle appliquée").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Passages conservés dans la langue d’origine").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restaurer les originaux").performClick()
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.message == "Originaux restaurés" }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun userCanReviewManualExchangeControlsAndReturnToLocalMode() {
        val data = File(directory, "manual/data").apply { mkdirs() }
        val original = """{"displayName":"Village","events":[null,{"pages":[{"list":[{"code":401,"parameters":["Hello \\N[1]!"]},{"code":111,"parameters":[4,1,1,"Hero"]}]}]}]}""".toByteArray()
        val map = File(data, "Map001.json").apply { writeBytes(original) }
        val actorsOriginal = """[null,{"name":"Hero"}]""".toByteArray()
        val actors = File(data, "Actors.json").apply { writeBytes(actorsOriginal) }
        val game = GameEntity(
            id = "translation-manual-ui", title = "The Clockwork Garden", documentUri = Uri.fromFile(data.parentFile).toString(),
            physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MZ", launcher = "JOIPLAY",
            sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "manual-test"
        )
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        }
        val controller = GameTranslationController(app, scope, GameTranslationManager(context))
        AppLocalizer.language = AppLanguage.FRENCH
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { GameTranslationScreen(game, controller) {} }
            }
        }
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.analysis != null }
        assertNull(controller.state.value.error)
        assertEquals(2, controller.state.value.analysis!!.files)
        // The content column scrolls independently of the fixed action row, especially in landscape.
        compose.onNodeWithText("Sur cet appareil").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Traduire avec Google").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Fichier pour IA").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithText("Exporter les textes").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Importer la traduction").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Traduire avec Google").assertDoesNotExist()
        val markerHelp = "Modifiez uniquement les champs translation. Conservez les identifiants, les textes sources et les marqueurs ASTRA. Les instructions sont incluses dans le fichier."
        compose.onNodeWithText(markerHelp).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Les noms utilisés par les conditions du jeu sont conservés pour rester compatibles avec vos sauvegardes.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The Clockwork Garden").performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(app.cacheDir, "ui-review").apply { mkdirs() }.resolve("translation-manual.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        // No picker is opened here: those platform flows are outside this screen-state test.
        compose.onNodeWithText("Sur cet appareil").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithText("Traduire avec Google").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Exporter les textes").assertDoesNotExist()
        compose.onNodeWithText("Importer la traduction").assertDoesNotExist()
        compose.onNodeWithText(markerHelp).assertDoesNotExist()
        assertArrayEquals(original, map.readBytes())
        assertArrayEquals(actorsOriginal, actors.readBytes())
        assertFalse(controller.state.value.analysis!!.installed)
    }
}
