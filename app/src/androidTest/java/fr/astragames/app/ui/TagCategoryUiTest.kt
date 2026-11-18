package fr.astragames.app.ui

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
class TagCategoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val previousLanguage = AppLocalizer.language
    private val store = ViewModelStore()

    @Before fun french() { AppLocalizer.language = AppLanguage.FRENCH }
    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        AppLocalizer.language = previousLanguage
    }

    @Composable private fun French(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme { content() } }
    }

    private fun model(): AstraViewModel {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        return ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
    }

    @Test fun categoryCountsReadTheCatalogOnlyOnceWhileScrolling() {
        val categories = (0 until 12).map { TagCategoryEntity("c$it", "Catégorie $it", "cat $it", it) }
        val source = (0 until 120).map { TagEntity("t$it", "Tag $it", "tag $it", categories[it % categories.size].name) }
        val reads = AtomicInteger()
        val tags = object : AbstractList<TagEntity>() {
            override val size get() = source.size
            override fun get(index: Int): TagEntity { reads.incrementAndGet(); return source[index] }
        }
        val vm = model()
        val state = AstraUiState(tags = tags, tagCategories = categories)
        compose.setContent { French { CategoryManager(state, vm, {}, {}) } }
        compose.onAllNodesWithContentDescription("Monter").onFirst().assertIsNotEnabled()
        categories.indices.forEach { index ->
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            compose.onNodeWithText(categories[index].name).assertIsDisplayed()
        }
        compose.onAllNodesWithText("10 tag(s)").onFirst().assertExists()
        compose.onAllNodesWithContentDescription("Descendre").onLast().assertIsNotEnabled()
        val count = reads.get()
        Log.i("AstraTagCategories", "catalog reads=$count tags=120 categories=12")
        assertEquals(source.size, count)
    }

    @Test fun categoryChoiceWaitsForOneWriteAndKeepsTheDialogAvailableForRetry() {
        val write = CompletableDeferred<Unit>()
        val choices = mutableListOf<String?>()
        val category = TagCategoryEntity("stable", "Romans", "romans")
        var displayed by mutableStateOf(category)
        var dismissed = false
        compose.setContent { French {
            CategoryChoiceDialog(listOf(displayed), { id ->
                choices += id
                if (choices.size == 1) {
                    write.await()
                    throw SQLiteFullException("fixture full")
                }
            }, { dismissed = true })
        } }
        fun choice(label: String): SemanticsNodeInteraction {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            return compose.onNodeWithText(label)
        }
        choice("Romans").performClick()
        choice("Romans").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Annuler").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("stable"), choices)
            assertFalse(dismissed)
            displayed = category.copy(name = "Fictions")
            write.complete(Unit)
        }
        compose.onNodeWithText("Enregistrement impossible.").assertIsDisplayed()
        choice("Fictions").performClick()
        compose.runOnIdle { assertEquals(listOf("stable", "stable"), choices) }
    }

    @Test fun deletingTagsThatAlreadyDisappearedReportsTheActualOutcome() = runBlocking {
        val vm = model()
        val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { vm.events.filterIsInstance<UiEvent.Message>().first() } }
        vm.deleteTags(setOf(UUID.randomUUID().toString()))
        assertEquals(UiEvent.Message("Ces tags ont déjà été supprimés."), message.await())
    }

    @Test fun tagManagerRestoresThePendingSelectionAndReportsAConcurrentDeletion() {
        val dao = ApplicationProvider.getApplicationContext<AstraApplication>().container.dao
        val vm = model()
        val suffix = UUID.randomUUID().toString().take(8)
        val cat = TagCategoryEntity(suffix, "Romans $suffix", "romans $suffix")
        val tags = listOf(TagEntity("$suffix-a", "Ambre", "$suffix-a"), TagEntity("$suffix-b", "Opale", "$suffix-b"))
        val ids = tags.map { it.id }
        val restore = StateRestorationTester(compose)
        val vertical = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        fun select(tag: TagEntity) {
            compose.onNode(vertical).performScrollToNode(hasText(tag.name))
            compose.onNode(hasText(tag.name) and isToggleable()).performClick()
        }
        try {
            runBlocking { dao.upsertTagCategory(cat); dao.insertTags(tags) }
            restore.setContent { French {
                val state by vm.uiState.collectAsState()
                TagsScreen(state.copy(tags = state.tags.filter { it.id in ids }, tagCategories = state.tagCategories.filter { it.id == cat.id }), vm, {}, {})
            } }
            compose.waitUntil(5_000) { vm.uiState.value.tags.count { it.id in ids } == 2 }
            select(tags[0])
            select(tags[1])
            compose.onNodeWithText("Classer").performClick()
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Classer les tags").assertIsDisplayed()
            val renamed = "Fictions $suffix"
            runBlocking { vm.renameTagCategory(cat, renamed); dao.deleteTags(listOf(tags[1].id)) }
            compose.waitUntil(5_000) { vm.uiState.value.let { s -> s.tags.none { it.id == tags[1].id } && s.tagCategories.any { it.name == renamed } } }
            compose.onNodeWithText(renamed).performClick()
            compose.onNodeWithText("Un tag sélectionné a été supprimé. Vérifiez votre sélection.").assertIsDisplayed()
            assertNull(runBlocking { dao.getTags().single { it.id == tags[0].id }.groupName })
            compose.onNodeWithText("Annuler").performClick()
            compose.onNodeWithText("1 sélectionné(s)").assertIsDisplayed()
            compose.onNodeWithText("Classer").performClick()
            compose.onNodeWithText(renamed).performClick()
            compose.waitUntil(5_000) { vm.uiState.value.tags.any { it.id == tags[0].id && it.groupName == renamed } }
            compose.onNodeWithText("Classer les tags").assertDoesNotExist()
        } finally {
            runBlocking { dao.deleteTags(ids); dao.deleteTagCategoryRaw(cat.id) }
        }
    }

    @Test fun editingATagKeepsItsDraftWhenTheCatalogChangesAndTheScreenIsRestored() {
        val cat = TagCategoryEntity("c", "Genres", "genres")
        var tag by mutableStateOf(TagEntity("t", "Original", "original", "Héritage"))
        val restore = StateRestorationTester(compose)
        var saved: Triple<String, String?, Boolean>? = null
        restore.setContent { French {
            TagEditDialog(tag, listOf(cat), { name, id, changed -> saved = Triple(name, id, changed) }, {})
        } }
        val field = compose.onNode(hasSetTextAction())
        compose.runOnIdle { tag = tag.copy(name = "Importé") }
        field.assertTextContains("Importé")
        field.performTextReplacement("Mon brouillon")
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { tag = tag.copy(name = "Renommé", groupName = cat.name) }
        restore.emulateSavedInstanceStateRestore()
        field.assertTextContains("Mon brouillon")
        compose.onNodeWithText("Genres").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Enregistrer").performClick()
        compose.runOnIdle { assertEquals(Triple("Mon brouillon", cat.id, false), saved) }
    }

    @Test fun categoryFiltersDistinguishUserNamesFromAllAndUncategorizedAndFollowRenames() {
        val all = TagCategoryEntity("one", "__all__", "all")
        val none = TagCategoryEntity("two", "__none__", "none")
        var first by mutableStateOf(all)
        compose.setContent { French {
            val tags = listOf(TagEntity("a", "Alpha", "alpha", first.name), TagEntity("b", "Beta", "beta", none.name), TagEntity("c", "Gamma", "gamma"))
            GameTagPickerSheet(tags, listOf(first, none), emptySet(), { _, _ -> }, {})
        } }
        fun choose(label: String) {
            compose.onNodeWithTag("game-tag-choices").performScrollToIndex(1)
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .performScrollToNode(hasText(label))
            val horizontal = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
            compose.onNode(hasText(label) and hasAnyAncestor(horizontal)).performClick()
        }
        fun expectOnly(name: String) {
            compose.onNodeWithTag("game-tag-choices").performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertIsDisplayed()
            listOf("Alpha", "Beta", "Gamma").filterNot { it == name }.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        }
        choose("__all__")
        expectOnly("Alpha")
        compose.runOnIdle { first = all.copy(name = "Fictions", normalizedName = "fictions") }
        expectOnly("Alpha")
        choose("__none__")
        expectOnly("Beta")
        choose("Sans catégorie")
        expectOnly("Gamma")
    }
}
