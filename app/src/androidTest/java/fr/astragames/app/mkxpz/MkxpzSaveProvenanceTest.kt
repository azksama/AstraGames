package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.SaveBackupDetails
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.data.saves.SaveManager
import fr.astragames.app.data.saves.SaveProvenance
import fr.astragames.app.data.saves.SaveStorage
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.readBytes
import fr.astragames.app.data.saves.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The two Save01 files are real, separately writable targets, not duplicate presentation rows. */
@RunWith(AndroidJUnit4::class)
class MkxpzSaveProvenanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureId = "mkxpz-provenance-${UUID.randomUUID()}"
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private lateinit var database: AstraDatabase
    private lateinit var manager: SaveManager
    private lateinit var game: GameEntity
    private lateinit var profile: LaunchProfileEntity
    private lateinit var storage: MkxpzStorage
    private lateinit var sourceUri: String
    private lateinit var privateUri: String
    private val originalSource = saveBytes(11)
    private val originalPrivate = saveBytes(42)

    @Before fun importAuthoredSafGameWithExistingSave() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        manager = SaveManager(context, database.dao(), SaveFinder(context))
        val result = requireNotNull(context.contentResolver.call(provider, "fixture", fixtureId, null))
        val root = requireNotNull(documentDir(context, Uri.parse(requireNotNull(result.getString("uri")))))
        val folder = requireNotNull(root.findFile("game"))
        val ini = requireNotNull(folder.createFile("application/octet-stream", "Game.ini"))
        requireNotNull(context.contentResolver.openOutputStream(ini.uri, "wt")).use {
            it.write("[Game]\nLibrary=RGSS104E.dll\nScripts=Data/Scripts.rxdata\nTitle=Authored provenance fixture\n".toByteArray())
        }
        val source = requireNotNull(folder.createFile("application/octet-stream", "Save01.rxdata"))
        sourceUri = source.uri.toString()
        requireNotNull(context.contentResolver.openOutputStream(source.uri, "wt")).use { it.write(originalSource) }
        game = GameEntity(
            id = fixtureId, title = "MKXP-Z save provenance fixture", documentUri = folder.uri.toString(),
            physicalPath = null, executableName = "Game.exe", engine = "UNKNOWN", launcher = "JOIPLAY",
            sourceId = fixtureId, dateAdded = 1, lastModified = 1, fingerprint = fixtureId
        )
        profile = LaunchProfileEntity(gameId = fixtureId, launcherType = MkxpzRuntime.LAUNCHER,
            engineOverride = "RPG_MAKER_XP")
        database.dao().upsertGame(game)
        database.dao().upsertLaunchProfile(profile)
        storage = MkxpzStorage(context, fixtureId)
        storage.prepare(game, profile, 1)
        val privateSave = File(storage.gameDirectory, "Save01.rxdata")
        assertArrayEquals("The source save existed before import", originalSource, privateSave.readBytes())
        privateSave.writeBytes(originalPrivate)
        privateUri = Uri.fromFile(privateSave).toString()
    }

    @After fun removeOnlyAuthoredData() {
        if (::database.isInitialized) database.close()
        try {
            if (::storage.isInitialized) deleteOwnedDirectory(storage.directory)
            deleteOwnedDirectory(File(context.filesDir, "save-backups/" + sha256Hex(fixtureId.toByteArray())))
        } finally {
            context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        }
    }

    private fun deleteOwnedDirectory(directory: File) {
        check(directory.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
        check(directory.canonicalFile != context.filesDir.canonicalFile)
        check(!directory.exists() || directory.deleteRecursively())
    }

    private fun saveBytes(value: Long) = Marshal.dump(MarshalValue.ObjectValue(
        MarshalValue.SymbolValue("Game_Party"),
        linkedMapOf(MarshalValue.SymbolValue("@gold") to MarshalValue.IntValue(value))
    ))

    private suspend fun saves(): Pair<GameSave, GameSave> {
        val rows = manager.listSaves(game)
        assertEquals("The SAF original and imported copy must both remain visible", 2, rows.size)
        assertEquals(setOf("Save01.rxdata"), rows.map { it.name }.toSet())
        assertEquals(setOf(sourceUri, privateUri), rows.map { it.uri }.toSet())
        assertTrue(rows.all { it.engine == "RPG_MAKER_XP" })
        return rows.single { it.uri == sourceUri } to rows.single { it.uri == privateUri }
    }

    private suspend fun edit(save: GameSave, value: String) = manager.readSaveSnapshot(game, save).let { opened ->
        val entry = opened.entries.single { it.editable }
        manager.writeSave(game, save, listOf(SaveEdit(entry.path, entry.type, value)), opened.sha256)
    }

    private fun assertBytes(source: ByteArray, privateCopy: ByteArray) {
        assertArrayEquals("The SAF source is a separate target", source, readBytes(context, sourceUri))
        assertArrayEquals("The MKXP-Z copy is a separate target", privateCopy, readBytes(context, privateUri))
    }

    private fun assertNativeProvenance(source: SaveProvenance?, privateCopy: SaveProvenance?) {
        assertEquals(SaveProvenance(SaveStorage.SOURCE, false), source)
        assertEquals(SaveProvenance(SaveStorage.MKXPZ_PRIVATE, true), privateCopy)
    }

    @Test fun sameNamedRowsEditAndRestoreOnlyTheirOwnSourceAndKeepDistinctBackupOrigins() = runBlocking {
        val (source, privateCopy) = saves()
        assertNativeProvenance(source.provenance, privateCopy.provenance)
        assertBytes(originalSource, originalPrivate)
        assertEquals(privateUri, manager.listSaves(game).first().uri)
        assertNativeProvenance(manager.readSaveSnapshot(game, source).provenance,
            manager.readSaveSnapshot(game, privateCopy).provenance)

        val sourceBackup = edit(source, "21")
        assertBytes(saveBytes(21), originalPrivate)
        val privateBackup = edit(privateCopy, "84")
        assertBytes(saveBytes(21), saveBytes(84))
        assertEquals(sourceBackup.sourceName, privateBackup.sourceName)
        assertNotEquals(sourceBackup.sourceUri, privateBackup.sourceUri)
        assertArrayEquals(originalSource, readBytes(context, sourceBackup.backupUri))
        assertArrayEquals(originalPrivate, readBytes(context, privateBackup.backupUri))

        val backups = withTimeout(10_000) { manager.observeBackupsWithProvenance(game.id).first { it.size == 2 } }
        assertNativeProvenance(backups.single { it.backup.id == sourceBackup.id }.provenance,
            backups.single { it.backup.id == privateBackup.id }.provenance)

        manager.restoreBackup(sourceBackup)
        assertBytes(originalSource, saveBytes(84))
        manager.restoreBackup(privateBackup)
        assertBytes(originalSource, originalPrivate)
        val sourceHistory = database.dao().getSaveBackupsForSource(sourceUri)
        val privateHistory = database.dao().getSaveBackupsForSource(privateUri)
        assertEquals(2, sourceHistory.size)
        assertEquals(2, privateHistory.size)
        assertArrayEquals(saveBytes(21), readBytes(context, sourceHistory.single { it.id != sourceBackup.id }.backupUri))
        assertArrayEquals(saveBytes(84), readBytes(context, privateHistory.single { it.id != privateBackup.id }.backupUri))
        assertEquals("UNKNOWN", database.dao().getGame(game.id)?.engine)
        assertEquals("JOIPLAY", database.dao().getGame(game.id)?.launcher)
    }

    @Test fun externalLauncherChangeUpdatesExistingBackupFlowAndRereadsOpenSaveProvenance() = runBlocking {
        val (source, privateCopy) = saves()
        val sourceBackup = edit(source, "21")
        val privateBackup = edit(privateCopy, "84")
        val emissions = Channel<List<SaveBackupDetails>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) {
            manager.observeBackupsWithProvenance(game.id).collect { emissions.send(it) }
        }
        suspend fun nextMatching(predicate: (List<SaveBackupDetails>) -> Boolean) = withTimeout(10_000) {
            var latest = emissions.receive()
            while (!predicate(latest)) latest = emissions.receive()
            latest
        }
        try {
            val native = nextMatching { it.size == 2 && it.any { row -> row.provenance?.activeForCurrentLauncher == true } }
            assertNativeProvenance(native.single { it.backup.id == sourceBackup.id }.provenance,
                native.single { it.backup.id == privateBackup.id }.provenance)
            database.dao().upsertLaunchProfile(profile.copy(launcherType = "JOIPLAY"))
            val external = nextMatching { rows ->
                rows.size == 2 && rows.single { it.backup.id == privateBackup.id }.provenance ==
                    SaveProvenance(SaveStorage.MKXPZ_PRIVATE, false)
            }
            assertEquals(SaveProvenance(SaveStorage.SOURCE, null),
                external.single { it.backup.id == sourceBackup.id }.provenance)
            assertEquals(SaveProvenance(SaveStorage.MKXPZ_PRIVATE, false),
                external.single { it.backup.id == privateBackup.id }.provenance)
            val (externalSource, externalPrivate) = saves()
            assertEquals(SaveProvenance(SaveStorage.SOURCE, null), externalSource.provenance)
            assertEquals(SaveProvenance(SaveStorage.MKXPZ_PRIVATE, false), externalPrivate.provenance)
            // These are the original opened rows; an editor read must not reuse their stale labels.
            assertEquals(externalSource.provenance, manager.readSaveSnapshot(game, source).provenance)
            assertEquals(externalPrivate.provenance, manager.readSaveSnapshot(game, privateCopy).provenance)
            manager.restoreBackup(privateBackup)
            assertBytes(saveBytes(21), originalPrivate)
            manager.restoreBackup(sourceBackup)
            assertBytes(originalSource, originalPrivate)
            assertEquals("UNKNOWN", database.dao().getGame(game.id)?.engine)
            assertEquals("JOIPLAY", database.dao().getGame(game.id)?.launcher)
        } finally {
            observer.cancelAndJoin()
            emissions.close()
        }
    }
}
