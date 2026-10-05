package fr.astragames.app.data.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackupRollbackTest {
    @Test fun restoresInReverseOrderAndRetainsOriginalCopies() = withDirectory { root ->
        val first = File(root, "first").apply { writeText("changed") }
        val second = File(root, "second").apply { writeText("new") }
        val backup = File(root, "first.original").apply { writeText("original") }
        val visited = mutableListOf<String>()
        val failures = BackupRollback.restore(listOf(first to backup, second to null),
            copyFile = { source, target -> visited += target.name; source.copyTo(target, overwrite = true) },
            deleteFile = { visited += it.name; it.delete() })
        assertTrue(failures.isEmpty())
        assertEquals(listOf("second", "first"), visited)
        assertEquals("original", first.readText())
        assertFalse(second.exists())
        assertEquals("original", backup.readText())
    }

    @Test fun copyFailuresDoNotSkipRemainingFilesOrDeleteRecoveryCopies() = withDirectory { root ->
        val first = File(root, "first").apply { writeText("changed first") }
        val second = File(root, "second").apply { writeText("changed second") }
        val firstBackup = File(root, "first.original").apply { writeText("old first") }
        val secondBackup = File(root, "second.original").apply { writeText("old second") }
        val fault = IOException("write failure")
        val failures = BackupRollback.restore(listOf(first to firstBackup, second to secondBackup), copyFile = { source, target ->
            if (target == second) throw fault
            source.copyTo(target, overwrite = true)
        })
        assertEquals(1, failures.size)
        assertSame(fault, failures.single().cause)
        assertTrue(failures.single().message.orEmpty().contains(second.absolutePath))
        assertEquals("old first", first.readText())
        assertEquals("old second", secondBackup.readText())
        assertEquals("old first", firstBackup.readText())
    }

    @Test fun falseDeletesAreReportedAndOtherRestorationsStillRun() = withDirectory { root ->
        val original = File(root, "original").apply { writeText("old") }
        val changed = File(root, "changed").apply { writeText("new content") }
        val created = File(root, "created").apply { writeText("new file") }
        val failures = BackupRollback.restore(listOf(changed to original, created to null), deleteFile = { false })
        assertEquals(1, failures.size)
        assertTrue(created.exists())
        assertEquals("old", changed.readText())
        assertEquals("old", original.readText())
    }

    @Test fun silentCorruptionAndMissingOriginalsAreReportedTogether() = withDirectory { root ->
        val original = File(root, "original").apply { writeText("old") }
        val changed = File(root, "changed").apply { writeText("new") }
        val missing = File(root, "missing.original")
        val untouched = File(root, "untouched").apply { writeText("keep") }
        val failures = BackupRollback.restore(listOf(changed to original, untouched to missing), copyFile = { _, target -> target.writeText("bad") })
        assertEquals(2, failures.size)
        assertEquals("keep", untouched.readText())
        assertEquals("old", original.readText())
    }

    @Test fun alreadyAbsentNewFilesDoNotCountAsRollbackFailures() = withDirectory { root ->
        val missing = File(root, "already removed")
        assertTrue(BackupRollback.restore(listOf(missing to null), deleteFile = { throw AssertionError("No deletion needed") }).isEmpty())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("astra-rollback-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
