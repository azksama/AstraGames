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
        repository = GameRepository(context, db, scanner, BackupManager(context, db))
        fixtureId = UUID.randomUUID().toString()
        val uri = context.contentResolver.call(provider, "fixture", fixtureId, null)!!.getString("uri")!!
        root = documentDir(context, Uri.parse(uri))!!
        library = root.createDirectory("library")!!
        dao.upsertSource(GameSourceEntity("source", "Library", library.uri.toString()))
    }

    @After fun cleanup() {
        for (method in listOf("denyAccess", "denyDelete", "ineffectiveDelete", "loadingChildren")) context.contentResolver.call(provider, method, null, null)
        context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        db.close()
    }

    private fun makeFolder(name: String, parent: DocumentFile = library): DocumentFile = parent.createDirectory(name)!!.also { folder ->
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

    @Test fun incompleteListingPreservesKnownGamesUntilACompleteScan() = runBlocking {
        val folder = makeFolder("Available")
        val game = scanned(folder)
        context.contentResolver.call(provider, "loadingChildren", GameFolders.documentId(library.uri), null)
        val incomplete = scanner.scan("source")
        assertTrue(incomplete.toString(), incomplete.errors.isNotEmpty())
        assertEquals(game, dao.getGame(game.id))
        assertEquals(1, dao.getSource("source")!!.gamesCount)
        assertTrue(repository.latestScanReport("source")!!.errors.isNotEmpty())

        context.contentResolver.call(provider, "loadingChildren", null, null)
        assertTrue(folder.delete())
        assertEquals(1, scanner.scan("source").missing)
        assertEquals(0, dao.getSource("source")!!.gamesCount)
    }

    @Test fun scanReadsDirectoryMetadataInBatches() = runBlocking {
        val folder = makeFolder("Wide")
        repeat(40) { folder.createFile("application/octet-stream", "asset-$it.bin")!! }
        context.contentResolver.call(provider, "resetQueryStats", null, null)
        val report = scanner.scan("source")
        val stats = context.contentResolver.call(provider, "queryStats", null, null)!!
        val counts = "documentQueries=${stats.getInt("documents")} childQueries=${stats.getInt("children")}"
        android.util.Log.i("AstraScanTest", counts)
        assertEquals(1, report.added)
        assertEquals("RPG_MAKER_VX_ACE", dao.getAllGames().single().engine)
        assertTrue(counts, stats.getInt("documents") <= 12)
        assertEquals(counts, 3, stats.getInt("children"))
    }

    @Test fun finishingScanPreservesSourceOptionsEditedDuringTraversal() = runBlocking {
        makeFolder("Options")
        var edited = false
        scanner.scan("source") { progress ->
            if (!edited && progress.phase == "Analyse du dossier principal") {
                edited = true
                runBlocking { repository.toggleSource("source"); repository.toggleSourceRecursive("source") }
            }
        }
        val source = dao.getSource("source")!!
        assertTrue(edited)
        assertFalse(source.enabled)
        assertFalse(source.recursive)
        assertEquals("SUCCESS", source.lastScanStatus)
        assertEquals(1, source.gamesCount)
    }

    @Test fun inaccessibleCopyDoesNotDonateItsRecordToAnotherSource() = runBlocking {
        val old = makeFolder("Quest v1.0")
        val original = scanned(old)
        repository.toggleFavorite(original.id)
        val otherSource = root.createDirectory("other-library")!!
        val copy = otherSource.createDirectory("Quest v2.0")!!
        copy.createFile("application/octet-stream", "Game.exe")!!
        copy.createDirectory("Data")!!.createFile("application/octet-stream", "System.rvdata2")!!
        dao.upsertSource(GameSourceEntity("other", "Other", otherSource.uri.toString()))
        context.contentResolver.call(provider, "denyAccess", GameFolders.documentId(library.uri), null)
        val report = scanner.scan("other")
        assertEquals(report.toString(), 0, report.moved)
        assertEquals(1, report.added)
        assertEquals(original.copy(favorite = true), dao.getGame(original.id))
        assertFalse(dao.getGamesForSource("other").single().favorite)
    }

    @Test fun mergingWaitsForACompleteSaveListingBeforeDeletingTheCopy() = runBlocking {
        val main = scanned(makeFolder("Quest v1.0"))
        val folder = makeFolder("Quest v2.0")
        val other = scanned(folder)
        val saves = folder.createDirectory("save")!!
        val save = saves.createFile("application/octet-stream", "slot.sav")!!
        write(save, "only copy")
        context.contentResolver.call(provider, "loadingChildren", GameFolders.documentId(saves.uri), null)
        val attempt = runCatching { repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.KEEP_BOTH, true) }
        assertTrue("An incomplete inventory must stop the merge", attempt.isFailure)
        assertEquals(other, dao.getGame(other.id))
        assertEquals("only copy", read(save))
    }

    @Test fun mergingCopiesSavesNestedBeyondSevenDirectories() = runBlocking {
        val mainFolder = makeFolder("Quest v1.0")
        val main = scanned(mainFolder)
        val secondary = makeFolder("Quest v2.0")
        val other = scanned(secondary)
        val path = listOf("save") + (1..8).map { "folder-$it" }
        val nested = path.fold(secondary) { parent, name -> parent.createDirectory(name)!! }
        write(nested.createFile("application/octet-stream", "slot.sav")!!, "nested save")
        repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.KEEP_BOTH, true)
        val copied = path.fold<String, DocumentFile?>(mainFolder) { parent, name -> parent?.findFile(name) }?.findFile("slot.sav")
        assertNotNull("The secondary folder was removed before the nested save was recovered", copied)
        assertEquals("nested save", read(copied!!))
        assertFalse(secondary.exists())
    }

    @Test fun failedCatalogueMergeCanBeRetriedWithoutCountingSessionsTwice() = runBlocking {
        val main = scanned(makeFolder("Quest v1.0"))
        val other = scanned(makeFolder("Quest v2.0"))
        repository.recordLaunch(main.id)
        repository.recordLaunch(other.id)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_merge BEFORE DELETE ON games BEGIN SELECT RAISE(ABORT, 'fixture: write failed'); END")
        val attempt = runCatching { repository.mergeDuplicate(main.id, other.id, false, SaveConflictStrategy.KEEP_PRIMARY, false) }
        assertTrue(attempt.isFailure)
        assertEquals(1, dao.getGame(main.id)!!.playCount)
        assertNotNull(dao.getGame(other.id))
        assertTrue(dao.getIgnoredFolders().isEmpty())
        sql.execSQL("DROP TRIGGER abort_merge")
        repository.mergeDuplicate(main.id, other.id, false, SaveConflictStrategy.KEEP_PRIMARY, false)
        assertEquals(2, dao.getGame(main.id)!!.playCount)
        assertNull(dao.getGame(other.id))
    }

    @Test fun retryingPhysicalMergeKeepsTheCoverAndOnlyOneCopyOfEachSave() = runBlocking {
        val first = makeFolder("Quest v1.0")
        val second = makeFolder("Quest v2.0")
        write(first.createDirectory("Save")!!.createFile("application/octet-stream", "Slot.SAV")!!, "primary")
        write(second.createDirectory("save")!!.createFile("application/octet-stream", "slot.sav")!!, "secondary")
        write(second.createFile("image/png", "cover.png")!!, "secondary cover")
        val main = scanned(first)
        val other = dao.getAllGames().single { it.id != main.id }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_merge BEFORE DELETE ON games BEGIN SELECT RAISE(ABORT, 'fixture: write failed'); END")
        val attempt = runCatching { repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.KEEP_BOTH, true) }
        assertTrue(attempt.isFailure)
        assertTrue(second.exists())
        assertEquals(other, dao.getGame(other.id))
        sql.execSQL("DROP TRIGGER abort_merge")
        repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.KEEP_BOTH, true)
        assertFalse(second.exists())
        assertEquals(1, first.listFiles().count { it.name.equals("save", true) })
        val copies = first.findFile("Save")!!.listFiles()
        assertEquals(2, copies.size)
        assertEquals(setOf("primary", "secondary"), copies.map(::read).toSet())
        val inherited = Uri.parse(dao.getGame(main.id)!!.coverUri!!)
        assertEquals("secondary cover", context.contentResolver.openInputStream(inherited)!!.bufferedReader().use { it.readText() })
        repository.removeCover(main.id)
    }

    @Test fun rescanningAnUnchangedLibraryDoesNotRewriteAvailability() = runBlocking {
        makeFolder("Unchanged")
        scanner.scan("source")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER forbid_rewrite BEFORE UPDATE OF missing ON games BEGIN SELECT RAISE(ABORT, 'unchanged game rewritten'); END")
        val report = scanner.scan("source")
        assertEquals(1, report.unchanged)
        assertTrue(report.errors.isEmpty())
    }

    @Test fun confirmedMovePreservesTheOriginalGameAndFavorite() = runBlocking {
        val old = makeFolder("Quest v1.0")
        val original = scanned(old)
        repository.toggleFavorite(original.id)
        assertTrue(old.renameTo("Quest v2.0"))
        val report = scanner.scan("source")
        assertEquals(report.toString(), 1, report.moved)
        val found = dao.getAllGames().single()
        assertEquals(original.id, found.id)
        assertTrue(found.favorite)
        assertEquals(old.uri.toString(), found.documentUri)
    }

    @Test fun shortTitlesInIndependentDuplicateGroupsHaveDistinctStableKeys() = runBlocking {
        val entry = scanned(makeFolder("Groups"))
        fun copy(id: String, title: String, product: String) =
            entry.copy(id = id, title = title, fingerprint = id, productCode = product)
        val games = listOf(copy("a", "II", "RJ0100"), copy("b", "One", "RJ0100"),
            copy("c", "II", "RJ0200"), copy("d", "Two", "RJ0200"))
        val groups = DuplicateDetector.groups(games)
        assertEquals(2, groups.size)
        assertEquals(2, groups.map { it.key }.distinct().size)
        assertEquals(groups, DuplicateDetector.groups(games.reversed()))
        assertTrue(groups.all { it.isIgnored(setOf("title:ii")) })
        assertEquals(1, groups.count { it.isIgnored(setOf(groups.first().key)) })
    }

    @Test fun volumeRootsAndTreeIdsNamedDocumentResolveCorrectly() {
        val authority = "com.android.externalstorage.documents"
        val root = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
        val game = DocumentsContract.buildDocumentUriUsingTree(root, "primary:Games/Quest")
        val otherVolume = DocumentsContract.buildTreeDocumentUri(authority, "primary2:Games/Quest")
        assertTrue(GameFolders.contains(context, root.toString(), game.toString()))
        assertFalse(GameFolders.contains(context, root.toString(), otherVolume.toString()))
        assertEquals("document", GameFolders.documentId(DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, "document")))
    }

    @Test fun partialTraversalKeepsTheUnreadableBranchAndCountsItsSibling() = runBlocking {
        val branch = library.createDirectory("Collection")!!
        val inaccessible = makeFolder("Archived", branch)
        makeFolder("Available")
        val original = scanned(inaccessible)
        context.contentResolver.call(provider, "denyAccess", GameFolders.documentId(branch.uri), null)
        val report = scanner.scan("source")
        assertEquals(1, report.found)
        assertTrue(report.errors.any { "Collection" in it })
        assertEquals(original, dao.getGame(original.id))
        val source = dao.getSource("source")!!
        assertEquals("PARTIAL", source.lastScanStatus)
        assertEquals(2, source.gamesCount)
    }

    @Test fun removingSourceDuringTraversalLeavesNoGamesOrHistoryBehind() = runBlocking {
        makeFolder("Concurrent")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scan = async(Dispatchers.IO) {
            scanner.scan("source") { progress ->
                if (progress.phase == "Analyse du dossier principal") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val removal = async(start = CoroutineStart.UNDISPATCHED) { repository.removeSource("source", removeGames = true) }
        try { assertFalse(removal.isCompleted) } finally { release.countDown() }
        scan.await()
        removal.await()
        assertNull(dao.getSource("source"))
        assertTrue(dao.getAllGames().isEmpty())
        assertNull(dao.getLatestScanHistory("source"))
    }

    @Test fun replacingRgssSavesDoesNotReplaceTheGameDatabase() = runBlocking {
        val first = makeFolder("Quest v1.0")
        val second = makeFolder("Quest v2.0")
        val primarySystem = first.findFile("Data")!!.findFile("System.rvdata2")!!
        val primarySave = first.createFile("application/octet-stream", "Save01.rvdata2")!!
        write(primarySave, "old progress")
        write(second.createFile("application/octet-stream", "Save01.rvdata2")!!, "new progress")
        val secondaryData = second.findFile("Data")!!
        write(secondaryData.createFile("application/octet-stream", "Map001.rvdata2")!!, "secondary map")
        write(secondaryData.createFile("application/octet-stream", "CustomProgress.rvdata2")!!, "custom progress")
        val main = scanned(first)
        val other = dao.getAllGames().single { it.id != main.id }
        val preview = repository.previewDuplicateMerge(main.id, other.id)
        repository.mergeDuplicate(main.id, other.id, true, SaveConflictStrategy.REPLACE_WITH_SECONDARY, true)
        assertEquals("engine-Quest v1.0", read(primarySystem))
        val primaryData = first.findFile("Data")!!
        assertNull(primaryData.findFile("Map001.rvdata2"))
        assertEquals("custom progress", read(primaryData.findFile("CustomProgress.rvdata2")!!))
        assertEquals("new progress", read(primarySave))
        assertEquals(listOf("Save01.rvdata2"), preview.conflictingSaves.map { it.relativePath })
        assertEquals(1, dao.getSaveBackupsForSource(primarySave.uri.toString()).size)
    }
}
