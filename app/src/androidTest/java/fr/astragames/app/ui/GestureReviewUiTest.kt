package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.metadata.CoverCandidate
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After

class GestureReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private var coverFile: File? = null
    @After fun cleanup() { coverFile?.delete() }

    @Test fun searchWaitsForReleaseAndReversingThePullCancelsIt() {
        navigation()
        val area = compose.onNodeWithTag("gesture-page")
        area.performTouchInput { down(Offset(centerX, height * .15f)); moveTo(Offset(centerX, height * .8f), 500) }
        compose.onNodeWithText("Search opened").assertDoesNotExist()
        screenshot("search-pull-held")
        area.performTouchInput { moveTo(Offset(centerX, height * .15f), 500); up() }
        compose.onNodeWithText("Search opened").assertDoesNotExist()
        area.performTouchInput { swipeDown() }
        compose.onNodeWithText("Search opened").assertIsDisplayed()
    }

    @Test fun cancelledPullDoesNotOpenSearchAndTheNextGestureStillWorks() {
        navigation()
        val area = compose.onNodeWithTag("gesture-page")
        area.performTouchInput { down(Offset(centerX, height * .15f)); moveTo(Offset(centerX, height * .8f), 500); cancel() }
        compose.onNodeWithText("Search opened").assertDoesNotExist()
        area.performTouchInput { swipeDown() }
        compose.onNodeWithText("Search opened").assertIsDisplayed()
    }

    @Test fun horizontalSliderDoesNotTriggerPageNavigation() {
        var value = .5f
        navigation {
            var slider by remember { mutableFloatStateOf(value) }
            Slider(slider, { slider = it; value = it }, Modifier.fillMaxWidth().testTag("gesture-slider"))
        }
        compose.onNodeWithTag("gesture-slider").performTouchInput { swipe(Offset(width * .3f, centerY), Offset(width * .85f, centerY), 600) }
        compose.onNodeWithText("Another page").assertDoesNotExist()
        compose.runOnIdle { assertTrue(value > .7f) }
    }

    @Test fun horizontalShortcutWorksAndAReversedSwipeStaysOnThePage() {
        navigation()
        val area = compose.onNodeWithTag("gesture-page")
        area.performTouchInput {
            down(Offset(width * .8f, centerY)); moveTo(Offset(width * .2f, centerY), 500)
            moveTo(Offset(width * .8f, centerY), 500); up()
        }
        compose.onNodeWithText("Another page").assertDoesNotExist()
        area.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Another page").assertIsDisplayed()
    }

    @Test fun coverPickerCanScrollPastLongTitlesToTheLastImageWithLargeText() {
        var chosen: CoverCandidate? = null
        var local = 0
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "gesture-cover-${java.util.UUID.randomUUID()}.png").also { coverFile = it }
        instrumentation.context.assets.open("design/mossbound.png").use { input -> file.outputStream().use(input::copyTo) }
        val image = android.net.Uri.fromFile(file).toString()
        val candidates = List(18) { i -> CoverCandidate(imageUrl = "$image#$i",
            thumbnailUrl = image, source = "Fixture", matchedTitle = "Cover $i", confidence = 1f) }
        content(2f) {
            CoverPickerSheet(game(), CoverSearchState(results = candidates, browserUrl = "https://example.com/"), false,
                { chosen = it }, { local++ }, {}, {})
        }
        val grid = compose.onNodeWithTag("cover-picker-grid")
        grid.performScrollToNode(hasText("Image locale"))
        compose.onNodeWithText("Image locale").assertIsDisplayed().performClick()
        grid.performScrollToNode(hasText("Ouvrir dans le navigateur"))
        compose.onNodeWithText("Ouvrir dans le navigateur").assertIsDisplayed()
        grid.performScrollToNode(hasText("Cover 17"))
        compose.onNodeWithContentDescription("Cover 17").performClick()
        compose.runOnIdle { assertEquals(1, local); assertEquals(candidates.last(), chosen) }
        screenshot("cover-picker-large-text")
    }

    @Test fun coverDownloadDisablesCompetingLocalAndRemoveActions() {
        content {
            CoverPickerSheet(game().copy(coverUri = "file:///cover.png"), CoverSearchState(downloading = true), false, {}, {}, {}, {})
        }
        compose.onNodeWithTag("cover-picker-grid").performScrollToNode(hasText("Image locale"))
        compose.onNodeWithText("Image locale").assertIsNotEnabled()
        compose.onNodeWithText("Supprimer").assertIsNotEnabled()
    }

    private fun navigation(extra: @Composable () -> Unit = {}) = content {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "home") {
            composable("home") {
                Box(Modifier.fillMaxSize().testTag("gesture-page").pullDownSearch("home", nav).topLevelSwipe("home", nav)) {
                    Column(Modifier.fillMaxWidth().padding(top = 100.dp)) { extra() }
                }
            }
            composable("search") { Text("Search opened") }
            composable("library") { Text("Another page") }
            composable("settings") { Text("Another page") }
        }
    }

    private fun content(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH,
            LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            AstraTheme { Surface(Modifier.fillMaxSize(), content = body) }
        }
    }

    private fun game() = GameEntity(id = "gesture-review", title = "Une très longue aventure parmi les constellations oubliées et les royaumes lointains",
        documentUri = "file:///gesture-review", physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV",
        launcher = "JOIPLAY", sourceId = "fixture", dateAdded = 0, lastModified = 0, fingerprint = "gesture-review")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Drag handle", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.mainClock.advanceTimeBy(2_000)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(400)
        val variant = InstrumentationRegistry.getArguments().getString("gestureVariant") ?: "portrait"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "gesture-evidence/$variant-$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
