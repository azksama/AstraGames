package fr.astragames.app.windows

import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfSaveMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migratesSeventyThousandCachedAssetsAndPendingSavesWithoutProviderQueries() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.getExternalFilesDir(null), "wolf-migration-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        val stages = mutableListOf<String>()
        try {
            File(source, "Game.exe").writeText("fixture")
            repeat(100) { folder ->
                val data = File(source, "Data/$folder").apply { mkdirs() }
                repeat(700) { File(data, "$it.bin").createNewFile() }
            }
            File(source, "SaveData").mkdirs()
            repeat(32) { File(source, "SaveData/$it.sav").writeText("old-$it") }
            cached.prepare("Game.exe") { }
            repeat(32) { File(cached.game, "SaveData/$it.sav").writeText("pending-$it") }
            File(cached.game, "custom-state.dat").writeText("nonstandard save")
            // Android's real external-storage URI resolves to this writable physical folder.
            // The provider is deliberately unavailable: successful migration must use that folder.
            val relative = source.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
            val uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$relative")
            val noProvider = object : ContextWrapper(context) {
                override fun getContentResolver(): ContentResolver = error("Migration must not query a document provider")
            }
            val direct = WolfGameStorage(noProvider, id, uri.toString())
            val protected = File(cached.game, "Data/0/0.bin")
            assertTrue(protected.setReadable(false, false))
            val started = android.os.SystemClock.elapsedRealtime()
            direct.prepare("Game.exe", stages::add)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue(direct.isDirect)
            assertEquals(source.canonicalFile, direct.game)
            repeat(32) { slot ->
                assertEquals("pending-$slot", File(source, "SaveData/$slot.sav").readText())
                assertTrue(File(source, "SaveData").listFiles()!!.any { it.name.startsWith(".astra-wolf-backup-") && it.readText() == "old-$slot" })
            }
            assertEquals("nonstandard save", File(source, "custom-state.dat").readText())
            assertTrue(stages.any { it.contains("70033 fichiers vérifiés") && it.contains("33 récupéré(s)") })
            assertTrue("Migration took $elapsed ms", elapsed < 60_000)
            assertTrue("Progress must be throttled: ${stages.size}", stages.size < 100)
            File(context.getExternalFilesDir(null), "wolf-migration-benchmark.txt")
                .writeText("cachedAssets=70000 recoveredFiles=33 migrationMs=$elapsed providerQueries=0 progressUpdates=${stages.size}")
            direct.restoreSourceConfiguration()
        } finally { source.deleteRecursively(); cached.directory.deleteRecursively() }
    }

    @Test fun cancellationKeepsRecoveredAndPendingSavesAndResumesMigration() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "cancel-migration-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        try {
            File(source, "Game.exe").writeText("fixture")
            repeat(3) { File(source, "$it.sav").writeText("old-$it") }
            cached.prepare("Game.exe") { }
            repeat(3) { File(cached.game, "$it.sav").writeText("pending-$it") }
            val result = runCatching {
                WolfGameStorage(context, id, source.toURI().toString()).prepare("Game.exe") { message ->
                    if (message.contains("1 récupéré(s)")) throw CancellationException("Fixture interruption during migration")
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(1, (0..2).count { File(source, "$it.sav").readText() == "pending-$it" })
            repeat(3) { assertEquals("pending-$it", File(cached.game, "$it.sav").readText()) }
            val resumed = WolfGameStorage(context, id, source.toURI().toString())
            resumed.prepare("Game.exe") { }
            assertTrue(resumed.isDirect)
            repeat(3) { assertEquals("pending-$it", File(source, "$it.sav").readText()) }
            assertEquals(3, source.listFiles()!!.count { it.name.startsWith(".astra-wolf-backup-") })
            resumed.restoreSourceConfiguration()
        } finally { source.deleteRecursively(); cached.directory.deleteRecursively() }
    }

    @Test fun sourceAlreadyWrittenBeforeProcessDeathDoesNotCreateAConflictOrDuplicateBackup() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "checkpoint-migration-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        try {
            File(source, "Game.exe").writeText("fixture")
            File(source, "slot.sav").writeText("old")
            cached.prepare("Game.exe") { }
            val originalManifest = File(cached.directory, "source-hashes.json").readBytes()
            val originalState = File(cached.directory, "import-state.json").readBytes()
            File(cached.game, "slot.sav").writeText("pending")
            assertEquals(0, cached.synchronize())
            // Lose the final checkpoint, as if Android killed Astra immediately after a save write.
            File(cached.directory, "source-hashes.json").writeBytes(originalManifest)
            File(cached.directory, "import-state.json").writeBytes(originalState)
            val resumed = WolfGameStorage(context, id, source.toURI().toString())
            resumed.prepare("Game.exe") { }
            assertTrue(resumed.isDirect)
            assertEquals("pending", File(source, "slot.sav").readText())
            val backups = source.listFiles()!!.filter { it.name.startsWith(".astra-wolf-backup-") }
            assertEquals(1, backups.size)
            assertEquals("old", backups.single().readText())
            resumed.restoreSourceConfiguration()
        } finally { source.deleteRecursively(); cached.directory.deleteRecursively() }
    }

    @Test fun migrationNeverFollowsPrivateLinksOrCopiesInternalBackups() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "links-migration-$id").apply { mkdirs() }
        val outside = File(context.cacheDir, "outside-$id").apply { mkdirs() }
        val cached = WolfGameStorage(context, id, source.toURI().toString(), preferDirect = false)
        try {
            File(source, "Game.exe").writeText("fixture")
            cached.prepare("Game.exe") { }
            File(outside, "slot.sav").writeText("outside data")
            java.nio.file.Files.createSymbolicLink(File(cached.game, "SaveData").toPath(), outside.toPath())
            File(cached.game, ".astra-wolf-backup-private.sav").writeText("internal backup")
            val direct = WolfGameStorage(context, id, source.toURI().toString())
            direct.prepare("Game.exe") { }
            assertTrue(direct.isDirect)
            assertFalse(File(source, "SaveData").exists())
            assertFalse(File(source, ".astra-wolf-backup-private.sav").exists())
            assertEquals("outside data", File(outside, "slot.sav").readText())
            direct.restoreSourceConfiguration()
        } finally { source.deleteRecursively(); cached.directory.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun safMigrationBatchesNamesAndPreservesNestedPendingFilesAndSourceBackups() = runBlocking {
        withProvider { id, uri, source ->
            File(source, "Data").mkdirs()
            repeat(1500) { File(source, "Data/asset-$it.bin").writeText("asset-$it") }
            File(source, "Data/state.dat").writeText("before")
            val storage = WolfGameStorage(context, id, uri)
            storage.prepare { }
            File(storage.game, "Data/state.dat").writeText("pending")
            File(storage.game, "Data/new-state.dat").writeText("new pending")
            File(storage.game, "SaveData/nested/slot.sav").apply { parentFile!!.mkdirs(); writeText("nested pending") }
            providerCall("resetQueryStats")
            assertEquals(0, storage.synchronize())
            assertEquals("pending", File(source, "Data/state.dat").readText())
            assertEquals("new pending", File(source, "Data/new-state.dat").readText())
            assertEquals("nested pending", File(source, "SaveData/nested/slot.sav").readText())
            val stats = requireNotNull(providerCall("queryStats"))
            assertTrue("Document metadata queries: $stats", stats.getInt("documents") < 10)
            assertTrue("Child folder queries: $stats", stats.getInt("children") <= 10)
            assertTrue(File(source, "Data").listFiles()!!.any { it.name.startsWith(".astra-wolf-backup-") && it.readText() == "before" })
            File(context.getExternalFilesDir(null), "wolf-saf-migration-queries.txt")
                .writeText("assets=1500 changedFiles=3 documentQueries=${stats.getInt("documents")} childQueries=${stats.getInt("children")}")
        }
    }

    @Test fun rejectedSafFinalRenameRestoresOriginalAndAllowsRetry() = runBlocking {
        withProvider { id, uri, source ->
            File(source, "slot.sav").writeText("original")
            val storage = WolfGameStorage(context, id, uri)
            storage.prepare { }
            File(storage.game, "slot.sav").writeText("pending")
            val baseline = JSONObject(File(storage.directory, "source-hashes.json").readText()).getString("slot.sav")
            providerCall("failNextRename", "$id/slot.sav")
            assertTrue(runCatching { storage.synchronize() }.isFailure)
            assertEquals("original", File(source, "slot.sav").readText())
            assertEquals("pending", File(storage.game, "slot.sav").readText())
            assertEquals(baseline, JSONObject(File(storage.directory, "source-hashes.json").readText()).getString("slot.sav"))
            assertFalse(source.listFiles()!!.any { it.name.startsWith(".astra-wolf-") })
            assertEquals(0, storage.synchronize())
            assertEquals("pending", File(source, "slot.sav").readText())
        }
    }

    @Test fun safConflictKeepsBothVersionsAndDoesNotOverwriteSource() = runBlocking {
        withProvider { id, uri, source ->
            File(source, "slot.sav").writeText("original")
            val storage = WolfGameStorage(context, id, uri)
            storage.prepare { }
            File(storage.game, "slot.sav").writeText("private progress")
            File(source, "slot.sav").writeText("external progress")
            assertEquals(1, storage.synchronize())
            assertEquals("external progress", File(source, "slot.sav").readText())
            assertEquals("private progress", File(storage.game, "slot.sav").readText())
            val output = java.io.ByteArrayOutputStream()
            storage.exportSaves(output)
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                val entries = buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
                assertEquals("private progress", entries["slot.sav"])
            }
        }
    }

    private fun providerCall(method: String, argument: String? = null) = context.contentResolver.call(
        Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}"), method, argument, null)

    private suspend fun withProvider(block: suspend (String, String, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val uri = requireNotNull(providerCall("fixture", id)?.getString("uri"))
        val source = File(context.filesDir, "fixtures/$id")
        val storage = WolfGameStorage(context, id, uri)
        try { block(id, uri, source) }
        finally { providerCall("removeFixture", id); storage.directory.deleteRecursively() }
    }
}
