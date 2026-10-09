package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class WolfDirectStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun firstLaunchUsesSeventyThousandOriginalAssetsWithoutImporting() = runBlocking {
        val id = "direct-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            repeat(100) { folder ->
                val data = File(source, "Data/$folder").apply { mkdirs() }
                repeat(700) { File(data, "$it.bin").createNewFile() }
            }
            val started = android.os.SystemClock.elapsedRealtime()
            storage.prepare("Game.exe") { }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertEquals(source.canonicalFile, storage.game)
            assertTrue(storage.isDirect)
            assertFalse(File(storage.directory, "game").exists())
            assertFalse(File(storage.directory, "source-hashes.json").exists())
            File(source, "SaveData").mkdirs()
            File(storage.game, "SaveData/Save01.sav").writeText("progress")
            assertEquals(0, storage.synchronize())
            assertEquals("progress", File(source, "SaveData/Save01.sav").readText())
            assertTrue("Direct prepare took $elapsed ms", elapsed < 60_000)
            File(context.getExternalFilesDir(null), "wolf-direct-benchmark.txt").writeText("files=70001 directPrepareMs=$elapsed copiedFiles=0")
        } finally { storage.restoreSourceConfiguration(); source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun migratesPendingPrivateSavesBeforeSwitchingToOriginal() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "migration-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        val direct = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "SaveData").mkdirs(); File(source, "SaveData/Save01.sav").writeText("original")
            cached.prepare("Game.exe") { }
            File(cached.game, "SaveData/Save01.sav").writeText("pending progress")
            direct.prepare("Game.exe") { }
            assertTrue(direct.isDirect)
            assertEquals("pending progress", File(source, "SaveData/Save01.sav").readText())
            assertTrue(File(source, "SaveData").listFiles()!!.any { it.name.startsWith(".astra-wolf-backup-") })
        } finally { direct.restoreSourceConfiguration(); source.deleteRecursively(); direct.directory.deleteRecursively() }
    }

    @Test fun conflictingSavesKeepPrivateCopyPlayableAndExportable() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "conflict-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        val direct = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "Save01.sav").writeText("original")
            cached.prepare("Game.exe") { }
            File(cached.game, "Save01.sav").writeText("private progress")
            File(source, "Save01.sav").writeText("external progress")
            direct.prepare("Game.exe") { }
            assertFalse(direct.isDirect)
            assertEquals("external progress", File(source, "Save01.sav").readText())
            assertEquals("private progress", File(direct.game, "Save01.sav").readText())
            val output = ByteArrayOutputStream(); direct.exportSaves(output)
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                assertEquals("Save01.sav", zip.nextEntry.name)
                assertEquals("private progress", zip.readBytes().toString(Charsets.UTF_8))
                assertNull(zip.nextEntry)
            }
        } finally { source.deleteRecursively(); direct.directory.deleteRecursively() }
    }

    @Test fun softwareConfigurationRestoresOriginalBytesAndRecoversAfterProcessDeath() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "configuration-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        val original = "SoftModeFlag=0\r\nWindowModeFlag=0\r\nOriginal=été\r\n".toByteArray(Charsets.ISO_8859_1)
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "Game.ini").writeBytes(original)
            storage.prepare("Game.exe") { }
            assertTrue(File(source, "Game.ini").readText().contains("SoftModeFlag=1"))
            WolfGameStorage(context, id, source.toURI().toString()).restoreSourceConfiguration()
            assertArrayEquals(original, File(source, "Game.ini").readBytes())
            File(source, "Game.ini").delete()
            storage.prepare("Game.exe") { }
            storage.restoreSourceConfiguration()
            assertFalse(File(source, "Game.ini").exists())
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun configurationRecoveryPreservesConcurrentEdits() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "configuration-conflict-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "Game.ini").writeText("SoftModeFlag=0")
            storage.prepare("Game.exe") { }
            File(source, "Game.ini").writeText("external edit")
            assertTrue(runCatching { storage.restoreSourceConfiguration() }.isFailure)
            assertEquals("external edit", File(source, "Game.ini").readText())
            assertTrue(File(storage.directory, "source-configuration.json").isFile)
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun restoringSoftwareFlagsPreservesPreferencesWrittenByTheGame() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "configuration-game-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString())
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "Game.ini").writeText("SoftModeFlag=0\r\nWindowModeFlag=0\r\nVolume=90\r\n")
            storage.prepare("Game.exe") { }
            val ini = File(source, "Game.ini")
            ini.writeText(ini.readText().replace("Volume=90", "Volume=40"))
            storage.restoreSourceConfiguration()
            assertTrue(ini.readText().contains("SoftModeFlag=0"))
            assertTrue(ini.readText().contains("WindowModeFlag=0"))
            assertTrue(ini.readText().contains("Volume=40"))
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun savesInWindowsUserFoldersRemainDiscoverableAndExportAcrossSessions() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "windows-saves-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        try {
            File(source, "Game.exe").writeText("fixture")
            storage.prepare("Game.exe") { }
            storage.captureWindowsSaveBaseline()
            val saved = File(storage.prefix, "drive_c/users/player/AppData/Roaming/Game/progress.dat").apply {
                parentFile!!.mkdirs(); writeText("Windows progress")
            }
            val reopened = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
            // Recover a crash even if another launch establishes a new baseline first.
            reopened.captureWindowsSaveBaseline()
            assertEquals(saved.canonicalFile, reopened.saveFiles().single().file)
            val output = ByteArrayOutputStream(); reopened.exportSaves(output)
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                assertEquals("Windows/drive_c/users/player/AppData/Roaming/Game/progress.dat", zip.nextEntry.name)
                assertEquals("Windows progress", zip.readBytes().toString(Charsets.UTF_8))
            }
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }

    @Test fun cloudDocumentProviderFallsBackToPrivateCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val provider = android.net.Uri.parse("content://${fr.astragames.app.data.local.FixtureDocumentsProvider.AUTHORITY}")
        val fixture = requireNotNull(context.contentResolver.call(provider, "fixture", id, null))
        val uri = requireNotNull(fixture.getString("uri"))
        val source = requireNotNull(fr.astragames.app.data.saves.documentDir(context, android.net.Uri.parse(uri)))
        val exe = requireNotNull(source.createFile("application/octet-stream", "Game.exe"))
        context.contentResolver.openOutputStream(exe.uri)!!.use { it.write("fixture".toByteArray()) }
        val storage = WolfGameStorage(context, id, uri)
        try {
            storage.prepare("Game.exe") { }
            assertFalse(storage.isDirect)
            assertEquals("fixture", File(storage.game, "Game.exe").readText())
        } finally { context.contentResolver.call(provider, "removeFixture", id, null); storage.directory.deleteRecursively() }
    }

    @Test fun windowsSavesFromOlderVersionsAreFoundWithoutANewInGameSave() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "legacy-windows-saves-$id").apply { mkdirs() }
        val storage = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        try {
            File(source, "Game.exe").writeText("fixture")
            storage.prepare("Game.exe") { }
            val saved = File(storage.prefix, "drive_c/users/player/AppData/Local/Game/progress.dat").apply {
                parentFile!!.mkdirs(); writeText("old Windows progress")
            }
            File(storage.prefix, "drive_c/users/player/Desktop/desktop.ini").apply { parentFile!!.mkdirs(); writeText("system") }
            assertEquals(saved.canonicalFile, storage.saveFiles().single().file)
            storage.captureWindowsSaveBaseline()
            assertEquals(saved.canonicalFile, storage.saveFiles().single().file)
        } finally { source.deleteRecursively(); storage.directory.deleteRecursively() }
    }
}
