package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfControlBindingsTest {
    @Test fun namesUseTheRemappedNativeScanOrPadMaskAndTheirVersionSpecificTables() {
        val system = linkedMapOf<Int, Int>()
        for (profile in listOf(225, 369)) {
            assertEquals(listOf("Z", "X", "C", "ボタン1", "ボタン2", "ボタン3"),
                (1..6).map { WolfControlBindings.name(system, it, profile) })
        }
        WolfControlBindings.write(system, 52, 142, 369)
        assertEquals("左Shift", WolfControlBindings.name(system, 1, 369))
        WolfControlBindings.write(system, 52, 355, 369)
        assertEquals("???", WolfControlBindings.name(system, 1, 369))
        system[55] = 0x10 or 0x20
        assertEquals("", WolfControlBindings.name(system, 4, 369))
        for (button in 301..320) {
            system[55] = WolfControlBindings.physicalButtonBits(button)
            assertEquals("ボタン${button - 300}", WolfControlBindings.name(system, 4, 369))
            assertEquals(if (button <= 315) "ボタン${button - 300}" else "", WolfControlBindings.name(system, 4, 225))
        }
    }
    @Test fun constructorCodesAndDuplicateRoleAssignmentsUseTheNativeSwap() {
        val system = linkedMapOf<Int, Int>()
        assertEquals(listOf(144, 145, 146, 301, 302, 303), (52..57).map { WolfControlBindings.read(system, it, 369) })
        WolfControlBindings.write(system, 52, 146, 369)
        assertEquals(listOf(146, 145, 144), (52..54).map { WolfControlBindings.read(system, it, 369) })
        WolfControlBindings.write(system, 53, 146, 369)
        assertEquals(listOf(145, 146, 144), (52..54).map { WolfControlBindings.read(system, it, 369) })
        WolfControlBindings.write(system, 57, 301, 369)
        assertEquals(listOf(303, 302, 301), (55..57).map { WolfControlBindings.read(system, it, 369) })
        assertEquals(listOf(0x40, 0x20, 0x10), (55..57).map { system[it] })
    }

    @Test fun invalidCodesLeaveEveryExistingRoleUnchanged() {
        val system = linkedMapOf(52 to 150, 53 to 151, 54 to 152, 55 to 0x100, 56 to 0x200, 57 to 0x400)
        val saved = system.toMap()
        listOf(Int.MIN_VALUE, 0, 99, 356, Int.MAX_VALUE).forEach { WolfControlBindings.write(system, 52, it, 369) }
        listOf(Int.MIN_VALUE, 0, 300, 321, Int.MAX_VALUE).forEach { WolfControlBindings.write(system, 55, it, 369) }
        assertEquals(saved, system)
    }

    @Test fun legacyControllerSetterAndGetterKeepTheirDistinctHistoricalTables() {
        val legacy = linkedMapOf<Int, Int>()
        WolfControlBindings.write(legacy, 55, 318, 225)
        assertEquals(0x400, legacy[55]); assertEquals(307, WolfControlBindings.read(legacy, 55, 225))
        WolfControlBindings.write(legacy, 55, 319, 225)
        assertEquals(310, WolfControlBindings.read(legacy, 55, 225))
        WolfControlBindings.write(legacy, 55, 320, 225)
        assertEquals(0x400000, legacy[55]); assertEquals(0, WolfControlBindings.read(legacy, 55, 225))
        WolfControlBindings.write(legacy, 56, 317, 225)
        assertEquals(0x100000, legacy[56]); assertEquals(0, WolfControlBindings.read(legacy, 56, 225))
        val modern = linkedMapOf<Int, Int>()
        for (code in 301..320) {
            WolfControlBindings.write(modern, 55, code, 369)
            assertEquals(code, WolfControlBindings.read(modern, 55, 369))
            assertEquals(WolfControlBindings.physicalButtonBits(code), modern[55])
        }
    }
}
