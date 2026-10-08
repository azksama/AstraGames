package fr.astragames.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SourceSubfolderDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseNestedFolderWithoutSelectingTheParentSource() {
        var selected: List<String>? = null
        compose.setContent {
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
        compose.onNodeWithText("Scanner ce dossier").assertIsNotEnabled()
        compose.onNodeWithText("Wolf").performClick()
        compose.onNodeWithText("Nouveaux").performClick()
        compose.onNodeWithText("Aucun sous-dossier").assertExists()
        compose.onNodeWithText("Dossier parent").performClick()
        compose.onNodeWithText("Nouveaux").assertExists()
        compose.onNodeWithText("Scanner ce dossier").performClick()
        compose.runOnIdle { assertEquals(listOf("Wolf"), selected) }
    }
}
