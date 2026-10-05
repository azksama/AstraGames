package fr.astragames.app.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class BackupEnvelopeTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun encryptionCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    private fun decryptionCipher(iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    }
    private fun envelope() = BackupEnvelope(::encryptionCipher, ::decryptionCipher)
    private fun encode(plain: ByteArray): ByteArray = ByteArrayOutputStream().also {
        envelope().write(plain.inputStream(), plain.size.toLong(), it)
    }.toByteArray()
    private fun decode(encrypted: ByteArray): ByteArray = ByteArrayOutputStream().also {
        envelope().read(encrypted.inputStream(), it)
    }.toByteArray()
    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + it / 53).toByte() }
    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Invalid backup was accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: java.io.EOFException) {
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test fun realAeadRoundTripsEmptyExactAndPartialChunks() {
        listOf(0, 1, BackupEnvelope.CHUNK_BYTES, BackupEnvelope.CHUNK_BYTES + 7, BackupEnvelope.CHUNK_BYTES * 2 + 123).forEach { size ->
            val plain = bytes(size)
            val encrypted = encode(plain)
            assertArrayEquals(plain, decode(encrypted))
            val records = maxOf(1, (size + BackupEnvelope.CHUNK_BYTES - 1) / BackupEnvelope.CHUNK_BYTES)
            assertEquals(BackupEnvelope.HEADER_BYTES + size + records * 28, encrypted.size)
        }
    }

    @Test fun independentReaderVerifiesAst2WireLayoutAndAad() {
        val plain = bytes(BackupEnvelope.CHUNK_BYTES + 17)
        val encoded = encode(plain)
        val header = encoded.copyOfRange(0, 32)
        val reader = ByteBuffer.wrap(encoded)
        assertEquals(0x41535432, reader.int) // ASCII AST2, big endian.
        assertEquals(1_048_576, reader.int)
        assertEquals(plain.size.toLong(), reader.long)
        reader.position(32)
        val recovered = ByteArrayOutputStream()
        var offset = 0
        var index = 0
        while (offset < plain.size) {
            val length = minOf(1_048_576, plain.size - offset)
            val iv = ByteArray(12).also(reader::get)
            val ciphertext = ByteArray(length + 16).also(reader::get)
            val cipher = decryptionCipher(iv)
            cipher.updateAAD(ByteBuffer.allocate(40).put(header).putInt(index).putInt(length).array())
            recovered.write(cipher.doFinal(ciphertext))
            offset += length
            index++
        }
        assertFalse(reader.hasRemaining())
        assertArrayEquals(plain, recovered.toByteArray())
    }

    @Test fun oldAst1AndUnencryptedZipRemainReadable() {
        val plain = bytes(98_765)
        val cipher = encryptionCipher()
        val old = "AST1".toByteArray(Charsets.US_ASCII) + cipher.iv + cipher.doFinal(plain)
        assertArrayEquals(plain, decode(old))
        rejects { decode(old.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
        val zip = byteArrayOf(80, 75, 3, 4) + bytes(200)
        assertArrayEquals(zip, decode(zip))
        val emptyZip = byteArrayOf(80, 75, 5, 6) + ByteArray(18)
        assertArrayEquals(emptyZip, decode(emptyZip))
        rejects { decode("AST3".toByteArray()) }
    }

    @Test fun headerNonceCiphertextAndTagTamperingIsRejected() {
        val original = encode(bytes(BackupEnvelope.CHUNK_BYTES + 17))
        // Version, chunk size, total length, archive identity, first IV, body, final tag.
        listOf(3, 7, 15, 16, 32, 44, original.lastIndex).forEach { offset ->
            rejects { decode(original.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }) }
        }
        val empty = encode(ByteArray(0))
        rejects { decode(empty.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
    }

    @Test fun truncationIncludingWholeRecordsAndTrailingBytesAreRejected() {
        val encoded = encode(bytes(BackupEnvelope.CHUNK_BYTES + 17))
        val afterFirst = 32 + 12 + BackupEnvelope.CHUNK_BYTES + 16
        listOf(0, 3, 4, 31, 32, 43, 44, afterFirst, encoded.size - 1).forEach { length ->
            rejects { decode(encoded.copyOf(length)) }
        }
        rejects { decode(encoded + byteArrayOf(0)) }
        rejects { decode(encode(ByteArray(0)).copyOf(32)) }
    }

    @Test fun reorderedRepeatedAndCrossArchiveRecordsAreRejected() {
        val first = encode(bytes(BackupEnvelope.CHUNK_BYTES * 2))
        val second = encode(bytes(BackupEnvelope.CHUNK_BYTES * 2))
        val recordSize = BackupEnvelope.CHUNK_BYTES + 28
        val firstRecord = first.copyOfRange(32, 32 + recordSize)
        val secondRecord = first.copyOfRange(32 + recordSize, first.size)
        val header = first.copyOfRange(0, 32)
        rejects { decode(header + secondRecord + firstRecord) }
        rejects { decode(header + firstRecord + firstRecord) }
        rejects { decode(header + second.copyOfRange(32, second.size)) }
        rejects { decode(first + second) }
    }

    @Test fun sizesAreValidatedBeforeAllocationsOrReadingPayload() {
        val unreadable = object : InputStream() { override fun read(): Int = throw AssertionError("Payload must not be read") }
        rejects { envelope().write(unreadable, BackupArchive.MAX_BYTES + 1, ByteArrayOutputStream()) }
        rejects { envelope().write(unreadable, -1, ByteArrayOutputStream()) }
        val valid = encode(ByteArray(0))
        listOf(-1L, BackupArchive.MAX_BYTES + 1, Long.MAX_VALUE).forEach { length ->
            val invalid = valid.copyOf().also { ByteBuffer.wrap(it).putLong(8, length) }
            rejects { decode(invalid) }
        }
        rejects { decode(valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) }) }
        rejects { envelope().write(byteArrayOf(1, 2).inputStream(), 1, ByteArrayOutputStream()) }
        rejects { envelope().write(byteArrayOf(1).inputStream(), 2, ByteArrayOutputStream()) }
    }

    @Test fun partialAndNonProgressingReadsDoNotTruncateRecords() {
        fun shortReads(bytes: ByteArray): InputStream = object : ByteArrayInputStream(bytes) {
            private var zero = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                zero = !zero
                return if (zero) 0 else super.read(buffer, offset, minOf(length, 7))
            }
        }
        val plain = bytes(2_000)
        val encoded = ByteArrayOutputStream().also { envelope().write(shortReads(plain), plain.size.toLong(), it) }.toByteArray()
        val recovered = ByteArrayOutputStream().also { envelope().read(shortReads(encoded), it) }.toByteArray()
        assertArrayEquals(plain, recovered)
    }

    @Test fun verificationChecksAuthenticatedContentsLengthAndDigest() {
        val plain = bytes(BackupEnvelope.CHUNK_BYTES + 37)
        val encoded = encode(plain)
        val hash = MessageDigest.getInstance("SHA-256").digest(plain)
        envelope().verify(encoded.inputStream(), plain.size.toLong(), hash)
        rejects { envelope().verify(encoded.inputStream(), plain.size - 1L, hash) }
        rejects { envelope().verify(encoded.inputStream(), plain.size.toLong(), ByteArray(32)) }
        rejects { envelope().verify(encoded.copyOf(encoded.size - 1).inputStream(), plain.size.toLong(), hash) }
        val altered = encode(plain.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        rejects { envelope().verify(altered.inputStream(), plain.size.toLong(), hash) }
        val zip = byteArrayOf(80, 75, 3, 4)
        rejects { envelope().verify(zip.inputStream(), zip.size.toLong(), MessageDigest.getInstance("SHA-256").digest(zip)) }
    }

    @Test fun authenticationFailureHasActionableMessageAndPreservesCause() {
        val corrupt = encode(ByteArray(0)).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val failure = assertThrows(IllegalArgumentException::class.java) { decode(corrupt) }
        assertTrue(failure.message.orEmpty().contains("autre installation d’Astra"))
        assertTrue(failure.cause is javax.crypto.BadPaddingException)
        val ioFailure = java.io.IOException("provider disconnected")
        val input = object : InputStream() { override fun read(): Int = throw ioFailure }
        assertSame(ioFailure, assertThrows(java.io.IOException::class.java) { envelope().read(input, ByteArrayOutputStream()) })
    }

    @Test fun generatedLargeInputStreamsThroughFilesInBoundedReads() {
        val length = BackupEnvelope.CHUNK_BYTES * 3L + 93
        val expected = MessageDigest.getInstance("SHA-256")
        var largestPlainRead = 0
        val generated = object : InputStream() {
            var position = 0L
            override fun read(): Int = if (position == length) -1 else ((position++ * 31) and 255).toInt().also { expected.update(it.toByte()) }
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                largestPlainRead = maxOf(largestPlainRead, size)
                val count = minOf(size.toLong(), length - position).toInt()
                if (count == 0) return -1
                repeat(count) { buffer[offset + it] = ((position++ * 31) and 255).toByte() }
                expected.update(buffer, offset, count)
                return count
            }
        }
        val file = Files.createTempFile("astra-envelope", ".astra").toFile()
        try {
            file.outputStream().use { envelope().write(generated, length, it) }
            assertTrue(largestPlainRead <= BackupEnvelope.CHUNK_BYTES)
            val actual = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val digestOutput = object : java.io.OutputStream() {
                override fun write(value: Int) { actual.update(value.toByte()); total++ }
                override fun write(buffer: ByteArray, offset: Int, size: Int) {
                    assertTrue(size <= BackupEnvelope.CHUNK_BYTES)
                    actual.update(buffer, offset, size)
                    total += size
                }
            }
            file.inputStream().use { envelope().read(it, digestOutput) }
            assertEquals(length, total)
            assertArrayEquals(expected.digest(), actual.digest())
        } finally {
            file.delete()
        }
    }
}
