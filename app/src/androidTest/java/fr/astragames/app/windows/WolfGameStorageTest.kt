package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfGameStorageTest {
    @Test fun importsWithoutChangingOriginalAndPreservesSaveConflicts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-fixture-$id").apply { mkdirs() }
        val originalIni = "SoftModeFlag=0\r\nWindowModeFlag=0\r\n"
        File(source, "Game.ini").writeText(originalIni)
        File(source, "Game.exe").writeText("fixture")
        File(source, "Save").mkdirs()
        val sourceSave = File(source, "Save/Save01.sav").apply { writeText("old") }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            storage.prepare { }
            assertEquals(originalIni, File(source, "Game.ini").readText())
            assertTrue(File(storage.game, "Game.ini").readText().contains("SoftModeFlag=1"))
            File(storage.game, "Save/Save01.sav").writeText("new progress")
            assertEquals(0, storage.synchronize())
            assertEquals("new progress", sourceSave.readText())
            assertTrue(File(source, "Save").listFiles()!!.any { it.name.startsWith(".astra-wolf-backup-") && it.readText() == "old" })
            File(storage.game, "Save/Save01.sav").writeText("unsynchronized progress")
            sourceSave.writeText("external progress")
            WolfGameStorage(context, id, source.toURI().toString()).prepare { }
            assertEquals("unsynchronized progress", File(storage.game, "Save/Save01.sav").readText())
            val exported = java.io.ByteArrayOutputStream()
            storage.exportSaves(exported)
            java.util.zip.ZipInputStream(exported.toByteArray().inputStream()).use { zip ->
                assertEquals("Save/Save01.sav", zip.nextEntry.name)
                assertEquals("unsynchronized progress", zip.readBytes().toString(Charsets.UTF_8))
                assertNull(zip.nextEntry)
            }
            assertEquals(1, storage.synchronize())
            assertEquals("external progress", sourceSave.readText())
            assertEquals("unsynchronized progress", File(storage.game, "Save/Save01.sav").readText())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }
    @Test fun createsPrivateSoftwareConfigurationForGamesWithoutIni() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-fixture-$id").apply { mkdirs() }
        File(source, "Game.exe").writeText("fixture")
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            storage.prepare { }
            assertTrue(File(storage.game, "Game.ini").readText().contains("SoftModeFlag=1"))
            storage.synchronize()
            assertFalse(File(source, "Game.ini").exists())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }
}
