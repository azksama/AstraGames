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

class WolfNativeStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.cacheDir, "native-source-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe/invalid input was accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
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
}
