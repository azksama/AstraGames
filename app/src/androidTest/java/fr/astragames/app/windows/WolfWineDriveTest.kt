package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class WolfWineDriveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun directAndCopiedGamesKeepTheirSavesOnTheMappedWritableVolume() = runBlocking {
        for (direct in listOf(true, false)) {
            val id = "wine-drive-${UUID.randomUUID()}"
            val original = File(context.cacheDir, id).apply { mkdirs() }
            val storage = WolfGameStorage(context, id, original.toURI().toString(), preferDirect = direct)
            try {
                File(original, "Sub folder").mkdirs()
                File(original, "Sub folder/Game.exe").writeText("authored fixture")
                val save = File(original, "Sub folder/SaveData/Save01.sav").apply { parentFile!!.mkdirs(); writeText("existing progress") }
                storage.prepare("Sub folder/Game.exe") { }
                val drive = WolfWineDrive(storage.prefix, storage.game)
                drive.mount(); drive.mount()
                assertEquals(storage.game.canonicalFile, File(storage.prefix, "dosdevices/g:").canonicalFile)
                assertEquals("G:\\Sub folder\\Game.exe", drive.executable("Sub folder/Game.exe"))
                assertTrue(drive.verifyWritable(File(storage.game, "Sub folder")) > 0)
                assertEquals("existing progress", save.readText())
                assertEquals("existing progress", File(storage.game, "Sub folder/SaveData/Save01.sav").readText())
                assertFalse(storage.game.walkTopDown().any { it.name.startsWith(".astra-wolf-write-") })
                assertFalse(File(storage.prefix, "dosdevices").listFiles().orEmpty().any { it.name.startsWith(".astra-game-drive-") })
            } finally { storage.restoreSourceConfiguration(); original.deleteRecursively(); storage.directory.deleteRecursively() }
        }
    }

    @Test fun recoveryReplacesOnlyAnOldSymlinkAndRefusesToDeleteExistingDriveFiles() {
        val root = File(context.cacheDir, "wine-drive-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val old = File(root, "old").apply { mkdirs() }
            val current = File(root, "new").apply { mkdirs() }
            val prefix = File(root, "prefix")
            WolfWineDrive(prefix, old).mount()
            WolfWineDrive(prefix, current).mount()
            val link = File(prefix, "dosdevices/g:")
            assertEquals(current.canonicalFile, link.canonicalFile)
            Files.delete(link.toPath())
            link.mkdirs(); File(link, "Save01.sav").writeText("preserve this progress")
            val error = runCatching { WolfWineDrive(prefix, current).mount() }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("preserve this progress", File(link, "Save01.sav").readText())
            assertTrue(runCatching { WolfWineDrive(prefix, current).executable("../escape.exe") }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
