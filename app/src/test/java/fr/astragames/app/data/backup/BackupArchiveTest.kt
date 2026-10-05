package fr.astragames.app.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTest {
    @Test fun extractsSupportedCatalogAndAssets() = withDirectory { root ->
        BackupArchive.extract(zip("database/astra_games.db" to "catalog", "covers/poster.png" to "image").inputStream(), root)
        assertEquals("catalog", File(root, "database/astra_games.db").readText())
        assertEquals("image", File(root, "covers/poster.png").readText())
    }

    @Test fun rejectsTraversalEvenWhenItEndsInsideStaging() = withDirectory { root ->
        assertThrows(IllegalStateException::class.java) {
            BackupArchive.extract(zip("covers/../database/astra_games.db" to "malicious").inputStream(), root)
        }
        assertFalse(File(root, "database/astra_games.db").exists())
    }

    @Test fun rejectsUnexpectedPrivateFiles() = withDirectory { root ->
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(zip("datastore/astra_settings.preferences_pb" to "pin").inputStream(), root)
        }
    }

    @Test fun rejectsCaseInsensitiveDuplicates() = withDirectory { root ->
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(zip("covers/poster.png" to "one", "covers/POSTER.png" to "two").inputStream(), root)
        }
    }

    @Test fun countsDirectoryPayloadTowardArchiveBudget() = withDirectory { root ->
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(zip("covers/" to "123456", "covers/image" to "12345").inputStream(), root, maxBytes = 10)
        }
    }

    @Test fun writerAndReaderShareExpandedByteAndEntryBudgets() = withDirectory { root ->
        val source = File(root, "source").apply { writeText("12345") }
        val entries = sequenceOf("database/astra_games.db" to source, "covers/image" to source)
        val encoded = ByteArrayOutputStream().also { BackupArchive.write(it, entries, maxBytes = 10, maxEntries = 2) }.toByteArray()
        val destination = File(root, "result").apply { mkdir() }
        BackupArchive.extract(encoded.inputStream(), destination, maxBytes = 10, maxEntries = 2)
        assertEquals("12345", File(destination, "covers/image").readText())
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(ByteArrayOutputStream(), entries, maxBytes = 9, maxEntries = 2) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.extract(encoded.inputStream(), destination, maxBytes = 9, maxEntries = 2) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(ByteArrayOutputStream(), entries, maxBytes = 10, maxEntries = 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.extract(encoded.inputStream(), destination, maxBytes = 10, maxEntries = 1) }
    }

    @Test fun highlyCompressibleInputCannotBypassExportBudget() = withDirectory { root ->
        val source = File(root, "source").apply { writeBytes(ByteArray(100_000)) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(ByteArrayOutputStream(), sequenceOf("covers/image" to source), maxBytes = 10_000)
        }
    }

    @Test fun writerRejectsAliasesAndUnexpectedPrivateFiles() = withDirectory { root ->
        val source = File(root, "source").apply { writeText("data") }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(ByteArrayOutputStream(), sequenceOf("covers/a" to source, "covers/A" to source))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(ByteArrayOutputStream(), sequenceOf("datastore/settings" to source))
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("astra-backup-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
