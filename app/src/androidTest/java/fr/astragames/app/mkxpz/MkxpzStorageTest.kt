package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.CompatibilitySeverity
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.data.saves.SaveManager
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.launcher.CompatibilityDiagnostic
import fr.astragames.app.launcher.JoiPlayLauncher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Real Android files/providers and Room; only uniquely named test fixtures are removed. */
class MkxpzStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AstraDatabase
    private lateinit var manager: SaveManager
    private val ownedDirectories = mutableListOf<File>()
    private val ownedSymlinks = mutableListOf<Path>()

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        manager = SaveManager(context, database.dao(), SaveFinder(context))
    }

    @After fun removeOnlyTestData() {
        database.close()
        // Unlink before walking either tree; never traverse another game's target.
        ownedSymlinks.forEach { Files.deleteIfExists(it) }
        ownedDirectories.asReversed().forEach { directory ->
            val absolute = directory.canonicalFile.toPath()
            check(absolute.startsWith(context.filesDir.canonicalFile.toPath()) ||
                absolute.startsWith(context.cacheDir.canonicalFile.toPath()))
            check(directory != context.filesDir && directory != context.cacheDir)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private data class Fixture(val source: File, val game: GameEntity, val storage: MkxpzStorage)

    private suspend fun fixture(withIni: Boolean = true): Fixture {
        val id = "mkxpz-storage-test-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdirs()) }
        val storage = MkxpzStorage(context, id)
        ownedDirectories += source
        ownedDirectories += storage.directory
        ownedDirectories += File(context.filesDir, "save-backups/" + sha256Hex(id.toByteArray(Charsets.UTF_8)))
        if (withIni) writeIni(source)
        File(source, "Data").mkdirs()
        File(source, "Data/asset.dat").writeText("original authored asset")
        val game = GameEntity(id = id, title = "MKXP-Z storage fixture", documentUri = Uri.fromFile(source).toString(),
            physicalPath = source.absolutePath, executableName = "Game.exe", engine = "RPG_MAKER_XP",
            launcher = MkxpzRuntime.LAUNCHER, sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)
        database.dao().upsertGame(game)
        return Fixture(source, game, storage)
    }

    private fun writeIni(source: File) {
        File(source, "Game.ini").writeText("[Game]\nLibrary=RGSS104E.dll\nScripts=Data/Scripts.rxdata\nTitle=Authored fixture\n")
    }

    private fun saveBytes(value: Long) = Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(value))))
    private fun saveFile(directory: File) = File(directory, "Save01.rxdata")

    @Test fun repeatedPreparationPreservesPrivateProgressAndDoesNotPublishToSource() = runBlocking {
        val fixture = fixture()
        val original = saveBytes(11)
        saveFile(fixture.source).writeBytes(original)
        val bootstrap = fixture.storage.prepare(fixture.game, null, 1)
        assertTrue(fixture.storage.isImported)
        assertArrayEquals(original, saveFile(fixture.storage.gameDirectory).readBytes())
        val config = JSONObject(File(bootstrap, "mkxp.json").readText())
        assertEquals(fixture.storage.gameDirectory.absolutePath, config.getString("gameFolder"))
        assertEquals(1, config.getInt("rgssVersion"))
        assertEquals(0, config.getJSONArray("RTP").length())

        val privateProgress = saveBytes(42)
        saveFile(fixture.storage.gameDirectory).writeBytes(privateProgress)
        // A source modified by another launcher must not replace local progress on relaunch.
        val externalProgress = saveBytes(99)
        saveFile(fixture.source).writeBytes(externalProgress)
        File(fixture.source, "Data/asset.dat").writeText("external asset revision")
        val reopened = MkxpzStorage(context, fixture.game.id)
        assertEquals(bootstrap, reopened.prepare(fixture.game, null, 1))
        assertArrayEquals(privateProgress, saveFile(reopened.gameDirectory).readBytes())
        assertArrayEquals(externalProgress, saveFile(fixture.source).readBytes())
        assertEquals("original authored asset", File(reopened.gameDirectory, "Data/asset.dat").readText())
        assertEquals("external asset revision", File(fixture.source, "Data/asset.dat").readText())
    }

    @Test fun failedImportResumesMissingFilesWithoutReplacingExistingPrivateSave() = runBlocking {
        val fixture = fixture(withIni = false)
        val original = saveBytes(7)
        saveFile(fixture.source).writeBytes(original)
        val failure = runCatching { fixture.storage.prepare(fixture.game, null, 1) }.exceptionOrNull()
        assertNotNull("Missing Game.ini must fail the import", failure)
        assertTrue(failure!!.message.orEmpty().contains("Game.ini"))
        assertFalse("A partial copy must not be certified complete", fixture.storage.isImported)
        assertArrayEquals(original, saveFile(fixture.storage.gameDirectory).readBytes())

        val retainedProgress = saveBytes(31)
        saveFile(fixture.storage.gameDirectory).writeBytes(retainedProgress)
        writeIni(fixture.source)
        File(fixture.source, "Data/new-asset.dat").writeText("asset supplied before retry")
        val reopened = MkxpzStorage(context, fixture.game.id)
        reopened.prepare(fixture.game, null, 1)
        assertTrue(reopened.isImported)
        assertTrue(File(reopened.gameDirectory, "Game.ini").isFile)
        assertEquals("asset supplied before retry", File(reopened.gameDirectory, "Data/new-asset.dat").readText())
        assertArrayEquals(retainedProgress, saveFile(reopened.gameDirectory).readBytes())
        assertArrayEquals(original, saveFile(fixture.source).readBytes())
        assertFalse(reopened.directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun privateSaveCanBeDiscoveredEditedAndRestoredAfterSourceDisappears() = runBlocking {
        val fixture = fixture()
        fixture.storage.prepare(fixture.game, null, 1)
        val original = saveBytes(16)
        val privateFile = saveFile(fixture.storage.gameDirectory).apply { writeBytes(original) }
        assertTrue(fixture.source.deleteRecursively())

        val reopened = MkxpzStorage(context, fixture.game.id)
        assertTrue(File(reopened.prepare(fixture.game, null, 1), "mkxp.json").isFile)
        assertFalse("A warm import must not recreate the absent source", fixture.source.exists())
        val report = CompatibilityDiagnostic(JoiPlayLauncher()).inspect(context, fixture.game, null)
        assertFalse("Source/entry checks must accept the imported game: $report", report.checks.any {
            it.label in setOf("Dossier", "Fichier d'entrée") && it.severity == CompatibilitySeverity.ERROR
        })
        val save = manager.listSaves(fixture.game).single()
        assertEquals(Uri.fromFile(privateFile).toString(), save.uri)
        val entry = manager.readSave(fixture.game, save).second.single { it.editable }
        assertEquals("16", entry.displayValue)
        val backup = manager.writeSave(fixture.game, save,
            listOf(SaveEdit(entry.path, entry.type, "54")), sha256Hex(original))
        assertEquals("54", manager.readSave(fixture.game, save).second.single { it.editable }.displayValue)
        assertArrayEquals(original, File(requireNotNull(Uri.parse(backup.backupUri).path)).readBytes())
        manager.restoreBackup(backup)
        assertArrayEquals(original, privateFile.readBytes())
        assertFalse(fixture.source.exists())
    }

    @Test fun readEditAndRestoreRefuseOtherGamePrivateFilesAndEscapingSymlinks() = runBlocking {
        val owner = fixture()
        val other = fixture()
        owner.storage.prepare(owner.game, null, 1)
        other.storage.prepare(other.game, null, 1)
        val original = saveBytes(12)
        val otherBytes = saveBytes(87)
        saveFile(owner.storage.gameDirectory).writeBytes(original)
        val otherFile = saveFile(other.storage.gameDirectory).apply { writeBytes(otherBytes) }
        val ownerSave = manager.listSaves(owner.game).single()
        val entry = manager.readSave(owner.game, ownerSave).second.single { it.editable }
        val edit = listOf(SaveEdit(entry.path, entry.type, "999"))
        val backup = manager.writeSave(owner.game, ownerSave,
            listOf(SaveEdit(entry.path, entry.type, "13")), sha256Hex(original))

        val unrelatedDir = File(context.filesDir, "mkxpz-unrelated-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        ownedDirectories += unrelatedDir
        val unrelated = saveFile(unrelatedDir).apply { writeBytes(otherBytes) }
        // Even a forged enabled catalogue location cannot authorize an arbitrary private file.
        for (folder in listOf(other.storage.gameDirectory, unrelatedDir)) {
            database.dao().upsertSaveLocation(GameSaveLocationEntity(UUID.randomUUID().toString(), owner.game.id,
                Uri.fromFile(folder).toString(), "CUSTOM", "forged fixture location", false, true, 1))
        }
        val link = File(owner.storage.gameDirectory, "Save99.rxdata").toPath()
        Files.createSymbolicLink(link, otherFile.toPath())
        ownedSymlinks.add(link)
        assertTrue(Files.isSymbolicLink(link))

        for (target in listOf(otherFile, unrelated, link.toFile())) {
            val forged = ownerSave.copy(uri = Uri.fromFile(target).toString())
            val readFailure = runCatching { manager.readSave(owner.game, forged) }.exceptionOrNull()
            assertTrue("Reading $target must fail authorization, not decoding: $readFailure", readFailure is IllegalArgumentException)
            val writeFailure = runCatching { manager.writeSave(owner.game, forged, edit) }.exceptionOrNull()
            assertTrue("Editing $target must fail authorization: $writeFailure", writeFailure is IllegalArgumentException)
            val restoreFailure = runCatching { manager.restoreBackup(backup.copy(sourceUri = forged.uri)) }.exceptionOrNull()
            assertTrue("Restoring over $target must fail authorization: $restoreFailure", restoreFailure is IllegalArgumentException)
            assertTrue(database.dao().getSaveBackupsForSource(forged.uri).isEmpty())
        }
        assertArrayEquals(otherBytes, otherFile.readBytes())
        assertArrayEquals(otherBytes, unrelated.readBytes())
        assertEquals("13", manager.readSave(owner.game, ownerSave).second.single { it.editable }.displayValue)
        manager.restoreBackup(backup)
        assertArrayEquals(original, saveFile(owner.storage.gameDirectory).readBytes())
    }
}
