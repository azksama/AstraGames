package fr.astragames.app.data.backup

import fr.astragames.app.core.filesystem.copyBoundedTo
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.BadPaddingException

/**
 * AST2 authenticates bounded records, including their archive identity, position and total size.
 * The output of [read] is staging data: callers must not use it until this method returns.
 * AST1 remains readable, but its single GCM record can still be buffered by the crypto provider.
 */
internal class BackupEnvelope(
    private val encryptionCipher: () -> Cipher,
    private val decryptionCipher: (ByteArray) -> Cipher
) {
    fun write(input: InputStream, length: Long, output: OutputStream) {
        require(length in 0..BackupArchive.MAX_BYTES) { "Sauvegarde trop volumineuse." }
        val identity = ByteArray(IDENTITY_BYTES).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC_V2).putInt(CHUNK_BYTES).putLong(length).put(identity).array()
        output.write(header)
        val buffer = ByteArray(minOf(length, CHUNK_BYTES.toLong()).toInt())
        var remaining = length
        var index = 0
        do {
            val count = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
            input.readExactly(buffer, count)
            val cipher = encryptionCipher()
            cipher.updateAAD(recordAad(header, index, count))
            val encrypted = cipher.doFinal(buffer, 0, count)
            val iv = cipher.iv
            check(iv.size == IV_BYTES && encrypted.size == count + TAG_BYTES) { "Format de chiffrement incompatible." }
            output.write(iv)
            output.write(encrypted)
            remaining -= count
            index++
        } while (remaining > 0)
        require(input.read() == -1) { "La sauvegarde a changé pendant l’export." }
    }

    fun read(input: InputStream, output: OutputStream) {
        val magic = ByteArray(4).also { input.readExactly(it) }
        when {
            magic.contentEquals(MAGIC_V2) -> readV2(input, output, magic)
            magic.contentEquals(MAGIC_V1) -> readV1(input, output)
            magic.contentEquals(ZIP_LOCAL_HEADER) || magic.contentEquals(ZIP_EMPTY_HEADER) -> {
                output.write(magic)
                input.copyBoundedTo(output, BackupArchive.MAX_BYTES - magic.size)
            }
            else -> error("Format de sauvegarde non reconnu.")
        }
    }

    /** Reopens a completed export without retaining plaintext or extracting its ZIP. */
    fun verify(input: InputStream, expectedLength: Long, expectedSha256: ByteArray) {
        require(expectedLength in 0..BackupArchive.MAX_BYTES && expectedSha256.size == 32)
        val magic = ByteArray(4).also { input.readExactly(it) }
        require(magic.contentEquals(MAGIC_V2)) { "Le fichier exporté ne correspond pas à la sauvegarde." }
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) {
                digest.update(value.toByte())
                length++
            }
            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                digest.update(buffer, offset, count)
                length += count
            }
        }
        readV2(input, sink, magic)
        require(length == expectedLength && MessageDigest.isEqual(digest.digest(), expectedSha256)) {
            "Le fichier exporté ne correspond pas à la sauvegarde."
        }
    }

    private fun readV2(input: InputStream, output: OutputStream, magic: ByteArray) {
        val header = ByteArray(HEADER_BYTES)
        magic.copyInto(header)
        input.readExactly(header, HEADER_BYTES - magic.size, magic.size)
        val metadata = ByteBuffer.wrap(header).apply { position(magic.size) }
        require(metadata.int == CHUNK_BYTES) { "Format de sauvegarde incompatible." }
        val length = metadata.long
        require(length in 0..BackupArchive.MAX_BYTES) { "Sauvegarde trop volumineuse." }
        var remaining = length
        var index = 0
        do {
            val count = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
            val iv = ByteArray(IV_BYTES).also { input.readExactly(it) }
            val encrypted = ByteArray(count + TAG_BYTES).also { input.readExactly(it) }
            val cipher = decryptionCipher(iv)
            cipher.updateAAD(recordAad(header, index, count))
            val plain = finishDecryption(cipher, encrypted)
            require(plain.size == count) { "Sauvegarde endommagée." }
            output.write(plain)
            remaining -= count
            index++
        } while (remaining > 0)
        require(input.read() == -1) { "Données inattendues après la sauvegarde." }
    }

    /** Avoids loading/copying the ciphertext; legacy providers may still buffer all plaintext. */
    private fun readV1(input: InputStream, output: OutputStream) {
        val iv = ByteArray(IV_BYTES).also { input.readExactly(it) }
        val cipher = decryptionCipher(iv)
        val buffer = ByteArray(64 * 1024)
        var encryptedBytes = 0L
        var plainBytes = 0L
        fun writePlain(plain: ByteArray?) {
            if (plain == null) return
            plainBytes += plain.size
            require(plainBytes <= BackupArchive.MAX_BYTES) { "Sauvegarde trop volumineuse." }
            output.write(plain)
        }
        while (true) {
            var count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                buffer[0] = value.toByte()
                count = 1
            }
            encryptedBytes += count
            require(encryptedBytes <= BackupArchive.MAX_BYTES + TAG_BYTES) { "Sauvegarde trop volumineuse." }
            writePlain(cipher.update(buffer, 0, count))
        }
        require(encryptedBytes >= TAG_BYTES) { "Sauvegarde tronquée." }
        // Call doFinal explicitly: CipherInputStream can hide authentication errors on close.
        writePlain(finishDecryption(cipher))
    }

    private fun finishDecryption(cipher: Cipher, encrypted: ByteArray? = null): ByteArray = try {
        if (encrypted == null) cipher.doFinal() else cipher.doFinal(encrypted)
    } catch (error: BadPaddingException) {
        throw IllegalArgumentException(
            "Impossible de déchiffrer cette sauvegarde. Elle est endommagée ou provient d’une autre installation d’Astra.",
            error
        )
    }

    private fun recordAad(header: ByteArray, index: Int, length: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + 8).put(header).putInt(index).putInt(length).array()

    private fun InputStream.readExactly(buffer: ByteArray, count: Int = buffer.size, offset: Int = 0) {
        var position = offset
        val end = offset + count
        while (position < end) {
            val read = read(buffer, position, end - position)
            if (read < 0) throw EOFException("Sauvegarde tronquée.")
            if (read == 0) {
                val value = read()
                if (value < 0) throw EOFException("Sauvegarde tronquée.")
                buffer[position++] = value.toByte()
            } else position += read
        }
    }

    companion object {
        internal const val CHUNK_BYTES = 1024 * 1024
        internal const val HEADER_BYTES = 32
        private const val IDENTITY_BYTES = 16
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private val MAGIC_V1 = byteArrayOf(65, 83, 84, 49)
        private val MAGIC_V2 = byteArrayOf(65, 83, 84, 50)
        private val ZIP_LOCAL_HEADER = byteArrayOf(80, 75, 3, 4)
        private val ZIP_EMPTY_HEADER = byteArrayOf(80, 75, 5, 6)
    }
}
