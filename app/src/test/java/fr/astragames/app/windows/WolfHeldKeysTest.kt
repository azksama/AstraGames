package fr.astragames.app.windows

import com.winlator.xserver.XKeycode
import org.junit.Assert.*
import org.junit.Test

class WolfHeldKeysTest {
    @Test fun overlappingCombosHoldSharedKeysUntilTheirLastOwnerReleases() {
        val events = mutableListOf<Pair<XKeycode, Boolean>>()
        val input = WolfHeldKeys { key, down -> events += key to down }
        input.set("first", listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_Z))
        input.set("second", listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_ENTER))
        input.release("first")
        assertEquals(listOf(XKeycode.KEY_CTRL_L to true, XKeycode.KEY_Z to true, XKeycode.KEY_ENTER to true, XKeycode.KEY_Z to false), events)
        input.release("second")
        assertEquals(listOf(XKeycode.KEY_ENTER to false, XKeycode.KEY_CTRL_L to false), events.takeLast(2))
    }
    @Test fun joystickGroupReconciliationDoesNotFlickerCommonKeysOrReleaseAnAction() {
        val events = mutableListOf<Pair<XKeycode, Boolean>>()
        val input = WolfHeldKeys { key, down -> events += key to down }
        input.set("accept", listOf(XKeycode.KEY_Z))
        input.replaceGroup("joystick_", mapOf("joystick_left" to listOf(XKeycode.KEY_Z, XKeycode.KEY_LEFT)))
        input.replaceGroup("joystick_", mapOf("joystick_right" to listOf(XKeycode.KEY_Z, XKeycode.KEY_RIGHT)))
        input.replaceGroup("joystick_", emptyMap())
        assertEquals(listOf(XKeycode.KEY_Z to true, XKeycode.KEY_LEFT to true, XKeycode.KEY_LEFT to false, XKeycode.KEY_RIGHT to true, XKeycode.KEY_RIGHT to false), events)
        input.releaseAll(); assertEquals(XKeycode.KEY_Z to false, events.last())
    }
    @Test fun repeatedOwnersAndRepeatedReleaseAllDoNotDuplicateNativeTransitions() {
        val events = mutableListOf<Pair<XKeycode, Boolean>>()
        val input = WolfHeldKeys { key, down -> events += key to down }
        input.set("latch", listOf(XKeycode.KEY_SHIFT_L)); input.set("latch", listOf(XKeycode.KEY_SHIFT_L))
        input.set("touch", listOf(XKeycode.KEY_SHIFT_L)); input.release("latch")
        assertFalse(input.active("latch")); assertTrue(input.active("touch"))
        input.releaseAll(); input.releaseAll(); input.release("touch")
        assertEquals(listOf(XKeycode.KEY_SHIFT_L to true, XKeycode.KEY_SHIFT_L to false), events)
    }
}
