package fr.astragames.app.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.repository.CollectionRuleDraft
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionMembershipTest {
    private fun game(id: String) = GameEntity(id, id, documentUri = "content://collections/$id", physicalPath = null,
        executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = "test",
        dateAdded = 1, lastModified = 1, fingerprint = id)

    @Test fun savedCollectionAndPreviewAgreeWhenAnExcludedFolderOrTagDisappears() {
        val games = listOf(game("one"), game("two").copy(favorite = true))
        val state = AstraUiState(games = games)
        for (field in listOf("TAG", "FOLDER")) {
            for (mode in listOf("ANY", "ALL")) {
                val collection = CollectionEntity("c", "Orpheline", matchMode = mode)
                val rules = listOf(CollectionRuleEntity("stale", "c", field, "NOT", "removed"), CollectionRuleEntity("favorite", "c", "FAVORITE", "IS", "true"))
                val preview = collectionPreviewCount(state, mode, rules.map { CollectionRuleDraft(it.field, it.operator, it.value) })
                val index = LibraryIndex(games, emptyList(), emptyList(), listOf(collection), rules, emptyList(), tagIds = emptySet())
                assertEquals(0, preview)
                assertEquals("$field $mode", preview, index.collectionGames.getValue("c").size)
            }
        }
    }

    @Test fun previewReadsTheFolderTreeOnceForAllGamesInANestedCollection() {
        val tree = (0 until 16).map { LibraryFolderEntity("f$it", "Niveau $it", if (it == 0) null else "f${it - 1}") }
        var reads = 0
        val folders = object : AbstractList<LibraryFolderEntity>() {
            override val size get() = tree.size
            override fun get(index: Int): LibraryFolderEntity {
                reads++
                return tree[index]
            }
        }
        val games = (0 until 64).map { game("g$it").copy(libraryFolderId = if (it % 2 == 0) "f15" else null) }
        val rules = listOf(CollectionRuleDraft("FOLDER", "IS", "f0"), CollectionRuleDraft("FOLDER", "NOT", "f7"))
        val state = AstraUiState(games = games, folders = folders)
        assertEquals(64, collectionPreviewCount(state, "ANY", rules))
        Log.i("AstraCollectionTest", "preview folder reads=$reads; folders=${tree.size}; games=${games.size}")
        assertTrue("The preview read $reads folder rows for ${tree.size} folders", reads <= tree.size * 5)
    }

    @Test fun anExistingUnusedTagAndFolderStillMatchNegativeRules() {
        val games = listOf(game("outside"), game("inside").copy(libraryFolderId = "leaf"), game("hidden").copy(hidden = true))
        val folders = listOf(LibraryFolderEntity("root", "Romans"), LibraryFolderEntity("leaf", "Courts", "root"))
        val collection = CollectionEntity("c", "À classer")
        val rules = listOf(CollectionRuleEntity("tag", "c", "TAG", "NOT", "unused"), CollectionRuleEntity("folder", "c", "FOLDER", "NOT", "root"))
        val state = AstraUiState(games = games, folders = folders, tags = listOf(TagEntity("unused", "Inédit", "inedit")))
        val index = LibraryIndex(games, emptyList(), folders, listOf(collection), rules, emptyList(), tagIds = setOf("unused"))
        assertEquals(listOf("outside"), index.collectionGames.getValue("c").map { it.id })
        assertEquals(1, collectionPreviewCount(state, "ALL", rules.map { CollectionRuleDraft(it.field, it.operator, it.value) }))
    }
}
