package fr.astragames.app.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WolfCheatProtocolTest {
    @Test fun signedAndFractionalValuesRoundTripWithoutDoublePrecisionLoss() {
        listOf(WolfCheatType.INT8 to "-128", WolfCheatType.INT16 to "-32768", WolfCheatType.INT32 to "-2147483648",
            WolfCheatType.INT64 to "9223372036854775807", WolfCheatType.FLOAT to "-123.5", WolfCheatType.DOUBLE to "456.25").forEach { (type, text) ->
            assertEquals(text, type.format(type.parse(text)))
        }
        assertEquals("1.5", WolfCheatType.FLOAT.format(WolfCheatType.FLOAT.parse("1,5")))
    }
    @Test fun overflowAndNonFiniteInputAreRejected() {
        listOf(WolfCheatType.INT8 to "128", WolfCheatType.INT16 to "32768", WolfCheatType.INT32 to "2147483648",
            WolfCheatType.INT64 to "9223372036854775808", WolfCheatType.FLOAT to "NaN", WolfCheatType.DOUBLE to "Infinity").forEach { (type,text) ->
            assertTrue(runCatching { type.parse(text) }.isFailure)
        }
    }
    @Test fun responsesAreBoundToRequestAndLengthChecked() {
        val bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(WolfCheatProtocol.MAGIC);putInt(7);putInt(0);putInt(2);putLong(1);putLong(4096)
            putInt(0);putInt(0);putInt(1);putInt(0);putLong(0x1234);putLong(42)
        }.array()
        assertEquals(WolfCheatRow(0x1234,42), WolfCheatProtocol.response(bytes,7).rows.single())
        assertTrue(runCatching { WolfCheatProtocol.response(bytes,8) }.isFailure)
        assertTrue(runCatching { WolfCheatProtocol.response(bytes.copyOf(63),7) }.isFailure)
        assertTrue(runCatching { WolfCheatProtocol.response(bytes.copyOf(65),7) }.isFailure)
    }
    @Test fun mutationUsesExpectedPreviousValueAndPreserves64BitBits() {
        val b=ByteBuffer.wrap(WolfCheatProtocol.request(1,4,WolfCheatType.INT64,address=0x100000001,value=Long.MAX_VALUE,other=Long.MIN_VALUE)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(56,b.capacity());assertEquals(0x100000001,b.getLong(24));assertEquals(Long.MAX_VALUE,b.getLong(32));assertEquals(Long.MIN_VALUE,b.getLong(40))
    }
}
