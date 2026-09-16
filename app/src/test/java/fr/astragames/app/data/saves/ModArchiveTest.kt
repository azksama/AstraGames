package fr.astragames.app.data.saves

import fr.astragames.app.data.mods.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg paths: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> paths.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("content".toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    @Test fun unwrapsDownloadFolderButKeepsEngineLayout() {
        val root = ModArchive.extract(zip("download/game/script.rpy").inputStream(), temp.newFolder())
        assertTrue(java.io.File(root, "game/script.rpy").isFile)
    }

    @Test fun rejectsTraversalCaseDuplicatesAndEmptyArchives() {
        for (archive in listOf(zip("../outside"), zip("game/a", "game/A"), zip())) {
            assertTrue(runCatching { ModArchive.extract(archive.inputStream(), temp.newFolder()) }.isFailure)
        }
    }

    @Test fun manifestRejectsUnsafePathsAndUnknownModes() {
        for (field in listOf("\"target\":\"../outside\"", "\"filesRoot\":\"/tmp\"", "\"installMode\":\"WHAT\"", "\"formatVersion\":2")) {
            assertTrue(runCatching { AstraModManifestParser.parse("{\"id\":\"a\",\"name\":\"A\",$field}") }.isFailure)
        }
        assertNull(ZipPathGuard.sanitize("C:relative"))
        assertNull(ZipPathGuard.sanitize("game/./script.rpy"))
        assertEquals("v1..2/file", ZipPathGuard.sanitize("v1..2/file"))
    }
}
