package fr.astragames.app.ui

import android.database.sqlite.SQLiteFullException
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val originalLanguage = AppLocalizer.language
    private val tagA = TagEntity("a", "Aventure", "aventure")
    private val tagB = TagEntity("b", "Roman", "roman")
    private val tagC = TagEntity("c", "Voyage", "voyage")

    @Before fun useFrench() { AppLocalizer.language = AppLanguage.FRENCH }
    @After fun restoreLanguage() { AppLocalizer.language = originalLanguage }

    @Composable private fun French(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme { content() } }
    }

    private fun row(name: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("game-tag-choices").performScrollToNode(hasText(name))
        return compose.onNode(hasText(name) and isToggleable())
    }

    @Test fun bulkPickerKeepsTheSelectionAfterFailureAndSubmitsOnlyOnce() {
        val firstWrite = CompletableDeferred<Unit>()
        var attempts = 0
        var applied: Set<String>? = null
        compose.setContent { French {
            GameTagPickerSheet(listOf(tagA, tagB), emptyList(), emptySet(), { selected, _ ->
                attempts++
                if (attempts == 1) {
                    firstWrite.await()
                    throw SQLiteFullException("fixture full")
                }
                applied = selected
            }, {}, allowEmptySelection = false)
        } }
        val save = compose.onNodeWithText("Valider")
        save.assertIsNotEnabled()
        row("Roman").performClick().assertIsOn()
        save.performClick()
        save.assertIsNotEnabled().performTouchInput { click() }
        row("Roman").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts); firstWrite.complete(Unit) }
        compose.onNodeWithText("Enregistrement impossible.").assertIsDisplayed()
        row("Roman").assertIsOn()
        save.performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(setOf("b"), applied) }
    }

    @Test fun filterRestorationKeepsTheQueryAndDraftAlongsideIncomingTags() {
        val restore = StateRestorationTester(compose)
        val newTag = TagEntity("d", "Nouveau", "nouveau")
        var initial by mutableStateOf(setOf("a"))
        var applied: Pair<Set<String>, Set<String>>? = null
        restore.setContent { French {
            GameTagPickerSheet(listOf(tagA, tagB, tagC, newTag), emptyList(), initial,
                { included, excluded -> applied = included to excluded }, {}, initialExcluded = setOf("b"))
        } }
        row("Aventure").performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
        row("Roman").performClick().assertIsOff()
        row("Voyage").performClick().assertIsOn()
        compose.runOnIdle { initial = initial + "d" }
        compose.onNodeWithTag("game-tag-choices").performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).performTextReplacement("Voy")
        Espresso.closeSoftKeyboard()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("game-tag-choices").performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).assertTextContains("Voy")
        row("Voyage").assertIsOn()
        compose.onNodeWithText("Valider").performClick()
        compose.runOnIdle { assertEquals(setOf("c", "d") to setOf("a"), applied) }
    }

    @Test fun removedTagDisablesBulkConfirmationUntilAnotherTagIsChosen() {
        var tags by mutableStateOf(listOf(tagA, tagB))
        var applied: Set<String>? = null
        compose.setContent { French {
            GameTagPickerSheet(tags, emptyList(), emptySet(), { selected, _ -> applied = selected }, {}, allowEmptySelection = false)
        } }
        row("Aventure").performClick()
        compose.runOnIdle { tags = listOf(tagB) }
        compose.onNodeWithText("Valider").assertIsNotEnabled()
        row("Roman").performClick()
        compose.onNodeWithText("Valider").performClick()
        compose.runOnIdle { assertEquals(setOf("b"), applied) }
    }

    @Test fun filtersOpenedFromTheLibraryRestoreTheUnappliedTagChoices() {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent { French {
                val state by vm.uiState.collectAsState()
                FilterStrip(state.copy(tags = listOf(tagA, tagB)), vm)
            } }
            compose.onNodeWithText("Filtres").performClick()
            compose.onNodeWithText("0 tag(s) sélectionné(s)").performClick()
            row("Aventure").performClick()
            row("Roman").performClick().performClick()
            restore.emulateSavedInstanceStateRestore()
            row("Aventure").assertIsOn()
            compose.onNodeWithText("Valider").performClick()
            compose.waitUntil(5_000) { vm.uiState.value.filters.let { it.tagIds == setOf("a") && it.excludedTagIds == setOf("b") } }
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun setupRestoresTheTagsChosenBeforeSavingTheGame() {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val dao = app.container.dao
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
        val id = UUID.randomUUID().toString()
        val game = GameEntity(id, "Chroniques", documentUri = "content://bulk/$id", physicalPath = null, executableName = "index.html",
            engine = "HTML5", launcher = "EXTERNAL", sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)
        val tags = listOf(tagA, tagB).map { it.copy(id = "$id-${it.id}", normalizedName = "$id-${it.id}") }
        val restore = StateRestorationTester(compose)
        fun setupTagCount(count: Int) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Tags ($count)"))
        }
        try {
            runBlocking {
                dao.upsertGame(game)
                dao.insertTags(tags)
                dao.addGameTag(fr.astragames.app.data.local.GameTagCrossRef(id, tags.first().id))
            }
            restore.setContent { French { NewGameSetupScreen(game, AstraUiState(tags = tags, setupQueue = listOf(game)), vm, {}) } }
            setupTagCount(1)
            compose.onNodeWithText("Tags (1)").performClick()
            row("Roman").performClick()
            compose.onNodeWithText("Valider").performClick()
            setupTagCount(2)
            restore.emulateSavedInstanceStateRestore()
            setupTagCount(2)
            compose.onNodeWithText("Terminer").performClick()
            compose.waitUntil(5_000) { runBlocking { dao.getTagIdsForGame(id).size == 2 } }
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { dao.deleteGameCompletely(id); dao.deleteTags(tags.map { it.id }) }
        }
    }

    @Test fun libraryRetainsTheOriginalTargetsAndLetsTheUserRetryOnTheRemainingGame() {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val dao = app.container.dao
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
        val prefix = UUID.randomUUID().toString()
        fun game(suffix: String) = GameEntity("$prefix-$suffix", "Livre $suffix", documentUri = "content://bulk/$prefix/$suffix",
            physicalPath = null, executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = prefix,
            dateAdded = 1, lastModified = 1, fingerprint = "$prefix-$suffix")
        val first = game("A")
        val second = game("B")
        val ids = setOf(first.id, second.id)
        val tag = TagEntity("$prefix-tag", "Lecture", "lecture-$prefix")
        val restore = StateRestorationTester(compose)
        fun chooseAction(label: String) {
            compose.onNodeWithContentDescription("Actions de la sélection").performClick()
            compose.onNodeWithText(label).performClick()
        }
        try {
            runBlocking { dao.upsertGame(first); dao.upsertGame(second); dao.upsertTag(tag) }
            restore.setContent { French {
                val state by vm.uiState.collectAsState()
                val fixture = state.games.filter { it.id in ids }
                LibraryScreen(state.copy(games = fixture, filteredGames = fixture, tags = state.tags.filter { it.id == tag.id }),
                    vm, {}, {})
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(first.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(first.title).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.onNodeWithText(second.title).performClick()
            compose.onNodeWithText("2 sélectionné(s)").assertIsDisplayed()
            chooseAction("Ajouter aux favoris")
            compose.waitUntil(10_000) { vm.uiState.value.games.count { it.id in ids && it.favorite } == 2 }
            chooseAction("Ajouter des tags")
            row(tag.name).performClick().assertIsOn()
            restore.emulateSavedInstanceStateRestore()
            row(tag.name).assertIsOn()
            runBlocking { dao.deleteGameCompletely(second.id) }
            compose.waitUntil(10_000) { vm.uiState.value.games.none { it.id == second.id } }
            compose.onNodeWithText("Valider").performClick()
            val error = "Un jeu sélectionné a été retiré de la bibliothèque."
            compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(error).assertIsDisplayed()
            assertTrue(runBlocking { dao.getTagIdsForGame(first.id) }.isEmpty())
            Espresso.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("1 sélectionné(s)").fetchSemanticsNodes().isNotEmpty() }
            chooseAction("Ajouter des tags")
            row(tag.name).performClick()
            compose.onNodeWithText("Valider").performClick()
            compose.waitUntil(10_000) { vm.uiState.value.gameTagRefs.any { it.gameId == first.id && it.tagId == tag.id } }
            compose.onNodeWithText("Valider").assertDoesNotExist()
            assertTrue(runBlocking { dao.getTagIdsForGame(second.id) }.isEmpty())
            runBlocking {
                val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { vm.events.filterIsInstance<UiEvent.Message>().first() } }
                vm.setGamesFavorite(ids, false).join()
                assertEquals(error, message.await().text)
                assertTrue(dao.getGame(first.id)!!.favorite)
            }
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { ids.forEach { dao.deleteGameCompletely(it) }; dao.deleteTags(listOf(tag.id)) }
        }
    }
}
