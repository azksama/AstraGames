package fr.astragames.app.ui

import fr.astragames.app.core.model.TagMatchMode
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {
    @Test fun searchUsesIdsFromResultsAndCurrentCatalogValues() {
        val updated = game("a", "Updated title").copy(favorite = true)
        val index = index(listOf(updated, game("b", "Other")))

        assertEquals(listOf(updated), index.filter(LibraryFilters(favoritesOnly = true), setOf("a", "removed"), emptyList(), emptyMap()))
        assertTrue(index.filter(LibraryFilters(), emptySet(), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun folderFilterIncludesNestedChildrenAndTerminatesWithCycles() {
        val folders = listOf(
            LibraryFolderEntity("root", "Root", parentId = "child"),
            LibraryFolderEntity("child", "Child", parentId = "root"),
            LibraryFolderEntity("nested", "Nested", parentId = "child")
        )
        val index = index(listOf(game("a").copy(libraryFolderId = "nested"), game("b")), folders = folders)

        assertEquals(listOf("a"), index.results(LibraryFilters(folderId = "root")))
    }

    @Test fun requiredExcludedAndAnyTagsComposeWithFavorites() {
        val index = index(
            listOf(game("a").copy(favorite = true), game("b").copy(favorite = true), game("c")),
            refs = listOf(GameTagCrossRef("a", "story"), GameTagCrossRef("b", "story"), GameTagCrossRef("b", "avoid"))
        )
        assertEquals(listOf("a"), index.results(LibraryFilters(
            tagIds = setOf("story", "rpg"), excludedTagIds = setOf("avoid"), tagMode = TagMatchMode.ANY, favoritesOnly = true
        )))
        assertTrue(index.results(LibraryFilters(tagIds = setOf("story", "rpg"))).isEmpty())
    }

    @Test fun selectedSmartCollectionUsesSameMembershipAsItsPreview() {
        val index = index(
            listOf(game("a"), game("b")),
            refs = listOf(GameTagCrossRef("b", "story")),
            collections = listOf(CollectionEntity("c", "Stories")),
            rules = listOf(CollectionRuleEntity("r", "c", "TAG", "IS", "story"))
        )
        assertEquals(index.collectionGames["c"]!!.map { it.id }, index.results(LibraryFilters(collectionId = "c")))
        assertTrue(index.results(LibraryFilters(collectionId = "deleted")).isEmpty())
    }

    @Test fun equalSortValuesHaveDeterministicTitleAndIdOrder() {
        val index = index(listOf(game("z", "Zulu"), game("b", "alpha"), game("a", "Alpha")))

        LibrarySort.entries.forEach { sort ->
            assertEquals(sort.name, listOf("a", "b", "z"), index.results(LibraryFilters(sort = sort)))
        }
    }

    @Test fun smartCollectionsReuseNestedFolderMembershipForPositiveAndNegativeRules() {
        val index = index(
            listOf(game("inside").copy(libraryFolderId = "nested"), game("outside")),
            folders = listOf(LibraryFolderEntity("root", "Root"), LibraryFolderEntity("nested", "Nested", parentId = "root")),
            collections = listOf(CollectionEntity("yes", "In folder"), CollectionEntity("no", "Outside folder")),
            rules = listOf(
                CollectionRuleEntity("r1", "yes", "FOLDER", "IS", "root"),
                CollectionRuleEntity("r2", "no", "FOLDER", "NOT", "root")
            )
        )

        assertEquals(listOf("inside"), index.results(LibraryFilters(collectionId = "yes")))
        assertEquals(listOf("outside"), index.results(LibraryFilters(collectionId = "no")))
    }

    @Test fun updateSortingRequiresAKnownInstalledVersion() {
        val index = index(listOf(game("unknown", "Alpha"), game("current", "Beta").copy(version = "2"), game("update", "Zulu").copy(version = "1")))
        val versions = mapOf("unknown" to "2", "current" to "2", "update" to "2")

        assertEquals(listOf("update", "unknown", "current"), index.filter(
            LibraryFilters(sort = LibrarySort.UPDATE_AVAILABLE), null, emptyList(), versions
        ).map { it.id })
    }

    @Test fun systemAndSourceFiltersIntersectSearchResults() {
        val index = index(listOf(game("a"), game("b"), game("c").copy(sourceId = "other")))
        val folders = listOf(SystemFolderFilter("system", "source", "Games", 1, setOf("a", "c")))
        assertEquals(listOf("a"), index.filter(
            LibraryFilters(systemFolderId = "system", sourceId = "source"), setOf("a", "b", "c"), folders, emptyMap()
        ).map { it.id })
    }

    private fun LibraryIndex.results(filters: LibraryFilters) = filter(filters, null, emptyList(), emptyMap()).map { it.id }

    private fun index(
        games: List<GameEntity>, refs: List<GameTagCrossRef> = emptyList(),
        folders: List<LibraryFolderEntity> = emptyList(), collections: List<CollectionEntity> = emptyList(),
        rules: List<CollectionRuleEntity> = emptyList()
    ) = LibraryIndex(games, refs, folders, collections, rules, emptyList())

    private fun game(id: String, title: String = id) = GameEntity(
        id = id, title = title, documentUri = "content://games/$id", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "source",
        dateAdded = 1, lastModified = 1, fingerprint = id
    )
}
