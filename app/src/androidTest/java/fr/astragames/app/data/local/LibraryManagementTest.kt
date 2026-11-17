package fr.astragames.app.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.core.filesystem.GameFolders
import fr.astragames.app.core.search.DuplicateDetector
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.repository.SaveConflictStrategy
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryManagementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private lateinit var db: AstraDatabase
    private lateinit var dao: AstraDao
    private lateinit var scanner: RecursiveSourceScanner
    private lateinit var repository: GameRepository
    private lateinit var fixtureId: String
    private lateinit var root: DocumentFile
    private lateinit var library: DocumentFile

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        dao = db.dao()
        scanner = RecursiveSourceScanner(context, dao, FileAccessResolver(context))
        repository = GameRepository(context, dao, scanner, BackupManager(context, db))
        fixtureId = UUID.randomUUID().toString()
        val uri = context.contentResolver.call(provider, "fixture", fixtureId, null)!!.getString("uri")!!
        root = documentDir(context, Uri.parse(uri))!!
        library = root.createDirectory("library")!!
        dao.upsertSource(GameSourceEntity("source", "Library", library.uri.toString()))
    }

    @After fun cleanup() {
        for (method in listOf("denyAccess", "denyDelete", "ineffectiveDelete")) context.contentResolver.call(provider, method, null, null)
        context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        db.close()
    }

    private fun makeFolder(name: String): DocumentFile = library.createDirectory(name)!!.also { folder ->
        write(folder.createFile("application/octet-stream", "Game.exe")!!, "executable-$name")
        val data = folder.createDirectory("Data")!!
        write(data.createFile("application/octet-stream", "System.rvdata2")!!, "engine-$name")
    }
    private fun write(file: DocumentFile, value: String) { context.contentResolver.openOutputStream(file.uri)!!.use { it.write(value.toByteArray()) } }
    private fun read(file: DocumentFile) = context.contentResolver.openInputStream(file.uri)!!.bufferedReader().use { it.readText() }
    private suspend fun scanned(folder: DocumentFile): GameEntity {
        scanner.scan("source")
        return dao.getAllGames().single { GameFolders.same(it.documentUri, folder.uri.toString()) }
    }

    @Test fun deleteRemovesRealSafFolderAndRecordButKeepsTheOtherCopy() = runBlocking {
        val removed = makeFolder("Quest v1.0")
        val retained = makeFolder("Quest v2.0")
        val game = scanned(removed)
        val retainedGame = dao.getAllGames().single { it.id != game.id }
        assertEquals(1, DuplicateDetector.groups(dao.getAllGames()).size)
        repository.deleteGame(game.id, true)
        assertFalse(removed.exists())
        assertNull(dao.getGame(game.id))
        assertEquals(retainedGame, dao.getGame(retainedGame.id))
        assertEquals(1, dao.getSource("source")!!.gamesCount)
        assertEquals("executable-Quest v2.0", read(retained.findFile("Game.exe")!!))
        assertTrue(dao.getIgnoredFolders().isEmpty())
        assertEquals(0, scanner.scan("source").added)
        assertEquals(1, dao.getAllGames().size)
    }

    @Test fun ignoreExcludesOnlyTheSelectedFolderAndCanBeRestored() = runBlocking {
        val ignored = makeFolder("Quest v1.0")
        val game = scanned(ignored)
        repository.deleteGame(game.id, false)
        val copy = makeFolder("Quest v2.0")
        val result = scanner.scan("source")
        assertEquals(1, result.ignored)
        assertEquals(1, result.added)
        assertTrue(ignored.exists())
        assertEquals("executable-Quest v1.0", read(ignored.findFile("Game.exe")!!))
        assertTrue(GameFolders.same(copy.uri.toString(), dao.getAllGames().single().documentUri))
        repository.restoreDeletedGame(dao.getIgnoredFolders().single().id)
        assertEquals(1, scanner.scan("source").added)
        assertEquals(2, dao.getAllGames().size)
    }

    @Test fun ignoreStopsTargetedDescendantScansAndSurvivesUnavailableStorage() = runBlocking {
        val folder = makeFolder("Ignored")
        val nested = folder.createDirectory("nested")!!
        write(nested.createFile("application/octet-stream", "Game.exe")!!, "nested")
        repository.deleteGame(scanned(folder).id, false)
        context.contentResolver.call(provider, "resetQueryStats", null, null)
        val report = scanner.scanSubfolder("source", listOf("Ignored", "nested"))
        assertEquals(1, report.ignored)
        assertEquals(0, report.added)
        assertTrue(dao.getAllGames().isEmpty())
        context.contentResolver.call(provider, "denyAccess", GameFolders.documentId(library.uri), null)
        assertTrue(scanner.scan("source").errors.isNotEmpty())
        assertEquals(1, dao.getIgnoredFolders().size)
        context.contentResolver.call(provider, "denyAccess", null, null)
        assertEquals(1, scanner.scan("source").ignored)
    }

    @Test fun alternateTreeGrantCannotBypassAnIgnoredFolder() = runBlocking {
        val folder = makeFolder("Ignored")
        repository.deleteGame(scanned(folder).id, false)
        val alternate = DocumentsContract.buildTreeDocumentUri(folder.uri.authority, GameFolders.documentId(folder.uri))
        context.grantUriPermission(context.packageName, alternate, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        dao.upsertSource(GameSourceEntity("alternate", "Alternate", alternate.toString()))
        assertTrue(GameFolders.same(folder.uri.toString(), alternate.toString()))
        val alternateReport = scanner.scan("alternate")
        assertTrue(alternateReport.toString(), alternateReport.errors.isEmpty())
        assertEquals(alternateReport.toString(), 1, alternateReport.ignored)
        assertTrue(dao.getAllGames().isEmpty())
        context.revokeUriPermission(alternate, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun providerRefusalKeepsRecordAndFiles() = runBlocking {
        val folder = makeFolder("Denied")
        val game = scanned(folder)
        context.contentResolver.call(provider, "denyDelete", GameFolders.documentId(folder.uri), null)
        assertNotNull(runCatching { repository.deleteGame(game.id, true) }.exceptionOrNull())
        assertEquals(game, dao.getGame(game.id))
        assertTrue(folder.exists())
        assertTrue(dao.getIgnoredFolders().isEmpty())
    }

    @Test fun providerReportingSuccessWithoutDeletingDoesNotRemoveTheRecord() = runBlocking {
        val folder = makeFolder("Still present")
        val game = scanned(folder)
        context.contentResolver.call(provider, "ineffectiveDelete", GameFolders.documentId(folder.uri), null)
        assertNotNull(runCatching { repository.deleteGame(game.id, true) }.exceptionOrNull())
        assertEquals(game, dao.getGame(game.id))
        assertTrue(folder.exists())
    }

    @Test fun sourceRootAndFoldersSharedByAnotherGameAreProtected() = runBlocking {
        val folder = makeFolder("Shared")
        val game = scanned(folder)
        val alternate = DocumentsContract.buildTreeDocumentUri(folder.uri.authority, GameFolders.documentId(folder.uri)).toString()
        dao.upsertGame(game.copy(id = "alias", documentUri = alternate))
        assertNotNull(runCatching { repository.deleteGame(game.id, true) }.exceptionOrNull())
        assertNotNull(runCatching { repository.mergeDuplicate(game.id, "alias", false, SaveConflictStrategy.KEEP_BOTH, true) }.exceptionOrNull())
        dao.deleteGameCompletely("alias")
        dao.upsertSource(GameSourceEntity("nested-source", "Game itself", folder.uri.toString()))
        assertNotNull(runCatching { repository.deleteGame(game.id, true) }.exceptionOrNull())
        assertTrue(folder.exists())
        assertEquals(game, dao.getGame(game.id))
    }

    @Test fun mergeRecoversSavesThenDeletesOnlyTheSecondaryFolder() = runBlocking {
        val primary = makeFolder("Quest v1.0")
        val secondary = makeFolder("Quest v2.0")
        val save = secondary.createDirectory("save")!!.createFile("application/octet-stream", "Save01.rvdata2")!!
        write(save, "valuable-save")
        val main = scanned(primary)
        val other = dao.getAllGames().single { it.id != main.id }
        repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.KEEP_BOTH, true)
        assertEquals("valuable-save", read(primary.findFile("save")!!.findFile("Save01.rvdata2")!!))
        assertFalse(secondary.exists())
        assertNotNull(dao.getGame(main.id))
        assertNull(dao.getGame(other.id))
    }

    @Test fun batchFailureReportsCompletedWorkAndRetainsTheFailedGame() = runBlocking {
        val first = makeFolder("First")
        val second = makeFolder("Second")
        val game = scanned(first)
        val other = dao.getAllGames().single { it.id != game.id }
        context.contentResolver.call(provider, "denyDelete", GameFolders.documentId(second.uri), null)
        val failure = runCatching { repository.deleteGames(linkedSetOf(game.id, other.id), true) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("1 jeu(x) déjà traité(s)"))
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertNull(dao.getGame(game.id))
        assertNotNull(dao.getGame(other.id))
    }

    @Test fun ignoringDuringAScanWaitsAndNeverRecreatesTheRecord() = runBlocking {
        val folder = makeFolder("Concurrent")
        val game = scanned(folder)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scan = async(Dispatchers.IO) { scanner.scan("source") { progress ->
            if (progress.phase == "Préparation du dossier") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val removal = async(Dispatchers.IO) { repository.deleteGame(game.id, false) }
        try { delay(100); assertFalse(removal.isCompleted) } finally { release.countDown() }
        scan.await(); removal.await()
        assertEquals(1, scanner.scan("source").ignored)
        assertNull(dao.getGame(game.id))
    }

    @Test fun duplicatesUseSourcesAndVersionsWithoutConfusingSequels() = runBlocking {
        val entry = scanned(makeFolder("Example"))
        val sameSource = listOf(entry.copy(title = "Original", f95Url = "https://f95zone.to/threads/123/"),
            entry.copy(id = "other", title = "Translation", fingerprint = "different", f95Url = "https://f95zone.to/threads/123/"))
        assertEquals(2, DuplicateDetector.groups(sameSource).single().games.size)
        assertTrue(DuplicateDetector.groups(listOf(entry.copy(title = "Adventure 1", fingerprint = "one"),
            entry.copy(id = "other", title = "Adventure 2", fingerprint = "two"))).isEmpty())
        assertEquals(2, DuplicateDetector.groups(listOf(entry.copy(title = "Adventure v1.0 Windows"),
            entry.copy(id = "copy", title = "Adventure v2.0 Android copie", fingerprint = "two"))).single().games.size)
    }
}
