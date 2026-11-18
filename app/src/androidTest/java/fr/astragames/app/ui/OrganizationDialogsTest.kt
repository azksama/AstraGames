package fr.astragames.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun folderPickerShowsBothPathsAndCanRetryAFailedChoice() {
        val folders = listOf(LibraryFolderEntity("a", "Romans"), LibraryFolderEntity("b", "RPG"),
            LibraryFolderEntity("a1", "À finir", "a"), LibraryFolderEntity("b1", "À finir", "b"))
        var picked: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme {
                GameFolderDialog(folders, "a1", { id ->
                    if (id == "a1") error("Écriture refusée")
                    picked = id
                }, {})
            } }
        }
        fun option(path: String): SemanticsNodeInteraction {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(path))
            return compose.onNodeWithText(path)
        }
        option("Romans / À finir").assertIsSelected().performClick()
        compose.onNodeWithText("Écriture refusée").assertIsDisplayed()
        option("RPG / À finir").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("b1", picked) }
    }

    @Test fun aPendingCollectionSaveAcceptsOneTapAndKeepsTheDraftOnFailure() {
        val firstWrite = CompletableDeferred<Unit>()
        var attempts = 0
        var savedName: String? = null
        val collection = CollectionEntity("c", "Initial")
        val rules = listOf(CollectionRuleEntity("r", "c", "AVAILABLE", "IS", "true"))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme {
                SmartCollectionEditorDialog(collection, rules, AstraUiState(), { _, name, _, _ ->
                    attempts++
                    if (attempts == 1) {
                        firstWrite.await()
                        error("Écriture refusée")
                    }
                    savedName = name
                }, {})
            } }
        }
        val actions = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        actions.performScrollToNode(hasText("Nom de la collection"))
        compose.onNode(hasSetTextAction() and hasText("Nom de la collection")).performTextReplacement("Mon brouillon")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        actions.performScrollToNode(hasText("Enregistrer"))
        val save = compose.onNodeWithText("Enregistrer")
        save.performClick()
        save.assertIsNotEnabled().performTouchInput { click() }
        val removeRule = hasContentDescription("Retirer")
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(removeRule)
        compose.onNode(removeRule).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts); firstWrite.complete(Unit) }
        actions.performScrollToNode(hasText("Écriture refusée"))
        compose.onNodeWithText("Écriture refusée").assertIsDisplayed()
        actions.performScrollToNode(hasText("Enregistrer"))
        save.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals("Mon brouillon", savedName) }
    }

    @Test fun folderDeletionAndInvalidRuleMessagesFollowTheSelectedLanguage() {
        val deletion = "Les jeux classés directement ici passeront dans « Sans dossier ». Les sous-dossiers remonteront d’un niveau avec leurs jeux."
        val validation = "Vérifiez la règle 12 avant d’enregistrer la collection."
        AppLanguage.entries.filter { it != AppLanguage.FRENCH }.forEach { language ->
            val message = AppLocalizer.text(deletion, language)
            assertNotEquals(deletion, message)
            assertTrue(message.contains(AppLocalizer.text("Sans dossier", language)))
            val ruleError = AppLocalizer.text(validation, language)
            assertNotEquals(validation, ruleError)
            assertTrue(ruleError.contains("12"))
        }
    }

    @Test fun collectionScreenRestoresAnOpenDraftAndItsRules() {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) { AstraTheme {
                    CollectionsScreen(AstraUiState(settingsLoaded = true), vm, {})
                } }
            }
            compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Créer une collection intelligente"))
            compose.onNodeWithText("Créer une collection intelligente").performClick()
            val name = hasSetTextAction() and hasText("Nom de la collection")
            compose.onNode(name).performTextReplacement("À retrouver")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onAllNodes(hasScrollToNodeAction() and hasAnyAncestor(isDialog())).onLast().performScrollToNode(hasText("Ajouter une règle"))
            compose.onNodeWithText("Ajouter une règle").performClick()
            restoration.emulateSavedInstanceStateRestore()
            val actions = compose.onAllNodes(hasScrollToNodeAction() and hasAnyAncestor(isDialog())).onFirst()
            actions.performScrollToNode(hasText("Nom de la collection"))
            compose.onNode(name).assertTextContains("À retrouver")
            actions.performScrollToNode(hasText("Créer la collection"))
            compose.onNodeWithText("Créer la collection").assertIsEnabled()
        } finally { store.clear() }
    }
}
