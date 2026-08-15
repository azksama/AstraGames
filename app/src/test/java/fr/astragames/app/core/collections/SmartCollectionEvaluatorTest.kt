package fr.astragames.app.core.collections

import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionEvaluatorTest {
    private val game = GameEntity(
        id = "g1", title = "Astra Quest", documentUri = "content://g1", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "s1",
        coverUri = "content://cover", dateAdded = NOW - 2 * DAY, lastModified = 1,
        lastPlayedAt = NOW - DAY, favorite = true, libraryFolderId = "child", fingerprint = "fp"
    )
    private val folders = listOf(
        LibraryFolderEntity("root", "RPG"),
        LibraryFolderEntity("child", "À finir", parentId = "root")
    )
    private val refs = listOf(GameTagCrossRef("g1", "story"))
    private val play = GamePlayStat("g1", 5 * HOUR, NOW - DAY)

    @Test fun allModeRequiresEveryRuleAndIncludesSubfolders() {
        val rules = listOf(
            rule("engine", "ENGINE", "IS", "RPG_MAKER_MV"),
            rule("folder", "FOLDER", "IS", "root"),
            rule("tag", "TAG", "IS", "story"),
            rule("time", "PLAY_TIME", "GREATER_THAN", "4")
        )

        assertTrue(matches(CollectionEntity("c", "RPG longs"), rules))
        assertFalse(matches(CollectionEntity("c", "RPG longs"), rules + rule("cover", "COVER", "IS", "false")))
    }

    @Test fun anyModeAcceptsOneRuleAndSupportsNeverPlayed() {
        val rules = listOf(
            rule("engine", "ENGINE", "IS", "RENPY"),
            rule("favorite", "FAVORITE", "IS", "true")
        )

        assertTrue(matches(CollectionEntity("c", "Choix", matchMode = "ANY"), rules))
        assertFalse(matches(CollectionEntity("c", "Jamais"), listOf(rule("never", "LAST_PLAYED", "NEVER", ""))))
    }

    @Test fun dateAndNegativeRulesAreEvaluated() {
        val rules = listOf(
            rule("recent", "DATE_ADDED", "WITHIN_DAYS", "3"),
            rule("notRenpy", "ENGINE", "NOT", "RENPY"),
            rule("noOtherTag", "TAG", "NOT", "sandbox")
        )

        assertTrue(matches(CollectionEntity("c", "Récents"), rules))
    }

    private fun matches(collection: CollectionEntity, rules: List<CollectionRuleEntity>) =
        SmartCollectionEvaluator.matches(collection, rules, game, refs, folders, play, NOW)

    private fun rule(id: String, field: String, operator: String, value: String) =
        CollectionRuleEntity(id, "c", field, operator, value)

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        const val NOW = 2_000_000_000_000L
    }
}
