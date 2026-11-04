package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.GameEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercise the on-disk interruption boundary, including Android's case-sensitive filenames. */
class MkxpzImportResumeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedDirectories = mutableListOf<File>()
    private val sourceIni = "[Game]\nLibrary=RGSS104E.dll\nScripts=Data/Scripts.rxdata\nTitle=Resume fixture\n"

    @After fun removeOnlyTestData() {
        ownedDirectories.asReversed().forEach { directory ->
            val path = directory.canonicalFile.toPath()
            check(path.startsWith(context.filesDir.canonicalFile.toPath()) ||
                path.startsWith(context.cacheDir.canonicalFile.toPath()))
            check(directory != context.filesDir && directory != context.cacheDir)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private data class Fixture(val source: File, val game: GameEntity, val storage: MkxpzStorage)

    private fun fixture(): Fixture {
        val id = "mkxpz-resume-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdirs()) }
        File(source, "GAME.INI").writeText(sourceIni)
        File(source, "Save01.rxdata").writeText("source progress")
        File(source, "Data").mkdir()
        File(source, "Data/remaining.dat").writeText("remaining asset")
        val storage = MkxpzStorage(context, id)
        ownedDirectories += source
        ownedDirectories += storage.directory
        check(storage.gameDirectory.mkdirs())
        val game = GameEntity(id = id, title = "MKXP-Z resume fixture",
            documentUri = Uri.fromFile(source).toString(), physicalPath = source.absolutePath,
            executableName = "Game.exe", engine = "RPG_MAKER_XP", launcher = MkxpzRuntime.LAUNCHER,
            sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)
        return Fixture(source, game, storage)
    }

    private fun snapshot(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }

    private fun assertResume(privateIniName: String) {
        val fixture = fixture()
        val privateIni = sourceIni + "; private change survives retry\n"
        File(fixture.storage.gameDirectory, privateIniName).writeText(privateIni)
        File(fixture.storage.gameDirectory, "Save01.rxdata").writeText("private progress")
        val sourceBefore = snapshot(fixture.source)
        assertFalse(fixture.storage.isImported)
        assertFalse(File(fixture.storage.directory, "import-source.txt").exists())

        val reopened = MkxpzStorage(context, fixture.game.id)
        reopened.prepare(fixture.game, null, 1)

        assertTrue(reopened.isImported)
        assertEquals(privateIni, File(reopened.gameDirectory, "Game.ini").readText())
        assertEquals(listOf("Game.ini"), reopened.gameDirectory.listFiles().orEmpty()
            .filter { it.name.equals("Game.ini", ignoreCase = true) }.map { it.name })
        assertEquals("private progress", File(reopened.gameDirectory, "Save01.rxdata").readText())
        assertEquals("remaining asset", File(reopened.gameDirectory, "Data/remaining.dat").readText())
        assertEquals(sourceBefore, snapshot(fixture.source))
        assertFalse(reopened.directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        val completed = snapshot(reopened.directory)
        reopened.prepare(fixture.game, null, 1)
        assertEquals(completed, snapshot(reopened.directory))
    }

    @Test fun interruptionAfterIniNormalizationResumesWithoutRecreatingUppercaseIni() = assertResume("Game.ini")

    @Test fun interruptionBeforeIniNormalizationKeepsExistingPrivateIniAndProgress() = assertResume("GAME.INI")

    @Test fun resumedImportRejectsGenuinePrivateCaseCollisionWithoutChangingFiles() {
        val fixture = fixture()
        File(fixture.storage.gameDirectory, "GAME.INI").writeText(sourceIni)
        File(fixture.storage.gameDirectory, "Game.ini").writeText("different private configuration")
        File(fixture.storage.gameDirectory, "Save01.rxdata").writeText("private progress")
        val sourceBefore = snapshot(fixture.source)
        val privateBefore = snapshot(fixture.storage.directory)

        val failure = runCatching { fixture.storage.prepare(fixture.game, null, 1) }.exceptionOrNull()

        assertTrue("Real collisions must still fail: $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("ambigu", ignoreCase = true))
        assertFalse(fixture.storage.isImported)
        assertEquals(sourceBefore, snapshot(fixture.source))
        assertEquals(privateBefore, snapshot(fixture.storage.directory))
    }

    @Test fun normalizedPrivateCopyDoesNotHideGenuineSourceCaseCollision() {
        val fixture = fixture()
        File(fixture.source, "Game.ini").writeText("second source configuration")
        File(fixture.storage.gameDirectory, "Game.ini").writeText(sourceIni)
        val sourceBefore = snapshot(fixture.source)
        val privateBefore = snapshot(fixture.storage.directory)

        val failure = runCatching { fixture.storage.prepare(fixture.game, null, 1) }.exceptionOrNull()

        assertTrue("Source collisions must still fail: $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("ambigu", ignoreCase = true))
        assertFalse(fixture.storage.isImported)
        assertEquals(sourceBefore, snapshot(fixture.source))
        assertEquals(privateBefore, snapshot(fixture.storage.directory))
    }
}
