package fr.astragames.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SourceSubfolderDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseNestedFolderWithoutSelectingTheParentSource() {
        var selected: List<String>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                MaterialTheme {
                    SourceSubfolderDialog(
                        GameSourceEntity("source", "Jeux", "file:///unused"),
                        loadFolders = { path -> when (path) {
                            emptyList<String>() -> listOf("Wolf", "RenPy")
                            listOf("Wolf") -> listOf("Nouveaux")
                            else -> emptyList()
                        } },
                        onScan = { selected = it },
                        onDismiss = {}
                    )
                }
            }
        }
        fun showRow(label: String): SemanticsNodeInteraction {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            return compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Scanner ce dossier").assertIsNotEnabled()
        showRow("Wolf").performClick()
        restoration.emulateSavedInstanceStateRestore()
        showRow("Nouveaux").performClick()
        showRow("Aucun sous-dossier")
        showRow("Dossier parent").performClick()
        showRow("Nouveaux")
        compose.onNodeWithText("Scanner ce dossier").performClick()
        compose.runOnIdle { assertEquals(listOf("Wolf"), selected) }
    }
}
