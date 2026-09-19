package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.LibraryViewMode

import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()

    private var previousLanguage = AppLanguage.ENGLISH

    @Before fun useEnglishLabels() {
        previousLanguage = AppLocalizer.language
        AppLocalizer.language = AppLanguage.ENGLISH
    }

    @After fun restoreLanguage() {
        AppLocalizer.language = previousLanguage
    }

    @Test fun settingsRowExposesOneSwitchAndTogglesExactlyOncePerTap() {
        val changes = mutableListOf<Boolean>()
        content {
            var enabled by remember { mutableStateOf(false) }
            SettingsSwitch("Scanner au lancement", enabled) {
                changes += it
                enabled = it
            }
        }

        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        val setting = compose.onNodeWithText("Scan on launch")
        setting.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff().performClick().assertIsOn()
        setting.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test fun navigationAnnouncesSelectedTabAndChangesSelection() {
        content {
            var destination by remember { mutableStateOf(Destination.LIBRARY) }
            Row {
                NavIcon(Destination.LIBRARY, destination == Destination.LIBRARY, { destination = Destination.LIBRARY })
                NavIcon(Destination.SETTINGS, destination == Destination.SETTINGS, { destination = Destination.SETTINGS })
            }
        }

        compose.onNodeWithContentDescription("Games")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
        compose.onNodeWithContentDescription("Settings").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Games").assertIsNotSelected()
    }

    @Test fun userTitleStaysUntouchedAndFavoriteDoesNotOpenTheGame() {
        val game = game("user-title", "Paramètres")
        val opened = mutableListOf<String>()
        val favorited = mutableListOf<String>()
        content {
            GameCollection(listOf(game), LibraryViewMode.LIST, 2, opened::add, favorited::add)
        }

        compose.onNodeWithText("Paramètres").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add to favorites").performClick()
        compose.runOnIdle {
            assertEquals(listOf(game.id), favorited)
            assertTrue(opened.isEmpty())
        }
        compose.onNodeWithText("Paramètres").performClick()
        compose.runOnIdle { assertEquals(listOf(game.id), opened) }
    }

    @Test fun gridShowsLocalizedMissingStatusWithoutChangingUserTitle() {
        content {
            GameCollection(listOf(game("missing", "Annuler").copy(missing = true)), LibraryViewMode.GRID, 2, {}, {})
        }

        compose.onNodeWithText("Annuler").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Missing").assertIsDisplayed()
    }

    @Test fun emptyResultsProvideAnActionThatResetsFilters() {
        var resets = 0
        content {
            GameCollection(emptyList(), LibraryViewMode.GRID, 2, {}, {}, onResetFilters = { resets++ })
        }

        compose.onNodeWithText("No games match the filters").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun ordinaryTagPickerRemainsBinaryAfterThreeTaps() {
        var saved: Set<String>? = null
        content {
            GameTagPickerSheet(
                tags = listOf(TagEntity("exploration", "Exploration", "exploration")),
                categories = emptyList(),
                initial = emptySet(),
                onSave = { saved = it },
                onDismiss = {}
            )
        }

        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Exploration").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Exploration").performClick()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(setOf("exploration"), saved) }
    }

    @Test fun populatedGridLibraryCanBeRenderedAndCaptured() = captureLibrary(LibraryViewMode.GRID, "library-grid.png")

    @Test fun populatedListLibraryCanBeRenderedAndCaptured() = captureLibrary(LibraryViewMode.LIST, "library-list.png")

    @Test fun pullingDownAtTopOpensSearch() = verifySearchPull(initialItem = 0, opens = true)

    @Test fun pullingDownInsideListKeepsScrolling() = verifySearchPull(initialItem = 25, opens = false)

    @Test fun pullingDownOnEmptyPageOpensSearch() = verifySearchPull(initialItem = null, opens = true)

    private fun verifySearchPull(initialItem: Int?, opens: Boolean) {
        content {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "library") {
                composable("library") {
                    Box(Modifier.fillMaxSize().testTag("pull-area").pullDownSearch("library", nav)) {
                        if (initialItem != null) LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(initialItem)) {
                            items(80) { Text("Item $it", Modifier.height(64.dp)) }
                        }
                    }
                }
                composable("search") { Text("Search opened") }
            }
        }
        compose.onNodeWithTag("pull-area").performTouchInput { swipeDown() }
        compose.waitForIdle()
        if (opens) compose.onNodeWithText("Search opened").assertIsDisplayed()
        else compose.onNodeWithText("Search opened").assertDoesNotExist()
    }

    private fun captureLibrary(viewMode: LibraryViewMode, filename: String) {
        val games = listOf(
            game("north", "Northern Lights").copy(favorite = true),
            game("garden", "The Clockwork Garden").copy(engine = "RENPY"),
            game("echoes", "Echoes of Tomorrow").copy(missing = true),
            game("stars", "A Sky Full of Stories").copy(engine = "RPG_MAKER_MZ"),
            game("orbit", "Orbit 42").copy(engine = "HTML5"),
            game("island", "The Last Island").copy(favorite = true)
        )
        content {
            Column {
                CompactHeader("Astra", "6 jeux")
                GameCollection(games, viewMode, 2, {}, {}, onQuickGame = {})
            }
        }
        compose.onNodeWithText("Northern Lights").assertIsDisplayed()
        compose.onNodeWithText("The Clockwork Garden").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ui-review")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, filename).outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                AstraTheme {
                    Surface(Modifier.fillMaxSize(), content = body)
                }
            }
        }
    }

    private fun game(id: String, title: String) = GameEntity(
        id = id,
        title = title,
        documentUri = "content://ui-fixture/$id",
        physicalPath = null,
        executableName = "index.html",
        engine = "RPG_MAKER_MV",
        launcher = "JOIPLAY",
        sourceId = "ui-fixture",
        dateAdded = 1L,
        lastModified = 1L,
        fingerprint = "ui-fixture-$id"
    )
}
