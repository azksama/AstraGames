package fr.astragames.app.ui

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.readBytes
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SaveEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val fixtureId = "save-editor-ui-${UUID.randomUUID()}"
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val previousLanguage = AppLocalizer.language
    private val dismissed = AtomicInteger()
    private val original = """{"party":{"@":"Game_Party","@c":1,"gold":120},"locked":null,"name":"Hero"}""".toByteArray()
    private lateinit var vm: AstraViewModel
    private lateinit var game: GameEntity
    private lateinit var save: GameSave

    @Before fun fixtures() {
        val result = requireNotNull(app.contentResolver.call(provider, "fixture", fixtureId, null))
        val documents = requireNotNull(documentDir(app, Uri.parse(requireNotNull(result.getString("uri")))))
        val folder = requireNotNull(documents.findFile("game"))
        val source = requireNotNull(requireNotNull(folder.findFile("save")).findFile("file1.rpgsave"))
        requireNotNull(app.contentResolver.openOutputStream(source.uri, "wt")).use { it.write(original) }
        game = GameEntity(
            id = fixtureId, title = "Save editor fixture", documentUri = folder.uri.toString(), physicalPath = null,
            executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = fixtureId,
            dateAdded = 1, lastModified = 1, fingerprint = fixtureId
        )
        runBlocking {
            app.container.dao.upsertGame(game)
            save = app.container.saveManager.listSaves(game).single()
        }
        compose.runOnUiThread { vm = AstraViewModel(app); store.put(fixtureId, vm) }
        AppLocalizer.language = AppLanguage.FRENCH
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        try {
            runBlocking {
                if (::save.isInitialized) app.container.dao.getSaveBackupsForSource(save.uri).forEach {
                    app.container.saveManager.deleteBackup(it)
                }
                app.container.dao.deleteGameCompletely(fixtureId)
            }
        } finally {
            app.contentResolver.call(provider, "removeFixture", fixtureId, null)
            AppLocalizer.language = previousLanguage
        }
    }

    private fun showEditor() {
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { SaveEditorDialog(game, save, vm) { dismissed.incrementAndGet() } }
            }
        }
        awaitLoaded()
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
    }

    private fun awaitLoaded() {
        compose.waitUntil(10_000) { !vm.tools.toolsBusy.value && vm.tools.saveRevision.value?.first == save.uri }
        // Complete the effect that captures the opened revision before restoring saved state.
        compose.waitForIdle()
        assertNull(vm.tools.toolsError.value)
    }

    private fun money() = compose.onNode(hasSetTextAction() and hasText("Argent"))

    private fun editMoney(value: String) {
        money().performTextReplacement(value)
        closeSoftKeyboard()
    }

    @Test fun draftRequiresConfirmationAndCanBeKeptOrDiscardedWithoutWriting() {
        showEditor()
        editMoney("777")
        compose.onNodeWithText("Enregistrer").assertIsEnabled()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Abandonner les modifications ?").assertIsDisplayed()
        assertEquals(0, dismissed.get())
        compose.onNodeWithText("Continuer à modifier").performClick()
        money().assertTextContains("777")
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Abandonner").performClick()
        compose.waitUntil(10_000) { dismissed.get() == 1 }
        assertArrayEquals(original, readBytes(app, save.uri))
        assertTrue(runBlocking { app.container.dao.getSaveBackupsForSource(save.uri).isEmpty() })
    }

    @Test fun advancedEditorDoesNotOfferTextInputsForReadOnlyValuesOrJsonExMetadata() {
        showEditor()
        compose.onNodeWithText("Avancé").performClick()
        val search = compose.onNode(hasSetTextAction() and hasText("Recherche"))
        search.performTextReplacement("root.locked")
        closeSoftKeyboard()
        compose.onNode(hasText("root.locked") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("null").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1) // Only the search field.
        search.performTextReplacement("root.party.@")
        closeSoftKeyboard()
        compose.onNodeWithText("Aucun champ ne correspond aux filtres.").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
        assertArrayEquals(original, readBytes(app, save.uri))
    }

    @Test fun restoredDraftCanBeSavedThroughSafWithAnExactOriginalBackup() {
        showEditor()
        editMoney("777")
        restoration.emulateSavedInstanceStateRestore()
        awaitLoaded()
        money().assertTextContains("777")
        assertEquals(save.uri to sha256Hex(original), vm.tools.saveRevision.value)
        compose.onNodeWithText("Enregistrer").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { dismissed.get() == 1 && !vm.tools.toolsBusy.value }
        assertNull(vm.tools.toolsError.value)
        val edited = JSONObject(readBytes(app, save.uri).toString(Charsets.UTF_8))
        assertEquals(777, edited.getJSONObject("party").getInt("gold"))
        assertEquals("Game_Party", edited.getJSONObject("party").getString("@"))
        assertEquals(1, edited.getJSONObject("party").getInt("@c"))
        assertTrue(edited.isNull("locked"))
        assertEquals("Hero", edited.getString("name"))
        val backup = runBlocking { app.container.dao.getSaveBackupsForSource(save.uri).single() }
        assertArrayEquals(original, readBytes(app, backup.backupUri))
    }

    @Test fun restoredDraftKeepsItsOriginalRevisionAndCannotOverwriteAnExternalChange() {
        showEditor()
        editMoney("777")
        val external = original.toString(Charsets.UTF_8).replace("120", "999").toByteArray()
        requireNotNull(app.contentResolver.openOutputStream(Uri.parse(save.uri), "wt")).use { it.write(external) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) {
            !vm.tools.toolsBusy.value && vm.tools.toolsError.value.orEmpty().contains("change depuis son ouverture")
        }
        compose.onNodeWithText("change depuis son ouverture", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
        assertNull(vm.tools.saveRevision.value)
        assertTrue(vm.tools.saveEntries.value.isEmpty())
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Abandonner les modifications ?").assertIsDisplayed()
        assertEquals(0, dismissed.get())
        assertArrayEquals(external, readBytes(app, save.uri))
        assertTrue(runBlocking { app.container.dao.getSaveBackupsForSource(save.uri).isEmpty() })
    }
}
