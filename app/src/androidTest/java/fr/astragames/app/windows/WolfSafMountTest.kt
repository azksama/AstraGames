package fr.astragames.app.windows

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** SDK instrumentation: actual provider FDs/SCM_RIGHTS/mmap and durable writeback. */
class WolfSafMountTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Uri.parse("content://fr.astragames.app.test.storage")

    private class Fixture(val id: String, val uri: String, val source: File, val storage: WolfGameStorage)
    private fun fixture(): Fixture {
        val id = "wolf-saf-${UUID.randomUUID()}"
        val uri = context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri")!!
        val source = File(context.filesDir, "fixtures/$id")
        File(source, "Game.exe").writeText("authored fixture")
        File(source, "Game.ini").writeText("SoftModeFlag=0\r\nWindowModeFlag=0\r\nUserSetting=old\r\n")
        File(source, "Save").mkdirs()
        File(source, "Data 日本語").mkdirs()
        File(source, "Data 日本語/asset.bin").writeText("streamed fixture 123")
        return Fixture(id, uri, source, WolfGameStorage(context, id, uri))
    }
    private fun close(fixture: Fixture, extra: WolfGameStorage? = null) {
        extra?.closeMount(); fixture.storage.closeMount()
        runCatching { fixture.storage.restoreSourceConfiguration() }
        context.contentResolver.call(provider, "removeFixture", fixture.id, null)
        fixture.storage.directory.deleteRecursively()
    }
    private fun socket(storage: WolfGameStorage) = storage.runtimeEnvironment().getValue("ASTRA_SAF_SOCKET")
    // Use the exact canonical root exported to Wine, not Android's alternate filesDir alias.
    private fun root(storage: WolfGameStorage) = storage.runtimeEnvironment().getValue("ASTRA_SAF_ROOT")
    private suspend fun prepare(storage: WolfGameStorage) {
        val stages = mutableListOf<String>()
        try { storage.prepare("Game.exe", stages::add) }
        catch (error: Exception) { throw AssertionError("SAF preparation failed:\n${stages.joinToString("\n")}", error) }
        assertTrue("SAF preparation fell back:\n${stages.joinToString("\n")}", storage.isSaf)
    }

    @Test fun readsProviderDescriptorsWithoutImportingAssetsAndPublishesNativeSave() = runBlocking {
        val fixture = fixture()
        try {
            // A full import would open this inaccessible asset. Preparation must not read it.
            File(fixture.source, "UnusedAssets").mkdirs()
            repeat(5000) { File(fixture.source, "UnusedAssets/$it.bin").createNewFile() }
            val denied = File(fixture.source, "UnusedAssets/unreadable.bin").apply { writeText("never opened") }
            android.system.Os.chmod(denied.path, 0)
            context.contentResolver.call(provider, "resetQueryStats", null, null)
            val started = android.os.SystemClock.elapsedRealtime()
            prepare(fixture.storage)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue(fixture.storage.accessDescription, fixture.storage.isSaf)
            assertFalse(fixture.storage.isDirect)
            assertFalse(File(fixture.storage.directory, "game").exists())
            assertFalse(File(fixture.storage.game, "UnusedAssets").exists())
            assertFalse(File(fixture.storage.game, "Data 日本語/asset.bin").exists())
            assertEquals(0L, File(fixture.storage.game, "Game.exe").length())
            assertTrue("prepare took $elapsed ms", elapsed < 10_000)
            assertEquals("OK", WolfSafBridge.probe(root(fixture.storage), socket(fixture.storage)))
            assertEquals("native durable save", File(fixture.source, "Save/renamed.sav").readText())
            assertFalse(File(fixture.source, "Save/slot.sav").exists())
            assertEquals(0, fixture.storage.synchronize())
            assertEquals("SoftModeFlag=0\r\nWindowModeFlag=0\r\nUserSetting=old\r\n", File(fixture.source, "Game.ini").readText())
            val stats = context.contentResolver.call(provider, "queryStats", null, null)!!
            File(context.getExternalFilesDir(null), "wolf-saf-descriptor-proof.txt").writeText(
                "SDK instrumentation; actual FD/mmap/SCM_RIGHTS; prepareMs=$elapsed; unusedAssets=5001; importedAssets=0; " +
                    "queries=${stats.getInt("documents")}/${stats.getInt("children")}; ${fixture.storage.storageStatistics()}")
        } finally { close(fixture) }
    }

    @Test fun incompleteWriteNeverReplacesValidSaveAndRemainsExportableAfterNextSave() = runBlocking {
        val fixture = fixture(); var resumed: WolfGameStorage? = null
        try {
            val source = File(fixture.source, "Save/progress.sav").apply { writeText("valid progress") }
            prepare(fixture.storage)
            assertEquals("OK", WolfSafBridge.stage(root(fixture.storage), socket(fixture.storage), "Save/progress.sav", "half save"))
            assertEquals("valid progress", source.readText())
            // The native stage loses the descriptor without close/fsync COMMIT, like a killed writer.
            File(fixture.storage.game, "Game.ini").appendText("PlayedHours=1\r\n")
            fixture.storage.closeMount()
            resumed = WolfGameStorage(context, fixture.id, fixture.uri)
            prepare(resumed)
            assertEquals("valid progress", source.readText())
            assertEquals("valid progress", WolfSafBridge.read(root(resumed), socket(resumed), "Save/progress.sav"))
            assertEquals("half save", resumed.saveFiles().single { it.path == "Save/progress.sav" }.file.readText())
            assertTrue(File(fixture.source, "Game.ini").readText().contains("PlayedHours=1"))
            assertTrue(File(fixture.source, "Game.ini").readText().contains("SoftModeFlag=0"))
            assertEquals(1, resumed.synchronize())
            assertEquals("OK", WolfSafBridge.stage(root(resumed), socket(resumed), "Save/progress.sav", "new valid save", commit = true))
            assertEquals("new valid save", source.readText())
            assertTrue(resumed.saveFiles().any { it.path.startsWith("Recovered/") && it.file.readText() == "half save" })
            assertEquals(0, resumed.synchronize())
        } finally { close(fixture, resumed) }
    }

    @Test fun failedPublicationPreservesSourceAndRecoversOnNextLaunch() = runBlocking {
        val fixture = fixture(); var resumed: WolfGameStorage? = null
        try {
            File(fixture.source, "Save/progress.sav").writeText("before")
            prepare(fixture.storage)
            context.contentResolver.call(provider, "failNextRename", "${fixture.id}/Save/progress.sav", null)
            val result = WolfSafBridge.stage(root(fixture.storage), socket(fixture.storage), "Save/progress.sav", "after", commit = true)
            assertTrue(result, result.startsWith("stage commit errno="))
            assertEquals("before", File(fixture.source, "Save/progress.sav").readText())
            assertEquals("after", fixture.storage.saveFiles().single { it.path == "Save/progress.sav" }.file.readText())
            fixture.storage.closeMount()
            resumed = WolfGameStorage(context, fixture.id, fixture.uri)
            prepare(resumed)
            assertEquals("after", File(fixture.source, "Save/progress.sav").readText())
        } finally { close(fixture, resumed) }
    }

    @Test fun completedWriteSurvivesWithdrawnProviderAccessAndPublishesAfterReauthorization() = runBlocking {
        val fixture = fixture(); var resumed: WolfGameStorage? = null
        try {
            val source = File(fixture.source, "Save/progress.sav").apply { writeText("before") }
            prepare(fixture.storage)
            // Actual provider SecurityException after the native write+fsync, before COMMIT publication.
            val result = WolfSafBridge.stage(root(fixture.storage), socket(fixture.storage), "Save/progress.sav", "after", commit = true,
                beforeCommit = Runnable { context.contentResolver.call(provider, "denyAccess", fixture.id, null) })
            assertEquals("stage commit errno=13", result)
            assertEquals("before", source.readText())
            assertEquals("after", fixture.storage.saveFiles().single { it.path == "Save/progress.sav" }.file.readText())
            fixture.storage.closeMount()
            resumed = WolfGameStorage(context, fixture.id, fixture.uri)
            assertTrue(runCatching { resumed.prepare("Game.exe") { } }.isFailure)
            assertEquals("after", resumed.saveFiles().single { it.path == "Save/progress.sav" }.file.readText())
            assertFalse(File(resumed.directory, "game").exists())
            context.contentResolver.call(provider, "denyAccess", null, null)
            prepare(resumed)
            assertEquals("after", source.readText())
            assertEquals(0, resumed.synchronize())
        } finally { context.contentResolver.call(provider, "denyAccess", null, null); close(fixture, resumed) }
    }

    @Test fun externalChangesConflictAndEditedExportRetainsItsOriginalBaseline() = runBlocking {
        val fixture = fixture()
        try {
            val source = File(fixture.source, "Save/progress.sav").apply { writeText("before") }
            prepare(fixture.storage)
            val exported = fixture.storage.saveFiles().single { it.path == "Save/progress.sav" }.file
            exported.writeText("edited locally")
            source.writeText("external progress")
            fixture.storage.savedFileChanged(exported)
            assertEquals(1, fixture.storage.synchronize())
            assertEquals("external progress", source.readText())
            assertEquals("edited locally", fixture.storage.saveFiles().single { it.path == "Save/progress.sav" }.file.readText())
        } finally { close(fixture) }
    }

    @Test fun unsupportedProviderPublicationFallsBackExplicitlyToPrivateImport() = runBlocking {
        val fixture = fixture()
        try {
            // Debug provider rejects every rename for this one fixture, including the capability probe.
            context.contentResolver.call(provider, "denyRenames", fixture.id, null)
            val stages = mutableListOf<String>()
            fixture.storage.prepare("Game.exe", stages::add)
            assertFalse(fixture.storage.isSaf)
            assertFalse(fixture.storage.isDirect)
            assertTrue(stages.joinToString("\n"), stages.any { it.contains("SAF à la demande indisponible") })
            assertEquals("authored fixture", File(fixture.storage.game, "Game.exe").readText())
        } finally { context.contentResolver.call(provider, "denyRenames", null, null); close(fixture) }
    }
}
