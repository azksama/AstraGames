package fr.astragames.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.data.saves.documentDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native Android Keystore + real content:// streams; no UI, network or production data reset. */
@RunWith(AndroidJUnit4::class)
class BackupAndroidTest {
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var testKey: SecretKey
    private var database: AstraDatabase? = null
    private var databaseName: String? = null
    private var fixtureId: String? = null
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "backup-android-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        alias = "astra_test_backup_${UUID.randomUUID()}"
        testKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    @After fun cleanup() {
        try {
            fixtureId?.let { context.contentResolver.call(provider, "removeFixture", it, null) }
        } finally {
            database?.close()
            databaseName?.let(app::deleteDatabase)
            root.deleteRecursively()
            // This UUID alias belongs only to this test. Never delete/reset astra_master_key.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (iv == null) init(mode, testKey) else init(mode, testKey, GCMParameterSpec(128, iv))
        assertTrue("Expected Android Keystore, received ${this.provider.name}", this.provider.name.contains("AndroidKeyStore"))
    }

    private fun envelope() = BackupEnvelope(
        encryptionCipher = { cipher(Cipher.ENCRYPT_MODE) },
        decryptionCipher = { cipher(Cipher.DECRYPT_MODE, it) }
    )

    @Test fun nativeKeystoreAuthenticatesMultipleChunksAndEmptyArchive() {
        val envelope = envelope()
        val plain = ByteArray(BackupEnvelope.CHUNK_BYTES + 123).also(SecureRandom()::nextBytes)
        val encoded = ByteArrayOutputStream().also { envelope.write(plain.inputStream(), plain.size.toLong(), it) }.toByteArray()
        assertEquals("AST2", encoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(32 + plain.size + 2 * 28, encoded.size)
        envelope.verify(encoded.inputStream(), plain.size.toLong(), MessageDigest.getInstance("SHA-256").digest(plain))
        val restored = ByteArrayOutputStream().also { envelope.read(encoded.inputStream(), it) }.toByteArray()
        assertArrayEquals(plain, restored)

        val empty = ByteArrayOutputStream().also { envelope.write(ByteArray(0).inputStream(), 0, it) }.toByteArray()
        assertEquals(60, empty.size) // An empty archive still carries an IV and authenticated tag.
        envelope.verify(empty.inputStream(), 0, MessageDigest.getInstance("SHA-256").digest(ByteArray(0)))
    }

    @Test fun nativeKeystoreRejectsAlteredRecordAndHeader() {
        val envelope = envelope()
        val plain = ByteArray(BackupEnvelope.CHUNK_BYTES + 31) { (it * 31).toByte() }
        val encoded = ByteArrayOutputStream().also { envelope.write(plain.inputStream(), plain.size.toLong(), it) }.toByteArray()
        listOf(16, encoded.lastIndex).forEach { offset ->
            val corrupt = encoded.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                envelope.read(corrupt.inputStream(), ByteArrayOutputStream())
            }
            assertTrue(failure.cause is BadPaddingException)
            assertTrue(failure.message.orEmpty().contains("autre installation d’Astra"))
        }
    }

    @Test fun catalogueExportAndRestoreUseSafAndRejectCorruptionBeforeMutation() = runBlocking<Unit> {
        val fixture = UUID.randomUUID().toString().also { fixtureId = it }
        val treeUri = Uri.parse(context.contentResolver.call(provider, "fixture", fixture, null)!!.getString("uri"))
        val tree = documentDir(context, treeUri)!!
        val gameFolder = tree.findFile("game")!!
        val saveFolder = gameFolder.findFile("save")!!
        val sourceUri = saveFolder.findFile("file1.rpgsave")!!.uri.toString()
        val exportFolder = tree.createDirectory("catalogue-exports")!!
        val dbName = "backup-android-${UUID.randomUUID()}.db".also { databaseName = it }
        val db = Room.databaseBuilder(app, AstraDatabase::class.java, dbName).build().also { database = it }
        val dao = db.dao()
        val game = GameEntity(
            id = "backup-game", title = "Backup Android", documentUri = gameFolder.uri.toString(), physicalPath = null,
            executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "backup-source",
            dateAdded = 1, lastModified = 1, fingerprint = "backup-fixture"
        )
        dao.upsertSource(GameSourceEntity(game.sourceId, "Backup fixture", treeUri.toString()))
        dao.upsertGame(game)
        dao.upsertSaveLocation(GameSaveLocationEntity("location", game.id, saveFolder.uri.toString(), "CUSTOM", "Fixture", false, true, 1))
        val savedOriginal = "original save backup"
        val backupFile = File(context.filesDir, "save-backups/${game.id}/original.save").apply {
            parentFile!!.mkdirs()
            writeText(savedOriginal)
        }
        dao.insertSaveBackup(SaveBackupEntity("backup", game.id, sourceUri, "file1.rpgsave", Uri.fromFile(backupFile).toString(), 1, savedOriginal.length.toLong()))
        val asset = File(context.filesDir, "covers/large-fixture.bin").apply { parentFile!!.mkdirs() }
        val assetBytes = ByteArray(BackupEnvelope.CHUNK_BYTES + 321).also(SecureRandom()::nextBytes)
        asset.writeBytes(assetBytes)

        // Exercise the production class and its existing key; this test never alters/deletes that key.
        val manager = BackupManager(context, db)
        val name = manager.create(exportFolder.uri)
        val exported = exportFolder.findFile(name)!!
        val encoded = context.contentResolver.openInputStream(exported.uri)!!.use { it.readBytes() }
        assertEquals("AST2", encoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertTrue(encoded.size > BackupEnvelope.CHUNK_BYTES)

        dao.upsertGame(game.copy(title = "Before rejected restore"))
        backupFile.writeText("changed backup")
        asset.writeText("changed asset")
        val corrupt = exportFolder.createFile("application/octet-stream", "corrupted.astra")!!
        context.contentResolver.openOutputStream(corrupt.uri, "wt")!!.use { output ->
            output.write(encoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        }
        val failure = runCatching { manager.restore(corrupt.uri) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && failure.cause is BadPaddingException)
        assertEquals("Before rejected restore", dao.getGame(game.id)!!.title)
        assertEquals("changed backup", backupFile.readText())
        assertEquals("changed asset", asset.readText())

        dao.deleteGameCompletely(game.id)
        manager.restore(exported.uri)
        assertEquals(game.title, dao.getGame(game.id)!!.title)
        assertEquals(1, dao.getSaveLocations(game.id).size)
        assertEquals(1, dao.getSaveBackupsForSource(sourceUri).size)
        assertEquals(savedOriginal, backupFile.readText())
        assertArrayEquals(assetBytes, asset.readBytes())
        assertEquals(game.id, dao.searchGames("Backup*").first().single().id)
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }
}
