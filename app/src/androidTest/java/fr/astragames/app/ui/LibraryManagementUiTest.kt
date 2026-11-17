package fr.astragames.app.ui

import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.metadata.MetadataSource
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.SearchEngine
import fr.astragames.app.ui.theme.AstraTheme
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class LibraryManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val game = GameEntity("management-ui", "Astra Quest", documentUri = "content://test/game", physicalPath = "/Games/Astra Quest",
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "test")

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { block() } } }
    }

    @Test fun physicalDeletionIsExplicitAndFailureKeepsTheDialogOpen() {
        var dismissed = false
        content { RemoveGamesDialog(listOf(game), true, { files -> assertTrue(files); error("Android refuse la suppression") }, { dismissed = true }) }
        compose.onNodeWithText("/Games/Astra Quest").performScrollTo().assertIsDisplayed()
        screenshot("delete-confirmation")
        compose.onNodeWithText("Supprimer définitivement").performClick()
        compose.onNodeWithText("Android refuse la suppression").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun ignoreIsSeparateAndNeverRequestsPhysicalDeletion() {
        var requested: Boolean? = null
        content { RemoveGamesDialog(listOf(game), true, { requested = it }, {}) }
        compose.onNodeWithText("Ignorer le dossier").performScrollTo().performClick()
        compose.onNodeWithText("Ignorer").performClick()
        compose.runOnIdle { assertEquals(false, requested) }
    }

    @Test fun metadataSearchAlwaysOpensInsideAstraEvenWithExternalBrowserPreference() {
        var fetched: String? = null
        content { F95ImportSheet(game, emptyList(), F95ImportState(gameId = game.id), true, null,
            { fetched = it }, {}, { fail("Automatic search must not be used") }, {}, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("Rechercher dans Astra").performScrollTo().performClick()
        compose.onNodeWithText("Navigateur Astra").assertIsDisplayed()
        compose.onNodeWithTag("metadata-browser-address").assertIsDisplayed()
        compose.onNodeWithText("Utiliser le lien").assertIsNotEnabled()
        compose.runOnIdle { assertNull(fetched) }
        screenshot("browser-search")
    }

    @Test fun f95PageCanBeReadAndIsSelectedOnlyOnUseLink() = browserSelection(MetadataSource.F95ZONE, "https://f95zone.to/threads/123/")

    @Test fun ryuugamesPageCanBeReadAndIsSelectedOnlyOnUseLink() = browserSelection(MetadataSource.RYUUGAMES, "https://www.ryuugames.com/astra-fixture/")

    private fun browserSelection(source: MetadataSource, url: String) {
        var selected: String? = null
        content { SearchF95PickerDialog("https://example.com/", SearchEngine.entries.first(), { selected = it }, {}, source) }
        compose.onNodeWithTag("metadata-browser-address").performTextReplacement(url)
        compose.onNodeWithTag("metadata-browser-address").performImeAction()
        onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription() = "Load a deterministic HTTPS game page in the real Android WebView"
            override fun perform(uiController: UiController, view: View) {
                (view as WebView).stopLoading()
                view.loadDataWithBaseURL(url, "<html><body><h1>Astra fixture</h1><p>Game page for selection.</p></body></html>", "text/html", "UTF-8", url)
            }
        })
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Utiliser le lien").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.runOnIdle { assertNull(selected) }
        screenshot("browser-${source.name.lowercase()}")
        compose.onNodeWithText("Utiliser le lien").performClick()
        compose.runOnIdle { assertEquals(source.pageUrl(url), selected) }
    }

    @Test fun browserRejectsLocalFileAddresses() {
        content { SearchF95PickerDialog("https://example.com/", SearchEngine.entries.first(), { fail("No valid page") }, {}) }
        compose.onNodeWithTag("metadata-browser-address").performTextReplacement("file:///sdcard/private.txt")
        compose.onNodeWithTag("metadata-browser-address").performImeAction()
        compose.onNodeWithText("Utilisez une adresse HTTPS ou le nom du jeu.").assertIsDisplayed()
        compose.onNodeWithText("Utiliser le lien").assertIsNotEnabled()
    }

    @Test fun browserCanChangeSearchEngineWithoutLeavingAstra() {
        content { SearchF95PickerDialog(SearchEngine.YANDEX.webSearchUrl("Astra fixture f95zone.to"), SearchEngine.YANDEX, {}, {}) }
        compose.onNodeWithText("Yandex").performClick()
        compose.onNodeWithText("Google").performClick()
        compose.onNodeWithText("Google").assertIsDisplayed()
        compose.onNodeWithText("Navigateur Astra").assertIsDisplayed()
        compose.onNodeWithTag("metadata-browser-address").assert(hasText("google.com", substring = true))
    }

    @Test fun recentlyAddedFiltersShowTheRightGamesAndCustomPeriodCanBeCancelled() {
        val now = System.currentTimeMillis()
        val games = listOf(game.copy(id = "today", title = "Today fixture", dateAdded = now - 60_000),
            game.copy(id = "ten", title = "Ten days fixture", dateAdded = now - 10 * 86_400_000L),
            game.copy(id = "old", title = "Old fixture", dateAdded = now - 160 * 86_400_000L))
        content { RecentlyAddedCollection(games, LibraryViewMode.LIST, 2, {}, {}) }
        compose.onNodeWithText("Today fixture").assertIsDisplayed()
        compose.onNodeWithText("Ten days fixture").assertDoesNotExist()
        compose.onNodeWithText("14 jours").performClick()
        compose.onNodeWithText("Ten days fixture").assertIsDisplayed()
        compose.onNodeWithTag("added-date-filters").performScrollToNode(hasText("Plus de 3 mois"))
        compose.onNodeWithText("Plus de 3 mois").performClick()
        compose.onNodeWithText("Old fixture").assertIsDisplayed()
        compose.onNodeWithText("Today fixture").assertDoesNotExist()
        compose.onNodeWithTag("added-date-filters").performScrollToNode(hasText("Période personnalisée"))
        compose.onNodeWithText("Période personnalisée").performClick()
        compose.onNodeWithText("Dates d’ajout").assertIsDisplayed()
        compose.onNodeWithText("Appliquer").assertIsNotEnabled()
        screenshot("custom-dates")
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Old fixture").assertIsDisplayed()
        screenshot("recent-dates")
    }

    @Test fun dateBoundariesRespectCalendarMonthsAndLocalInclusiveDays() {
        val paris = ZoneId.of("Europe/Paris")
        val now = Instant.parse("2026-03-31T10:00:00Z").toEpochMilli()
        val month = addedDateRange(AddedPeriod.MONTH, now, paris)
        assertEquals(Instant.parse("2026-02-28T11:00:00Z").toEpochMilli(), month.fromInclusive)
        assertTrue(month.contains(month.fromInclusive))
        assertFalse(month.contains(month.fromInclusive - 1))
        assertTrue(month.contains(now))
        assertFalse(month.contains(now + 1))
        val daylightSavingDay = addedDateRange(AddedPeriod.CUSTOM, now, paris, LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29))
        assertEquals(23 * 3_600_000L, daylightSavingDay.untilExclusive - daylightSavingDay.fromInclusive)
        assertTrue(daylightSavingDay.contains(daylightSavingDay.untilExclusive - 1))
        assertFalse(daylightSavingDay.contains(daylightSavingDay.untilExclusive))
        val old = addedDateRange(AddedPeriod.OLDER, now, paris)
        val recent = addedDateRange(AddedPeriod.THREE_MONTHS, now, paris)
        assertEquals(old.untilExclusive, recent.fromInclusive)
    }

    @Test fun customCalendarAppliesTheChosenStartAndEndDays() {
        var selected: Pair<Long, Long>? = null
        content { AddedDatePicker(null, null, { start, end -> selected = start to end }, {}) }
        screenshot("calendar-before-selection")
        val month = LocalDate.now()
        val format = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", java.util.Locale.FRENCH)
        compose.onNode(hasText(month.withDayOfMonth(2).format(format), ignoreCase = true) and hasClickAction()).performClick()
        compose.onNode(hasText(month.withDayOfMonth(7).format(format), ignoreCase = true) and hasClickAction()).performClick()
        compose.onNodeWithText("Appliquer").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(month.withDayOfMonth(2).toEpochDay() to month.withDayOfMonth(7).toEpochDay(), selected) }
    }

    @Test fun duplicateCardsExposeEachExactFolderAndRemovalAction() {
        var chosen: String? = null
        val copies = listOf(game, game.copy(id = "copy", version = "2.0", physicalPath = "/Games/Quest-copy"))
        content { DuplicateComparisonContent(fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup("fixture", copies),
            AstraUiState(games = copies), game.id, null, true, {}, fr.astragames.app.data.repository.SaveConflictStrategy.KEEP_BOTH,
            {}, false, {}, {}, {}, {}, onRemove = { chosen = it.id }) }
        compose.onNodeWithTag("duplicate-comparison").performScrollToNode(hasText("Supprimer ou ignorer"))
        val remove = compose.onAllNodesWithText("Supprimer ou ignorer")[0]
        remove.performScrollTo()
        // The cards sit in a horizontal row inside the vertical comparison list.
        // On short screens, scroll the outer list as a user would to reach the card footer.
        repeat(24) {
            if (!remove.isDisplayed()) compose.onNodeWithTag("duplicate-comparison")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100f) }
        }
        screenshot("duplicate-scroll")
        remove.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(game.id, chosen) }
        screenshot("duplicate-comparison")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(350)
        val variant = InstrumentationRegistry.getArguments().getString("variant") ?: "portrait"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "library-beta26/$variant-$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
