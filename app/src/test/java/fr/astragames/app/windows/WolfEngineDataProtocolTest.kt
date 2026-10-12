package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WolfEngineDataProtocolTest {
    @Test fun requestBindsLogicalIdTokenAndBoundedUtf8Values() {
        val bytes = WolfEngineDataProtocol.request(7, 14, token = 123, variable = 3_000_009, before = "星", after = "Astra")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(WolfCheatProtocol.MAGIC, b.int)
        assertEquals(7, b.int); assertEquals(14, b.int)
        assertEquals(3_000_009L, b.getLong(24))
        assertEquals(WolfEngineDataProtocol.MAGIC, b.getInt(56))
        assertEquals(123L, b.getLong(64))
        assertEquals(-1, b.getInt(76))
        assertEquals(3, b.getInt(84)); assertEquals(5, b.getInt(88))
        assertEquals("星Astra", String(bytes.copyOfRange(96, bytes.size), Charsets.UTF_8))
    }

    @Test fun cannotSubmitOversizeNulInvalidUnicodeOrScannerAddresses() {
        assertFails { WolfEngineDataProtocol.request(1, 14, after = "😀".repeat(1025)) }
        assertFails { WolfEngineDataProtocol.request(1, 12, query = "a\u0000b") }
        assertFails { WolfEngineDataProtocol.request(1, 12, query = "\uD800") }
        assertFails { WolfEngineDataProtocol.request(1, 10) }
        assertFails { WolfEngineDataProtocol.request(1, 12, bank = -1) }
        assertFails { WolfEngineDataProtocol.request(1, 13, variable = -1) }
    }

    @Test fun decodesSignedNumbersTextGroupsAndOfficialBuildMetadata() {
        val response = WolfEngineDataProtocol.response(frame(listOf(
            row(2_100_123, 1, 1, 123, 0, 4, "-2147483648"),
            row(3_000_006, 2, 0, 6, 1, 15, "星"),
            row(15_000_410, 3, 4, 10, 0, 4, "2147483647"),
            row(15_000_405, 4, 4, 5, 1, 63, "texte")
        )), 11).checked()
        assertEquals("Wolf 3.729", (response.probe as EngineDataProbe.Supported).build)
        assertEquals(EngineDataValue.Number(Int.MIN_VALUE), response.variables[0].value)
        assertEquals(EngineDataValue.Text("星"), response.variables[1].value)
        assertEquals(EngineDataCategory.COMMON_NUMBERS, response.variables[2].category)
        assertEquals(EngineDataCategory.COMMON_TEXT, response.variables[3].category)
    }

    @Test fun unknownLayoutIsExplicitAndNeverReturnsEditableRows() {
        val response = WolfEngineDataProtocol.response(frame(emptyList(), status = 20, layout = 0), 11)
        assertTrue(response.probe is EngineDataProbe.Unavailable)
        assertTrue((response.probe as EngineDataProbe.Unavailable).reason.contains("scanner"))
        assertTrue(response.variables.isEmpty())
        assertFails { response.checked() }
        assertFails { WolfEngineDataProtocol.response(frame(listOf(row()), status = 20), 11) }
    }

    @Test fun rejectsOtherSessionVersionTruncationBadUtf8AndMismatchedLogicalCategory() {
        val valid = frame(listOf(row()))
        assertFails { WolfEngineDataProtocol.response(valid, 12) }
        assertFails { WolfEngineDataProtocol.response(valid.copyOf(valid.size - 1), 11) }
        assertFails { WolfEngineDataProtocol.response(valid + byteArrayOf(0), 11) }
        fun corrupt(offset: Int, value: Int) = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
        assertFails { WolfEngineDataProtocol.response(corrupt(12, 2), 11) }
        assertFails { WolfEngineDataProtocol.response(corrupt(24, 99), 11) }
        assertFails { WolfEngineDataProtocol.response(corrupt(104, 3_000_000), 11) }
        assertFails { WolfEngineDataProtocol.response(corrupt(140, 4), 11) }
        assertFails { WolfEngineDataProtocol.response(frame(listOf(row(value = "2147483648"))), 11) }
        val invalid = frame(listOf(row(id = 3_000_000, category = 2, type = 1, value = "a")))
        invalid[144] = 0xff.toByte()
        assertFails { WolfEngineDataProtocol.response(invalid, 11) }
        assertFails { WolfEngineDataProtocol.response(frame(listOf(row(), row())), 11) }
    }

    private fun row(id: Int = 2_000_000, category: Int = 1, bank: Int = 0, index: Int = 0,
                    type: Int = 0, capacity: Int = 4, value: String = "0"): ByteArray {
        val text = value.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(40 + text.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(id); putInt(category); putInt(bank); putInt(index); putInt(type); putInt(capacity)
            putLong(456); putInt(text.size); putInt(0); put(text)
        }.array()
    }
    private fun frame(rows: List<ByteArray>, status: Int = 0, layout: Int = 2): ByteArray =
        ByteBuffer.allocate(104 + rows.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(WolfEngineDataProtocol.MAGIC); putInt(11); putInt(status); putInt(1); putLong(123)
            putInt(layout); putInt(if (layout == 1) 932 else 65001); putInt(rows.size); putInt(rows.sumOf { it.size }); putLong(rows.size.toLong())
            putInt(2); putInt(1000); putInt(450); putInt(0); repeat(10) { putInt(if (it < 2) 5000 else 0) }; rows.forEach { put(it) }
        }.array()
    private fun assertFails(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
}
