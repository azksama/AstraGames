package fr.astragames.app.data.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.mods.ModsManager
import fr.astragames.app.data.saves.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StorageWorkflowsTest {
    private lateinit var context: Context
    private lateinit var database: AstraDatabase
    private lateinit var root: File
    private lateinit var databaseName: String

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "workflow-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        databaseName = "workflow-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(app, AstraDatabase::class.java, databaseName).build()
    }

    @After fun cleanup() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    private fun game(folder: File) = GameEntity(
        id = "test-game", title = "Test", documentUri = Uri.fromFile(folder).toString(), physicalPath = folder.path,
        engine = "RENPY", launcher = "JOIPLAY", sourceId = "test-source", dateAdded = 1, lastModified = 1, executableName = "Game.exe", fingerprint = "test"
    )

    @Test fun importInstallConflictAndUninstallRestoreOriginalFiles() = runTest {
        val folder = File(root, "game-root/game").apply { mkdirs() }
        val original = File(folder, "script.rpy").apply { writeText("original script with a long tail") }
        val game = game(folder.parentFile!!)
        database.dao().upsertGame(game)
        val repo = File(root, "mods").apply { mkdirs() }
        val archive = File(root, "mod.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("wrapper/astra-mod.json" to """{"id":"test.mod","name":"Test mod","engines":["RenPy"],"target":"game","filesRoot":"files"}""",
                "wrapper/files/script.rpy" to "mod", "wrapper/files/new.rpy" to "new").forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val manager = ModsManager(context, database.dao())
        val mod = manager.importZip(Uri.fromFile(archive), Uri.fromFile(repo).toString(), "RENPY", false)
        assertTrue(mod.hasManifest)
        val installation = manager.install(game, mod)
        assertEquals("mod", original.readText())
        assertTrue(File(folder, "new.rpy").exists())
        original.writeText("user change")
        assertEquals(1, manager.uninstall(installation).size)
        assertTrue(File(folder, "new.rpy").exists()) // No partial uninstall before reporting conflicts.
        assertTrue(manager.uninstall(installation, true).isEmpty())
        assertEquals("original script with a long tail", original.readText())
        assertFalse(File(folder, "new.rpy").exists())
    }

    @Test fun saveWriteCreatesBackupAndRejectsStaleEditor() = runTest {
        val folder = File(root, "rpg/save").apply { mkdirs() }
        val file = File(folder, "file1.rpgsave").apply { writeText("""{"party":{"gold":12345}}""") }
        val game = game(folder.parentFile!!).copy(engine = "RPG_MAKER_MV")
        database.dao().upsertGame(game)
        val manager = SaveManager(context, database.dao(), SaveFinder(context))
        val save = manager.listSaves(game).single()
        val backup = manager.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "1")))
        assertEquals(1, org.json.JSONObject(file.readText()).getJSONObject("party").getInt("gold"))
        assertTrue(runCatching { manager.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "2")), "stale") }.isFailure)
        manager.restoreBackup(backup)
        assertEquals(12345, org.json.JSONObject(file.readText()).getJSONObject("party").getInt("gold"))
        assertEquals(2, database.dao().getSaveBackupsForSource(save.uri).size)
    }

    @Test fun nestedSafDocumentsSupportSaveEditingAndMods() = runTest {
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        val uri = Uri.parse(context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri"))
        try {
            val documents = documentDir(context, uri)!!
            val gameFolder = documents.findFile("game")!!
            val game = game(root).copy(documentUri = gameFolder.uri.toString(), engine = "RPG_MAKER_MV")
            database.dao().upsertGame(game)
            val saves = SaveManager(context, database.dao(), SaveFinder(context))
            val save = saves.listSaves(game).single()
            saves.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "3")))
            assertEquals("3", saves.readSave(game, save).second.first { it.path == "root.party.gold" }.displayValue)
            val modFolder = documents.findFile("mod")!!
            val mod = ModEntity("saf-mod", "saf-mod", "SAF", null, null, null, game.engine, modFolder.uri.toString(), false, "OVERLAY", ".", "files", 1)
            database.dao().upsertMod(mod)
            val mods = ModsManager(context, database.dao())
            val installation = mods.install(game, mod)
            val script = gameFolder.findFile("script.rpy")!!
            assertEquals("mod", context.contentResolver.openInputStream(script.uri)!!.bufferedReader().use { it.readText() })
            mods.uninstall(installation)
            assertEquals("original", context.contentResolver.openInputStream(script.uri)!!.bufferedReader().use { it.readText() })
        } finally {
            context.contentResolver.call(provider, "removeFixture", id, null)
        }
    }

    @Test fun versionEightCatalogueRoundTripIncludesSaveBackups() = runTest {
        val game = game(File(root, "game").apply { mkdirs() })
        val dao = database.dao()
        dao.upsertGame(game)
        dao.upsertSaveLocation(GameSaveLocationEntity("loc", game.id, game.documentUri, "CUSTOM", "test", false, true, 1))
        val backupFile = File(context.filesDir, "save-backups/test-game/test.save").apply { parentFile!!.mkdirs(); writeText("original") }
        dao.insertSaveBackup(SaveBackupEntity("b", game.id, game.documentUri, "test.save", Uri.fromFile(backupFile).toString(), 1, 8))
        val exportDir = File(root, "exports").apply { mkdirs() }
        val manager = BackupManager(context, database)
        val name = manager.create(Uri.fromFile(exportDir))
        dao.deleteGameCompletely(game.id)
        backupFile.writeText("modified")
        manager.restore(Uri.fromFile(File(exportDir, name)))
        assertEquals(game.title, dao.getGame(game.id)!!.title)
        assertEquals(1, dao.getSaveLocations(game.id).size)
        assertEquals(1, dao.getSaveBackupsForSource(game.documentUri).size)
        assertEquals("original", backupFile.readText())
        assertEquals(game.id, dao.searchGames("Test*").first().single().id)
    }
}
