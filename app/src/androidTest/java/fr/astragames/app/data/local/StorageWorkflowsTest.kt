package fr.astragames.app.data.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.mods.ModsManager
import fr.astragames.app.data.saves.*
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

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

    private fun game(folder: DocumentFile, engine: String = "RENPY") = game(root).copy(
        documentUri = folder.uri.toString(), physicalPath = null, engine = engine
    )

    private suspend fun withSafFixture(block: suspend (DocumentFile) -> Unit) {
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        try {
            val result = requireNotNull(context.contentResolver.call(provider, "fixture", id, null))
            val uri = Uri.parse(requireNotNull(result.getString("uri")))
            block(requireNotNull(documentDir(context, uri)))
        } finally {
            context.contentResolver.call(provider, "removeFixture", id, null)
        }
    }

    @Test fun rescanningKeepsBothMetadataSources() = runTest {
        val folder = File(root, "metadata-rescan").apply { mkdirs() }
        File(folder, "Game.exe").writeText("fixture")
        val dao = database.dao()
        val original = game(folder).copy(f95Url = "https://f95zone.to/threads/42/", ryuugamesUrl = "https://www.ryuugames.com/example/")
        dao.upsertGame(original)
        dao.upsertSource(GameSourceEntity(original.sourceId, "Fixture", Uri.fromFile(folder).toString()))
        RecursiveSourceScanner(context, dao, FileAccessResolver(context)).scan(original.sourceId)
        val scanned = dao.getGame(original.id)!!
        assertEquals(original.f95Url, scanned.f95Url)
        assertEquals(original.ryuugamesUrl, scanned.ryuugamesUrl)
        assertFalse(scanned.missing)
    }

    @Test fun cancellationDuringScanReconciliationFinishesTheSourceAndHistory() = runTest {
        val folder = File(root, "scan-root").apply { mkdirs() }
        val dao = database.dao()
        val game = game(folder)
        dao.upsertGame(game)
        dao.upsertSource(GameSourceEntity(game.sourceId, "Test", Uri.fromFile(folder).toString()))
        val scanner = RecursiveSourceScanner(context, dao, FileAccessResolver(context))

        val failure = runCatching {
            scanner.scan(game.sourceId) { progress ->
                if (progress.phase == "Vérification des jeux déplacés ou supprimés") throw CancellationException("Cancelled after traversal")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        val source = dao.getSource(game.sourceId)!!
        assertEquals("PARTIAL", source.lastScanStatus)
        assertEquals("Scan interrompu", source.lastError)
        assertNotNull(source.lastScanAt)
        assertEquals(false, dao.getGame(game.id)?.missing)
        val history = dao.getLatestScanHistory(game.sourceId)!!
        assertNotNull(history.finishedAt)
        assertEquals(1, history.gamesFound)
    }

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
        withSafFixture { documents ->
            val game = game(requireNotNull(documents.findFile("game")), "RPG_MAKER_MV")
            database.dao().upsertGame(game)
            val manager = SaveManager(context, database.dao(), SaveFinder(context))
            val save = manager.listSaves(game).single()
            val original = readBytes(context, save.uri)
            val openedRevision = sha256Hex(original)
            val backup = manager.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "1")), openedRevision)
            val edited = readBytes(context, save.uri)
            assertEquals(1, org.json.JSONObject(edited.toString(Charsets.UTF_8)).getJSONObject("party").getInt("gold"))
            val staleFailure = runCatching {
                manager.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "2")), openedRevision)
            }.exceptionOrNull()
            assertTrue(staleFailure is IllegalStateException)
            assertTrue(staleFailure?.message.orEmpty().contains("change depuis son ouverture"))
            assertArrayEquals(edited, readBytes(context, save.uri))
            assertEquals(1, database.dao().getSaveBackupsForSource(save.uri).size)

            manager.restoreBackup(backup)
            assertArrayEquals(original, readBytes(context, save.uri))
            val history = database.dao().getSaveBackupsForSource(save.uri)
            assertEquals(2, history.size)
            val beforeRestore = history.single { it.id != backup.id }
            assertArrayEquals(edited, readBytes(context, beforeRestore.backupUri))
        }
    }

    @Test fun saveWriteRejectsPrivateAppFilesWithoutChangingThemOrCreatingBackups() = runTest {
        val folder = File(context.filesDir, "rpg/save").apply { mkdirs() }
        val original = """{"party":{"gold":12345}}""".toByteArray()
        val file = File(folder, "file1.rpgsave").apply { writeBytes(original) }
        val game = game(requireNotNull(folder.parentFile)).copy(engine = "RPG_MAKER_MV")
        database.dao().upsertGame(game)
        val manager = SaveManager(context, database.dao(), SaveFinder(context))
        val save = manager.listSaves(game).single()
        val failure = runCatching {
            manager.writeSave(game, save, listOf(SaveEdit("root.party.gold", SaveEntryType.INT, "1")))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("fichier interne Astra"))
        assertArrayEquals(original, file.readBytes())
        assertTrue(database.dao().getSaveBackupsForSource(save.uri).isEmpty())
    }

    @Test fun nestedSafDocumentsSupportSaveEditingAndMods() = runTest {
        withSafFixture { documents ->
            val gameFolder = documents.findFile("game")!!
            val game = game(gameFolder, "RPG_MAKER_MV")
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
        }
    }

    @Test fun versionEightCatalogueRoundTripIncludesSaveBackups() = runTest {
        withSafFixture { documents ->
            val gameFolder = requireNotNull(documents.findFile("game"))
            val saveFolder = requireNotNull(gameFolder.findFile("save"))
            val sourceSave = requireNotNull(saveFolder.findFile("file1.rpgsave"))
            val original = readBytes(context, sourceSave.uri.toString())
            val game = game(gameFolder, "RPG_MAKER_MV")
            val dao = database.dao()
            dao.upsertGame(game)
            dao.upsertSaveLocation(GameSaveLocationEntity("loc", game.id, saveFolder.uri.toString(), "CUSTOM", "test", false, true, 1))
            val backupFile = File(context.filesDir, "save-backups/test-game/test.save").apply {
                requireNotNull(parentFile).mkdirs(); writeBytes(original)
            }
            dao.insertSaveBackup(SaveBackupEntity("b", game.id, sourceSave.uri.toString(), "file1.rpgsave", Uri.fromFile(backupFile).toString(), 1, original.size.toLong()))
            val exportDir = File(root, "exports").apply { mkdirs() }
            val manager = BackupManager(context, database)
            val name = manager.create(Uri.fromFile(exportDir))
            val archive = File(exportDir, name)
            assertEquals("AST2", archive.inputStream().use { input -> String(ByteArray(4).also { assertEquals(4, input.read(it)) }, Charsets.US_ASCII) })
            dao.deleteGameCompletely(game.id)
            backupFile.writeText("modified")
            manager.restore(Uri.fromFile(archive))
            assertEquals(game.title, dao.getGame(game.id)!!.title)
            assertEquals(game.documentUri, dao.getGame(game.id)!!.documentUri)
            assertNull(dao.getGame(game.id)!!.physicalPath)
            assertEquals(saveFolder.uri.toString(), dao.getSaveLocations(game.id).single().uri)
            assertEquals(1, dao.getSaveBackupsForSource(sourceSave.uri.toString()).size)
            assertArrayEquals(original, backupFile.readBytes())
            assertEquals(game.id, dao.searchGames("Test*").first().single().id)
        }
    }
}
