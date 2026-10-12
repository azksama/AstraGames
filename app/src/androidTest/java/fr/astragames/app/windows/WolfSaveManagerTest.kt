package fr.astragames.app.windows

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.saves.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Android provider/Room/backup integration; codec input is an authored golden fixture. */
class WolfSaveManagerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun original() = InstrumentationRegistry.getInstrumentation().context.assets.open("format-008e-utf8.sav").use { it.readBytes() }
    private fun game(id: String, uri: String) = GameEntity(id = id, title = "Wolf save test", documentUri = uri,
        physicalPath = null, executableName = "Game.exe", engine = "WOLF_RPG", launcher = "WOLF_RUNTIME",
        sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)

    @Test fun privateCopyEditsPublishToSourceAndBackupsRestoreOnlyTheSelectedGame() = runBlocking {
        val id = "wolf-save-manager-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { mkdirs() }
        val storage = WolfGameStorage(context, id, Uri.fromFile(source).toString(), preferDirect = false)
        val database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        try {
            File(source, "Game.exe").writeText("authored fixture")
            File(source, "Save").mkdirs()
            val bytes = original()
            File(source, "Save/SaveData01.sav").writeBytes(bytes)
            storage.prepare("Game.exe") { }
            val game = game(id, Uri.fromFile(source).toString())
            database.dao().upsertGame(game)
            val manager = SaveManager(context, database.dao(), SaveFinder(context))
            val save = manager.listSaves(game).single()
            assertTrue(save.uri.contains("wolf-games/"))
            val entry = manager.readSave(game, save).second.first { it.editable }
            val revision = sha256Hex(bytes)
            val backup = manager.writeSave(game, save, listOf(SaveEdit(entry.path, entry.type, "123456")), revision)
            assertEquals("123456", manager.readSave(game, save).second.single { it.path == entry.path }.displayValue)
            assertArrayEquals(readBytes(context, save.uri), File(source, "Save/SaveData01.sav").readBytes())
            assertArrayEquals(bytes, readBytes(context, backup.backupUri))
            assertTrue(runCatching { manager.writeSave(game, save, listOf(SaveEdit(entry.path, entry.type, "9")), revision) }.isFailure)
            manager.restoreBackup(backup)
            assertArrayEquals(bytes, readBytes(context, save.uri))
            assertArrayEquals(bytes, File(source, "Save/SaveData01.sav").readBytes())
            val unrelated = File(context.filesDir, "$id-unrelated.sav").apply { writeBytes(bytes) }
            try {
                database.dao().upsertSaveLocation(GameSaveLocationEntity("forged-$id", id, Uri.fromFile(context.filesDir).toString(), "CUSTOM", "forged", false, true, 1))
                val forged = save.copy(uri = Uri.fromFile(unrelated).toString())
                assertTrue(runCatching { manager.readSave(game, forged) }.isFailure)
                assertTrue(runCatching { manager.writeSave(game, forged, listOf(SaveEdit(entry.path, entry.type, "9"))) }.isFailure)
                assertArrayEquals(bytes, unrelated.readBytes())
            } finally { unrelated.delete() }
            database.dao().getSaveBackupsForSource(save.uri).forEach { manager.deleteBackup(it) }
        } finally { database.close(); storage.closeMount(); storage.directory.deleteRecursively(); source.deleteRecursively() }
    }

    @Test fun safExportHasStableIdentityThroughEditPublicationAndBackupRestore() = runBlocking {
        val id = "wolf-save-saf-${UUID.randomUUID()}"
        val provider = Uri.parse("content://fr.astragames.app.test.storage")
        val uri = context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri")!!
        val source = File(context.filesDir, "fixtures/$id")
        val storage = WolfGameStorage(context, id, uri)
        val database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        try {
            File(source, "Game.exe").writeText("authored fixture")
            File(source, "Save").mkdirs()
            val bytes = original(); val sourceSave = File(source, "Save/SaveData01.sav").apply { writeBytes(bytes) }
            storage.prepare("Game.exe") { }; assertTrue(storage.isSaf); storage.closeMount()
            val game = game(id, uri); database.dao().upsertGame(game)
            val manager = SaveManager(context, database.dao(), SaveFinder(context))
            val save = manager.listSaves(game).single { Uri.parse(it.uri).scheme == "file" }
            val entry = manager.readSave(game, save).second.first { it.editable }
            val backup = manager.writeSave(game, save, listOf(SaveEdit(entry.path, entry.type, "654321")), sha256Hex(bytes))
            assertEquals(save.uri, manager.listSaves(game).single { Uri.parse(it.uri).scheme == "file" }.uri)
            assertEquals("654321", SaveCodec.entries(SaveCodec.read("WOLF_RPG", sourceSave.readBytes())).single { it.path == entry.path }.displayValue)
            manager.restoreBackup(backup)
            assertArrayEquals(bytes, sourceSave.readBytes())
            database.dao().getSaveBackupsForSource(save.uri).forEach { manager.deleteBackup(it) }
        } finally {
            database.close(); storage.closeMount(); storage.directory.deleteRecursively()
            context.contentResolver.call(provider, "removeFixture", id, null)
        }
    }
}
