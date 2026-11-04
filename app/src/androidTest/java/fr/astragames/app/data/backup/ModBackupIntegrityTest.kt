package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.ModEntity
import fr.astragames.app.data.local.ModInstallationEntity
import fr.astragames.app.data.local.ModInstalledFileEntity
import fr.astragames.app.data.mods.ModsManager
import fr.astragames.app.data.saves.documentDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual mod installs and BackupManager archives; every game/database belongs to this fixture. */
class ModBackupIntegrityTest {
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: AstraDatabase
    private lateinit var databaseName: String
    private lateinit var fixtureId: String
    private lateinit var tree: DocumentFile
    private lateinit var exports: DocumentFile
    private lateinit var game: GameEntity
    private lateinit var gameDirectory: DocumentFile
    private lateinit var mod: ModEntity
    private lateinit var installation: ModInstallationEntity
    private lateinit var replaced: ModInstalledFileEntity
    private lateinit var added: ModInstalledFileEntity
    private lateinit var originalBackup: File
    private val gameDirectories = mutableListOf<DocumentFile>()
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "mod-backup-integrity-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        fixtureId = UUID.randomUUID().toString()
        val fixture = requireNotNull(context.contentResolver.call(provider, "fixture", fixtureId, null))
        val treeUri = Uri.parse(requireNotNull(fixture.getString("uri")))
        tree = requireNotNull(documentDir(context, treeUri))
        exports = requireNotNull(tree.createDirectory("mod-integrity-archives"))
        databaseName = "mod-backup-integrity-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(app, AstraDatabase::class.java, databaseName).build()
        database.dao().upsertSource(GameSourceEntity("source-$fixtureId", "Isolated mod source", treeUri.toString()))

        gameDirectory = requireNotNull(tree.createDirectory("mod-integrity-game"))
        gameDirectories += gameDirectory
        write(requireNotNull(gameDirectory.createFile("application/octet-stream", "replaced.dat")), ORIGINAL)
        game = GameEntity(
            id = "mod-game-$fixtureId", title = "Game before archive", documentUri = gameDirectory.uri.toString(),
            physicalPath = null, executableName = null, engine = "OTHER", launcher = "JOIPLAY",
            sourceId = "source-$fixtureId", dateAdded = 1, lastModified = 1, fingerprint = fixtureId
        )
        database.dao().upsertGame(game)
        val packageDirectory = requireNotNull(tree.createDirectory("mod-integrity-package"))
        val files = requireNotNull(packageDirectory.createDirectory("files"))
        write(requireNotNull(files.createFile("application/octet-stream", "replaced.dat")), MOD_REPLACEMENT)
        write(requireNotNull(files.createFile("application/octet-stream", "added.dat")), MOD_ADDITION)
        mod = ModEntity(
            id = "mod-$fixtureId", modId = "mod-$fixtureId", name = "Integrity fixture", version = "1",
            author = null, description = null, engine = "OTHER", folderUri = packageDirectory.uri.toString(),
            hasManifest = false, installMode = "OVERLAY", target = ".", filesRoot = "files", lastSeenAt = 1
        )
        database.dao().upsertMod(mod)
        installation = ModsManager(context, database.dao()).install(game, mod)
        assertEquals("INSTALLED", installation.status)
        val rows = database.dao().getInstalledFiles(installation.id)
        assertEquals(2, rows.size)
        replaced = rows.single { it.action == "REPLACED" }
        added = rows.single { it.action == "ADDED" }
        assertEquals(hash(ORIGINAL), replaced.originalHash)
        assertEquals(hash(MOD_REPLACEMENT), replaced.installedHash)
        assertNull("An ADDED file has no original bytes", added.originalHash)
        assertNull("An ADDED file needs no original backup", added.backupUri)
        originalBackup = File(requireNotNull(replaced.backupUri))
        assertArrayEquals(ORIGINAL, originalBackup.readBytes())
        assertModdedGame()
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) app.deleteDatabase(databaseName)
        if (::fixtureId.isInitialized) context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun missingOriginalBackupInLegacyArchiveIsRejectedBeforeAnyMutation() = runBlocking<Unit> {
        val staging = unpack(export())
        val archivedBackup = File(staging, originalBackup.relativeTo(context.filesDir).invariantSeparatorsPath)
        assertArrayEquals(ORIGINAL, archivedBackup.readBytes())
        check(archivedBackup.delete())
        val damaged = packLegacy(staging)
        changeLiveCatalogueAfterExport()
        assertRestoreRejectedWithoutMutation(damaged, "missing-original")
    }

    @Test fun changedOriginalBackupWithUnchangedHashIsRejectedBeforeAnyMutation() = runBlocking<Unit> {
        val staging = unpack(export())
        val archivedBackup = File(staging, originalBackup.relativeTo(context.filesDir).invariantSeparatorsPath)
        assertArrayEquals(ORIGINAL, archivedBackup.readBytes())
        archivedBackup.writeText("archive bytes no longer match the installed originalHash")
        // The catalog and its originalHash remain exactly as emitted by BackupManager.
        val damaged = packLegacy(staging)
        changeLiveCatalogueAfterExport()
        assertRestoreRejectedWithoutMutation(damaged, "changed-original")
    }

    @Test fun validLegacyArchiveRestoresOriginalBackupAndAllowsRealUninstall() = runBlocking<Unit> {
        val archive = packLegacy(unpack(export()))
        restoreAndUninstall(archive)
    }

    @Test fun encryptedArchiveKeepsAddedWithoutOriginalHashAndAllowsRealUninstall() = runBlocking<Unit> {
        restoreAndUninstall(export())
    }

    @Test fun missingLiveOriginalBackupPreventsExportWithoutLeavingAnArchive() = runBlocking<Unit> {
        check(originalBackup.delete())
        assertExportRejectedWithoutMutation("export-missing-original")
    }

    @Test fun changedLiveOriginalBackupPreventsExportWithoutLeavingAnArchive() = runBlocking<Unit> {
        originalBackup.writeText("damaged live original; catalog hash deliberately unchanged")
        assertExportRejectedWithoutMutation("export-changed-original")
    }

    @Test fun legacyFileUriAndNonUuidBackupNameRestoreAndUninstall() = runBlocking<Unit> {
        val staging = unpack(export())
        // Historical backups used path separators replaced by '__', not UUID basenames.
        val relative = "mod-backups/${installation.id}/script__nested.rpy"
        val oldEntry = File(staging, originalBackup.relativeTo(context.filesDir).invariantSeparatorsPath)
        val legacyEntry = File(staging, relative)
        check(oldEntry.renameTo(legacyEntry))
        modifyDatabase(staging) { copy ->
            copy.execSQL("UPDATE mod_installed_files SET backupUri = ? WHERE id = ?", arrayOf(
                Uri.fromFile(File("/legacy/android-user/files/$relative")).toString(), replaced.id
            ))
        }
        changeLiveCatalogueAfterExport()
        BackupManager(context, database).restore(packLegacy(staging))
        val restored = database.dao().getInstalledFiles(installation.id).single { it.id == replaced.id }
        val expectedFile = File(context.filesDir, relative)
        assertEquals("Restored backup must be usable by ModsManager, which consumes an absolute path", expectedFile.absolutePath, restored.backupUri)
        assertArrayEquals(ORIGINAL, expectedFile.readBytes())
        uninstallAndAssertOriginal()
    }

    @Test fun crossInstallationBackupReferenceIsRejectedEvenWithMatchingOriginalHash() = runBlocking<Unit> {
        val foreign = installSecondGame()
        assertEquals(replaced.originalHash, foreign.originalHash)
        val staging = unpack(export())
        modifyDatabase(staging) { copy ->
            copy.execSQL("UPDATE mod_installed_files SET backupUri = ? WHERE id = ?", arrayOf(foreign.backupUri, replaced.id))
        }
        changeLiveCatalogueAfterExport()
        assertRestoreRejectedWithoutMutation(packLegacy(staging), "cross-installation-original")
        assertArrayEquals(ORIGINAL, File(requireNotNull(foreign.backupUri)).readBytes())
    }

    @Test fun backupSymlinkIsRejectedByExportAndRestoreWithoutChangingItsTarget() = runBlocking<Unit> {
        val archive = export()
        val sentinel = File(root, "outside-managed-backups.bin").apply { writeBytes(ORIGINAL) }
        check(originalBackup.delete())
        Files.createSymbolicLink(originalBackup.toPath(), sentinel.toPath())
        try {
            assertExportRejectedWithoutMutation("export-backup-symlink")
            assertRestoreRejectedWithoutMutation(archive, "restore-backup-symlink")
            assertArrayEquals(ORIGINAL, sentinel.readBytes())
        } finally {
            Files.delete(originalBackup.toPath())
            originalBackup.writeBytes(ORIGINAL)
        }
    }

    @Test fun unreferencedArchiveBackupIsRejectedBeforeAnyMutation() = runBlocking<Unit> {
        val staging = unpack(export())
        File(staging, "mod-backups/unreferenced-installation/leftover.bin").apply {
            parentFile!!.mkdirs(); writeText("unowned archive payload")
        }
        changeLiveCatalogueAfterExport()
        assertRestoreRejectedWithoutMutation(packLegacy(staging), "unreferenced-archive-payload")
    }

    @Test fun localOrphanIsExcludedFromExportAndRetainedUnchanged() = runBlocking<Unit> {
        val orphan = File(context.filesDir, "mod-backups/deleted-catalogue-installation/old__script.rpy").apply {
            parentFile!!.mkdirs(); writeText("local recovery original after catalog deletion")
        }
        val before = protectedBytes()
        val staging = unpack(export())
        assertFalse("Local orphans must not make an exported archive unowned", File(staging,
            orphan.relativeTo(context.filesDir).invariantSeparatorsPath).exists())
        assertArrayEquals(ORIGINAL, File(staging, originalBackup.relativeTo(context.filesDir).invariantSeparatorsPath).readBytes())
        assertProtectedUnchanged("export-with-local-orphan", before)
    }

    @Test fun twoReferencesToTheSameBackupThroughRawAndFileUriAreRejected() = runBlocking<Unit> {
        val staging = unpack(export())
        modifyDatabase(staging) { copy ->
            // Both rows are otherwise complete REPLACED records. The alias must not
            // allow two destinations to share one original backup.
            copy.execSQL("UPDATE mod_installed_files SET action = 'REPLACED', originalHash = ?, backupUri = ? WHERE id = ?",
                arrayOf(replaced.originalHash, Uri.fromFile(originalBackup).toString(), added.id))
        }
        changeLiveCatalogueAfterExport()
        assertRestoreRejectedWithoutMutation(packLegacy(staging), "duplicate-original-reference")
    }

    @Test fun invalidActionsIncompleteReplacementsAndOrphanRowsAreRejected() = runBlocking<Unit> {
        val archive = export()
        changeLiveCatalogueAfterExport()
        val mutations = listOf<Pair<String, (SQLiteDatabase) -> Unit>>(
            "unknown-action" to { copy -> copy.execSQL("UPDATE mod_installed_files SET action = 'UNKNOWN' WHERE id = ?", arrayOf(replaced.id)) },
            "replacement-without-backup" to { copy -> copy.execSQL("UPDATE mod_installed_files SET backupUri = NULL WHERE id = ?", arrayOf(replaced.id)) },
            "replacement-without-hash" to { copy -> copy.execSQL("UPDATE mod_installed_files SET originalHash = NULL WHERE id = ?", arrayOf(replaced.id)) },
            "replacement-invalid-hash" to { copy -> copy.execSQL("UPDATE mod_installed_files SET originalHash = 'not-a-sha256' WHERE id = ?", arrayOf(replaced.id)) },
            "replacement-uppercase-hash" to { copy -> copy.execSQL("UPDATE mod_installed_files SET originalHash = ? WHERE id = ?", arrayOf(requireNotNull(replaced.originalHash).uppercase(), replaced.id)) },
            "added-with-original-hash" to { copy -> copy.execSQL("UPDATE mod_installed_files SET originalHash = ? WHERE id = ?", arrayOf(replaced.originalHash, added.id)) },
            "added-with-backup" to { copy -> copy.execSQL("UPDATE mod_installed_files SET backupUri = ? WHERE id = ?", arrayOf(replaced.backupUri, added.id)) },
            "orphan-installed-file-row" to { copy -> copy.execSQL("DELETE FROM mod_installations WHERE id = ?", arrayOf(installation.id)) }
        )
        for ((name, mutation) in mutations) {
            val staging = unpack(archive)
            modifyDatabase(staging, mutation)
            assertRestoreRejectedWithoutMutation(packLegacy(staging), name)
        }
    }

    @Test fun databaseVersionsFiveToSevenWithoutModTablesAndPayloadRemainRestorable() = runBlocking<Unit> {
        val archive = export()
        for (version in 5..7) {
            val staging = unpack(archive)
            modifyDatabase(staging) { copy ->
                copy.execSQL("DROP TABLE mod_installed_files")
                copy.execSQL("DROP TABLE mod_installations")
                copy.execSQL("DROP TABLE mods")
                copy.version = version
            }
            check(File(staging, "mod-backups").deleteRecursively())
            BackupManager(context, database).restore(packLegacy(staging))
            assertEquals(game.title, database.dao().getGame(game.id)!!.title)
            assertTrue(database.dao().getMods().isEmpty())
            assertTrue(database.dao().getInstalledFiles(installation.id).isEmpty())
            // Restoring a pre-mod catalog does not alter the games or delete local
            // recovery originals, which now have no catalog owner.
            assertModdedGame()
            assertArrayEquals(ORIGINAL, originalBackup.readBytes())
        }
    }

    private suspend fun restoreAndUninstall(archive: Uri) {
        originalBackup.writeText("live backup changed after the valid archive")
        changeLiveCatalogueAfterExport()
        BackupManager(context, database).restore(archive)
        assertArrayEquals(ORIGINAL, originalBackup.readBytes())
        assertEquals(game.title, database.dao().getGame(game.id)!!.title)
        assertModdedGame()
        val restoredAdded = database.dao().getInstalledFiles(installation.id).single { it.id == added.id }
        assertNull(restoredAdded.originalHash)
        assertNull(restoredAdded.backupUri)
        uninstallAndAssertOriginal()
    }

    private suspend fun uninstallAndAssertOriginal() {
        val restoredInstallation = database.dao().getInstallationsForMod(installation.modId).single { it.id == installation.id }
        assertTrue(ModsManager(context, database.dao()).uninstall(restoredInstallation).isEmpty())
        assertArrayEquals(ORIGINAL, read(requireNotNull(gameDirectory.findFile("replaced.dat"))))
        assertNull(gameDirectory.findFile("added.dat"))
        assertTrue(database.dao().getInstalledFiles(installation.id).isEmpty())
        assertFalse(originalBackup.exists())
    }

    private suspend fun installSecondGame(): ModInstalledFileEntity {
        val folder = requireNotNull(tree.createDirectory("mod-integrity-second-game"))
        gameDirectories += folder
        write(requireNotNull(folder.createFile("application/octet-stream", "replaced.dat")), ORIGINAL)
        val secondGame = game.copy(id = "second-${game.id}", title = "Second isolated game", documentUri = folder.uri.toString(), fingerprint = "second-$fixtureId")
        database.dao().upsertGame(secondGame)
        val installed = ModsManager(context, database.dao()).install(secondGame, mod)
        return database.dao().getInstalledFiles(installed.id).single { it.action == "REPLACED" }
    }

    private suspend fun changeLiveCatalogueAfterExport() {
        database.dao().upsertGame(game.copy(title = "Current live title must survive rejected restore"))
    }

    private suspend fun export(): Uri = requireNotNull(exports.findFile(BackupManager(context, database).create(exports.uri))).uri

    private fun unpack(archive: Uri): File {
        val staging = File(root, "archive-${UUID.randomUUID()}").apply { check(mkdir()) }
        val zip = File(root, "plain-${UUID.randomUUID()}.zip")
        val envelope = BackupEnvelope(KeystoreCrypto::backupEncryptionCipher, KeystoreCrypto::backupDecryptionCipher)
        requireNotNull(context.contentResolver.openInputStream(archive)).use { input ->
            zip.outputStream().use { envelope.read(input, it) }
        }
        zip.inputStream().use { BackupArchive.extract(it, staging) }
        return staging
    }

    /** Legacy ZIP import is supported; mutation occurs after a real encrypted export. */
    private fun packLegacy(staging: File): Uri {
        val archive = requireNotNull(exports.createFile("application/zip", "legacy-${UUID.randomUUID()}.zip"))
        requireNotNull(context.contentResolver.openOutputStream(archive.uri, "wt")).use { output ->
            BackupArchive.write(output, staging.walkTopDown().filter(File::isFile)
                .map { it.relativeTo(staging).invariantSeparatorsPath to it })
        }
        return archive.uri
    }

    private fun modifyDatabase(staging: File, mutation: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(File(staging, BackupArchive.DATABASE_ENTRY).path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            mutation(copy)
        }
    }

    private suspend fun assertExportRejectedWithoutMutation(scenario: String) {
        val before = protectedBytes()
        val archivesBefore = exports.listFiles().associate { requireNotNull(it.name) to read(it) }
        val failure = runCatching { export() }.exceptionOrNull()
        assertNotNull("$scenario must not export an unusable original backup", failure)
        assertProtectedUnchanged(scenario, before)
        val archivesAfter = exports.listFiles().associate { requireNotNull(it.name) to read(it) }
        assertEquals("$scenario left a partial archive", archivesBefore.keys, archivesAfter.keys)
        archivesBefore.forEach { (name, bytes) -> assertArrayEquals("$scenario changed an existing archive", bytes, archivesAfter.getValue(name)) }
        assertTrue("$scenario left staging data", context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("backup-") })
        assertModdedGame()
    }

    private suspend fun assertRestoreRejectedWithoutMutation(archive: Uri, scenario: String) {
        val before = protectedBytes()
        val failure = runCatching { BackupManager(context, database).restore(archive) }.exceptionOrNull()
        val after = protectedBytes()
        val changed = (before.keys + after.keys).filter { key ->
            val a = before[key]; val b = after[key]
            a == null || b == null || !a.contentEquals(b)
        }
        Log.i("ModBackupIntegrity", JSONObject().put("fixture", fixtureId).put("scenario", scenario)
            .put("rejected", failure != null).put("changedProtectedFiles", changed.joinToString())
            .put("originalHash", replaced.originalHash).toString())
        assertNotNull("$scenario must reject before mutation; changed protected files: $changed", failure)
        assertProtectedUnchanged(scenario, before, after)
        assertTrue("Restore work must be cleaned after a complete rollback/refusal", context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
        assertModdedGame()
    }

    private fun assertProtectedUnchanged(scenario: String, before: Map<String, ByteArray>, after: Map<String, ByteArray> = protectedBytes()) {
        assertEquals("$scenario created or removed protected files", before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals("$scenario changed $name", bytes, after.getValue(name)) }
    }

    /** Exact live backup/catalog/WAL bytes, symlink targets and every installed game file. */
    private fun protectedBytes(): Map<String, ByteArray> = buildMap {
        context.filesDir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            val path = "private/${file.relativeTo(context.filesDir).invariantSeparatorsPath}"
            when {
                Files.isSymbolicLink(file.toPath()) -> put("link:$path", Files.readSymbolicLink(file.toPath()).toString().toByteArray())
                file.isDirectory -> put("dir:$path", byteArrayOf())
                file.isFile -> put(path, file.readBytes())
            }
        }
        fun documents(folder: DocumentFile, relative: String) {
            put("dir:$relative", byteArrayOf())
            folder.listFiles().forEach { file ->
                val name = "$relative/${requireNotNull(file.name)}"
                if (file.isDirectory) documents(file, name) else put(name, read(file))
            }
        }
        gameDirectories.forEachIndexed { index, folder -> documents(folder, "game-$index") }
        val live = app.getDatabasePath(databaseName)
        listOf(live, File(live.path + "-wal")).filter(File::isFile).forEach { put("catalog/${it.name}", it.readBytes()) }
    }

    private fun assertModdedGame() {
        assertArrayEquals(MOD_REPLACEMENT, read(requireNotNull(gameDirectory.findFile("replaced.dat"))))
        assertArrayEquals(MOD_ADDITION, read(requireNotNull(gameDirectory.findFile("added.dat"))))
    }

    private fun write(document: DocumentFile, bytes: ByteArray) {
        requireNotNull(context.contentResolver.openOutputStream(document.uri, "wt")).use { it.write(bytes) }
    }

    private fun read(document: DocumentFile): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(document.uri)).use { it.readBytes() }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val ORIGINAL = "Original game bytes preserved before mod installation".toByteArray()
        private val MOD_REPLACEMENT = "Mod replaces an existing game file".toByteArray()
        private val MOD_ADDITION = "Mod adds a new game file".toByteArray()
    }
}
