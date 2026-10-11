package fr.astragames.app.wolfnative

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WolfNativeMidiTest {
    @Test fun midiTicksRespectTempoChangesAndTransposeNotesWithoutEditingOriginalOrDrums() {
        val track = byteArrayOf(0, 0xff.toByte(), 0x51, 3, 7, 0xa1.toByte(), 0x20,
            0, 0x90.toByte(), 60, 64, 0, 0x99.toByte(), 60, 64,
            96, 0xff.toByte(), 0x51, 3, 3, 0xd0.toByte(), 0x90.toByte(),
            96, 0xff.toByte(), 0x2f, 0)
        val bytes = midi(96, track); val original = bytes.copyOf()
        val info = requireNotNull(WolfNativeMidi.parse(bytes))
        assertEquals(500, info.millis(96)); assertEquals(750, info.millis(192))
        assertEquals(192, info.lengthTicks)
        assertEquals(96, info.ticks(500)); assertEquals(192, info.ticks(750))
        for (time in listOf(0, 250, 501, 700, 999)) {
            val tick = info.ticks(time)
            assertTrue(info.millis(tick) <= time)
            assertTrue(info.millis(tick + 1) > time)
        }
        val raised = info.transpose(bytes, 12)
        assertEquals(72, raised[31].toInt()); assertEquals(60, raised[35].toInt())
        assertArrayEquals(original, bytes)
    }

    @Test fun smpteTimingIsIndependentOfTempoAndTruncatedEventsAreRejected() {
        val bytes = midi(0xe728, byteArrayOf(0, 0xff.toByte(), 0x2f, 0)) // 25 frames/sec,40ticks/frame.
        assertEquals(1000, requireNotNull(WolfNativeMidi.parse(bytes)).millis(1000))
        assertNull(WolfNativeMidi.parse("RIFF".toByteArray()))
        try { WolfNativeMidi.parse(midi(96, byteArrayOf(0, 0x90.toByte(), 60))); fail("Truncated note accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun midi(division: Int, track: ByteArray): ByteArray = ByteArrayOutputStream().run {
        fun be(value: Int, count: Int) { repeat(count) { write(value ushr ((count - 1 - it) * 8) and 255) } }
        write("MThd".toByteArray()); be(6, 4); be(0, 2); be(1, 2); be(division, 2)
        write("MTrk".toByteArray()); be(track.size, 4); write(track); toByteArray()
    }
}
