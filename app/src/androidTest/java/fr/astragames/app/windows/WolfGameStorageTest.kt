package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfGameStorageTest {
    @Test fun importsNestedSafFilesWithBatchedDocumentMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val provider = android.net.Uri.parse("content://${fr.astragames.app.data.local.FixtureDocumentsProvider.AUTHORITY}")
        val fixture = requireNotNull(context.contentResolver.call(provider, "fixture", id, null))
        val uri = requireNotNull(fixture.getString("uri"))
        val source = requireNotNull(fr.astragames.app.data.saves.documentDir(context, android.net.Uri.parse(uri)))
        val data = requireNotNull(source.createDirectory("Data 日本語"))
        val file = requireNotNull(data.createFile("application/octet-stream", "asset.bin"))
        context.contentResolver.openOutputStream(file.uri)!!.use { it.write("nested SAF asset".toByteArray()) }
        val storage = WolfGameStorage(context, id, uri)
        try {
            storage.prepare { }
            assertEquals("nested SAF asset", File(storage.game, "Data 日本語/asset.bin").readText())
        } finally { context.contentResolver.call(provider, "removeFixture", id, null); storage.directory.deleteRecursively() }
    }
    @Test fun preparationBatchesProgressAndDoesNotRewriteIdenticalFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "wolf-bulk-${UUID.randomUUID()}").apply { mkdirs() }
        repeat(1000) { File(source, "data-$it.bin").writeText("asset-$it") }
        val storage = WolfGameStorage(context, UUID.randomUUID().toString(), source.toURI().toString())
        try {
            val updates = mutableListOf<String>()
            storage.prepare(updates::add)
            assertTrue("Progress must not fsync once per file", updates.size < 100)
            assertEquals("Préparation terminée : 1000 fichiers", updates.last())
            val asset = File(storage.game, "data-0.bin")
            assertTrue(asset.setLastModified(1_000_000))
            File(source, "data-1.bin").writeText("updated source")
            storage.prepare { }
            assertEquals(1_000_000L, asset.lastModified())
            assertEquals("updated source", File(storage.game, "data-1.bin").readText())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }
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
