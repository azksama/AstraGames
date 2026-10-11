package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfSystemSupportTest {
    @Test fun systemTextIdsCannotAliasACharacterOrOverflowTheStringNamespace() {
        assertEquals(9_000_112, WolfTextCodes.systemReference(112, false))
        assertEquals(9_900_068, WolfTextCodes.systemReference(68, true))
        for (id in listOf(-1, 100_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { WolfTextCodes.systemReference(id, false) }
            assertThrows(IllegalArgumentException::class.java) { WolfTextCodes.systemReference(id, true) }
        }
    }
    @Test fun parkMillerMatchesIndependentKnownVectorsAndRejection() {
        var value = 1
        val expected = listOf(16807, 282475249, 1622650073, 984943658, 1144108930)
        expected.forEach { next -> value = wolfRandomNext(value); assertEquals(next, value) }
        // Successive modular products: 2147480811, 2099818995, 2059077814,
        // then 221848493. The first three exceed Wolf's accepted bound.
        assertEquals(221848493, wolfRandomNext(127773))
    }

    @Test fun measuredFpsAndMillisecondsUseClockSamplesRatherThanTargetRate() {
        var nanos = 0L
        val state = WolfSystemTelemetry { nanos }
        assertEquals(0, state.internalFps); assertEquals(0, state.renderFps)
        repeat(15) { nanos += 66_666_667; state.tick() }
        assertEquals(14, state.internalFps)
        nanos += 9_000_000; assertEquals(509, state.millis(500, true)); assertEquals(500, state.millis(500, false))
        state.rendered(27); assertEquals(27, state.renderFps)
        state.focus(false); assertFalse(state.active); assertEquals(0, state.internalFps)
        nanos += 5_000_000_000; assertEquals(1999, state.millis(0, true))
        state.controller("Example pad", ""); assertEquals("UNKNOWN", state.controllerKind)
        state.controller("", "UNKNOWN"); assertEquals("", state.controllerKind)
    }

    @Test fun unsupportedAndReadOnlyFieldsCannotBeAdmittedByStoredData() {
        assertTrue(WolfSystemSupport.numericWrites.all { it in WolfSystemSupport.numericReads })
        assertTrue(WolfSystemSupport.stringWrites.all { it in WolfSystemSupport.stringReads })
        assertTrue(WolfSystemSupport.unsupportedNumbers.keys.none { it in WolfSystemSupport.numericReads })
        assertTrue(WolfSystemSupport.unsupportedStrings.keys.none { it in WolfSystemSupport.stringReads })
        val vars = WolfVariables(emptyMap()) { _, _ -> null }
        val address = EventAddress(0)
        vars.systemStrings[57] = "injected clipboard"
        assertThrows(IllegalArgumentException::class.java) { vars.readString(9_900_057, address) }
        for (id in WolfSystemSupport.numericReads - WolfSystemSupport.numericWrites)
            assertThrows("read-only Sys$id", IllegalArgumentException::class.java) { vars.write(9_000_000 + id, 0, address) }
        for (id in WolfSystemSupport.stringReads - WolfSystemSupport.stringWrites)
            assertThrows("read-only SysS$id", IllegalArgumentException::class.java) { vars.writeString(9_900_000 + id, "x", address) }
    }
}
