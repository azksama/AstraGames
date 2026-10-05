package fr.astragames.app.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationPatchTest {
    private class MemoryFiles : TranslationFiles {
        val content = mutableMapOf("Actors.json" to "original actors".toByteArray(), "Map001.json" to "original map".toByteArray())
        var failPath: String? = null
        override fun read(path: String) = content[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray) {
            if (path == failPath) { failPath = null; content[path] = bytes.take(3).toByteArray(); error("disk failure") }
            content[path] = bytes.copyOf()
        }
        override fun delete(path: String) { content.remove(path) }
    }
    private fun changes(files: MemoryFiles) = listOf("Actors.json", "Map001.json").map {
        TranslationPatch.Change(it, files.read(it)!!, "translated $it".toByteArray())
    }

    @Test fun originalsSurviveAndRestoreIsRepeatable() {
        val files = MemoryFiles(); val original = files.content.mapValues { it.value.copyOf() }
        val patch = TranslationPatch(files)
        patch.apply(changes(files), "en", "fr")
        assertTrue(patch.exists())
        assertEquals("translated Actors.json", files.read("Actors.json")!!.toString(Charsets.UTF_8))
        assertArrayEquals(original["Actors.json"], files.read(".astra-translation/original/Actors.json"))
        patch.restore(); patch.restore()
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, files.read(path)) }
        assertFalse(patch.exists())
    }

    @Test fun partialWriteRollsBackEveryTouchedFile() {
        val files = MemoryFiles(); val original = files.content.mapValues { it.value.copyOf() }
        files.failPath = "Map001.json"
        assertThrows(IllegalStateException::class.java) { TranslationPatch(files).apply(changes(files), "en", "fr") }
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, files.read(path)) }
    }

    @Test fun failedBackupNeverChangesTheGame() {
        val files = MemoryFiles(); val original = files.content.mapValues { it.value.copyOf() }
        files.failPath = ".astra-translation/original/Map001.json"
        assertThrows(IllegalStateException::class.java) { TranslationPatch(files).apply(changes(files), "en", "fr") }
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, files.read(path)) }
    }

    @Test fun refusesExternalChangesAndCorruptBackupsBeforeRestoringAnyFile() {
        val files = MemoryFiles(); val patch = TranslationPatch(files)
        patch.apply(changes(files), "en", "fr")
        files.write("Map001.json", "new game version".toByteArray())
        assertThrows(IllegalStateException::class.java) { patch.restore() }
        assertEquals("translated Actors.json", files.read("Actors.json")!!.toString(Charsets.UTF_8))
        files.write("Map001.json", "translated Map001.json".toByteArray())
        files.write(".astra-translation/original/Map001.json", "damaged".toByteArray())
        assertThrows(IllegalStateException::class.java) { patch.restore() }
        assertEquals("translated Actors.json", files.read("Actors.json")!!.toString(Charsets.UTF_8))
    }

    @Test fun journalRecoversAnInterruptedTruncation() {
        val files = MemoryFiles(); val original = files.read("Map001.json")!!
        val patch = TranslationPatch(files)
        patch.apply(changes(files), "en", "fr")
        files.write(".astra-translation/pending", "Map001.json".toByteArray())
        files.write("Map001.json", byteArrayOf())
        TranslationPatch(files).restore()
        assertArrayEquals(original, files.read("Map001.json"))
    }

    @Test fun refusesRetranslatingOverExistingBackupAndStaleAnalysis() {
        val files = MemoryFiles(); val patch = TranslationPatch(files); val changes = changes(files)
        files.write("Actors.json", "external change".toByteArray())
        assertThrows(IllegalStateException::class.java) { patch.apply(changes, "en", "fr") }
        patch.apply(changes(files), "en", "fr")
        assertThrows(IllegalStateException::class.java) { patch.apply(changes(files), "en", "de") }
    }

    @Test fun launchRejectsMixedFilesPendingWritesAndUnknownJournalVersions() {
        val files = MemoryFiles(); val patch = TranslationPatch(files)
        val original = files.read("Actors.json")!!
        assertTrue(patch.isLaunchSafe())
        patch.apply(changes(files), "en", "fr")
        assertTrue(patch.isLaunchSafe())
        files.write("Actors.json", original)
        assertFalse(patch.isLaunchSafe())
        patch.restore()
        assertTrue(patch.isLaunchSafe())
        patch.apply(changes(files), "en", "fr")
        files.write(".astra-translation/pending", "Map001.json".toByteArray())
        assertFalse(patch.isLaunchSafe())
        files.delete(".astra-translation/pending")
        val journal = org.json.JSONObject(files.read(TranslationPatch.MANIFEST)!!.toString(Charsets.UTF_8)).put("version", 2)
        files.write(TranslationPatch.MANIFEST, journal.toString().toByteArray())
        assertFalse(patch.isLaunchSafe())
    }

    @Test fun failedJournalLeavesOriginalsIntactAndAllowsRetry() {
        val files = MemoryFiles(); val original = files.content.mapValues { it.value.copyOf() }
        val patch = TranslationPatch(files)
        files.failPath = TranslationPatch.MANIFEST
        assertThrows(IllegalStateException::class.java) { patch.apply(changes(files), "en", "fr") }
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, files.read(path)) }
        assertFalse(patch.exists())
        assertTrue(patch.isLaunchSafe())
        patch.apply(changes(files), "en", "fr")
        assertTrue(patch.exists())
    }

    @Test fun orphanPendingMarkerNeverAllowsLaunchingOrStartingAnotherPatch() {
        val files = MemoryFiles()
        files.write(".astra-translation/pending", "Map001.json".toByteArray())
        val patch = TranslationPatch(files)
        assertFalse(patch.isLaunchSafe())
        assertThrows(IllegalStateException::class.java) { patch.apply(changes(files), "en", "fr") }
    }

    @Test fun duplicateJournalKeysAreRejectedWithoutChangingAnyGameFile() {
        val files = MemoryFiles(); val patch = TranslationPatch(files)
        patch.apply(changes(files), "en", "fr")
        val journal = files.read(TranslationPatch.MANIFEST)!!.toString(Charsets.UTF_8)
        files.write(TranslationPatch.MANIFEST, journal.replaceFirst("{", "{\"version\":1,").toByteArray())
        assertFalse(patch.isLaunchSafe())
        assertThrows(IllegalArgumentException::class.java) { patch.restore() }
        assertEquals("translated Actors.json", files.read("Actors.json")!!.toString(Charsets.UTF_8))
    }

    @Test fun restoreRechecksConflictsAfterPreflightBeforeEveryWrite() {
        val files = MemoryFiles()
        TranslationPatch(files).apply(changes(files), "en", "fr")
        var mapReads = 0
        val concurrent = object : TranslationFiles {
            override fun read(path: String): ByteArray? {
                if (path == "Map001.json" && ++mapReads == 2) files.write(path, "external update".toByteArray())
                return files.read(path)
            }
            override fun write(path: String, bytes: ByteArray) = files.write(path, bytes)
            override fun delete(path: String) = files.delete(path)
        }
        assertThrows(IllegalStateException::class.java) { TranslationPatch(concurrent).restore() }
        assertEquals("external update", files.read("Map001.json")!!.toString(Charsets.UTF_8))
        assertTrue(TranslationPatch(files).exists())
        assertFalse(TranslationPatch(files).isLaunchSafe())
    }
}
