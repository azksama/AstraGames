package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LibraryFolderEntity
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

/** Isolated UI fixtures: no catalogue, source, game or preference writes. */
@RunWith(AndroidJUnit4::class)
class GlobalReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private var previousLanguage = AppLanguage.ENGLISH

    @Before fun useFrench() {
        previousLanguage = AppLocalizer.language
        AppLocalizer.language = AppLanguage.FRENCH
    }

    @After fun restoreLanguage() { AppLocalizer.language = previousLanguage }

    @Test fun longQuickActionsCanScrollToAndExecuteTheLastAction() {
        var selections = 0
        content(fontScale = 2f) {
            QuickGameActionsSheet(game("Une aventure au-delà des étoiles : le retour des voyageurs perdus dans les royaumes oubliés"),
                {}, {}, {}, { selections++ }, {})
        }
        try {
            // LazyColumn scrolling cannot expand the Material sheet's parent. Exercise its
            // accessible drag-handle action first, as a user would on a short window.
            val expand = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand), useUnmergedTree = true)
            if (expand.fetchSemanticsNodes().isNotEmpty()) {
                expand.onFirst().performSemanticsAction(SemanticsActions.Expand) { assertTrue(it()) }
                compose.waitForIdle()
            }
            compose.onAllNodes(hasScrollToNodeAction()).onLast()
                .performScrollToNode(hasText("Démarrer une sélection multiple"))
            compose.onNodeWithText("Démarrer une sélection multiple").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, selections) }
            // Expansion can display Material's transient drag-handle tooltip.
            // Let its Compose timer expire before the evidence screenshot.
            if (compose.onAllNodesWithText("Drag handle", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.mainClock.advanceTimeBy(2_000)
            }
            capture("quick-actions-large-text")
        } catch (failure: Throwable) {
            runCatching {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val directory = File(context.cacheDir, "global-review-ui").apply { mkdirs() }
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(directory, "quick-actions-failure.txt").writeText(buildString {
                    appendLine(context.resources.configuration.toString())
                    roots.fetchSemanticsNodes().indices.forEach { appendLine(roots[it].printToString()) }
                })
                File(directory, "quick-actions-failure.png").outputStream().use {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw failure
        }
    }

    @Test fun missingGameCannotBeLaunchedFromQuickActions() {
        var launches = 0
        content {
            QuickGameActionsSheet(game("Jeu introuvable").copy(missing = true),
                { launches++ }, {}, {}, {}, {})
        }
        compose.onNodeWithText("Jouer").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, launches) }
    }

    @Test fun folderChoiceAnnouncesSelectionAndChangesOnlyTheRequestedFolder() {
        var picked: String? = null
        content {
            var selected by remember { mutableStateOf<String?>(null) }
            GameFolderDialog(listOf(LibraryFolderEntity("fixture-folder", "Aventures")), selected,
                { selected = it; picked = it }, {})
        }
        compose.onNodeWithText("Sans dossier")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        compose.onNodeWithText("Aventures").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("Sans dossier").assertIsNotSelected()
        compose.runOnIdle { assertEquals("fixture-folder", picked) }
    }

    private fun content(fontScale: Float = 1f, body: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AstraTheme { Surface(Modifier.fillMaxSize(), content = body) }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // UiAutomation includes WindowManager dialog transitions, outside Compose's test clock.
        android.os.SystemClock.sleep(400)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "global-review-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun game(title: String) = GameEntity(
        id = "global-ui-fixture", title = title, documentUri = "content://global-ui-fixture/game",
        physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_VX_ACE",
        launcher = "ASTRA_MKXPZ", sourceId = "global-ui-fixture", dateAdded = 1,
        lastModified = 1, fingerprint = "global-ui-fixture")
}
