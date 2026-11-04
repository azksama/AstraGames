package fr.astragames.app.mkxpz

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.documentDir
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Case-sensitive Android files and a real DocumentsProvider, not the host's Windows filesystem. */
class MkxpzIniTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedDirectories = mutableListOf<File>()
    private val providerFixtures = mutableListOf<String>()
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val iniBytes = "[Game]\nLibrary=RGSS104E.dll\nScripts=Data/Scripts.rxdata\nTitle=Case fixture\n".toByteArray()

    @After fun removeOnlyTestData() {
        providerFixtures.forEach { context.contentResolver.call(provider, "removeFixture", it, null) }
        ownedDirectories.asReversed().forEach { directory ->
            val path = directory.canonicalFile.toPath()
            check(path.startsWith(context.filesDir.canonicalFile.toPath()) ||
                path.startsWith(context.cacheDir.canonicalFile.toPath()))
            check(directory != context.filesDir && directory != context.cacheDir)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private data class Fixture(val source: File, val game: GameEntity, val storage: MkxpzStorage)

    private fun fixture(executable: String = "Game.exe", iniName: String = "Game.ini"): Fixture {
        val id = "mkxpz-ini-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdir()) }
        val storage = MkxpzStorage(context, id)
        ownedDirectories += source
        ownedDirectories += storage.directory
        File(source, executable).writeText("synthetic entry; the RGSS runtime does not execute PE files")
        File(source, iniName).writeBytes(iniBytes)
        val game = game(id, Uri.fromFile(source), source.absolutePath, executable)
        return Fixture(source, game, storage)
    }

    private fun game(id: String, source: Uri, path: String?, executable: String) = GameEntity(
        id = id, title = "INI case fixture", documentUri = source.toString(), physicalPath = path,
        executableName = executable, engine = "RPG_MAKER_XP", launcher = MkxpzRuntime.LAUNCHER,
        sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)

    private fun assertNativeIni(storage: MkxpzStorage, bootstrap: File, execName: String) {
        val config = JSONObject(File(bootstrap, "mkxp.json").readText())
        assertEquals(execName, config.getString("execName"))
        assertEquals(storage.gameDirectory.absolutePath, config.getString("gameFolder"))
        // Native Config::readGameINI performs fopen(execName + ".ini") before the VFS.
        val ini = File(storage.gameDirectory, "$execName.ini")
        assertTrue("Native exact INI path must exist: $ini", ini.isFile)
        assertArrayEquals(iniBytes, ini.readBytes())
        assertEquals(listOf("$execName.ini"), storage.gameDirectory.listFiles().orEmpty()
            .filter { it.name.equals("$execName.ini", ignoreCase = true) }.map { it.name })
        assertTrue(storage.isImported)
    }

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().joinToString(",") }

    @Test fun uppercaseExtensionIsNormalizedOnlyInPrivateCopyAndSurvivesRelaunch() {
        val fixture = fixture(iniName = "GAME.INI")
        val sourceBefore = snapshot(fixture.source)
        val bootstrap = fixture.storage.prepare(fixture.game, null, 1)
        assertNativeIni(fixture.storage, bootstrap, "Game")
        assertEquals(sourceBefore, snapshot(fixture.source))
        val save = File(fixture.storage.gameDirectory, "Save01.rxdata").apply { writeText("private progress") }
        val privateIni = File(fixture.storage.gameDirectory, "Game.ini").apply { appendText("; local change\n") }
        val retainedIni = privateIni.readBytes()
        // A completed import must work offline and must not replay the source over local progress.
        assertTrue(fixture.source.deleteRecursively())
        val reopened = MkxpzStorage(context, fixture.game.id)
        assertEquals(bootstrap, reopened.prepare(fixture.game, null, 1))
        assertArrayEquals(retainedIni, privateIni.readBytes())
        assertEquals("private progress", save.readText())
        assertFalse(fixture.source.exists())
    }

    @Test fun lowercaseExecutableGetsItsExactNativeIniWithoutChangingSource() {
        val fixture = fixture(executable = "game.exe")
        val sourceBefore = snapshot(fixture.source)
        assertNativeIni(fixture.storage, fixture.storage.prepare(fixture.game, null, 1), "game")
        assertEquals(sourceBefore, snapshot(fixture.source))
    }

    @Test fun profileExecutableDeterminesTheNativeIniName() {
        val fixture = fixture(iniName = "bOoT.INI")
        val sourceBefore = snapshot(fixture.source)
        val profile = LaunchProfileEntity(fixture.game.id, launcherType = MkxpzRuntime.LAUNCHER,
            executableName = "Boot.exe")
        assertNativeIni(fixture.storage, fixture.storage.prepare(fixture.game, profile, 1), "Boot")
        assertEquals(sourceBefore, snapshot(fixture.source))
    }

    @Test fun safIniIsResolvedWithoutRenamingOrWritingItsDocument() {
        val id = "mkxpz-ini-${UUID.randomUUID()}"
        providerFixtures += id
        val treeUri = Uri.parse(context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri"))
        val source = documentDir(context, treeUri)!!.createDirectory("native-game")!!
        val original = source.createFile("application/octet-stream", "gAmE.INI")!!
        context.contentResolver.openOutputStream(original.uri, "wt")!!.use { it.write(iniBytes) }
        val storage = MkxpzStorage(context, id)
        ownedDirectories += storage.directory
        val game = game(id, source.uri, null, "Game.exe")
        assertNativeIni(storage, storage.prepare(game, null, 1), "Game")
        assertEquals(listOf("gAmE.INI"), source.listFiles().map { it.name })
        context.contentResolver.openInputStream(original.uri)!!.use { assertArrayEquals(iniBytes, it.readBytes()) }
    }

    @Test fun sourceCaseCollisionIsExplicitlyRejectedEvenWithAnExactMatch() {
        val fixture = fixture()
        File(fixture.source, "GAME.INI").writeText("[Game]\nScripts=Different.rxdata\n")
        val sourceBefore = snapshot(fixture.source)
        val failure = runCatching { fixture.storage.prepare(fixture.game, null, 1) }.exceptionOrNull()
        assertTrue("Ambiguous INIs must not pick whichever entry is listed first: $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("ambigu", ignoreCase = true))
        assertTrue(failure.message.orEmpty().contains("Game.ini"))
        assertTrue(failure.message.orEmpty().contains("GAME.INI"))
        assertFalse(fixture.storage.isImported)
        assertEquals(sourceBefore, snapshot(fixture.source))
    }

    @Test fun completedPrivateCopyWithCaseCollisionIsRejectedWithoutChangingAnyFiles() {
        val fixture = fixture()
        fixture.storage.prepare(fixture.game, null, 1)
        File(fixture.storage.gameDirectory, "GAME.INI").writeText("[Game]\nScripts=Different.rxdata\n")
        File(fixture.storage.gameDirectory, "Save01.rxdata").writeText("retained private progress")
        val sourceBefore = snapshot(fixture.source)
        val privateBefore = snapshot(fixture.storage.directory)
        val failure = runCatching { fixture.storage.prepare(fixture.game, null, 1) }.exceptionOrNull()
        assertTrue("An existing exact match must not hide a second INI: $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("ambigu", ignoreCase = true))
        assertEquals(sourceBefore, snapshot(fixture.source))
        assertEquals(privateBefore, snapshot(fixture.storage.directory))
    }
}
