package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.SaveEntryType
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.ui.GameToolsController
import fr.astragames.app.ui.UiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The editor's real controller/container; only this test's UUID rows and files are created or removed. */
@RunWith(AndroidJUnit4::class)
class MkxpzProfileSaveControllerTest {
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
    private lateinit var controller: GameToolsController
    private val ownedIds = mutableListOf<String>()
    private val ownedDirectories = mutableListOf<File>()

    @Before fun createController() = runBlocking {
        withContext(Dispatchers.Main) {
            controller = GameToolsController(app, controllerScope,
                MutableSharedFlow<UiEvent>(extraBufferCapacity = 8), {})
        }
    }

    @After fun removeOnlyAuthoredFixtures() = runBlocking {
        controllerJob.cancelAndJoin()
        try {
            ownedIds.forEach { id ->
                check(id.startsWith("mkxpz-profile-controller-test-"))
                app.container.dao.deleteGameCompletely(id)
            }
        } finally {
            ownedDirectories.asReversed().forEach { directory ->
                val canonical = directory.canonicalFile.toPath()
                check(canonical.startsWith(app.filesDir.canonicalFile.toPath()) ||
                    canonical.startsWith(app.cacheDir.canonicalFile.toPath()))
                check(directory != app.filesDir && directory != app.cacheDir)
                check(!directory.exists() || directory.deleteRecursively())
            }
        }
    }

    private data class Fixture(
        val game: GameEntity,
        val profile: LaunchProfileEntity,
        val sourceSave: File,
        val privateSave: File,
        val save: GameSave,
        val bytes: ByteArray
    )

    private fun saveBytes(value: Long) =
        Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(value))))

    private suspend fun fixture(value: Long = 17): Fixture {
        val id = "mkxpz-profile-controller-test-${UUID.randomUUID()}"
        ownedIds += id
        val source = File(app.cacheDir, id).apply { check(mkdirs()) }
        val storage = MkxpzStorage(app, id)
        ownedDirectories += source
        ownedDirectories += storage.directory
        ownedDirectories += File(app.filesDir, "save-backups/" + sha256Hex(id.toByteArray(Charsets.UTF_8)))
        File(source, "Game.ini").writeText(
            "[Game]\nLibrary=RGSS301.dll\nScripts=Data/Scripts.rvdata2\nTitle=Authored controller fixture\n"
        )
        val bytes = saveBytes(value)
        val sourceSave = File(source, "Save01.rvdata2").apply { writeBytes(bytes) }
        val game = GameEntity(
            id = id, title = "MKXP-Z save controller fixture", documentUri = Uri.fromFile(source).toString(),
            physicalPath = source.absolutePath, executableName = "Game.exe", engine = "UNKNOWN",
            launcher = "JOIPLAY", sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id
        )
        val profile = LaunchProfileEntity(
            gameId = id, launcherType = MkxpzRuntime.LAUNCHER, engineOverride = "RPG_MAKER_VX_ACE"
        )
        app.container.dao.upsertGame(game)
        app.container.dao.upsertLaunchProfile(profile)
        storage.prepare(game, profile, 3)
        val privateSave = File(storage.gameDirectory, sourceSave.name)
        val save = app.container.saveManager.listSaves(game).single {
            Uri.parse(it.uri).path?.let { path -> File(path).canonicalFile == privateSave.canonicalFile } == true
        }
        assertEquals("RPG_MAKER_VX_ACE", save.engine)
        assertEquals("UNKNOWN", app.container.dao.getGame(id)?.engine)
        return Fixture(game, profile, sourceSave, privateSave, save, bytes)
    }

    private suspend fun load(gameId: String, save: GameSave, expectedHash: String? = null) {
        withContext(Dispatchers.Main) {
            controller.loadSaveEntries(gameId, save, expectedHash)
        }
        withTimeout(10_000) { controller.toolsBusy.first { !it } }
    }

    private fun assertLoaded(save: GameSave, value: String, bytes: ByteArray) {
        assertNull(controller.toolsError.value)
        val entry = controller.saveEntries.value.single { it.editable }
        assertEquals(SaveEntryType.INT, entry.type)
        assertEquals(value, entry.displayValue)
        assertEquals(save.uri to sha256Hex(bytes), controller.saveRevision.value)
    }

    private fun assertRejected() {
        assertFalse("A rejected load must provide a diagnostic", controller.toolsError.value.isNullOrBlank())
        assertTrue("Rejected data must not remain visible in the editor", controller.saveEntries.value.isEmpty())
        assertNull("Rejected data must not supply a writable revision", controller.saveRevision.value)
    }

    @Test fun editorLoadsCorrectedEngineByGameIdThenRejectsProfileChangedForSameSaveUri() = runBlocking {
        val fixture = fixture()
        load(fixture.game.id, fixture.save, sha256Hex(fixture.bytes))
        assertLoaded(fixture.save, "17", fixture.bytes)

        app.container.dao.upsertLaunchProfile(fixture.profile.copy(engineOverride = "RPG_MAKER_XP"))
        // Same URI and old GameSave: the controller must consult the current game/profile again.
        load(fixture.game.id, fixture.save, sha256Hex(fixture.bytes))
        assertRejected()
        assertArrayEquals(fixture.bytes, fixture.privateSave.readBytes())
        assertArrayEquals(fixture.bytes, fixture.sourceSave.readBytes())
        assertEquals("UNKNOWN", app.container.dao.getGame(fixture.game.id)?.engine)
        assertTrue(app.container.dao.getSaveBackupsForSource(fixture.save.uri).isEmpty())

        app.container.dao.upsertLaunchProfile(fixture.profile)
        load(fixture.game.id, fixture.save)
        assertLoaded(fixture.save, "17", fixture.bytes)
    }

    @Test fun editorRejectsAnotherGamesPrivateFileEvenWhenItsMarshalAndEngineAreValid() = runBlocking {
        val owner = fixture(17)
        val other = fixture(88)
        load(owner.game.id, owner.save)
        assertLoaded(owner.save, "17", owner.bytes)

        val forged = owner.save.copy(uri = other.save.uri, name = other.save.name)
        // No expected hash: only source authorization can reject this valid same-engine file.
        load(owner.game.id, forged)
        assertRejected()
        assertArrayEquals(owner.bytes, owner.privateSave.readBytes())
        assertArrayEquals(other.bytes, other.privateSave.readBytes())
        assertArrayEquals(owner.bytes, owner.sourceSave.readBytes())
        assertArrayEquals(other.bytes, other.sourceSave.readBytes())
        assertEquals("UNKNOWN", app.container.dao.getGame(owner.game.id)?.engine)
        assertEquals("UNKNOWN", app.container.dao.getGame(other.game.id)?.engine)
        assertTrue(app.container.dao.getSaveBackupsForSource(owner.save.uri).isEmpty())
        assertTrue(app.container.dao.getSaveBackupsForSource(other.save.uri).isEmpty())
    }

    @Test fun editorRejectsOldRevisionThenLoadsEntriesAndHashFromCurrentPrivateSave() = runBlocking {
        val fixture = fixture()
        load(fixture.game.id, fixture.save)
        assertLoaded(fixture.save, "17", fixture.bytes)
        val changed = saveBytes(61)
        fixture.privateSave.writeBytes(changed)

        load(fixture.game.id, fixture.save, sha256Hex(fixture.bytes))
        assertRejected()
        assertArrayEquals(changed, fixture.privateSave.readBytes())
        assertArrayEquals(fixture.bytes, fixture.sourceSave.readBytes())
        assertTrue(app.container.dao.getSaveBackupsForSource(fixture.save.uri).isEmpty())

        load(fixture.game.id, fixture.save)
        assertLoaded(fixture.save, "61", changed)
        assertEquals("UNKNOWN", app.container.dao.getGame(fixture.game.id)?.engine)
    }
}
