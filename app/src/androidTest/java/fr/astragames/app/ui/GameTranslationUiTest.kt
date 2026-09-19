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
import fr.astragames.app.core.model.ThemeMode
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.translation.GameTranslationManager
import fr.astragames.app.translation.TextTranslator
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                AstraTheme(ThemeMode.LIGHT, dynamicColor = false) { GameTranslationScreen(game, controller) {} }
            }
        }
        compose.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.analysis != null }
        compose.onNodeWithText("The Clockwork Garden").assertIsDisplayed()
        compose.onNodeWithText("Traduire avec Google").assertIsEnabled()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(app.cacheDir, "ui-review").apply { mkdirs() }.resolve("translation-light.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("Traduire avec Google").performClick()
        compose.onNodeWithText("Traduire le jeu ?").assertIsDisplayed()
        compose.onNodeWithText("Supprimer").assertDoesNotExist()
        compose.onAllNodesWithText("Traduire avec Google").onLast().performClick()
        compose.waitUntil(10_000) { controller.state.value.analysis?.installed == true && !controller.state.value.busy }
        compose.onNodeWithText("Restaurer les originaux").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { controller.state.value.message == "Originaux restaurés" && !controller.state.value.busy }
        assertArrayEquals(original, file.readBytes())
        compose.onNodeWithText("Traduire avec Google").assertIsEnabled()
    }
}
