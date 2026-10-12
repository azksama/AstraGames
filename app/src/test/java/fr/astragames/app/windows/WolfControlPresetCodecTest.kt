package fr.astragames.app.windows

import com.winlator.xserver.XKeycode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WolfControlPresetCodecTest {
    private val customId = "custom_" + "d".repeat(32)
    private fun layout() = JSONObject().put("version", WolfControlLayout.VERSION).put("movementMode", "JOYSTICK")
        .put("customControls", JSONArray().put(JSONObject().put("id", customId).put("label", "Potion").put("code", "KEY_F8")))
        .put("size_joystick", 145).put("opacity_accept", 45)
        .put("position_true_$customId", JSONArray(listOf(.8, .2)))
        .put("position_false_$customId", JSONArray(listOf(.2, .8)))
        .put("action_shift", WolfControlAction.encode(WolfControlAction(listOf(XKeycode.KEY_SHIFT_L, XKeycode.KEY_Z), WolfControlActionMode.TOGGLE)))
        .put("action_$customId", WolfControlAction.encode(WolfControlAction(listOf(XKeycode.KEY_F8), WolfControlActionMode.MACRO,
            listOf(WolfMacroStep(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_F8), 120), WolfMacroStep(emptyList(), 50), WolfMacroStep(listOf(XKeycode.KEY_ENTER), 100)))))
    private fun document(value: JSONObject = layout()) = JSONObject().put("format", WolfControlPresetCodec.FORMAT)
        .put("version", WolfControlPresetCodec.VERSION).put("name", "Exploration").put("layout", value)
    private fun rejected(value: String) {
        try { WolfControlPresetCodec.decode(value); fail("Invalid preset accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exportImportPreservesBothOrientationsCombosTogglesAndBoundedSequences() {
        val imported = WolfControlPresetCodec.decode(WolfControlPresetCodec.encode("Exploration", layout()))
        assertEquals("Exploration", imported.name)
        assertEquals(WolfControlLayout.VERSION, imported.layout.getInt("version"))
        assertEquals(WolfMovementMode.JOYSTICK, WolfControlLayout.movement(imported.layout))
        assertEquals(.8, imported.layout.getJSONArray("position_true_$customId").getDouble(0), .001)
        assertEquals(.8, imported.layout.getJSONArray("position_false_$customId").getDouble(1), .001)
        val shift = WolfControlLayout.action(imported.layout, "shift", XKeycode.KEY_SHIFT_L)
        assertEquals(WolfControlActionMode.TOGGLE, shift.mode)
        assertEquals(listOf(XKeycode.KEY_SHIFT_L, XKeycode.KEY_Z), shift.keys)
        val sequence = WolfControlLayout.action(imported.layout, customId, XKeycode.KEY_F8)
        assertEquals(WolfControlActionMode.MACRO, sequence.mode); assertEquals(3, sequence.steps.size)
        assertTrue(sequence.steps[1].keys.isEmpty()); assertEquals(50, sequence.steps[1].durationMs)
    }
    @Test fun legacyLayoutsMigrateAndExactNumericPhysicalKeysBecomeSymbolic() {
        for (version in 1..2) {
            val old = JSONObject().put("version", version).put("key_accept", 52).put("position_false_accept", JSONArray(listOf(.5, .7)))
            val migrated = WolfControlPresetCodec.decode(document(old).toString()).layout
            assertEquals(WolfControlLayout.VERSION, migrated.getInt("version")); assertEquals("KEY_Z", migrated.getString("key_accept"))
            assertEquals(WolfControlActionMode.HOLD, WolfControlLayout.action(migrated, "accept", XKeycode.KEY_ENTER).mode)
        }
        val action = JSONObject().put("mode", "HOLD").put("keys", JSONArray(listOf(37, 36)))
        assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_ENTER), WolfControlAction.decode(action).keys)
        listOf(0, 255, -1, 52.5, "52", "KEY_NONE", "KEY_CUSTOM_1").forEach { assertNull(WolfKeyboard.parseValue(it)) }
    }
    @Test fun malformedPositionsSizesOpacityAndKeyboardNumbersAreRejectedRatherThanClamped() {
        val invalid = listOf(
            "position_true_accept" to JSONArray(listOf("NaN", .5)),
            "position_false_accept" to JSONArray(listOf(1.1, .5)),
            "position_false_accept" to JSONArray(listOf(.5, .5, .5)),
            "opacity" to 0, "opacity_accept" to "75", "size" to 151, "size_accept" to 49,
            "key_accept" to 52.5, "key_accept" to "KEY_MAX", "key_joystick" to "KEY_A",
        )
        invalid.forEach { (key, value) -> rejected(document(layout().put(key, value)).toString()) }
        rejected(document().toString().replace("0.8", "1e99999"))
    }
    @Test fun duplicateUnknownAndOversizedControlsNeverEnterAImportedLayout() {
        val duplicate = layout(); duplicate.getJSONArray("customControls").put(duplicate.getJSONArray("customControls").getJSONObject(0))
        rejected(document(duplicate).toString())
        rejected(document(layout().put("size_unknown", 100)).toString())
        rejected(document(layout().put("position_true_../../path", JSONArray(listOf(.1, .1)))).toString())
        val many = JSONArray()
        repeat(25) { index -> many.put(JSONObject().put("id", "custom_" + index.toString(16).padStart(32, '0')).put("label", "B$index").put("code", "KEY_A")) }
        rejected(document(layout().put("customControls", many)).toString())
    }
    @Test fun scriptsGamePathsRuntimeChangesAndFutureVersionsAreRejected() {
        listOf("runtime", "storageMode", "fontMode", "script", "gamePath").forEach { field -> rejected(document(layout().put(field, "value")).toString()) }
        rejected(document().put("version", 2).toString()); rejected(document(layout().put("version", 4)).toString())
        rejected(document().put("name", "Bad\nname").toString()); rejected(document().put("name", "A".repeat(49)).toString())
        rejected(document().toString() + "{}"); rejected(document().put("format", "other").toString())
    }
    @Test fun combosAndMacrosAreValidatedWithFiniteIntegerBudgets() {
        fun action(keys: List<Any>, mode: String = "HOLD", steps: JSONArray? = null) = JSONObject().put("keys", JSONArray(keys)).put("mode", mode).apply { steps?.let { put("steps", it) } }
        val invalid = listOf(action(emptyList()), action(listOf("KEY_A", "KEY_A")), action(listOf("KEY_A", "KEY_B", "KEY_C", "KEY_D", "KEY_E")),
            action(listOf(52.5)), action(listOf("KEY_A"), "OTHER"), action(listOf("KEY_A"), "MACRO", JSONArray()),
            action(listOf("KEY_A"), "TOGGLE", JSONArray().put(JSONObject().put("keys", JSONArray()).put("durationMs", 30))))
        invalid.forEach { rejected(document(layout().put("action_accept", it)).toString()) }
        val steps = JSONArray(); repeat(6) { steps.put(JSONObject().put("keys", JSONArray(listOf("KEY_A"))).put("durationMs", 2000)) }
        rejected(document(layout().put("action_accept", action(listOf("KEY_A"), "MACRO", steps))).toString())
        val fractional = JSONArray().put(JSONObject().put("keys", JSONArray(listOf("KEY_A"))).put("durationMs", 120.5))
        rejected(document(layout().put("action_accept", action(listOf("KEY_A"), "MACRO", fractional))).toString())
    }
    @Test fun byteAndDepthLimitsApplyBeforeJsonParsing() {
        rejected("{" + " ".repeat(WolfControlPresetCodec.MAX_BYTES) + "}")
        rejected("{\"name\":\"" + "漢".repeat(WolfControlPresetCodec.MAX_BYTES / 2) + "\"}")
        rejected("[".repeat(1000) + "0" + "]".repeat(1000))
    }
}
