package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.*
import fr.astragames.app.mkxpz.MkxpzStorage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveBackupOwnershipTest {
    private fun state(value: Int) = byteArrayOf(4, 8, '['.code.toByte(), 6, 'i'.code.toByte(), (value + 5).toByte())

    @Test fun rejectsSubstitutedObjectsAndPersistedCrossGamePathsForRestoreAndDelete() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "backup-owner-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = File(root, "private").apply { mkdirs() } }
        val db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        try {
            val manager = SaveManager(context, db.dao(), SaveFinder(context))
            suspend fun fixture(id: String, value: Int): Pair<GameEntity, GameSave> {
                val game = GameEntity(id = id, title = id, documentUri = "content://fixture/$id", physicalPath = null,
                    executableName = "Game.exe", engine = "RPG_MAKER_VX_ACE", launcher = "ASTRA_MKXPZ", sourceId = id,
                    dateAdded = 1, lastModified = 1, fingerprint = id)
                db.dao().upsertGame(game)
                val save = File(MkxpzStorage(context, id).gameDirectory, "Save01.rvdata2").apply { parentFile!!.mkdirs(); writeBytes(state(value)) }
                return game to GameSave(Uri.fromFile(save).toString(), save.name, 1, game.engine, save.length(), 1)
            }
            val (gameA, saveA) = fixture("owner-a", 1)
            val (gameB, saveB) = fixture("owner-b", 2)
            suspend fun backup(game: GameEntity, save: GameSave) = manager.readSave(game, save).second.single { it.editable }.let { entry ->
                manager.writeSave(game, save, listOf(SaveEdit(entry.path, entry.type, "3")))
            }
            val a = backup(gameA, saveA)
            val b = backup(gameB, saveB)
            val bFile = File(requireNotNull(Uri.parse(b.backupUri).path))
            val originalA = readBytes(context, saveA.uri)
            val originalB = bFile.readBytes()
            for (forged in listOf(a.copy(backupUri = b.backupUri), b.copy(gameId = gameA.id, sourceUri = saveA.uri), a.copy(id = b.id))) {
                assertTrue(runCatching { manager.restoreBackup(forged) }.isFailure)
                assertTrue(runCatching { manager.deleteBackup(forged) }.isFailure)
                assertArrayEquals(originalA, readBytes(context, saveA.uri))
                assertArrayEquals(originalB, bFile.readBytes())
            }
            // A forged imported row must also fail; reloading the row alone is insufficient.
            val persisted = a.copy(id = "forged", backupUri = b.backupUri)
            db.dao().insertSaveBackup(persisted)
            assertTrue(runCatching { manager.restoreBackup(persisted) }.isFailure)
            assertTrue(runCatching { manager.deleteBackup(persisted) }.isFailure)
            assertArrayEquals(originalA, readBytes(context, saveA.uri))
            assertArrayEquals(originalB, bFile.readBytes())
            db.dao().deleteSaveBackup(persisted.id)
            manager.restoreBackup(a)
            assertArrayEquals(state(1), readBytes(context, saveA.uri))
            manager.deleteBackup(a)
            assertFalse(File(requireNotNull(Uri.parse(a.backupUri).path)).exists())
            assertArrayEquals(originalB, bFile.readBytes())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun rejectsOwnedSymlinkIncludingAnInRootLinkWithoutDeletingItsTarget() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "backup-link-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        val db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        try {
            val game = GameEntity(id = "link-owner", title = "Link", documentUri = "content://fixture/link", physicalPath = null,
                executableName = "Game.exe", engine = "RPG_MAKER_VX_ACE", launcher = "ASTRA_MKXPZ", sourceId = "source",
                dateAdded = 1, lastModified = 1, fingerprint = "link")
            db.dao().upsertGame(game)
            val target = File(SaveBackupPaths.directory(context, game.id), "target.backup").apply { parentFile!!.mkdirs(); writeBytes(state(1)) }
            val link = File(target.parentFile, "linked.backup")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val row = fr.astragames.app.data.local.SaveBackupEntity("link", game.id, "content://fixture/slot", "Save", Uri.fromFile(link).toString(), 1, target.length())
            db.dao().insertSaveBackup(row)
            val manager = SaveManager(context, db.dao(), SaveFinder(context))
            assertTrue(runCatching { manager.restoreBackup(row) }.isFailure)
            assertTrue(runCatching { manager.deleteBackup(row) }.isFailure)
            assertArrayEquals(state(1), target.readBytes())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            Files.delete(link.toPath())
        } finally { db.close(); root.deleteRecursively() }
    }
}
