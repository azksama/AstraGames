package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.data.saves.SaveManager
import fr.astragames.app.data.saves.SaveData
import fr.astragames.app.mkxpz.MkxpzStorage
import fr.astragames.app.mkxpz.MkxpzSavePaths
import fr.astragames.app.core.security.KeystoreCrypto
import android.database.sqlite.SQLiteDatabase
import java.nio.file.Files
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite snapshot, production envelope, SAF streams and isolated private game copy. */
@RunWith(AndroidJUnit4::class)
class MkxpzBackupTest {
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AstraDatabase
    private lateinit var dbName: String
    private lateinit var fixtureId: String
    private lateinit var exportUri: Uri
    private lateinit var game: GameEntity
    private lateinit var save: File
    private lateinit var backup: File
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "mkxpz-backup-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        fixtureId = UUID.randomUUID().toString()
        val treeUri = Uri.parse(context.contentResolver.call(provider, "fixture", fixtureId, null)!!.getString("uri"))
        val tree = documentDir(context, treeUri)!!
        exportUri = tree.createDirectory("mkxpz-exports")!!.uri
        dbName = "mkxpz-backup-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(app, AstraDatabase::class.java, dbName).build()
        game = GameEntity(id = "mkxpz-owner", title = "Native saves", documentUri = tree.findFile("game")!!.uri.toString(),
            physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY",
            sourceId = "mkxpz-source", dateAdded = 1, lastModified = 1, fingerprint = "mkxpz-backup-fixture")
        db.dao().upsertSource(GameSourceEntity(game.sourceId, "MKXP-Z fixture", treeUri.toString()))
        db.dao().upsertGame(game)
        // The stored catalogue engine is deliberately different from the effective engine.
        db.dao().upsertLaunchProfile(LaunchProfileEntity(game.id, "ASTRA_MKXPZ", "RPG_MAKER_VX_ACE"))
        val directory = MkxpzStorage(context, game.id).gameDirectory
        save = File(directory, "Save/Save01.rvdata2").apply { parentFile!!.mkdirs(); writeBytes(marshalState(2)) }
        File(directory, "Game.ini").writeText("game assets must not be archived")
        File(directory, "Data").mkdir()
        File(directory, "Data/Actors.rvdata2").writeText("not a save")
        db.dao().upsertSaveLocation(GameSaveLocationEntity("native-location", game.id, Uri.fromFile(save.parentFile).toString(), "RPG_MAKER", "Native", true, true, 1))
        backup = File(context.filesDir, "save-backups/${fr.astragames.app.mkxpz.MkxpzSavePaths.ownerKey(game.id)}/original.save").apply { parentFile!!.mkdirs(); writeBytes(marshalState(1)) }
        db.dao().insertSaveBackup(SaveBackupEntity("native-backup", game.id, Uri.fromFile(save).toString(), save.name, Uri.fromFile(backup).toString(), 1, backup.length()))
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::dbName.isInitialized) app.deleteDatabase(dbName)
        if (::fixtureId.isInitialized) context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun privateSavesAndBackupsRoundTripUsingEffectiveProfileEngine() = runBlocking<Unit> {
        val manager = BackupManager(context, db)
        val name = manager.create(exportUri)
        val archive = documentDir(context, exportUri)!!.findFile(name)!!.uri
        save.writeText("changed live save")
        backup.writeText("changed backup")
        db.dao().upsertGame(game.copy(title = "Changed title"))
        db.dao().deleteLaunchProfile(game.id)

        // Red on the original implementation: validateFileReferences rejects the
        // private MKXP-Z location/sourceUri before restoring this valid export.
        manager.restore(archive)

        assertArrayEquals(marshalState(2), save.readBytes())
        assertArrayEquals(marshalState(1), backup.readBytes())
        assertEquals(game.title, db.dao().getGame(game.id)!!.title)
        assertEquals("RPG_MAKER_VX_ACE", db.dao().getLaunchProfile(game.id)!!.engineOverride)
        assertEquals(Uri.fromFile(save.parentFile).toString(), db.dao().getSaveLocations(game.id).single().uri)
        assertEquals(Uri.fromFile(save).toString(), db.dao().getSaveBackupsForSource(Uri.fromFile(save).toString()).single().sourceUri)
        val saves = SaveManager(context, db.dao(), SaveFinder(context))
        val restored = saves.listSaves(game).single { it.uri == Uri.fromFile(save).toString() }
        val (data, entries) = saves.readSave(game, restored)
        assertTrue(data is SaveData.RawSave)
        assertEquals("2", entries.single { it.editable }.displayValue)
        saves.restoreBackup(db.dao().getSaveBackupsForSource(restored.uri).single())
        assertEquals("1", saves.readSave(game, restored).second.single { it.editable }.displayValue)
        assertArrayEquals(marshalState(1), save.readBytes())
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun archiveUsesTypedReferencesAndContainsSavesOnly() = runBlocking<Unit> {
        val archive = export()
        val staging = unpack(archive)
        val key = MkxpzSavePaths.ownerKey(game.id)
        assertTrue(File(staging, "mkxpz-saves/$key/Save/Save01.rvdata2").isFile)
        assertEquals(listOf("Save/Save01.rvdata2"), File(staging, "mkxpz-saves/$key").walkTopDown()
            .filter(File::isFile).map { it.relativeTo(File(staging, "mkxpz-saves/$key")).invariantSeparatorsPath }.toList())
        SQLiteDatabase.openDatabase(File(staging, BackupArchive.DATABASE_ENTRY).path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            copy.rawQuery("SELECT uri FROM game_save_locations", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("astra-mkxpz-save:v1:$key:Save", c.getString(0))
            }
            copy.rawQuery("SELECT sourceUri FROM save_backups", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("astra-mkxpz-save:v1:$key:Save/Save01.rvdata2", c.getString(0))
            }
        }
        // Export must not replace the live catalogue's usable file URIs with tokens.
        assertEquals(Uri.fromFile(save.parentFile).toString(), db.dao().getSaveLocations(game.id).single().uri)
    }

    @Test fun rejectsRawPrivateOtherOwnerTraversalAndWrongFieldBeforeAnyMutation() = runBlocking<Unit> {
        val otherId = "other-mkxpz-owner"
        db.dao().upsertGame(game.copy(id = otherId, documentUri = "content://fixture/other-game", engine = "RPG_MAKER_VX_ACE"))
        val otherSave = File(MkxpzStorage(context, otherId).gameDirectory, "Save01.rvdata2")
            .apply { parentFile!!.mkdirs(); writeText("other owner state") }
        val arbitrary = File(context.filesDir, "private-settings.bin").apply { writeText("private sentinel") }
        val original = export()
        val key = MkxpzSavePaths.ownerKey(game.id)
        val otherKey = MkxpzSavePaths.ownerKey(otherId)
        val cases = listOf(
            "raw-private" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf(Uri.fromFile(arbitrary).toString())) },
            "raw-owned-private" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf(Uri.fromFile(save).toString())) },
            "other-owner" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf("astra-mkxpz-save:v1:$otherKey:Save01.rvdata2")) },
            "traversal" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE save_backups SET sourceUri = ?", arrayOf("astra-mkxpz-save:v1:$key:Save/../../private-settings.bin")) },
            "wrong-field" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE games SET physicalPath = ? WHERE id = ?", arrayOf("astra-mkxpz-save:v1:$key:.", game.id)) },
            "wrong-engine" to { copy: SQLiteDatabase -> copy.execSQL("UPDATE launch_profiles SET engineOverride = 'RPG_MAKER_MV'") }
        )
        cases.forEach { (name, mutation) -> assertRejectedWithoutMutation(tamper(original, name, mutation)) }
        assertEquals("other owner state", otherSave.readText())
        assertEquals("private sentinel", arbitrary.readText())
    }

    @Test fun rejectsSymlinkFileAndParentBeforeWritingAnySaveOrBackup() = runBlocking<Unit> {
        val archive = export()
        val target = File(context.filesDir, "unrelated-private.bin").apply { writeText("must remain intact") }
        val bytes = save.readBytes()
        check(save.delete())
        Files.createSymbolicLink(save.toPath(), target.toPath())
        try { assertRejectedWithoutMutation(archive) } finally { Files.delete(save.toPath()); save.writeBytes(bytes) }

        val moved = File(save.parentFile!!.parentFile, "original-save-folder")
        check(save.parentFile!!.renameTo(moved))
        Files.createSymbolicLink(save.parentFile!!.toPath(), moved.toPath())
        try { assertRejectedWithoutMutation(archive) } finally {
            Files.delete(save.parentFile!!.toPath()); check(moved.renameTo(save.parentFile!!))
        }
        assertEquals("must remain intact", target.readText())
    }

    @Test fun rejectsSymlinkDuringExportInsteadOfArchivingItsTarget() = runBlocking<Unit> {
        val sentinel = File(context.filesDir, "secret.bin").apply { writeText("not exportable") }
        check(save.delete())
        Files.createSymbolicLink(save.toPath(), sentinel.toPath())
        try {
            assertNotNull(runCatching { BackupManager(context, db).create(exportUri) }.exceptionOrNull())
            assertTrue(documentDir(context, exportUri)!!.listFiles().isEmpty())
            assertEquals("not exportable", sentinel.readText())
        } finally { Files.delete(save.toPath()) }
    }

    @Test fun refusesUnownedWolfPrivateExportInsteadOfCreatingAnUnrestorableArchive() = runBlocking<Unit> {
        db.dao().upsertLaunchProfile(LaunchProfileEntity(game.id, "JOIPLAY", "WOLF_RPG"))
        val wolfSave = File(context.filesDir, "wolf-private-fixture/Save01.sav").apply {
            parentFile!!.mkdirs(); writeText("unowned Wolf state")
        }
        db.dao().upsertSaveLocation(GameSaveLocationEntity("native-location", game.id,
            Uri.fromFile(wolfSave.parentFile).toString(), "WOLF_PRIVATE", "Wolf", true, true, 1))
        db.dao().insertSaveBackup(SaveBackupEntity("native-backup", game.id, Uri.fromFile(wolfSave).toString(),
            wolfSave.name, Uri.fromFile(backup).toString(), 1, backup.length()))
        assertNotNull(runCatching { export() }.exceptionOrNull())
        assertTrue(documentDir(context, exportUri)!!.listFiles().isEmpty())
        assertEquals("unowned Wolf state", wolfSave.readText())
    }

    @Test fun rejectsArchiveWithValidTokenButMissingNativeSaveBytes() = runBlocking<Unit> {
        val staging = unpack(export())
        check(File(staging, "mkxpz-saves/${MkxpzSavePaths.ownerKey(game.id)}/Save/Save01.rvdata2").delete())
        val target = documentDir(context, exportUri)!!.createFile("application/octet-stream", "missing-native-save.zip")!!
        context.contentResolver.openOutputStream(target.uri, "wt")!!.use { output ->
            BackupArchive.write(output, staging.walkTopDown().filter(File::isFile).map { it.relativeTo(staging).invariantSeparatorsPath to it })
        }
        save.writeText("current native save must remain intact")
        assertRejectedWithoutMutation(target.uri)
    }

    private suspend fun export(): Uri {
        val name = BackupManager(context, db).create(exportUri)
        return documentDir(context, exportUri)!!.findFile(name)!!.uri
    }

    /** Ruby Marshal 4.8: an Array containing one small Integer. */
    private fun marshalState(value: Int) = byteArrayOf(4, 8, '['.code.toByte(), 6, 'i'.code.toByte(), (value + 5).toByte())

    private fun unpack(archive: Uri): File {
        val staging = File(root, "archive-${UUID.randomUUID()}").apply { mkdir() }
        val zip = File(root, "plain-${UUID.randomUUID()}.zip")
        val envelope = BackupEnvelope(KeystoreCrypto::backupEncryptionCipher, KeystoreCrypto::backupDecryptionCipher)
        context.contentResolver.openInputStream(archive)!!.use { input -> zip.outputStream().use { envelope.read(input, it) } }
        zip.inputStream().use { BackupArchive.extract(it, staging) }
        return staging
    }

    private fun tamper(archive: Uri, name: String, mutation: (SQLiteDatabase) -> Unit): Uri {
        val staging = unpack(archive)
        SQLiteDatabase.openDatabase(File(staging, BackupArchive.DATABASE_ENTRY).path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            mutation(copy)
        }
        val target = documentDir(context, exportUri)!!.createFile("application/octet-stream", "$name.zip")!!
        context.contentResolver.openOutputStream(target.uri, "wt")!!.use { output ->
            BackupArchive.write(output, staging.walkTopDown().filter(File::isFile).map { it.relativeTo(staging).invariantSeparatorsPath to it })
        }
        return target.uri
    }

    private fun protectedBytes(): Map<String, ByteArray> = buildMap {
        // Read links as links, without following a malicious directory while asserting.
        context.filesDir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) put(file.path, Files.readSymbolicLink(file.toPath()).toString().toByteArray())
            else if (file.isFile) put(file.path, file.readBytes())
        }
        val live = app.getDatabasePath(dbName)
        listOf(live, File(live.path + "-wal")).filter(File::isFile).forEach { put(it.path, it.readBytes()) }
    }

    private suspend fun assertRejectedWithoutMutation(archive: Uri) {
        val before = protectedBytes()
        assertNotNull("Malicious archive was accepted", runCatching { BackupManager(context, db).restore(archive) }.exceptionOrNull())
        val after = protectedBytes()
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertArrayEquals("Changed bytes at $path", bytes, after.getValue(path)) }
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }
}
