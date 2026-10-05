package fr.astragames.app.translation

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TranslationIoTest {
    private fun zeroBatchStream(bytes: ByteArray) = object : InputStream() {
        private val source = ByteArrayInputStream(bytes)
        override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        override fun read() = source.read()
    }

    @Test fun zeroLengthProviderReadsStillProgressIncludingZeroBytesAndEof() {
        val bytes = byteArrayOf(0, 42, -1, 5)
        assertArrayEquals(bytes, readTranslationBytes(zeroBatchStream(bytes), bytes.size))
        assertArrayEquals(byteArrayOf(), readTranslationBytes(zeroBatchStream(byteArrayOf()), 0))
    }

    @Test fun enforcesLimitForNormalAndZeroLengthBatchReads() {
        val bytes = byteArrayOf(1, 2, 3)
        assertThrows(IllegalArgumentException::class.java) { readTranslationBytes(ByteArrayInputStream(bytes), 2) }
        assertThrows(IllegalArgumentException::class.java) { readTranslationBytes(zeroBatchStream(bytes), 2) }
        assertArrayEquals(bytes, readTranslationBytes(ByteArrayInputStream(bytes), 3))
    }
}
