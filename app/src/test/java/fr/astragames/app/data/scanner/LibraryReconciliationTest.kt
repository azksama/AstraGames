package fr.astragames.app.data.scanner

import fr.astragames.app.data.local.GameEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryReconciliationTest {
    @Test fun recognizesMovedGameWithoutConfusingSameRecordForDuplicate() {
        val stored = game("one", "content://old")
        val moved = game("two", "content://new")
        assertTrue(MovedGameDetector.isMoved(stored, moved))
        assertTrue(DuplicateDetector.isDuplicate(stored, moved))
        assertFalse(DuplicateDetector.isDuplicate(stored, stored))
    }

    private fun game(id: String, uri: String) = GameEntity(
        id = id, title = "Astra Quest", documentUri = uri, physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY",
        sourceId = "source", dateAdded = 1, lastModified = 1, fingerprint = "same"
    )
}
