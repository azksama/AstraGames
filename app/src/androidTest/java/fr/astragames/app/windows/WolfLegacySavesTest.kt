package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class WolfLegacySavesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun listingDoesNotCreateNativeStorageAndExportKeepsAllRecoveryBytes() {
        val store = WolfLegacySaves(context, "legacy-export-${System.nanoTime()}")
        assertTrue(store.files().isEmpty())
        assertFalse(store.directory.exists())
        check(store.directory.mkdirs())
        val fixtures = mapOf("slot-0.astrawolf" to byteArrayOf(0, 1, 2),
            "slot-0.astrawolf.previous" to byteArrayOf(3, 4), "slot-1.astrawolf.bak" to byteArrayOf(5, 6),
            "slot-2.astrawolf.new" to byteArrayOf(7, 8))
        try {
            fixtures.forEach { (name, bytes) -> File(store.directory, name).writeBytes(bytes) }
            val output = ByteArrayOutputStream()
            store.export(output)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { entries[entry.name] = zip.readBytes(); entry = zip.nextEntry }
            }
            fixtures.forEach { (name, bytes) ->
                assertArrayEquals(bytes, entries["native-saves/$name"])
                assertArrayEquals(bytes, File(store.directory, name).readBytes())
            }
        } finally { store.directory.parentFile!!.deleteRecursively() }
    }
}
