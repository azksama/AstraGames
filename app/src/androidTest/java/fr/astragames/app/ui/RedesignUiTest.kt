package fr.astragames.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.AstraSettings
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RedesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val previousLanguage = AppLocalizer.language
    private lateinit var vm: AstraViewModel
    private lateinit var state: AstraUiState
    private val files = mutableListOf<File>()
    private val ids = listOf("design-review-eclipse", "design-review-moss", "design-review-neon")

    @Before fun fixtures() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val games = listOf("Eclipse: The Last Signal", "Mossbound", "Neon Drift").mapIndexed { i, title ->
            val cover = File(app.cacheDir, "${ids[i]}.png").also { files += it }
            assets.open("design/${listOf("eclipse", "mossbound", "neon")[i]}.png").use { input -> cover.outputStream().use(input::copyTo) }
            GameEntity(id = ids[i], title = title, documentUri = "file:///design-review/${ids[i]}", physicalPath = null,
                executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "design-review",
                dateAdded = 1, lastModified = 1, fingerprint = ids[i], lastPlayedAt = 1726100000000 - i * 100000L,
                playCount = 12 - i, coverUri = Uri.fromFile(cover).toString(), developer = "Studio Aurora", version = "1.2",
                description = "Explore les vestiges d’un monde oublié. Chaque signal révèle un passage, une histoire et une nouvelle aventure.")
        }
        runBlocking { games.forEach { app.container.dao.upsertGame(it) } }
        compose.runOnUiThread { vm = AstraViewModel(app); store.put("review", vm) }
        state = AstraUiState(games = games, filteredGames = games, settingsLoaded = true,
            settings = AstraSettings(onboardingCompleted = true, language = AppLanguage.FRENCH, scanOnLaunch = false, viewMode = LibraryViewMode.LIST),
            playStats = games.associate { it.id to GamePlayStat(it.id, 13_200_000L, it.lastPlayedAt) })
        AppLocalizer.language = AppLanguage.FRENCH
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        runBlocking { ids.forEach { app.container.dao.deleteGameCompletely(it) } }
        files.forEach { it.delete() }
        AppLocalizer.language = previousLanguage
    }

    private fun content() = compose.setContent {
        AstraTheme { AstraApp(state, vm, {}, {}, {}, {}, {}, {}) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File(app.cacheDir, "ui-review").apply { mkdirs() }.resolve("redesign-$name.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun navigationDetailAndActivityRemainReachable() {
        content()
        compose.onNodeWithText("Bibliothèque").assertIsDisplayed()
        capture("home")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Mossbound"))
        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp() }
        compose.onNodeWithText("Mossbound").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mossbound").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Jouer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jouer").performScrollTo().assertIsDisplayed()
        capture("detail")
        compose.onNodeWithText("Outils").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Traduire le jeu"))
        compose.onNodeWithText("Traduire le jeu").assertIsDisplayed()
        
        // Dismiss the tools sheet using the real system back action.
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Historique"))
        compose.onNodeWithText("Historique").performClick()
        compose.onNodeWithText("Aucune session enregistree").assertExists()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Mises à jour"))
        compose.onNodeWithText("Mises à jour").performClick()
        compose.onNodeWithContentDescription("Vérifier les mises à jour").assertExists()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithContentDescription("Jeux").performClick()
        compose.onNodeWithText("Tous les jeux").assertIsDisplayed()
        capture("games")
        compose.onNodeWithText("Rechercher un jeu").performClick()
        compose.onNodeWithText("Titre, moteur, développeur…").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithContentDescription("Collections").performClick()
        compose.onNodeWithText("Collections intelligentes").assertIsDisplayed()
        capture("collections")
    }

    @Test fun settingsRestoreUsesTheCorrectActionAndCanBeCancelled() {
        content()
        compose.onNodeWithContentDescription("Paramètres").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sources et scan"))
        compose.onNodeWithText("Sources et scan").assertIsDisplayed()
        capture("settings")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Restaurer une sauvegarde"))
        compose.onNodeWithText("Restaurer une sauvegarde").performClick()
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Supprimer").assertDoesNotExist()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Restaurer une sauvegarde ?").assertDoesNotExist()
    }
}
