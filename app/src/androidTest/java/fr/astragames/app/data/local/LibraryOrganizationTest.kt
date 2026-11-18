package fr.astragames.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.CollectionRuleDraft
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryOrganizationTest {
    private lateinit var db: AstraDatabase
    private lateinit var dao: AstraDao
    private lateinit var repository: GameRepository
    private val parent = LibraryFolderEntity("parent", "Romans")
    private val child = LibraryFolderEntity("child", "En cours", parent.id)
    private val leaf = LibraryFolderEntity("leaf", "Courts", child.id)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        dao = db.dao()
        repository = GameRepository(context, db, RecursiveSourceScanner(context, dao, FileAccessResolver(context)), BackupManager(context, db))
    }

    @After fun close() = db.close()

    private fun game(id: String, folder: String? = null) = GameEntity(id, "Jeu $id", documentUri = "content://organisation/$id",
        physicalPath = null, executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = "test",
        dateAdded = 1, lastModified = 1, fingerprint = id, libraryFolderId = folder)

    @Test fun failedFolderDeletionKeepsTheWholeTreeAndAssignmentsUntilRetry() = runBlocking {
        listOf(parent, child, leaf).forEach { dao.upsertFolder(it) }
        val direct = game("direct", child.id)
        val nested = game("nested", leaf.id)
        dao.upsertGame(direct)
        dao.upsertGame(nested)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_delete BEFORE DELETE ON library_folders BEGIN SELECT RAISE(ABORT, 'folder write refused'); END")
        try {
            repository.deleteFolder(child)
            fail("The database refused the delete")
        } catch (_: SQLiteException) { }
        assertEquals(listOf(parent, child, leaf).toSet(), dao.getFolders().toSet())
        assertEquals(direct, dao.getGame(direct.id))
        assertEquals(nested, dao.getGame(nested.id))
        sql.execSQL("DROP TRIGGER refuse_delete")
        repository.deleteFolder(child)
        assertEquals(setOf(parent, leaf.copy(parentId = parent.id)), dao.getFolders().toSet())
        assertEquals(direct.copy(libraryFolderId = null), dao.getGame(direct.id))
        assertEquals(nested, dao.getGame(nested.id))
    }

    @Test fun staleFolderEditsUseTheCurrentParentAndCannotResurrectADeletedFolder() = runBlocking {
        listOf(parent, child, leaf).forEach { dao.upsertFolder(it) }
        repository.deleteFolder(parent)
        repository.renameFolder(child, "Renommé")
        assertEquals(child.copy(name = "Renommé", parentId = null), dao.getFolders().single { it.id == child.id })
        repository.deleteFolder(child)
        assertEquals(listOf(leaf.copy(parentId = null)), dao.getFolders())
        assertTrue(runCatching { repository.renameFolder(child, "Obsolète") }.isFailure)
        assertTrue(runCatching { repository.createFolder("Perdu", child.id) }.isFailure)
        assertEquals(listOf(leaf.copy(parentId = null)), dao.getFolders())
    }

    @Test fun staleDestinationCannotLeaveAnInvisibleGameAssignment() = runBlocking {
        dao.upsertFolder(parent)
        dao.upsertGame(game("single"))
        dao.upsertGame(game("bulk"))
        repository.deleteFolder(parent)
        assertTrue(runCatching { repository.setGameFolder("single", parent.id) }.isFailure)
        assertTrue(runCatching { repository.setGamesFolder(setOf("single", "bulk"), parent.id) }.isFailure)
        assertTrue(dao.getAllGames().all { it.libraryFolderId == null })
    }

    @Test fun deletingAFolderInAnOldCycleBringsItsChildrenBackToTheRoot() = runBlocking {
        dao.upsertFolder(parent.copy(parentId = child.id))
        dao.upsertFolder(child)
        dao.upsertFolder(leaf)
        dao.upsertGame(game("reader", leaf.id))
        repository.deleteFolder(child)
        assertEquals(setOf(parent, leaf.copy(parentId = null)), dao.getFolders().toSet())
        assertEquals(leaf.id, dao.getGame("reader")!!.libraryFolderId)
    }

    @Test fun bulkFilingRollsBackTheFirstBatchIfTheLastGameCannotBeMoved() = runBlocking {
        val ids = (1..901).mapTo(linkedSetOf()) { "g-$it" }
        db.withTransaction {
            dao.upsertFolder(parent)
            ids.forEach { dao.insertGameRaw(game(it)) }
        }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_move BEFORE UPDATE OF libraryFolderId ON games WHEN OLD.id='g-901' BEGIN SELECT RAISE(ABORT, 'move refused'); END")
        try {
            repository.setGamesFolder(ids, parent.id)
            fail("The database refused the move")
        } catch (_: SQLiteException) { }
        assertEquals(0, dao.getAllGames().count { it.libraryFolderId == parent.id })
        sql.execSQL("DROP TRIGGER refuse_move")
        repository.setGamesFolder(ids, parent.id)
        assertEquals(901, dao.getAllGames().count { it.libraryFolderId == parent.id })
        assertTrue(runCatching { repository.setGamesFolder(ids + "removed", null) }.isFailure)
        assertEquals(901, dao.getAllGames().count { it.libraryFolderId == parent.id })
    }

    @Test fun mergingTagsRetargetsCollectionRulesAlongsideTheGames() = runBlocking {
        dao.upsertTag(TagEntity("keep", "Roman", "roman"))
        dao.upsertTag(TagEntity("old", "Romans", "romans"))
        dao.upsertFolder(LibraryFolderEntity("old", "Même identifiant"))
        dao.upsertGame(game("reader"))
        dao.addGameTag(GameTagCrossRef("reader", "old"))
        val collection = CollectionEntity("c", "Romans")
        val rules = listOf(
            CollectionRuleEntity("positive", "c", "TAG", "IS", "old"),
            CollectionRuleEntity("negative", "c", "TAG", "NOT", "old"),
            CollectionRuleEntity("folder", "c", "FOLDER", "IS", "old")
        )
        dao.replaceCollection(collection, rules)
        repository.mergeTags("keep", "old")
        assertEquals(setOf("keep"), dao.getTagIdsForGame("reader").toSet())
        assertEquals(rules.map { if (it.field == "TAG") it.copy(value = "keep") else it }, dao.observeCollectionRules().first())
    }

    @Test fun editingACollectionPreservesItsOrderAndCannotRecreateItAfterDeletion() = runBlocking {
        val collection = CollectionEntity("c", "À jouer", sortOrder = 17)
        val rules = listOf(CollectionRuleDraft("FAVORITE", "IS", "true"))
        dao.replaceCollection(collection, emptyList())
        repository.createOrUpdateCollection(collection.id, "Priorité", "ANY", rules)
        assertEquals(collection.copy(name = "Priorité", matchMode = "ANY"), dao.observeCollections().first().single())
        repository.deleteCollection(collection.id)
        assertTrue(runCatching { repository.createOrUpdateCollection(collection.id, "Ancien brouillon", "ALL", rules) }.isFailure)
        assertTrue(dao.observeCollections().first().isEmpty())
        assertTrue(dao.observeCollectionRules().first().isEmpty())
    }

    @Test fun replayingACreationAfterRecreationKeepsOneFolderAndOneCollection() = runBlocking {
        val rules = listOf(CollectionRuleDraft("FOLDER", "IS", "folder-draft"))
        repository.createFolder("Séries", draftId = "folder-draft")
        repository.createOrUpdateCollection(null, "Classés", "ALL", rules, draftId = "collection-draft")
        val collectionRules = dao.observeCollectionRules().first()
        repository.createFolder("Séries", draftId = "folder-draft")
        repository.createOrUpdateCollection(null, "Classés", "ALL", rules, draftId = "collection-draft")
        assertEquals("folder-draft", dao.getFolders().single().id)
        assertEquals("collection-draft", dao.observeCollections().first().single().id)
        assertEquals(collectionRules, dao.observeCollectionRules().first())
    }

    @Test fun invalidOrDisappearedRuleCannotReplaceSavedRules() = runBlocking {
        val collection = CollectionEntity("c", "Conservée")
        val original = CollectionRuleEntity("rule", collection.id, "AVAILABLE", "IS", "true")
        dao.replaceCollection(collection, listOf(original))
        val invalid = listOf(
            CollectionRuleDraft("FOLDER", "NOT", "removed"), CollectionRuleDraft("TAG", "IS", "removed"),
            CollectionRuleDraft("PLAY_TIME", "GREATER_THAN", "NaN"), CollectionRuleDraft("AVAILABLE", "NOT", "maybe"),
            CollectionRuleDraft("DATE_ADDED", "OLDER_THAN_DAYS", "-1"), CollectionRuleDraft("ENGINE", "BROKEN", "HTML5")
        )
        for (rule in invalid) {
            assertTrue(rule.toString(), runCatching { repository.createOrUpdateCollection(collection.id, "Modifiée", "ANY", listOf(rule)) }.isFailure)
        }
        assertEquals(collection, dao.observeCollections().first().single())
        assertEquals(listOf(original), dao.observeCollectionRules().first())
    }
}
