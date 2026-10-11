package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfSaveDataTest {
    @Test fun partialVariablesRoundTripWithNoInventedWorldState() {
        val data = WolfSaveData(numbers = mapOf(2_000_003 to -91, 9_000_024 to 1), strings = mapOf(3_000_007 to "日本語 · sauvegarde"))
        val loaded = WolfSaveData.decode(data.encode())
        assertNull(loaded.snapshot)
        assertEquals(data.numbers, loaded.numbers); assertEquals(data.strings, loaded.strings)
    }
    @Test fun fullStateAndIndependentWritesRemainSeparate() {
        val state = WolfSnapshot.State("fixture", 10, 0, 1f, 1f, 2, "", 123,
            mapOf(0 to 7), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val original = WolfSnapshot.encode(state)
        val data = WolfSaveData(original, mapOf(2_000_000 to 11), mapOf(3_000_000 to "new"))
        val loaded = WolfSaveData.decode(data.encode())
        assertArrayEquals(original, loaded.snapshot)
        assertEquals(7, WolfSnapshot.decode(loaded.snapshot!!, "fixture").numbers[0])
        assertEquals(11, loaded.numbers[2_000_000])
        val legacy = WolfSaveData.decode(original)
        assertArrayEquals(original, legacy.snapshot); assertTrue(legacy.numbers.isEmpty()); assertTrue(legacy.strings.isEmpty())
    }
    @Test fun mutableDatabaseOverridesKeepTheirEncodedRowAndFieldAddresses() {
        val original = WolfSnapshot.encode(WolfSnapshot.State("fixture", 10, 0, 1f, 1f, 2, "", 123,
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        val data = WolfSaveData(original, mapOf(1_106_000_001 to 19, 1_106_000_701 to -5), mapOf(1_106_000_702 to "sauvegarde"))
        val loaded = WolfSaveData.decode(data.encode())
        assertArrayEquals(original, loaded.snapshot); assertEquals(data.numbers, loaded.numbers); assertEquals(data.strings, loaded.strings)
    }
    @Test fun corruptTruncatedAndOversizedFilesFailBeforeAllocation() {
        val valid = WolfSaveData(numbers = mapOf(2_000_000 to 1)).encode()
        for (length in valid.indices) rejects { WolfSaveData.decode(valid.copyOf(length)) }
        rejects { WolfSaveData.decode(valid.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }) }
        rejects { WolfSaveData.decode(valid + 0) }
        rejects { WolfSaveData(strings = mapOf(3_000_000 to "a".repeat(1_000_001))).encode() }
        rejects { WolfSaveData(snapshot = ByteArray(16 * 1024 * 1024)).encode() }
        val malformedText = WolfSaveData(strings = mapOf(3_000_000 to "a")).encode()
        malformedText[malformedText.lastIndex - 4] = 0x80.toByte()
        val crc = java.util.zip.CRC32().apply { update(malformedText, 4, malformedText.size - 8) }.value.toInt()
        java.nio.ByteBuffer.wrap(malformedText, malformedText.size - 4, 4).putInt(crc)
        rejects { WolfSaveData.decode(malformedText) }
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid native save accepted") } catch (_: IllegalArgumentException) {} }
}
