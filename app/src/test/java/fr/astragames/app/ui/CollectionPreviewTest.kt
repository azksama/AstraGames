package fr.astragames.app.ui

import fr.astragames.app.data.local.*
import fr.astragames.app.data.repository.CollectionRuleDraft
import fr.astragames.app.ui.theme.astraColors
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CollectionPreviewTest {
    private val game = GameEntity(id = "g", title = "Game", documentUri = "content://g", physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "source", dateAdded = 1, lastModified = 1, fingerprint = "g", favorite = true)
    private val state = AstraUiState(games = listOf(game, game.copy(id = "missing", missing = true, favorite = false), game.copy(id = "hidden", hidden = true)))

    @Test fun previewUsesAvailableGamesAndBothRuleModes() {
        val rules = listOf(CollectionRuleDraft("AVAILABLE", "IS", "true"), CollectionRuleDraft("FAVORITE", "IS", "false"))
        assertEquals(0, collectionPreviewCount(state, "ALL", rules))
        assertEquals(2, collectionPreviewCount(state, "ANY", rules))
        assertEquals(1, collectionPreviewCount(state, "ALL", listOf(CollectionRuleDraft("AVAILABLE", "IS", "false"))))
    }

    @Test fun previewIncludesRealTagLinksAndDescendantFolders() {
        val linked = state.copy(games = listOf(game.copy(libraryFolderId = "child")),
            tags = listOf(TagEntity("story", "Histoire", "histoire")),
            gameTagRefs = listOf(GameTagCrossRef(game.id, "story")),
            folders = listOf(LibraryFolderEntity("parent", "RPG"), LibraryFolderEntity("child", "À jouer", "parent")))
        val rules = listOf(CollectionRuleDraft("TAG", "IS", "story"), CollectionRuleDraft("FOLDER", "IS", "parent"))
        assertEquals(1, collectionPreviewCount(linked, "ALL", rules))
        assertEquals(0, collectionPreviewCount(linked.copy(gameTagRefs = emptyList()), "ALL", rules))
    }

    @Test fun incompleteAndNonFiniteRulesCannotBeSavedOrCounted() {
        listOf("", "NaN", "Infinity", "-1").forEach { value ->
            val rule = CollectionRuleDraft("PLAY_TIME", "GREATER_THAN", value)
            assertFalse(rule.isValid(state))
            assertEquals(0, collectionPreviewCount(state, "ALL", listOf(rule)))
        }
        assertTrue(CollectionRuleDraft("LAST_PLAYED", "NEVER", "").isValid(state))
        assertFalse(CollectionRuleDraft("TAG", "IS", "removed").isValid(state))
    }

    @Test fun everyHueKeepsReadableTextAndResetRestoresOriginalColors() {
        for (hue in 0..359) {
            val palette = astraColors(hue)
            fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
                (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
            assertTrue("Caption contrast at $hue", contrast(palette.onSurfaceVariant, palette.surfaceContainer) >= 4.5f)
            assertTrue("Button contrast at $hue", contrast(palette.primary, palette.onPrimary) >= 4.5f)
        }
        assertEquals(0xFF09090F, astraColors(255).background.value.toLong().ushr(32))
        assertNotEquals(astraColors(255).primary, astraColors(120).primary)
    }
}
