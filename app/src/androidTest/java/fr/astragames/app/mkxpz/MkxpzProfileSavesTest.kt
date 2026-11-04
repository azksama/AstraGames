package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.SaveData
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveEntry
import fr.astragames.app.data.saves.SaveEntryType
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.data.saves.SaveManager
import fr.astragames.app.data.saves.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Profile corrections must reach save operations without rewriting the detected game engine. */
class MkxpzProfileSavesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AstraDatabase
    private lateinit var manager: SaveManager
    private val ownedDirectories = mutableListOf<File>()
    private val ownedSymlinks = mutableListOf<Path>()

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        manager = SaveManager(context, database.dao(), SaveFinder(context))
    }

    @After fun removeOnlyAuthoredFixtures() {
        if (::database.isInitialized) database.close()
        ownedSymlinks.forEach { Files.deleteIfExists(it) }
        ownedDirectories.asReversed().forEach { directory ->
            val canonical = directory.canonicalFile.toPath()
            check(canonical.startsWith(context.filesDir.canonicalFile.toPath()) ||
                canonical.startsWith(context.cacheDir.canonicalFile.toPath()))
            check(directory != context.filesDir && directory != context.cacheDir)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private data class Fixture(
        val game: GameEntity,
        val profile: LaunchProfileEntity,
        val source: File,
        val sourceSave: File,
        val storage: MkxpzStorage,
        val privateSave: File,
        val original: ByteArray
    )

    private fun saveBytes(value: Long) =
        Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(value))))

    private fun extension(engine: String) = when (engine) {
        "RPG_MAKER_XP" -> "rxdata"
        "RPG_MAKER_VX" -> "rvdata"
        "RPG_MAKER_VX_ACE" -> "rvdata2"
        else -> error("This test authors only RGSS fixtures")
    }

    private suspend fun fixture(detected: String, corrected: String, decoyEngine: String? = null): Fixture {
        val id = "mkxpz-profile-saves-test-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdirs()) }
        val storage = MkxpzStorage(context, id)
        ownedDirectories += source
        ownedDirectories += storage.directory
        ownedDirectories += File(context.filesDir, "save-backups/" + sha256Hex(id.toByteArray(Charsets.UTF_8)))
        val version = requireNotNull(MkxpzRuntime.rgssVersion(corrected))
        val library = when (version) {
            1 -> "RGSS104E.dll"
            2 -> "RGSS202E.dll"
            else -> "RGSS301.dll"
        }
        File(source, "Game.ini").writeText(
            "[Game]\nLibrary=$library\nScripts=Data/Scripts.${extension(corrected)}\nTitle=Authored profile fixture\n"
        )
        File(source, "Data").apply { check(mkdirs()) }
        val original = saveBytes(17)
        val sourceSave = File(source, "Save01.${extension(corrected)}").apply { writeBytes(original) }
        decoyEngine?.let { File(source, "Save02.${extension(it)}").writeBytes(saveBytes(901)) }
        val game = GameEntity(
            id = id, title = "MKXP-Z profile save fixture", documentUri = Uri.fromFile(source).toString(),
            physicalPath = source.absolutePath, executableName = "Game.exe", engine = detected,
            launcher = "JOIPLAY", sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id
        )
        val profile = LaunchProfileEntity(
            gameId = id, launcherType = MkxpzRuntime.LAUNCHER, engineOverride = corrected
        )
        database.dao().upsertGame(game)
        database.dao().upsertLaunchProfile(profile)
        storage.prepare(game, profile, version)
        val privateSave = File(storage.gameDirectory, sourceSave.name)
        assertArrayEquals("Import must carry the original authored save", original, privateSave.readBytes())
        return Fixture(game, profile, source, sourceSave, storage, privateSave, original)
    }

    private suspend fun privateSave(fixture: Fixture): GameSave {
        val matches = manager.listSaves(fixture.game).filter { save ->
            Uri.parse(save.uri).path?.let { File(it).canonicalFile == fixture.privateSave.canonicalFile } == true
        }
        assertEquals("The corrected profile must expose exactly this game's imported save", 1, matches.size)
        return matches.single()
    }

    private suspend fun editableEntry(fixture: Fixture, save: GameSave): SaveEntry {
        val (data, entries) = manager.readSave(fixture.game, save)
        assertTrue("An RGSS save must be decoded as Marshal", data is SaveData.RawSave)
        return entries.single { it.editable }.also { assertEquals(SaveEntryType.INT, it.type) }
    }

    private suspend fun assertDetectedEnginePreserved(fixture: Fixture) {
        assertEquals(fixture.game.engine, database.dao().getGame(fixture.game.id)?.engine)
        assertEquals("JOIPLAY", database.dao().getGame(fixture.game.id)?.launcher)
        assertEquals(MkxpzRuntime.LAUNCHER, database.dao().getLaunchProfile(fixture.game.id)?.launcherType)
    }

    private suspend fun assertRoundTrip(fixture: Fixture) {
        val locations = manager.detectLocations(fixture.game)
        val privateLocation = locations.single {
            Uri.parse(it.uri).path?.let { path -> File(path).canonicalFile == fixture.storage.gameDirectory.canonicalFile } == true
        }
        assertEquals("RPG_MAKER", privateLocation.type)
        assertTrue(privateLocation.autoDetected)
        val save = privateSave(fixture)
        assertEquals(fixture.profile.engineOverride, save.engine)
        assertEquals(fixture.privateSave.name, save.name)
        assertDetectedEnginePreserved(fixture)

        val entry = editableEntry(fixture, save)
        assertEquals("17", entry.displayValue)
        val backup = manager.writeSave(fixture.game, save,
            listOf(SaveEdit(entry.path, entry.type, "54")), sha256Hex(fixture.original))
        assertEquals("54", editableEntry(fixture, save).displayValue)
        assertEquals(fixture.game.id, backup.gameId)
        assertEquals(save.uri, backup.sourceUri)
        assertArrayEquals(fixture.original, File(requireNotNull(Uri.parse(backup.backupUri).path)).readBytes())
        assertArrayEquals("Editing the private copy must leave the original save intact",
            fixture.original, fixture.sourceSave.readBytes())
        assertDetectedEnginePreserved(fixture)

        // This path reloads games from Room: the stored engine remains UNKNOWN or incorrectly detected.
        manager.restoreBackup(backup)
        assertEquals("17", editableEntry(fixture, save).displayValue)
        assertArrayEquals(fixture.original, fixture.privateSave.readBytes())
        assertArrayEquals(fixture.original, fixture.sourceSave.readBytes())
        assertEquals("Restoration must preserve the edited state as a second backup",
            2, database.dao().getSaveBackupsForSource(save.uri).size)
        assertDetectedEnginePreserved(fixture)
    }

    @Test fun unknownCorrectedToXpDiscoversReadsEditsAndRestoresPrivateSave() = runBlocking {
        assertRoundTrip(fixture("UNKNOWN", "RPG_MAKER_XP"))
    }

    @Test fun unknownCorrectedToAceDiscoversReadsEditsAndRestoresPrivateSave() = runBlocking {
        assertRoundTrip(fixture("UNKNOWN", "RPG_MAKER_VX_ACE"))
    }

    @Test fun vxCorrectedToAceUsesAceExtensionAndCodecWithoutChangingDetectedEngine() = runBlocking {
        val fixture = fixture("RPG_MAKER_VX", "RPG_MAKER_VX_ACE", decoyEngine = "RPG_MAKER_VX")
        assertRoundTrip(fixture)
        val saves = manager.listSaves(fixture.game)
        assertTrue(saves.isNotEmpty())
        assertTrue("The old VX extension must not survive the corrected discovery",
            saves.all { it.engine == "RPG_MAKER_VX_ACE" && it.name.endsWith(".rvdata2") })
        assertArrayEquals(saveBytes(901), File(fixture.storage.gameDirectory, "Save02.rvdata").readBytes())
    }

    @Test fun changedProfileRejectsOpenSaveAndRestoreUntilMatchingProfileIsRestored() = runBlocking {
        // The initially correct detected engine makes this test reach the stale-profile bug before the fix.
        val fixture = fixture("RPG_MAKER_XP", "RPG_MAKER_XP")
        val opened = privateSave(fixture)
        val entry = editableEntry(fixture, opened)
        val backup = manager.writeSave(fixture.game, opened,
            listOf(SaveEdit(entry.path, entry.type, "23")), sha256Hex(fixture.original))
        val edited = fixture.privateSave.readBytes()
        val beforeBackups = database.dao().getSaveBackupsForSource(opened.uri).map { it.id }.toSet()
        database.dao().upsertLaunchProfile(fixture.profile.copy(engineOverride = "RPG_MAKER_VX_ACE"))

        val readFailure = runCatching { manager.readSave(fixture.game, opened) }.exceptionOrNull()
        assertTrue("An open save must not retain the old engine after a profile change: $readFailure",
            readFailure is IllegalArgumentException)
        val writeFailure = runCatching {
            manager.writeSave(fixture.game, opened,
                listOf(SaveEdit(entry.path, entry.type, "999")), sha256Hex(edited))
        }.exceptionOrNull()
        assertTrue("A stale engine must be refused before patching or creating a backup: $writeFailure",
            writeFailure is IllegalArgumentException)
        val restoreFailure = runCatching { manager.restoreBackup(backup) }.exceptionOrNull()
        assertTrue("Restore must reread the current profile, not only games.engine: $restoreFailure",
            restoreFailure is IllegalArgumentException)
        assertArrayEquals(edited, fixture.privateSave.readBytes())
        assertEquals(beforeBackups, database.dao().getSaveBackupsForSource(opened.uri).map { it.id }.toSet())
        assertArrayEquals(fixture.original, fixture.sourceSave.readBytes())

        database.dao().upsertLaunchProfile(fixture.profile)
        manager.restoreBackup(backup)
        assertArrayEquals(fixture.original, fixture.privateSave.readBytes())
        assertEquals("17", editableEntry(fixture, privateSave(fixture)).displayValue)
        assertDetectedEnginePreserved(fixture)
    }

    @Test fun correctedProfileStillRejectsOtherGameArbitraryPrivateFileAndEscapingSymlink() = runBlocking {
        val owner = fixture("UNKNOWN", "RPG_MAKER_VX_ACE")
        val other = fixture("UNKNOWN", "RPG_MAKER_VX_ACE")
        val otherBytes = saveBytes(88)
        other.privateSave.writeBytes(otherBytes)
        val save = privateSave(owner)
        val entry = editableEntry(owner, save)
        val backup = manager.writeSave(owner.game, save,
            listOf(SaveEdit(entry.path, entry.type, "32")), sha256Hex(owner.original))
        val beforeBackups = database.dao().getSaveBackupsForSource(save.uri).map { it.id }.toSet()

        val unrelatedDir = File(context.filesDir, "mkxpz-profile-unrelated-test-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        ownedDirectories += unrelatedDir
        val unrelated = File(unrelatedDir, owner.privateSave.name).apply { writeBytes(otherBytes) }
        for (folder in listOf(other.storage.gameDirectory, unrelatedDir)) {
            database.dao().upsertSaveLocation(GameSaveLocationEntity(
                UUID.randomUUID().toString(), owner.game.id, Uri.fromFile(folder).toString(),
                "CUSTOM", "forged authored test location", false, true, 1
            ))
        }
        val link = File(owner.storage.gameDirectory, "Save99.rvdata2").toPath()
        Files.createSymbolicLink(link, other.privateSave.toPath())
        ownedSymlinks.add(link)

        for (target in listOf(other.privateSave, unrelated, link.toFile())) {
            val forged = save.copy(uri = Uri.fromFile(target).toString(), name = target.name)
            val readFailure = runCatching { manager.readSave(owner.game, forged) }.exceptionOrNull()
            assertTrue("Profile correction must not authorize reading $target: $readFailure",
                readFailure is IllegalArgumentException)
            val writeFailure = runCatching {
                manager.writeSave(owner.game, forged, listOf(SaveEdit(entry.path, entry.type, "999")))
            }.exceptionOrNull()
            assertTrue("Profile correction must not authorize editing $target: $writeFailure",
                writeFailure is IllegalArgumentException)
            val restoreFailure = runCatching {
                manager.restoreBackup(backup.copy(sourceUri = forged.uri, sourceName = forged.name))
            }.exceptionOrNull()
            assertTrue("Profile correction must not authorize restoring onto $target: $restoreFailure",
                restoreFailure is IllegalArgumentException)
            assertTrue(database.dao().getSaveBackupsForSource(forged.uri).isEmpty())
        }

        assertArrayEquals(otherBytes, other.privateSave.readBytes())
        assertArrayEquals(otherBytes, unrelated.readBytes())
        assertEquals("32", editableEntry(owner, save).displayValue)
        assertEquals(beforeBackups, database.dao().getSaveBackupsForSource(save.uri).map { it.id }.toSet())
        assertArrayEquals(owner.original, owner.sourceSave.readBytes())
        assertDetectedEnginePreserved(owner)
        assertDetectedEnginePreserved(other)
    }
}
