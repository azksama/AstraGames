package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfGameStorageTest {
    @Test fun explicitRefreshRemovesUnchangedDeletedModsButPreservesLocalChangesAndSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-refresh-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            for (name in listOf("mod.dat", "local.dat", "save.sav")) File(source, name).writeText("original")
            storage.prepare { }
            File(storage.game, "local.dat").writeText("unsynchronized")
            source.listFiles()!!.forEach { it.delete() }
            storage.requestRefresh()
            storage.prepare { }
            assertFalse(File(storage.game, "mod.dat").exists())
            assertEquals("unsynchronized", File(storage.game, "local.dat").readText())
            assertEquals("original", File(storage.game, "save.sav").readText())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun warmLaunchReusesSeventyThousandAssetsAndStillImportsExternalSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-70k-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "Save").mkdirs()
            File(source, "Save/slot.sav").writeText("initial")
            repeat(100) { folder ->
                val data = File(source, "Data/$folder").apply { mkdirs() }
                repeat(700) { File(data, "$it.bin").writeText("asset-$folder-$it") }
            }
            val coldStart = android.os.SystemClock.elapsedRealtime()
            storage.prepare("Game.exe") { }
            val coldMs = android.os.SystemClock.elapsedRealtime() - coldStart
            File(source, "Save/slot.sav").writeText("external newer save")
            // The warm path must not open assets, even if the provider cannot read their data.
            val protectedAsset = File(source, "Data/0/0.bin")
            assertTrue(protectedAsset.setReadable(false, false))
            val progress = mutableListOf<String>()
            val start = android.os.SystemClock.elapsedRealtime()
            val reopened = WolfGameStorage(context, id, source.toURI().toString())
            reopened.prepare("Game.exe", progress::add)
            val warmMs = android.os.SystemClock.elapsedRealtime() - start
            assertEquals("external newer save", File(storage.game, "Save/slot.sav").readText())
            assertTrue(progress.last().contains("1 fichiers de sauvegarde"))
            assertTrue("Warm prepare took $warmMs ms", warmMs < 60_000)
            // Arbitrary game-created files outside Save are still synchronized.
            File(storage.game, "Data/custom-progress.dat").writeText("custom save")
            val syncStart = android.os.SystemClock.elapsedRealtime()
            assertEquals(0, reopened.synchronize())
            val syncMs = android.os.SystemClock.elapsedRealtime() - syncStart
            assertEquals("custom save", File(source, "Data/custom-progress.dat").readText())
            android.util.Log.i("WolfStorageBenchmark", "files=70002 coldMs=$coldMs warmMs=$warmMs syncMs=$syncMs")
            File(context.getExternalFilesDir(null), "wolf-storage-benchmark.txt").writeText("files=70002 coldMs=$coldMs warmMs=$warmMs syncMs=$syncMs")
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun betaFiveCacheMigratesWithoutRecopyingAndKeepsUnsynchronizedProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-migration-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("old exe")
            File(source, "progress.dat").writeText("before")
            storage.prepare("Game.exe") { }
            File(storage.directory, "import-state.json").delete()
            storage.prefix.mkdirs(); File(storage.prefix, ".astra-ready").writeText("wine9-astra-2")
            File(storage.game, "progress.dat").writeText("pending local save")
            File(source, "Game.exe").writeText("new exe")
            val reopened = WolfGameStorage(context, id, source.toURI().toString())
            reopened.prepare("Game.exe") { }
            assertEquals("old exe", File(storage.game, "Game.exe").readText())
            assertEquals(0, reopened.synchronize())
            assertEquals("pending local save", File(source, "progress.dat").readText())
            reopened.requestRefresh()
            reopened.prepare("Game.exe") { }
            assertEquals("new exe", File(storage.game, "Game.exe").readText())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun failedRefreshDoesNotCertifyPartialCopyOrLoseLocalSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "wolf-partial-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("game")
            storage.prepare("Game.exe") { }
            val unreadable = File(source, "new.bin").apply { writeText("new"); setReadable(false, false) }
            storage.requestRefresh()
            assertTrue(runCatching { storage.prepare("Game.exe") { } }.isFailure)
            assertFalse(org.json.JSONObject(File(storage.directory, "import-state.json").readText()).getBoolean("complete"))
            assertTrue(unreadable.setReadable(true, true))
            WolfGameStorage(context, id, source.toURI().toString()).prepare("Game.exe") { }
            assertEquals("new", File(storage.game, "new.bin").readText())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

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
            storage.prepare(progress = updates::add)
            assertTrue("Progress must not fsync once per file", updates.size < 100)
            assertEquals("Préparation terminée : 1000 fichiers", updates.last())
            val asset = File(storage.game, "data-0.bin")
            assertTrue(asset.setLastModified(1_000_000))
            File(source, "data-1.bin").writeText("updated source")
            storage.prepare { }
            assertEquals("asset-1", File(storage.game, "data-1.bin").readText())
            storage.requestRefresh()
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
