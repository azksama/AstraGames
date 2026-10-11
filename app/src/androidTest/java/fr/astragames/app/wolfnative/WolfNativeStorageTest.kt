package fr.astragames.app.wolfnative

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import fr.astragames.wolf.WolfSaveData
import fr.astragames.wolf.NativeEffect

class WolfNativeStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.cacheDir, "native-source-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe/invalid input was accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun gameTextOverlaySurvivesRestartPreservesOriginalBytesAndExportsItsPriorVersion() = fixture { root ->
        val id = "native-files-${UUID.randomUUID()}"
        File(root, "Data").mkdirs(); File(root, "Save").mkdirs()
        val original = File(root, "Save/progress.txt").apply { writeText("original game data") }
        val japanese = File(root, "Data/name.txt").apply { writeBytes("日本語".toByteArray(java.nio.charset.Charset.forName("windows-31j"))) }
        val before = original.readBytes()
        val source = AndroidWolfAssetSource(context, Uri.fromFile(root))
        var files = WolfNativeFiles(context, id, source)
        val privateRoot = files.directory.parentFile!!
        fun operation(kind: Int, path: String, value: String = "", encoding: Int = 0) = NativeEffect.FileOperation(1, kind, path, value, encoding)
        try {
            assertEquals("日本語", files.perform(operation(5, "data/NAME.TXT")))
            assertEquals("<NoFile>", files.perform(operation(5, "missing.txt")))
            assertEquals("0", files.perform(operation(8, "missing.txt")))
            files.perform(operation(6, "Save/progress.txt", "first\n日本語", 1))
            files.perform(operation(6, "save/PROGRESS.TXT", "second", 1))
            files.perform(operation(6, "Save/history.bak", "legitimate game file"))
            assertArrayEquals(before, original.readBytes())
            assertArrayEquals("日本語".toByteArray(java.nio.charset.Charset.forName("windows-31j")), japanese.readBytes())
            files.close(); files = WolfNativeFiles(context, id, source)
            assertEquals("second", files.perform(operation(5, "SAVE/Progress.TXT")))
            assertEquals("1", files.perform(operation(8, "save/progress.txt")))
            assertEquals(setOf("progress.txt", "history.bak"), files.list("Save").toSet())
            val store = WolfNativeSaveStore(context, id, "files-test")
            val output = java.io.ByteArrayOutputStream()
            store.export(output, files)
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                val entries = mutableMapOf<String, ByteArray>()
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
                assertTrue(entries.keys.contains("game-files/Save/progress.txt"))
                assertTrue(entries.keys.contains("game-files/Save/history.bak"))
                assertFalse(entries.keys.any { "Data/name.txt" in it })
                assertEquals(1, entries.keys.count { it.startsWith("file-backups/") })
                val previous = entries.entries.single { it.key.startsWith("file-backups/") }.value
                assertEquals("first\r\n日本語", fr.astragames.wolf.WolfFileText.decode(previous, 0, true))
            }
            fixture { outside ->
                File(outside, "secret.txt").writeText("outside")
                val link = File(files.directory, "escape")
                android.system.Os.symlink(outside.path, link.path)
                try {
                    rejects { files.read("escape/secret.txt") }
                    rejects { files.write("escape/new.txt", byteArrayOf(1)) }
                    assertFalse(File(outside, "new.txt").exists())
                } finally { link.delete() }
            }
        } finally {
            files.close(); source.close()
            check(privateRoot.canonicalFile.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            privateRoot.deleteRecursively()
        }
    }

    @Test fun protectedTextReportsItsUnsupportedProfileInsteadOfReadingClearTextOrReturningNoFile() = fixture { root ->
        val id = "native-protected-text-${UUID.randomUUID()}"
        File(root, "settings.txt").writeText("clear text must not win")
        File(root, "settings.txt.wolfx").writeBytes("WOLFX-owned-unsupported-fixture".toByteArray())
        File(root, "only.txt.wolfx").writeBytes("WOLFX-owned-unsupported-fixture".toByteArray())
        val privateRoot = WolfNativeSaveStore.directoryFor(context, id).parentFile!!
        try {
            AndroidWolfAssetSource(context, Uri.fromFile(root)).use { source ->
                WolfNativeFiles(context, id, source).use { files ->
                    for (path in listOf("settings.txt", "only.txt", "only.txt.wolfx")) {
                        try { files.perform(NativeEffect.FileOperation(1, 5, path)); fail("Protected text was treated as clear or missing") }
                        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("WOLFX")) }
                    }
                    assertEquals(0, source.filesRead)
                    assertEquals("<NoFile>", files.perform(NativeEffect.FileOperation(2, 5, "absent.txt")))
                    assertEquals("<NoFile>", files.perform(NativeEffect.FileOperation(3, 5, "absent.txt.wolfx")))
                }
            }
            assertEquals("clear text must not win", File(root, "settings.txt").readText())
            assertEquals("WOLFX-owned-unsupported-fixture", File(root, "only.txt.wolfx").readText())
        } finally {
            check(privateRoot.canonicalFile.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            privateRoot.deleteRecursively()
        }
    }

    @Test fun safGameTextReadsStayInsideTheSelectedFolderAndWritesFinishOnTheIoWorker() {
        val fixtureId = UUID.randomUUID().toString(); val id = "native-files-saf-$fixtureId"
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        var source: AndroidWolfAssetSource? = null; var files: WolfNativeFiles? = null
        try {
            val result = context.contentResolver.call(provider, "fixture", fixtureId, null)!!
            val parent = DocumentFile.fromTreeUri(context, Uri.parse(result.getString("uri")!!))!!
            val selected = parent.createDirectory("Game")!!
            val original = selected.createFile("text/plain", "settings.txt")!!
            context.contentResolver.openOutputStream(original.uri)!!.use { it.write("before".toByteArray()) }
            source = AndroidWolfAssetSource(context, selected.uri)
            files = WolfNativeFiles(context, id, source)
            val done = java.util.concurrent.CountDownLatch(1)
            var callback: Result<String>? = null; var callbackThread: Thread? = null
            files.execute(NativeEffect.FileOperation(5, 6, "settings.txt", "after")) {
                callback = it; callbackThread = Thread.currentThread(); done.countDown()
            }
            assertTrue("File write did not complete", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(callback!!.isSuccess); assertNotEquals(android.os.Looper.getMainLooper().thread, callbackThread)
            assertEquals("after", files.read("settings.txt").toString(Charsets.UTF_8))
            assertEquals("before", context.contentResolver.openInputStream(original.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            rejects { files.write("../parent.txt", byteArrayOf(1)) }
            assertFalse(parent.findFile("parent.txt")?.exists() == true)
        } finally {
            files?.close(); source?.close()
            val privateRoot = WolfNativeSaveStore.directoryFor(context, id).parentFile!!
            check(privateRoot.canonicalFile.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            privateRoot.deleteRecursively()
            context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        }
    }
    @Test fun directReadsAreLazyCaseAwareAndConfined() = fixture { root ->
        File(root, "Data/BasicData").mkdirs(); File(root, "Data/BasicData/Game.dat").writeText("profile")
        // This large unused subtree must never be visited to open a game profile.
        File(root, "Data/Unused").mkdirs(); repeat(1000) { File(root, "Data/Unused/$it.png").writeText("unused") }
        val source = AndroidWolfAssetSource(context, Uri.fromFile(root))
        assertEquals("profile", source.read("data\\basicdata\\GAME.DAT").toString(Charsets.UTF_8))
        assertEquals(1, source.filesRead); assertEquals(3, source.directoriesRead)
        assertFalse(source.exists("Data/BasicData/missing.dat"))
        assertEquals(1, source.filesRead); assertEquals(3, source.directoriesRead)
        for (path in listOf("../Game.dat", "/data/data/other/file", "C:/Game.dat", "Data/\u0000evil")) rejects { source.read(path) }
        assertEquals(1000, File(root, "Data/Unused").listFiles()!!.size)
    }
    @Test fun ambiguousCaseAndEscapingSymlinksAreRejected() = fixture { root ->
        File(root, "Hero.png").writeText("a"); File(root, "hero.PNG").writeText("b")
        val source = AndroidWolfAssetSource(context, Uri.fromFile(root))
        assertEquals("a", source.read("Hero.png").toString(Charsets.UTF_8))
        rejects { source.read("HERO.PNG") }
        fixture { other ->
            File(other, "secret").writeText("outside")
            android.system.Os.symlink(other.path, File(root, "escape").path)
            val fresh = AndroidWolfAssetSource(context, Uri.fromFile(root))
            rejects { fresh.read("escape/secret") }
            File(root, "escape").delete()
        }
    }
    @Test fun safNestedSelectionNeverReadsTheParentFolder() {
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        try {
            val result = context.contentResolver.call(provider, "fixture", id, null)!!
            val rootUri = Uri.parse(result.getString("uri")!!)
            val root = DocumentFile.fromTreeUri(context, rootUri)!!
            val selected = root.createDirectory("Selected")!!
            val data = selected.createDirectory("Data")!!
            val file = data.createFile("application/octet-stream", "Game.dat")!!
            context.contentResolver.openOutputStream(file.uri)!!.use { it.write("selected".toByteArray()) }
            val outside = root.createFile("application/octet-stream", "Parent.dat")!!
            context.contentResolver.openOutputStream(outside.uri)!!.use { it.write("parent".toByteArray()) }
            val source = AndroidWolfAssetSource(context, selected.uri)
            assertEquals("selected", source.read("data/game.dat").toString(Charsets.UTF_8))
            assertFalse(source.exists("Parent.dat")); assertEquals(listOf("Data"), source.list(""))
            rejects { source.read("../Parent.dat") }
        } finally { context.contentResolver.call(provider, "removeFixture", id, null) }
    }
    @Test fun nativeSavesAreAtomicSeparateAndBoundToTheGame() {
        val id = "native-save-test-${UUID.randomUUID()}"
        val store = WolfNativeSaveStore(context, id, "fingerprint-a")
        try {
            store.write(0, byteArrayOf(1, 2, 3)); store.write(0, byteArrayOf(4, 5))
            assertArrayEquals(byteArrayOf(4, 5), store.read(0)); assertEquals(listOf(0), store.slots())
            assertTrue(File(store.directory, "slot-0.astrawolf.previous").isFile)
            val exported = java.io.ByteArrayOutputStream().also(store::export).toByteArray()
            java.util.zip.ZipInputStream(exported.inputStream()).use { zip ->
                val entries = mutableMapOf<String, ByteArray>()
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
                assertEquals(setOf("native-saves/slot-0.astrawolf", "native-saves/slot-0.astrawolf.previous", "native-saves/README.txt"), entries.keys)
                assertArrayEquals(File(store.directory, "slot-0.astrawolf").readBytes(), entries["native-saves/slot-0.astrawolf"])
                assertTrue(entries.getValue("native-saves/README.txt").toString(Charsets.UTF_8).contains("pas compatibles"))
            }
            rejects { WolfNativeSaveStore(context, id, "fingerprint-b").read(0) }
            val file = File(store.directory, "slot-0.astrawolf")
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = 42; file.writeBytes(bytes)
            rejects { store.read(0) }
            assertTrue(File(store.directory, "slot-0.astrawolf.previous").isFile)
            for (slot in listOf(-1, Int.MAX_VALUE)) rejects { store.write(slot, byteArrayOf()) }
        } finally {
            check(store.directory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            store.directory.parentFile!!.deleteRecursively()
        }
    }
    @Test fun repeatedMetadataReadsReuseOneValidatedFileAndDetectReplacementAndCorruption() {
        val id = "native-save-read-cache-${UUID.randomUUID()}"
        val fixture = File(context.cacheDir, id).apply { mkdirs() }
        val store = WolfNativeSaveStore(context, id, "cached-save-fingerprint")
        try {
            NativeGameFixture.write(fixture)
            val state = AndroidWolfAssetSource(context, android.net.Uri.fromFile(fixture)).use {
                fr.astragames.wolf.WolfRuntime.load(it).snapshot()
            }
            val key = "slot:3"
            store.writeData(key, WolfSaveData(snapshot = state, numbers = mapOf(2_000_000 to 7, 2_000_001 to 9)))
            val first = store.readData(key)
            val before = store.envelopesRead
            repeat(60) { assertEquals(7, store.readData(key).numbers[2_000_000]) }
            if (android.os.Build.VERSION.SDK_INT >= 27) assertEquals(before, store.envelopesRead)
            else assertEquals(before + 60, store.envelopesRead)
            // A caller cannot mutate the retained state or variable table.
            val returnedState = requireNotNull(first.snapshot)
            returnedState[0] = (returnedState[0].toInt() xor 1).toByte()
            (first.numbers as MutableMap<Int, Int>)[2_000_000] = 99
            val second = store.readData(key)
            assertArrayEquals(state, second.snapshot); assertEquals(7, second.numbers[2_000_000])
            val file = File(store.directory, "slot-3.astrawolf")
            val oldTime = file.lastModified()
            // A different store instance replaces the same identity. Equal mtime
            // is insufficient: inode/ctime must invalidate the first instance.
            WolfNativeSaveStore(context, id, "cached-save-fingerprint").writeData(key,
                WolfSaveData(snapshot = state, numbers = mapOf(2_000_000 to 8, 2_000_001 to 9)))
            assertTrue(file.setLastModified(oldTime))
            assertEquals(8, store.readData(key).numbers[2_000_000])
            // Legacy AtomicFile recovery must still win over a warm cache.
            File("${file.path}.bak").writeBytes(File("${file.path}.previous").readBytes())
            assertEquals(7, store.readData(key).numbers[2_000_000])
            assertFalse(File("${file.path}.bak").exists())
            val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(bytes); file.setLastModified(oldTime)
            rejects { store.readData(key) }
        } finally {
            check(store.directory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            store.directory.parentFile!!.deleteRecursively()
            check(fixture.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            fixture.deleteRecursively()
        }
    }
    @Test fun namedPartialSavesKeepIndependentVariablesAndRejectEscapingNames() {
        val id = "native-partial-save-${UUID.randomUUID()}"
        val store = WolfNativeSaveStore(context, id, "partial-fingerprint")
        try {
            val key = "Save/日本語.sav"
            assertFalse(store.exists(key))
            store.writeData(key, WolfSaveData(numbers = mapOf(2_000_001 to 81)))
            val previous = store.readData(key)
            store.writeData(key, previous.copy(strings = mapOf(3_000_002 to "progress")))
            val loaded = store.readData(key)
            assertNull(loaded.snapshot); assertEquals(81, loaded.numbers[2_000_001]); assertEquals("progress", loaded.strings[3_000_002])
            assertTrue(store.exists(key)); assertTrue(store.slots().isEmpty())
            assertTrue(store.exists("save/日本語.SAV"))
            assertEquals(loaded.numbers, store.readData("SAVE/日本語.Sav").numbers)
            assertEquals(2, store.directory.listFiles()!!.size)
            assertTrue(store.directory.listFiles()!!.all { it.parentFile!!.canonicalFile == store.directory.canonicalFile })
            for (name in listOf("../Save/test.sav", "Save/../test.sav", "/absolute.sav", "C:/outside.sav", "Save//test.sav", "Save\\test.sav", "slot:-1", "slot:10000"))
                rejects { store.writeData(name, WolfSaveData()) }
            rejects { WolfNativeSaveStore(context, id, "different-game").readData(key) }
            assertEquals(81, store.readData(key).numbers[2_000_001])
        } finally {
            check(store.directory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            store.directory.parentFile!!.deleteRecursively()
        }
    }
}
