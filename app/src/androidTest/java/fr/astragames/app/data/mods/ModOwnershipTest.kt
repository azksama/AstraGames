package fr.astragames.app.data.mods

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.data.local.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Dummy games and isolated Room database; never uses the application's catalogue. */
class ModOwnershipTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var database: AstraDatabase
    private lateinit var manager: ModsManager

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "mod-ownership-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        database = Room.inMemoryDatabaseBuilder(app, AstraDatabase::class.java).build()
        manager = ModsManager(context, database.dao())
    }

    @After fun cleanup() { database.close(); root.deleteRecursively() }

    private suspend fun install(id: String): Pair<ModInstallationEntity, File> {
        val folder = File(root, "$id/game").apply { mkdirs() }
        val target = File(folder, "script.rpy").apply { writeText("same original") }
        val game = GameEntity(id = id, title = id, documentUri = Uri.fromFile(folder.parentFile).toString(),
            physicalPath = folder.parent, executableName = "Game.exe", engine = "RENPY", launcher = "JOIPLAY",
            sourceId = "fixture", dateAdded = 1, lastModified = 1, fingerprint = id)
        database.dao().upsertGame(game)
        val packageRoot = File(root, "package-$id").apply { mkdirs() }
        File(packageRoot, "script.rpy").writeText("mod $id")
        val mod = ModEntity(id, id, id, null, null, null, "RENPY", Uri.fromFile(packageRoot).toString(),
            false, "REPLACE", "game", ".", 1)
        database.dao().upsertMod(mod)
        return manager.install(game, mod) to target
    }

    @Test fun crossInstallationBackupIsRejectedEvenWhenOriginalHashesMatch() = runTest {
        val (first, target) = install("first")
        val (second, otherTarget) = install("second")
        val record = database.dao().getInstalledFiles(first.id).single()
        val foreign = database.dao().getInstalledFiles(second.id).single()
        database.dao().insertInstalledFiles(listOf(record.copy(backupUri = foreign.backupUri)))
        assertTrue(runCatching { manager.uninstall(first, true) }.isFailure)
        assertEquals("mod first", target.readText())
        assertEquals("mod second", otherTarget.readText())
        assertTrue(File(foreign.backupUri!!).isFile)
        assertEquals(1, database.dao().getInstalledFiles(first.id).size)
        database.dao().insertInstalledFiles(listOf(record))
        assertTrue(manager.uninstall(first).isEmpty())
        assertEquals("same original", target.readText())
        assertTrue(manager.uninstall(second).isEmpty())
        assertEquals("same original", otherTarget.readText())
    }

    @Test fun symlinkToAnotherInstallationsBackupIsRejectedWithoutMutatingEitherGame() = runTest {
        val (first, target) = install("first")
        val (second, otherTarget) = install("second")
        val record = database.dao().getInstalledFiles(first.id).single()
        val foreign = database.dao().getInstalledFiles(second.id).single()
        val backup = File(record.backupUri!!)
        val original = backup.readBytes()
        check(backup.delete())
        android.system.Os.symlink(foreign.backupUri!!, backup.path)
        try {
            assertTrue(runCatching { manager.uninstall(first, true) }.isFailure)
            assertEquals("mod first", target.readText())
            assertEquals("mod second", otherTarget.readText())
            assertEquals("same original", File(foreign.backupUri!!).readText())
        } finally {
            java.nio.file.Files.deleteIfExists(backup.toPath())
            backup.parentFile!!.mkdirs()
            backup.writeBytes(original)
        }
        assertTrue(manager.uninstall(first).isEmpty())
        assertTrue(manager.uninstall(second).isEmpty())
    }
}
