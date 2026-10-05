package fr.astragames.app.core.filesystem

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedCopyTest {
    @Test fun exactLimitAndEmptyInputAreAccepted() {
        val output = ByteArrayOutputStream()
        assertEquals(3L, byteArrayOf(1, 2, 3).inputStream().copyBoundedTo(output, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(0L, byteArrayOf().inputStream().copyBoundedTo(output, 0))
    }

    @Test fun oversizedInputFailsBeforeWritingTheOverflowingChunk() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            ByteArray(16_384).inputStream().copyBoundedTo(output, 9_000)
        }
        assertTrue(output.size() <= 9_000)
    }

    @Test fun nonProgressingBulkReadCannotLoopForever() {
        val input = object : InputStream() {
            var position = 0
            override fun read() = if (position++ == 0) 42 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        val output = ByteArrayOutputStream()
        assertEquals(1L, input.copyBoundedTo(output, 1))
        assertArrayEquals(byteArrayOf(42), output.toByteArray())
    }
}
