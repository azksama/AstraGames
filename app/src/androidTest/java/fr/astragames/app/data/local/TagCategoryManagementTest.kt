package fr.astragames.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
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
class TagCategoryManagementTest {
    private lateinit var db: AstraDatabase
    private lateinit var dao: AstraDao
    private lateinit var repository: GameRepository
    private val original = TagCategoryEntity("Romans", "Romans", "romans", 0)
    private val second = TagCategoryEntity("second", "Séries", "series", 1)
    private val firstTag = TagEntity("a", "Aventure", "aventure", "Romans")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        dao = db.dao()
        repository = GameRepository(context, db, RecursiveSourceScanner(context, dao, FileAccessResolver(context)), BackupManager(context, db))
    }

    @After fun close() = db.close()

    @Test fun deletingAnOldSelectionUsesTheCurrentNameAndLeavesAReplacementAlone() = runBlocking {
        dao.upsertTagCategory(original)
        dao.upsertTag(firstTag)
        repository.renameTagCategory(original, "Fictions")
        val replacement = original.copy(id = "replacement", sortOrder = 1)
        dao.upsertTagCategory(replacement)
        val otherTag = TagEntity("b", "Mystère", "mystere", "Romans")
        dao.upsertTag(otherTag)
        repeat(2) { repository.deleteTagCategory(original) }
        assertEquals(listOf(replacement), dao.getTagCategories())
        assertEquals(setOf(firstTag.copy(groupName = null), otherTag), dao.getTags().toSet())
        assertTrue(dao.observeAuditEvents().first().single().detail.contains("Fictions"))
    }

    @Test fun refusedDeletionOrAuditPreservesTheCategoryAndItsTags() = runBlocking {
        dao.upsertTagCategory(original)
        dao.upsertTag(firstTag)
        val sql = db.openHelper.writableDatabase
        for (change in listOf("DELETE ON tag_categories", "INSERT ON audit_events")) {
            sql.execSQL("CREATE TRIGGER refuse_write BEFORE $change BEGIN SELECT RAISE(ABORT, 'refused'); END")
            assertTrue(runCatching { repository.deleteTagCategory(original) }.exceptionOrNull() is SQLiteException)
            assertEquals(listOf(original), dao.getTagCategories())
            assertEquals(listOf(firstTag), dao.getTags())
            assertTrue(dao.observeAuditEvents().first().isEmpty())
            sql.execSQL("DROP TRIGGER refuse_write")
        }
        repository.deleteTagCategory(original)
        assertTrue(dao.getTagCategories().isEmpty())
        assertNull(dao.getTags().single().groupName)
        assertEquals(1, dao.observeAuditEvents().first().size)
    }

    @Test fun bulkMoveRollsBackEveryBatchAndRejectsAVanishedTag() = runBlocking {
        dao.upsertTagCategory(original)
        val tags = (1..901).map { TagEntity("t-$it", "Tag $it", "tag $it") }
        dao.insertTags(tags)
        val ids = tags.mapTo(linkedSetOf()) { it.id }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_last BEFORE UPDATE OF groupName ON tags WHEN OLD.id='t-901' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        assertTrue(runCatching { repository.moveTagsToCategory(ids, original.id) }.exceptionOrNull() is SQLiteException)
        assertEquals(0, dao.getTags().count { it.groupName != null })
        sql.execSQL("DROP TRIGGER refuse_last")
        repository.moveTagsToCategory(ids, original.id)
        assertEquals(901, dao.getTags().count { it.groupName == "Romans" })
        assertTrue(runCatching { repository.moveTagsToCategory(ids + "missing", null) }.isFailure)
        assertEquals(901, dao.getTags().count { it.groupName == "Romans" })
    }

    @Test fun theChosenCategoryFollowsARenameAndCannotBeReplacedByANameTwin() = runBlocking {
        dao.upsertTagCategory(original)
        dao.upsertTag(firstTag.copy(groupName = null))
        repository.renameTagCategory(original, "Fictions")
        dao.upsertTagCategory(original.copy(id = "replacement", sortOrder = 1))
        repository.moveTagsToCategory(setOf(firstTag.id), original.id)
        assertEquals("Fictions", dao.getTags().single().groupName)
        repository.deleteTagCategory(original)
        assertTrue(runCatching { repository.moveTagsToCategory(setOf(firstTag.id), original.id) }.isFailure)
        assertTrue(runCatching { repository.createTag("Autre", original.id) }.isFailure)
        assertTrue(runCatching { repository.editTag(firstTag, "Modifié", original.id) }.isFailure)
        assertEquals(listOf(firstTag.copy(groupName = null)), dao.getTags())
    }

    @Test fun renamingATagPreservesItsLatestCategoryAndSupportsLegacyGroups() = runBlocking {
        dao.upsertTagCategory(original)
        dao.upsertTagCategory(second)
        dao.upsertTag(firstTag)
        repository.moveTagsToCategory(setOf(firstTag.id), second.id)
        repository.editTag(firstTag, "Épopée", original.id, categoryChanged = false)
        assertEquals("Séries", dao.getTags().single().groupName)
        assertTrue(repository.createTag("Arcade", second.id))
        assertEquals("Séries", dao.getTags().single { it.name == "Arcade" }.groupName)

        val legacy = TagEntity("old", "Ancien", "ancien", "Héritage")
        dao.upsertTag(legacy)
        repository.editTag(legacy, "Archive", null, categoryChanged = false)
        assertEquals("Héritage", dao.getTags().single { it.id == legacy.id }.groupName)
        repository.editTag(legacy, "Archive", null, categoryChanged = true)
        assertNull(dao.getTags().single { it.id == legacy.id }.groupName)
    }

    @Test fun deletingSelectedTagsKeepsGameLinksAndAuditConsistentOnFailureAndRetry() = runBlocking {
        val tags = listOf(firstTag, TagEntity("b", "Histoire", "histoire"))
        val game = GameEntity("game", "Jeu", documentUri = "content://category/game", physicalPath = null,
            executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = "test",
            dateAdded = 1, lastModified = 1, fingerprint = "game")
        dao.insertGameRaw(game)
        dao.insertTags(tags)
        val ids = tags.mapTo(linkedSetOf()) { it.id }
        dao.insertGameTags(ids.map { GameTagCrossRef(game.id, it) })
        assertTrue(runCatching { repository.deleteTags(ids + "missing") }.isFailure)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_audit BEFORE INSERT ON audit_events BEGIN SELECT RAISE(ABORT, 'refused'); END")
        assertTrue(runCatching { repository.deleteTags(ids) }.exceptionOrNull() is SQLiteException)
        assertEquals(tags.toSet(), dao.getTags().toSet())
        assertEquals(ids, dao.getTagIdsForGame(game.id).toSet())
        assertTrue(dao.observeAuditEvents().first().isEmpty())
        sql.execSQL("DROP TRIGGER refuse_audit")
        assertEquals(2, repository.deleteTags(ids))
        assertEquals(0, repository.deleteTags(ids))
        assertTrue(dao.getTags().isEmpty())
        assertTrue(dao.getTagIdsForGame(game.id).isEmpty())
        assertEquals("TAG_DELETED", dao.observeAuditEvents().first().single().type)
    }

    @Test fun aRejectedReorderKeepsBothPositionsUntilRetry() = runBlocking {
        dao.upsertTagCategory(original)
        dao.upsertTagCategory(second)
        val sql = db.openHelper.writableDatabase
        for (event in listOf("INSERT", "UPDATE")) {
            sql.execSQL("CREATE TRIGGER refuse_$event BEFORE $event ON tag_categories WHEN NEW.id='second' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        }
        assertTrue(runCatching { repository.moveTagCategory(original.id, 1) }.exceptionOrNull() is SQLiteException)
        assertEquals(listOf(original, second), dao.getTagCategories())
        sql.execSQL("DROP TRIGGER refuse_INSERT")
        sql.execSQL("DROP TRIGGER refuse_UPDATE")
        repository.moveTagCategory(original.id, 1)
        assertEquals(listOf(second.id, original.id), dao.getTagCategories().map { it.id })
    }

    @Test fun movingOneCategoryPreservesTheRestOfAnOlderOrderWithTies() = runBlocking {
        val ordered = listOf("A", "B", "C", "D").mapIndexed { i, name ->
            TagCategoryEntity(name, name, name.lowercase(), if (i < 3) 0 else 8)
        }
        ordered.forEach { dao.upsertTagCategory(it) }
        repository.moveTagCategory("C", -1)
        assertEquals(listOf("A", "C", "B", "D"), dao.getTagCategories().map { it.id })
        repository.moveTagCategory("A", -1)
        repository.moveTagCategory("D", -1)
        assertEquals(listOf("A", "C", "D", "B"), dao.getTagCategories().map { it.id })
    }

    @Test fun olderAccentNormalizationCannotCreateASecondEquivalentCategory() = runBlocking {
        val legacy = TagCategoryEntity("legacy", "Récits", "récits", 4)
        dao.upsertTagCategory(legacy)
        dao.upsertTagCategory(second)
        repository.createTagCategory("RECITS")
        assertEquals(setOf(legacy, second), dao.getTagCategories().toSet())
        assertTrue(runCatching { repository.renameTagCategory(second, "Récits") }.isFailure)
        assertEquals(setOf(legacy, second), dao.getTagCategories().toSet())
    }
}
