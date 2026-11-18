package fr.astragames.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameEdits
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkLibraryActionsTest {
    private lateinit var db: AstraDatabase
    private lateinit var dao: AstraDao
    private lateinit var repository: GameRepository
    private val tagWrites = AtomicInteger()
    private val largestTagWrite = AtomicInteger()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queries = RoomDatabase.QueryCallback { sql, args ->
            if (sql.trimStart().startsWith("INSERT", ignoreCase = true) && sql.contains("game_tags", ignoreCase = true)) {
                tagWrites.incrementAndGet()
                largestTagWrite.updateAndGet { maxOf(it, args.size) }
            }
        }
        db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java)
            .setQueryCallback(queries, Executor { it.run() }).build()
        dao = db.dao()
        repository = GameRepository(context, db, RecursiveSourceScanner(context, dao, FileAccessResolver(context)), BackupManager(context, db))
    }

    @After fun close() = db.close()

    private suspend fun games(count: Int): Set<String> = db.withTransaction {
        (1..count).mapTo(linkedSetOf()) { i ->
            val id = "game-$i"
            dao.insertGameRaw(GameEntity(id, "Jeu $i", documentUri = "content://bulk/$id", physicalPath = null,
                executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = "test",
                dateAdded = 1, lastModified = 1, fingerprint = id))
            id
        }
    }

    private suspend fun tags(count: Int): Set<String> = db.withTransaction {
        (1..count).mapTo(linkedSetOf()) { i ->
            val id = "tag-$i"
            dao.upsertTag(TagEntity(id, id, id))
            id
        }
    }

    @Test fun favoritesRollBackEarlierBatchesAndCanBeRetried() = runBlocking {
        val ids = games(901)
        dao.toggleFavorite("game-2")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_favorite BEFORE UPDATE OF favorite ON games WHEN OLD.id='game-901' BEGIN SELECT RAISE(ABORT, 'favorite refused'); END")
        try {
            repository.setGamesFavorite(ids, true)
            fail("Expected the last write to fail")
        } catch (_: SQLiteException) { }
        assertEquals(setOf("game-2"), dao.getAllGames().filter { it.favorite }.mapTo(hashSetOf()) { it.id })
        sql.execSQL("DROP TRIGGER refuse_favorite")
        repository.setGamesFavorite(ids, true)
        assertEquals(901, dao.getAllGames().count { it.favorite })
        assertTrue(runCatching { repository.setGamesFavorite(ids + "removed", false) }.isFailure)
        assertEquals(901, dao.getAllGames().count { it.favorite })
    }

    @Test fun tagFailureKeepsEveryOriginalAssociationUntilRetry() = runBlocking {
        val ids = games(901)
        tags(3)
        val previous = setOf(GameTagCrossRef("game-1", "tag-1"), GameTagCrossRef("game-3", "tag-3"))
        dao.insertGameTags(previous.toList())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_tag BEFORE INSERT ON game_tags WHEN NEW.gameId='game-901' AND NEW.tagId='tag-2' BEGIN SELECT RAISE(ABORT, 'tag refused'); END")
        val added = setOf("tag-1", "tag-2")
        try {
            repository.addTagsToGames(ids, added)
            fail("Expected the storage refusal")
        } catch (_: SQLiteException) { }
        assertEquals(previous, dao.observeGameTagRefs().first().toSet())
        sql.execSQL("DROP TRIGGER refuse_tag")
        repeat(2) { repository.addTagsToGames(ids, added) }
        assertEquals(previous + ids.flatMap { id -> added.map { GameTagCrossRef(id, it) } }, dao.observeGameTagRefs().first().toSet())
    }

    @Test fun removedGamesAndTagsCannotCreateDanglingAssociations() = runBlocking {
        val ids = games(2)
        val labels = tags(2)
        dao.addGameTag(GameTagCrossRef("game-1", "tag-1"))
        dao.deleteGameCompletely("game-2")
        assertTrue(runCatching { repository.addTagsToGames(ids, labels) }.isFailure)
        dao.deleteTags(listOf("tag-2"))
        assertTrue(runCatching { repository.addTagsToGames(setOf("game-1"), labels) }.isFailure)
        assertEquals(listOf(GameTagCrossRef("game-1", "tag-1")), dao.observeGameTagRefs().first())
    }

    @Test fun staleTagsDoNotReplaceManualAssignmentsOrSaveHalfAnEdit() = runBlocking {
        games(1)
        tags(2)
        dao.addGameTag(GameTagCrossRef("game-1", "tag-1"))
        dao.deleteTags(listOf("tag-2"))
        val edits = GameEdits("Titre modifié", null, null, null, null, null, null, null)
        assertTrue(runCatching { repository.setGameTags("game-1", setOf("tag-2")) }.isFailure)
        assertTrue(runCatching { repository.updateGame("game-1", edits, selectedTags = setOf("tag-2")) }.isFailure)
        assertEquals("Jeu 1", dao.getGame("game-1")!!.title)
        assertEquals(listOf("tag-1"), dao.getTagIdsForGame("game-1"))
        repository.setGameTags("game-1", emptySet())
        assertTrue(dao.getTagIdsForGame("game-1").isEmpty())
    }

    @Test fun aTypicalBulkAdditionUsesOneInsertForAllGameTagPairs() = runBlocking {
        val ids = games(64)
        val labels = tags(5)
        tagWrites.set(0)
        repository.addTagsToGames(ids, labels)
        assertEquals(320, dao.observeGameTagRefs().first().size)
        Log.i("AstraBulkActions", "gameTags inserts=${tagWrites.get()} games=64 tags=5")
        assertEquals(1, tagWrites.get())
    }

    @Test fun savingTagChangesPreservesConcurrentAdditionsAndRemovals() = runBlocking {
        games(1)
        tags(4)
        val original = setOf("tag-1", "tag-2")
        repository.setGameTags("game-1", original)
        dao.removeGameTag("game-1", "tag-1")
        dao.addGameTag(GameTagCrossRef("game-1", "tag-3"))
        val selection = setOf("tag-1", "tag-4")
        repeat(2) { repository.setGameTags("game-1", selection, previous = original) }
        assertEquals(setOf("tag-3", "tag-4"), dao.getTagIdsForGame("game-1").toSet())
        repository.updateGame("game-1", GameEdits("Configuré", null, null, null, null, null, null, null),
            selectedTags = selection, previousTags = original)
        assertEquals("Configuré", dao.getGame("game-1")!!.title)
        assertEquals(setOf("tag-3", "tag-4"), dao.getTagIdsForGame("game-1").toSet())
    }

    @Test fun bothSelectionSizesRespectTheSqliteParameterBudget() = runBlocking {
        val ids = games(1003)
        val labels = tags(1001)
        val fewTags = labels.take(3).toSet()
        val fewGames = ids.take(2).toSet()
        repository.addTagsToGames(ids, fewTags)
        repository.addTagsToGames(fewGames, labels)
        val refs = dao.observeGameTagRefs().first()
        assertEquals(ids.size * fewTags.size + fewGames.size * (labels.size - fewTags.size), refs.size)
        assertEquals(labels, dao.getTagIdsForGame("game-1").toSet())
        assertEquals(fewTags, dao.getTagIdsForGame("game-1003").toSet())
        assertTrue(largestTagWrite.get() <= 999)
    }
}
