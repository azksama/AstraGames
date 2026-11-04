package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.saves.SaveCodec
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.mkxpz.MkxpzSavePaths
import fr.astragames.app.windows.WolfGameStorage
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * A CLEAN archive is still an explicit choice of save: its private bytes equal the
 * archived source baseline, but a newer source must not silently replace them.
 * Uses real encrypted BackupManager archives, Room and the debug document provider.
 */
class WolfRestoredBaselineTest {
    private enum class SnapshotMode { COPY, SAF }

    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: AstraDatabase
    private lateinit var databaseName: String
    private lateinit var fixtureId: String
    private lateinit var game: GameEntity
    private lateinit var sourceSave: DocumentFile
    private lateinit var sourceIni: DocumentFile
    private lateinit var exports: DocumentFile
    private lateinit var original: ByteArray
    private lateinit var external: ByteArray
    private lateinit var edited: ByteArray
    private lateinit var pending: ByteArray
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val storageRoot get() = File(context.filesDir, "wolf-games/${MkxpzSavePaths.ownerKey(game.id)}")
    private val restoredSave get() = File(storageRoot, "game/$SAVE_PATH")
    private val restoredToolSave get() = File(storageRoot, "game/$TOOL_PATH")
    private val safRoot get() = File(storageRoot, "saf-${MkxpzSavePaths.ownerKey(game.documentUri).take(24)}")
    private val pendingEvidence = linkedMapOf<String, ByteArray>()
    private var retainedSaf: WolfGameStorage? = null

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "wolf-restored-baseline-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        fixtureId = UUID.randomUUID().toString()
        val fixture = requireNotNull(context.contentResolver.call(provider, "fixture", fixtureId, null))
        val treeUri = Uri.parse(requireNotNull(fixture.getString("uri")))
        val tree = requireNotNull(documentDir(context, treeUri))
        // Do not use or modify the provider's unrelated starter game/mod fixtures.
        val source = requireNotNull(tree.createDirectory("wolf-clean-source"))
        exports = requireNotNull(tree.createDirectory("wolf-clean-archives"))
        val saveFolder = requireNotNull(source.createDirectory("save"))
        sourceSave = requireNotNull(saveFolder.createFile("application/octet-stream", "SaveData01.sav"))
        sourceIni = requireNotNull(source.createFile("application/octet-stream", "Game.ini"))
        val executable = requireNotNull(source.createFile("application/octet-stream", "Game.exe"))
        write(executable, "inert Wolf storage fixture".toByteArray())
        write(sourceIni, ORIGINAL_INI.toByteArray(Charsets.ISO_8859_1))

        original = InstrumentationRegistry.getInstrumentation().context.assets
            .open("format-008e-utf8.sav").use { it.readBytes() }
        val entry = SaveCodec.entries(SaveCodec.read("WOLF_RPG", original)).first { it.editable }
        fun variant(value: String) = SaveCodec.patch("WOLF_RPG", original, listOf(SaveEdit(entry.path, entry.type, value)))
        external = variant("234567")
        edited = variant("345678")
        pending = variant("456789")
        assertEquals("Fixture versions must be byte-distinct", 4, listOf(original, external, edited, pending).map(::hash).toSet().size)
        write(sourceSave, original)

        databaseName = "wolf-restored-baseline-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(app, AstraDatabase::class.java, databaseName).build()
        game = GameEntity(
            id = "wolf-restored-$fixtureId", title = "Wolf clean archive fixture",
            documentUri = source.uri.toString(), physicalPath = null, executableName = "Game.exe",
            engine = "WOLF_RPG", launcher = "WOLF_RUNTIME", sourceId = "source-$fixtureId",
            dateAdded = 1, lastModified = 1, fingerprint = "wolf-clean-$fixtureId"
        )
        database.dao().upsertSource(GameSourceEntity(game.sourceId, "Isolated Wolf source", treeUri.toString()))
        database.dao().upsertGame(game)
    }

    @After fun cleanup() {
        retainedSaf?.closeMount()
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) app.deleteDatabase(databaseName)
        if (::fixtureId.isInitialized) context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun cleanCopyArchiveKeepsSelectedSaveWhenSourceDiverges() =
        divergentSource(SnapshotMode.COPY)

    @Test fun cleanSafArchiveKeepsSelectedSaveWhenSourceDiverges() =
        divergentSource(SnapshotMode.SAF)

    @Test fun cleanCopyArchiveReportsConflictBeforeNextPrepare() =
        synchronizeBeforePrepare(SnapshotMode.COPY)

    @Test fun cleanSafArchiveReportsConflictBeforeNextPrepare() =
        synchronizeBeforePrepare(SnapshotMode.SAF)

    @Test fun cleanCopyArchiveWithUnchangedSourcePublishesLaterPrivateWrites() =
        unchangedSource(SnapshotMode.COPY)

    @Test fun cleanSafArchiveWithUnchangedSourcePublishesLaterPrivateWrites() =
        unchangedSource(SnapshotMode.SAF)

    @Test fun cleanSafArchiveKeepsSelectedSaveAndNewerJournalWhenSourceDiverges() =
        divergentSource(SnapshotMode.SAF, keepPendingJournal = true)

    @Test fun cleanSafArchiveWithUnchangedSourceDoesNotReplayNewerJournal() =
        unchangedSource(SnapshotMode.SAF, keepPendingJournal = true)

    @Test fun privateToolSlotWithNullBaselineSurvivesPublicationAndSourceRemovalRefresh() = runBlocking<Unit> {
        val archive = cleanArchive(SnapshotMode.COPY, includePrivateToolSave = true)
        BackupManager(context, database).restore(archive)
        assertToolCopies("restore source-absent slot", original, null)
        val first = WolfGameStorage(context, game.id, game.documentUri)
        try {
            first.prepare("Game.exe") { }
            assertToolCopies("prepare source-absent slot", original, null)
            assertEquals(0, first.synchronize())
            assertToolCopies("publish source-absent slot", original, original)
        } finally { first.closeMount() }

        // After successful publication the one-off restoration guard may be retired.
        // Save_MTool data must still be included by the public backup workflow.
        val published = requireNotNull(exports.findFile(BackupManager(context, database).create(exports.uri))).uri
        val publishedFiles = unpack(published)
        val publishedRoot = File(publishedFiles, "wolf-saves/${MkxpzSavePaths.ownerKey(game.id)}")
        assertArrayEquals(original, File(publishedRoot, "game/$TOOL_PATH").readBytes())
        assertEquals(hash(original), JSONObject(File(publishedRoot, "baselines.json").readText()).getString(TOOL_PATH))

        assertTrue(requireNotNull(sourceToolSave()).delete())
        val refreshed = WolfGameStorage(context, game.id, game.documentUri)
        try {
            refreshed.requestRefresh()
            refreshed.prepare("Game.exe") { }
            assertToolCopies("refresh must not purge a removed source save", original, null)
            assertCopies("refresh with absent Tool source", original, original)
        } finally { refreshed.closeMount() }
        editToolSave()
        repeat(2) { cycle -> verifyToolCycle("removed Tool source cycle $cycle", edited, null) }
    }

    @Test fun privateToolSlotWithNullBaselineRejectsSourceCreatedAfterExport() = runBlocking<Unit> {
        val archive = cleanArchive(SnapshotMode.COPY, includePrivateToolSave = true)
        writeSourceToolSave(external)
        BackupManager(context, database).restore(archive)
        assertToolCopies("restore A when source B appeared", original, external)
        repeat(2) { cycle -> verifyToolCycle("new Tool source A cycle $cycle", original, external) }
        editToolSave()
        repeat(2) { cycle -> verifyToolCycle("new Tool source edited C cycle $cycle", edited, external) }
    }

    @Test fun legacyRestoredModeWithoutPathListProtectsCleanAndToolSaves() = runBlocking<Unit> {
        val archive = cleanArchive(SnapshotMode.COPY, includePrivateToolSave = true)
        BackupManager(context, database).restore(archive)
        val published = WolfGameStorage(context, game.id, game.documentUri)
        try {
            published.prepare("Game.exe") { }
            assertEquals(0, published.synchronize())
        } finally { published.closeMount() }
        assertCopies("legacy fixture main source baseline A", original, original)
        assertToolCopies("legacy fixture Tool source baseline A", original, original)
        // This is the exact old on-disk format, before per-path restored ownership.
        // The source-hashes/import-state files are genuine product checkpoints.
        val legacyMode = JSONObject().put("mode", "COPY").put("source", game.documentUri).put("restoredArchive", true)
        File(storageRoot, "working-mode.json").writeText(legacyMode.toString())
        assertFalse(legacyMode.has("restoredPaths"))
        assertFalse(legacyMode.has("restoredOwner"))
        write(sourceSave, external)
        writeSourceToolSave(external)

        suspend fun verifyLegacyCycle(label: String, expected: ByteArray) {
            val reopened = WolfGameStorage(context, game.id, game.documentUri)
            try {
                reopened.requestRefresh()
                reopened.prepare("Game.exe") { }
                assertFalse(reopened.isSaf || reopened.isDirect)
                assertCopies("$label prepare", expected, external)
                assertToolCopies("$label prepare", expected, external)
                assertEquals("Both legacy restored saves have divergent sources", 2, reopened.synchronize())
                reopened.prepare("Game.exe") { }
                assertCopies("$label second prepare", expected, external)
                assertToolCopies("$label second prepare", expected, external)
            } finally { reopened.closeMount() }
            val fresh = WolfGameStorage(context, game.id, game.documentUri)
            try {
                assertEquals("A fresh synchronize must load migrated metadata", 2, fresh.synchronize())
                assertCopies("$label direct fresh synchronize", expected, external)
                assertToolCopies("$label direct fresh synchronize", expected, external)
            } finally { fresh.closeMount() }
        }
        repeat(2) { cycle -> verifyLegacyCycle("legacy A cycle $cycle", original) }
        editRestoredSave()
        editToolSave()
        repeat(2) { cycle -> verifyLegacyCycle("legacy edited C cycle $cycle", edited) }
    }

    private fun divergentSource(mode: SnapshotMode, keepPendingJournal: Boolean = false) = runBlocking<Unit> {
        val archive = cleanArchive(mode)
        if (keepPendingJournal) createNewerPendingJournal()
        write(sourceSave, external)
        BackupManager(context, database).restore(archive)
        assertCopies("restore CLEAN A over newer source B", original, external)
        if (keepPendingJournal) verifyRetainedMountAfterRestore(external, expectedConflicts = 1)

        // Each pass constructs a fresh object, then tests prepare -> synchronize -> prepare
        // on that same object. A fixed first launch must not regress on the following one.
        repeat(2) { cycle -> verifyCycle("$mode divergent A cycle $cycle", original, external, conflict = true) }
        editRestoredSave()
        repeat(2) { cycle -> verifyCycle("$mode divergent edited C cycle $cycle", edited, external, conflict = true) }
        assertPendingPreserved()
    }

    private fun synchronizeBeforePrepare(mode: SnapshotMode) = runBlocking<Unit> {
        val archive = cleanArchive(mode)
        write(sourceSave, external)
        BackupManager(context, database).restore(archive)
        assertCopies("restore before listing", original, external)
        val reopened = WolfGameStorage(context, game.id, game.documentUri)
        try {
            // saveFiles is a real save-manager entry point: it reloads the persisted
            // baseline without a prepare that could already have destroyed the evidence.
            val listed = reopened.saveFiles().single { it.path == SAVE_PATH }
            assertEquals(restoredSave.canonicalFile, listed.file.canonicalFile)
            assertArrayEquals(original, listed.file.readBytes())
            assertFalse(reopened.isSaf)
            assertFalse(reopened.isDirect)
            val progress = mutableListOf<String>()
            val conflicts = reopened.synchronize(progress::add)
            assertCopies("synchronize without prepare", original, external)
            assertEquals("A CLEAN restored snapshot must report the divergent source: $progress", 1, conflicts)
        } finally { reopened.closeMount() }
        verifyCycle("$mode after explicit pre-prepare conflict", original, external, conflict = true)
    }

    private fun unchangedSource(mode: SnapshotMode, keepPendingJournal: Boolean = false) = runBlocking<Unit> {
        val archive = cleanArchive(mode)
        if (keepPendingJournal) createNewerPendingJournal()
        BackupManager(context, database).restore(archive)
        assertCopies("restore CLEAN A over unchanged source A", original, original)
        if (keepPendingJournal) verifyRetainedMountAfterRestore(original, expectedConflicts = 0)
        repeat(2) { cycle -> verifyCycle("$mode unchanged A cycle $cycle", original, original, conflict = false) }

        // A source guard must permit a later real private save when the source still
        // matches the archived baseline, including after a process/object recreation.
        editRestoredSave()
        verifyCycle("$mode first publication of private C", edited, original, conflict = false, sourceAfterSync = edited)
        repeat(2) { cycle -> verifyCycle("$mode published C cycle $cycle", edited, edited, conflict = false) }
        assertPendingPreserved()
    }

    private suspend fun cleanArchive(mode: SnapshotMode, includePrivateToolSave: Boolean = false): Uri {
        val storage = WolfGameStorage(context, game.id, game.documentUri, preferDirect = mode == SnapshotMode.SAF)
        try {
            val progress = mutableListOf<String>()
            storage.prepare("Game.exe", progress::add)
            assertEquals("Initial mode must be $mode, not a fallback: $progress", mode == SnapshotMode.SAF, storage.isSaf)
            assertFalse(storage.isDirect)
            if (includePrivateToolSave) {
                assertEquals(SnapshotMode.COPY, mode)
                assertNull(sourceToolSave())
                File(storage.game, TOOL_PATH).apply { parentFile!!.mkdirs(); writeBytes(original) }
            }
            val local = storage.saveFiles().single { it.path == SAVE_PATH }.file
            assertArrayEquals("Snapshot must be CLEAN before exporting", original, local.readBytes())
            assertArrayEquals(original, read(sourceSave))
            assertTrue("A CLEAN SAF snapshot must have no pending save transaction", File(safRoot, "transactions")
                .listFiles().orEmpty().none { it.extension == "json" && JSONObject(it.readText()).optBoolean("dirty") })
            database.dao().upsertSaveLocation(GameSaveLocationEntity(
                id = "location-${game.id}", gameId = game.id, uri = Uri.fromFile(local.parentFile).toString(),
                type = "WOLF_PRIVATE", displayName = "Clean $mode snapshot", autoDetected = true, addedAt = 1
            ))
        } finally { storage.closeMount() }

        val manager = BackupManager(context, database)
        val archive = requireNotNull(exports.findFile(manager.create(exports.uri))).uri
        val extracted = unpack(archive)
        val owner = MkxpzSavePaths.ownerKey(game.id)
        val directory = File(extracted, "wolf-saves/$owner")
        val savePaths = if (includePrivateToolSave) setOf(SAVE_PATH, TOOL_PATH) else setOf(SAVE_PATH)
        assertEquals(savePaths.map { "game/$it" }.toSet() + "baselines.json", directory.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet())
        assertArrayEquals(original, File(directory, "game/$SAVE_PATH").readBytes())
        val baseline = JSONObject(File(directory, "baselines.json").readText())
        assertEquals(savePaths, baseline.keys().asSequence().toSet())
        assertEquals("Archive must contain the real CLEAN source A baseline", hash(original), baseline.getString(SAVE_PATH))
        if (includePrivateToolSave) {
            assertArrayEquals(original, File(directory, "game/$TOOL_PATH").readBytes())
            assertTrue("Private-only Tool save must retain a NULL source baseline", baseline.isNull(TOOL_PATH))
            assertNull(sourceToolSave())
        }
        assertArrayEquals("Export must not publish or change the source", original, read(sourceSave))
        receipt("clean archive $mode", original, original)
        return archive
    }

    private suspend fun createNewerPendingJournal() {
        val storage = WolfGameStorage(context, game.id, game.documentUri)
        // Keep an actually prepared SAF mount alive across restore, as a retained
        // controller could. A stale in-memory mode must not replay its newer D journal.
        retainedSaf = storage
        storage.prepare("Game.exe") { }
        val local = storage.saveFiles().single { it.path == SAVE_PATH }.file
        assertTrue("Pending-write fixture must use the original SAF session", storage.isSaf)
        local.writeBytes(pending)
        storage.savedFileChanged(local)
        val records = File(safRoot, "transactions").listFiles().orEmpty()
            .filter { it.extension == "json" }.map { JSONObject(it.readText()) }
        val record = records.single { it.getString("path") == SAVE_PATH }
        assertTrue(record.getBoolean("dirty"))
        assertEquals(hash(original), record.getString("baseline"))
        assertEquals(hash(pending), record.getString("sealed"))
        assertTrue(File(safRoot, "writes").listFiles().orEmpty().any { it.isFile && it.readBytes().contentEquals(pending) })
        for (name in listOf("transactions", "writes")) {
            File(safRoot, name).walkTopDown().filter(File::isFile).forEach {
                pendingEvidence[it.relativeTo(safRoot).invariantSeparatorsPath] = it.readBytes()
            }
        }
        assertTrue(pendingEvidence.isNotEmpty())
        assertArrayEquals("Pending D must remain unpublished before restore", original, read(sourceSave))
    }

    private suspend fun verifyRetainedMountAfterRestore(expectedSource: ByteArray, expectedConflicts: Int) {
        val storage = requireNotNull(retainedSaf)
        try {
            val conflicts = storage.synchronize()
            assertCopies("retained SAF object synchronizes after restore", original, expectedSource)
            assertFalse("Restored COPY must supersede the retained SAF mount", storage.isSaf || storage.isDirect)
            assertEquals("Retained SAF object must use the restored source baseline", expectedConflicts, conflicts)
            assertPendingPreserved()
        } finally {
            storage.closeMount()
            retainedSaf = null
        }
    }

    private suspend fun editRestoredSave() {
        val reopened = WolfGameStorage(context, game.id, game.documentUri)
        try {
            val local = reopened.saveFiles().single { it.path == SAVE_PATH }.file
            assertEquals(restoredSave.canonicalFile, local.canonicalFile)
            local.writeBytes(edited)
            reopened.savedFileChanged(local)
            assertArrayEquals(edited, restoredSave.readBytes())
        } finally { reopened.closeMount() }
    }

    private suspend fun editToolSave() {
        val reopened = WolfGameStorage(context, game.id, game.documentUri)
        try {
            val local = reopened.saveFiles().single { it.path == TOOL_PATH }.file
            assertEquals(restoredToolSave.canonicalFile, local.canonicalFile)
            local.writeBytes(edited)
            reopened.savedFileChanged(local)
        } finally { reopened.closeMount() }
    }

    private suspend fun verifyToolCycle(label: String, expected: ByteArray, source: ByteArray?) {
        val reopened = WolfGameStorage(context, game.id, game.documentUri)
        try {
            reopened.requestRefresh()
            reopened.prepare("Game.exe") { }
            assertFalse(reopened.isSaf || reopened.isDirect)
            assertToolCopies("$label prepare", expected, source)
            assertEquals("$label must report one Tool source conflict", 1, reopened.synchronize())
            reopened.prepare("Game.exe") { }
            assertToolCopies("$label second prepare", expected, source)
            assertCopies("$label main save unchanged", original, original)
        } finally { reopened.closeMount() }
        val fresh = WolfGameStorage(context, game.id, game.documentUri)
        try {
            assertEquals("$label direct fresh synchronize", 1, fresh.synchronize())
            assertToolCopies("$label direct fresh synchronize", expected, source)
            assertCopies("$label main save after fresh synchronize", original, original)
        } finally { fresh.closeMount() }
    }

    private fun assertToolCopies(label: String, expectedPrivate: ByteArray, expectedSource: ByteArray?) {
        assertTrue("$label: private Save_MTool/slot.dat was purged", restoredToolSave.isFile)
        assertArrayEquals("$label: Tool private save was replaced", expectedPrivate, restoredToolSave.readBytes())
        val source = sourceToolSave()
        if (expectedSource == null) assertNull("$label: source slot was unexpectedly created", source)
        else assertArrayEquals("$label: Tool source was overwritten", expectedSource, read(requireNotNull(source)))
    }

    private fun sourceToolSave(): DocumentFile? = requireNotNull(documentDir(context, Uri.parse(game.documentUri)))
        .findFile("Save_MTool")?.findFile("slot.dat")

    private fun writeSourceToolSave(bytes: ByteArray) {
        val source = requireNotNull(documentDir(context, Uri.parse(game.documentUri)))
        val folder = source.findFile("Save_MTool") ?: requireNotNull(source.createDirectory("Save_MTool"))
        val slot = folder.findFile("slot.dat") ?: requireNotNull(folder.createFile("application/octet-stream", "slot.dat"))
        write(slot, bytes)
    }

    private suspend fun verifyCycle(
        label: String,
        expectedPrivate: ByteArray,
        expectedSource: ByteArray,
        conflict: Boolean,
        sourceAfterSync: ByteArray = expectedSource
    ) {
        val reopened = WolfGameStorage(context, game.id, game.documentUri)
        try {
            reopened.prepare("Game.exe") { }
            assertFalse("$label must keep the restored COPY playable", reopened.isSaf)
            assertFalse("$label must not abandon the restored private copy", reopened.isDirect)
            assertEquals(File(storageRoot, "game").canonicalFile, reopened.game.canonicalFile)
            assertCopies("$label after prepare", expectedPrivate, expectedSource)
            val progress = mutableListOf<String>()
            val conflicts = reopened.synchronize(progress::add)
            assertCopies("$label after synchronize", expectedPrivate, sourceAfterSync)
            assertEquals("$label must report source divergence explicitly: $progress", if (conflict) 1 else 0, conflicts)
            reopened.prepare("Game.exe") { }
            assertFalse("$label second prepare must stay COPY", reopened.isSaf || reopened.isDirect)
            assertCopies("$label after second prepare", expectedPrivate, sourceAfterSync)
            assertPendingPreserved()
        } finally { reopened.closeMount() }
        // Public synchronize must work on a new instance without saveFiles/prepare.
        val fresh = WolfGameStorage(context, game.id, game.documentUri)
        try {
            assertEquals("$label fresh synchronize", if (conflict) 1 else 0, fresh.synchronize())
            assertCopies("$label after direct fresh synchronize", expectedPrivate, sourceAfterSync)
            assertPendingPreserved()
        } finally { fresh.closeMount() }
    }

    private fun assertCopies(label: String, expectedPrivate: ByteArray, expectedSource: ByteArray) {
        val actualPrivate = restoredSave.readBytes()
        val actualSource = read(sourceSave)
        receipt(label, actualPrivate, actualSource)
        assertArrayEquals("$label: selected private save was replaced", expectedPrivate, actualPrivate)
        assertArrayEquals("$label: source was overwritten", expectedSource, actualSource)
        assertArrayEquals("$label: source Game.ini changed", ORIGINAL_INI.toByteArray(Charsets.ISO_8859_1), read(sourceIni))
    }

    private fun assertPendingPreserved() {
        pendingEvidence.forEach { (name, bytes) ->
            val file = File(safRoot, name)
            assertTrue("Existing SAF recovery file was removed: $name", file.isFile)
            assertArrayEquals("Existing SAF recovery file was rewritten: $name", bytes, file.readBytes())
        }
    }

    private fun receipt(stage: String, privateBytes: ByteArray, sourceBytes: ByteArray) {
        Log.i("WolfRestoredBaseline", JSONObject().put("fixture", fixtureId).put("stage", stage)
            .put("privateSha256", hash(privateBytes)).put("sourceSha256", hash(sourceBytes))
            .put("archiveOriginalSha256", hash(original)).put("externalSha256", hash(external))
            .put("editedSha256", hash(edited)).put("retainedPendingFiles", pendingEvidence.size).toString())
    }

    private fun unpack(archive: Uri): File {
        val directory = File(root, "archive-${UUID.randomUUID()}").apply { check(mkdir()) }
        val zip = File(root, "archive-${UUID.randomUUID()}.zip")
        val envelope = BackupEnvelope(KeystoreCrypto::backupEncryptionCipher, KeystoreCrypto::backupDecryptionCipher)
        requireNotNull(context.contentResolver.openInputStream(archive)).use { input ->
            zip.outputStream().use { envelope.read(input, it) }
        }
        zip.inputStream().use { BackupArchive.extract(it, directory) }
        return directory
    }

    private fun write(document: DocumentFile, bytes: ByteArray) {
        requireNotNull(context.contentResolver.openOutputStream(document.uri, "wt")).use { it.write(bytes) }
    }

    private fun read(document: DocumentFile): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(document.uri)).use { it.readBytes() }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SAVE_PATH = "save/SaveData01.sav"
        private const val TOOL_PATH = "Save_MTool/slot.dat"
        private const val ORIGINAL_INI = "SoftModeFlag=0\r\nWindowModeFlag=1\r\n"
    }
}
