package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.*
import fr.astragames.app.data.saves.*
import fr.astragames.app.mkxpz.MkxpzSavePaths
import fr.astragames.app.windows.WolfGameStorage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Isolated Room/SAF fixtures exercise actual archives and SaveManager after restoring. */
class WolfBackupTest {
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AstraDatabase
    private lateinit var dbName: String
    private lateinit var fixtureId: String
    private lateinit var exportUri: Uri
    private lateinit var game: GameEntity
    private lateinit var save: File
    private lateinit var windowsSave: File
    private lateinit var backup: File
    private lateinit var bytes: ByteArray
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val ownerKey get() = MkxpzSavePaths.ownerKey(game.id)
    private val privateRoot get() = File(context.filesDir, "wolf-games/$ownerKey")

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "wolf-backup-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        fixtureId = UUID.randomUUID().toString()
        val treeUri = Uri.parse(context.contentResolver.call(provider, "fixture", fixtureId, null)!!.getString("uri"))
        val tree = documentDir(context, treeUri)!!
        exportUri = tree.createDirectory("wolf-exports")!!.uri
        dbName = "wolf-backup-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(app, AstraDatabase::class.java, dbName).build()
        game = GameEntity(id = "wolf-owner", title = "Wolf catalogue", documentUri = tree.findFile("game")!!.uri.toString(),
            physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY",
            sourceId = "wolf-source", dateAdded = 1, lastModified = 1, fingerprint = "wolf-backup-fixture")
        db.dao().upsertSource(GameSourceEntity(game.sourceId, "Wolf fixture", treeUri.toString()))
        db.dao().upsertGame(game)
        db.dao().upsertLaunchProfile(LaunchProfileEntity(game.id, "WOLF_RUNTIME", "WOLF_RPG"))
        bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("format-008e-utf8.sav").use { it.readBytes() }
        val source = documentDir(context, Uri.parse(game.documentUri))!!
        source.createFile("application/octet-stream", "Game.exe")!!.let { context.contentResolver.openOutputStream(it.uri)!!.use { out -> out.write(byteArrayOf(1)) } }
        val sourceFolder = source.findFile("save")!!
        sourceFolder.createFile("application/octet-stream", "SaveData01.sav")!!.let { context.contentResolver.openOutputStream(it.uri)!!.use { out -> out.write(bytes) } }
        save = File(privateRoot, "game/save/SaveData01.sav").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        windowsSave = File(privateRoot, "prefix/drive_c/users/fixture/AppData/Local/SaveData02.sav")
            .apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        File(privateRoot, "game/Game.exe").writeText("not an executable; must not be archived")
        File(privateRoot, "game/Data.wolf").writeText("assets excluded")
        File(privateRoot, "source-hashes.json").writeText(JSONObject().put("save/SaveData01.sav", sha256Hex(bytes))
            .put("Data.wolf", sha256Hex(File(privateRoot, "game/Data.wolf").readBytes())).toString())
        db.dao().upsertSaveLocation(GameSaveLocationEntity("wolf-location", game.id, Uri.fromFile(save.parentFile).toString(), "WOLF_PRIVATE", "Wolf", true, true, 1))
        backup = File(SaveBackupPaths.directory(context, game.id), "original.backup").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        db.dao().insertSaveBackup(SaveBackupEntity("wolf-backup", game.id, Uri.fromFile(save).toString(), save.name, Uri.fromFile(backup).toString(), 1, backup.length()))
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::dbName.isInitialized) app.deleteDatabase(dbName)
        if (::fixtureId.isInitialized) context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun privateCopyAndWindowsSavesRoundTripWithUsableBackupAndEffectiveEngine() = runBlocking<Unit> {
        val archive = export()
        val staging = unpack(archive)
        val names = File(staging, "wolf-saves/$ownerKey").walkTopDown().filter(File::isFile)
            .map { it.relativeTo(File(staging, "wolf-saves/$ownerKey")).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("game/save/SaveData01.sav", "windows/fixture/AppData/Local/SaveData02.sav", "baselines.json"), names)
        save.writeText("changed")
        windowsSave.writeText("changed windows")
        backup.writeText("changed backup")
        db.dao().upsertGame(game.copy(title = "Before restore"))
        db.dao().deleteLaunchProfile(game.id)
        BackupManager(context, db).restore(archive)
        assertArrayEquals(bytes, save.readBytes())
        assertArrayEquals(bytes, windowsSave.readBytes())
        assertArrayEquals(bytes, backup.readBytes())
        assertEquals(game.title, db.dao().getGame(game.id)!!.title)
        assertEquals("WOLF_RPG", db.dao().getLaunchProfile(game.id)!!.engineOverride)
        val manager = SaveManager(context, db.dao(), SaveFinder(context))
        val restored = manager.listSaves(game).single { it.uri == Uri.fromFile(save.canonicalFile).toString() }
        assertTrue(manager.readSave(game, restored).second.any { it.editable })
        // Restoring the selected original is permitted by the stronger backup ownership guard.
        manager.restoreBackup(db.dao().getSaveBackupsForSource(restored.uri).single())
        assertArrayEquals(bytes, save.readBytes())
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun safPendingSaveRestoresPrivatelyAndRetainsConflictWithChangedSource() = safRoundTrip(sourceDiverges = true)

    @Test fun safSnapshotStaysAuthoritativeAfterPublicationAndRepeatedLaunch() = safRoundTrip(sourceDiverges = false)

    private fun safRoundTrip(sourceDiverges: Boolean) = runBlocking<Unit> {
        // Start with a source-backed SAF session, no old COPY state.
        privateRoot.deleteRecursively()
        val source = documentDir(context, Uri.parse(game.documentUri))!!
        val folder = source.findFile("save")!!
        val sourceSave = folder.findFile("SaveData01.sav")!!
        context.contentResolver.openOutputStream(sourceSave.uri, "wt")!!.use { it.write(bytes) }
        val storage = WolfGameStorage(context, game.id, game.documentUri)
        try {
            val stages = mutableListOf<String>()
            storage.prepare("Game.exe", stages::add)
            assertTrue("SAF fallback: ${stages.joinToString(" | ")}", storage.isSaf)
            val exportedSave = storage.saveFiles().single { it.path == "save/SaveData01.sav" }.file
            val entry = SaveCodec.entries(SaveCodec.read("WOLF_RPG", bytes)).first { it.editable }
            val changed = SaveCodec.patch("WOLF_RPG", bytes, listOf(SaveEdit(entry.path, entry.type, "123456")))
            exportedSave.writeBytes(changed)
            storage.savedFileChanged(exportedSave)
            storage.closeMount()
            db.dao().upsertSaveLocation(GameSaveLocationEntity("wolf-location", game.id, Uri.fromFile(exportedSave.parentFile).toString(), "WOLF_PRIVATE", "SAF", true, true, 1))
            db.dao().insertSaveBackup(SaveBackupEntity("wolf-backup", game.id, Uri.fromFile(exportedSave).toString(), exportedSave.name, Uri.fromFile(backup).toString(), 1, backup.length()))
            val archive = export()
            // A newer pending journal must remain recoverable without replacing the selected snapshot.
            val newer = SaveCodec.patch("WOLF_RPG", bytes, listOf(SaveEdit(entry.path, entry.type, "456789")))
            exportedSave.writeBytes(newer)
            storage.savedFileChanged(exportedSave)
            storage.closeMount()
            val journalRoot = File(privateRoot, "saf-${MkxpzSavePaths.ownerKey(game.documentUri).take(24)}/transactions")
            val journals = journalRoot.listFiles().orEmpty().associate { it.name to it.readBytes() }
            assertTrue(journals.isNotEmpty())
            val conflict = if (sourceDiverges) SaveCodec.patch("WOLF_RPG", bytes, listOf(SaveEdit(entry.path, entry.type, "987654"))) else bytes
            context.contentResolver.openOutputStream(sourceSave.uri, "wt")!!.use { it.write(conflict) }
            BackupManager(context, db).restore(archive)
            assertArrayEquals(conflict, readBytes(context, sourceSave.uri.toString()))
            val restoredFile = File(privateRoot, "game/save/SaveData01.sav")
            assertArrayEquals(changed, restoredFile.readBytes())
            assertEquals(Uri.fromFile(restoredFile.canonicalFile).toString(), db.dao().getSaveBackupsForSource(Uri.fromFile(restoredFile.canonicalFile).toString()).single().sourceUri)
            val reopened = WolfGameStorage(context, game.id, game.documentUri)
            try {
                reopened.prepare("Game.exe") { }
                assertFalse(reopened.isSaf)
                assertArrayEquals(changed, restoredFile.readBytes())
                assertArrayEquals(conflict, readBytes(context, sourceSave.uri.toString()))
                val conflicts = reopened.synchronize()
                assertEquals(sourceDiverges, conflicts > 0)
                reopened.prepare("Game.exe") { }
                assertFalse(reopened.isSaf)
                assertArrayEquals(changed, restoredFile.readBytes())
                assertArrayEquals(if (sourceDiverges) conflict else changed, readBytes(context, sourceSave.uri.toString()))
                journals.forEach { (name, original) -> assertArrayEquals(original, File(journalRoot, name).readBytes()) }
            } finally { reopened.closeMount() }
        } finally { storage.closeMount() }
    }

    @Test fun rejectsCrossOwnerRawPrivateWrongEngineTraversalAndBackupSubstitution() = runBlocking<Unit> {
        val other = game.copy(id = "other-wolf", documentUri = "content://fixture/other", engine = "WOLF_RPG")
        db.dao().upsertGame(other)
        val otherKey = MkxpzSavePaths.ownerKey(other.id)
        val otherBackup = File(SaveBackupPaths.directory(context, other.id), "original.backup").apply { parentFile!!.mkdirs(); writeText("other backup") }
        val archive = export()
        val cases = listOf<(SQLiteDatabase) -> Unit>(
            { it.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf("astra-wolf-save:v1:$otherKey:game/save/SaveData01.sav")) },
            { it.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf(Uri.fromFile(save).toString())) },
            { it.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf("astra-wolf-save:v1:$ownerKey:game/../../secret.sav")) },
            { it.execSQL("UPDATE save_backups SET backupUri = ?", arrayOf(Uri.fromFile(otherBackup).toString())) },
            { it.execSQL("UPDATE launch_profiles SET engineOverride = 'RPG_MAKER_MV'") },
            { it.execSQL("UPDATE games SET physicalPath = ? WHERE id = ?", arrayOf("astra-wolf-save:v1:$ownerKey:game", game.id)) }
        )
        cases.forEach { mutation -> assertRejected(tamper(archive, mutation)) }
        assertEquals("other backup", otherBackup.readText())
    }

    @Test fun symlinkExportAndRestoreAreRejectedBeforeTouchingAnyData() = runBlocking<Unit> {
        val archive = export()
        val sentinel = File(context.filesDir, "sentinel.sav").apply { writeText("private sentinel") }
        check(save.delete())
        Files.createSymbolicLink(save.toPath(), sentinel.toPath())
        try {
            assertRejected(archive)
            assertTrue(runCatching { export() }.isFailure)
            assertEquals("private sentinel", sentinel.readText())
        } finally { Files.delete(save.toPath()); save.writeBytes(bytes) }
        val moved = File(save.parentFile!!.parentFile, "saved-directory")
        check(save.parentFile!!.renameTo(moved))
        Files.createSymbolicLink(save.parentFile!!.toPath(), moved.toPath())
        try { assertRejected(archive) } finally { Files.delete(save.parentFile!!.toPath()); check(moved.renameTo(save.parentFile!!)) }
    }

    @Test fun databaseFailureRollsBackNativeSavesBackupsAndGeneratedMetadata() = runBlocking<Unit> {
        val archive = export()
        save.writeText("newer save")
        windowsSave.writeText("newer windows")
        backup.writeText("newer backup")
        // Violates the existing unique source URI index when the catalogue is copied back.
        val damaged = tamper(archive) { copy ->
            copy.execSQL("DROP INDEX index_game_sources_treeUri")
            copy.execSQL("INSERT INTO game_sources SELECT 'duplicate', displayName, treeUri, enabled, recursive, maxDepth, autoScan, includeHiddenFolders, lastScanAt, lastScanStatus, gamesCount, lastError FROM game_sources LIMIT 1")
        }
        assertRejected(damaged)
        assertEquals("newer save", save.readText())
        assertEquals("newer windows", windowsSave.readText())
        assertEquals("newer backup", backup.readText())
    }

    @Test fun legacyDuplicateBackupsRemainUsableAndArchivesRebaseThemToTheirOwner() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        val legacy = File(context.filesDir, "save-backups/duplicate-import/$id").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val row = SaveBackupEntity(id, game.id, Uri.fromFile(save).toString(), save.name, Uri.fromFile(legacy).toString(), 2, legacy.length())
        db.dao().insertSaveBackup(row)
        val manager = SaveManager(context, db.dao(), SaveFinder(context))
        manager.restoreBackup(row)
        assertArrayEquals(bytes, save.readBytes())
        val archive = export()
        val staging = unpack(archive)
        val canonical = File(context.filesDir, "save-backups/$ownerKey/$id")
        val snapshot = File(staging, BackupArchive.DATABASE_ENTRY)
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            copy.rawQuery("SELECT backupUri FROM save_backups WHERE id = ?", arrayOf(id)).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(Uri.fromFile(canonical).toString(), cursor.getString(0))
            }
            // Model a real archive created before owner directories were introduced.
            copy.execSQL("UPDATE save_backups SET backupUri = ? WHERE id = ?", arrayOf(Uri.fromFile(legacy).toString(), id))
        }
        val oldEntry = File(staging, "save-backups/duplicate-import/$id").apply { parentFile!!.mkdirs() }
        check(File(staging, "save-backups/$ownerKey/$id").renameTo(oldEntry))
        BackupManager(context, db).restore(pack(staging))
        assertArrayEquals(bytes, canonical.readBytes())
        assertArrayEquals(bytes, legacy.readBytes())
        val imported = db.dao().getSaveBackupsForSource(Uri.fromFile(save.canonicalFile).toString()).single { it.id == id }
        assertEquals(Uri.fromFile(canonical).toString(), imported.backupUri)
        manager.deleteBackup(imported)
        assertFalse(canonical.exists())
        assertArrayEquals(bytes, legacy.readBytes())
        val malformed = tamper(archive) { copy ->
            copy.execSQL("UPDATE save_backups SET backupUri = ? WHERE id = ?", arrayOf(Uri.fromFile(File(legacy.parentFile, UUID.randomUUID().toString())).toString(), id))
        }
        assertRejected(malformed)
    }

    @Test fun nonSavSaveDataIsProtectedAndExecutableContentCannotBeArchived() = runBlocking<Unit> {
        val binary = File(privateRoot, "game/save/slot.dat").apply { writeText("private binary slot") }
        val generic = File(privateRoot, "game/slot.save").apply { writeText("generic slot") }
        val archive = export()
        binary.writeText("changed")
        generic.writeText("changed")
        BackupManager(context, db).restore(archive)
        assertEquals("private binary slot", binary.readText())
        assertEquals("generic slot", generic.readText())
        assertFalse(WolfBackupReferences.allowedArchiveFile("wolf-saves/$ownerKey/game/save/run.exe"))
        assertFalse(WolfBackupReferences.allowedArchiveFile("wolf-saves/$ownerKey/game/save/run.rb"))
        assertFalse(WolfBackupReferences.allowedArchiveFile("wolf-saves/$ownerKey/windows/fixture/startup/run.bat"))
        // Changed assets outside recognized save paths must fail explicitly, never yield a partial success.
        File(privateRoot, "game/Data.wolf").writeText("changed unrecognized private data")
        assertTrue(runCatching { export() }.isFailure)
    }

    @Test fun archiveWithValidTokenButMissingWolfSaveBytesIsRejectedBeforeMutation() = runBlocking<Unit> {
        val staging = unpack(export())
        check(File(staging, "wolf-saves/$ownerKey/game/save/SaveData01.sav").delete())
        save.writeText("current live save must remain intact")
        assertRejected(pack(staging))
    }

    private suspend fun export(): Uri = BackupManager(context, db).create(exportUri).let { documentDir(context, exportUri)!!.findFile(it)!!.uri }

    private fun unpack(archive: Uri): File {
        val staging = File(root, "archive-${UUID.randomUUID()}").apply { mkdir() }
        val zip = File(root, "plain-${UUID.randomUUID()}.zip")
        val envelope = BackupEnvelope(KeystoreCrypto::backupEncryptionCipher, KeystoreCrypto::backupDecryptionCipher)
        context.contentResolver.openInputStream(archive)!!.use { input -> zip.outputStream().use { envelope.read(input, it) } }
        zip.inputStream().use { BackupArchive.extract(it, staging) }
        return staging
    }

    private fun tamper(archive: Uri, mutation: (SQLiteDatabase) -> Unit): Uri {
        val staging = unpack(archive)
        SQLiteDatabase.openDatabase(File(staging, BackupArchive.DATABASE_ENTRY).path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            mutation(copy)
        }
        return pack(staging)
    }

    private fun pack(staging: File): Uri {
        val target = documentDir(context, exportUri)!!.createFile("application/octet-stream", "tampered-${UUID.randomUUID()}.zip")!!
        context.contentResolver.openOutputStream(target.uri, "wt")!!.use { output ->
            BackupArchive.write(output, staging.walkTopDown().filter(File::isFile).map { it.relativeTo(staging).invariantSeparatorsPath to it })
        }
        return target.uri
    }

    private fun protectedBytes(): Map<String, ByteArray> = buildMap {
        context.filesDir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) put(file.path, Files.readSymbolicLink(file.toPath()).toString().toByteArray())
            else if (file.isFile) put(file.path, file.readBytes())
        }
        val live = app.getDatabasePath(dbName)
        listOf(live, File(live.path + "-wal")).filter(File::isFile).forEach { put(it.path, it.readBytes()) }
    }

    private suspend fun assertRejected(archive: Uri) {
        val before = protectedBytes()
        assertTrue("Archive should fail", runCatching { BackupManager(context, db).restore(archive) }.isFailure)
        val after = protectedBytes()
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertArrayEquals("Changed $path", bytes, after.getValue(path)) }
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }
}
