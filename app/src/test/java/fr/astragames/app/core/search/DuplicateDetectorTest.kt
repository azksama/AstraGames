package fr.astragames.app.core.search

import fr.astragames.app.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {
    @Test fun groupsIdenticalFingerprints() {
        val groups = DuplicateDetector.groups(listOf(game("1", "Alpha", "same"), game("2", "Beta", "same")))
        assertEquals(setOf("1", "2"), groups.single().games.map { it.id }.toSet())
    }

    @Test fun ignoresPlatformAndVersionSuffixesInTitles() {
        val groups = DuplicateDetector.groups(
            listOf(game("1", "Astra Quest v1.2 Windows", "one"), game("2", "Astra Quest 1.3 x64", "two"))
        )
        assertEquals(2, groups.single().games.size)
    }

    @Test fun doesNotGroupDistinctGames() {
        assertTrue(DuplicateDetector.groups(listOf(game("1", "Alpha", "one"), game("2", "Beta", "two"))).isEmpty())
    }

    @Test fun emptyFingerprintsDoNotMatchUnrelatedGames() {
        assertTrue(DuplicateDetector.groups(listOf(game("1", "Alpha", ""), game("2", "Beta", ""))).isEmpty())
    }

    private fun game(id: String, title: String, fingerprint: String) = GameEntity(
        id = id, title = title, documentUri = "content://$id", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "s1",
        dateAdded = 1, lastModified = 1, fingerprint = fingerprint
    )
}
