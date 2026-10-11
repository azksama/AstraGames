package fr.astragames.app.windows

import com.winlator.xserver.XKeycode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WolfControlLayoutTest {
    private val customId = "custom_" + "a".repeat(32)
    private fun source() = JSONObject().put("customControls", JSONArray().put(JSONObject().put("id", customId).put("label", "Menu F8").put("code", "KEY_F8")))
        .put("key_left", "KEY_A").put("position_false_$customId", JSONArray(listOf(.2, .7)))
        .put("position_true_$customId", JSONArray(listOf(.8, .3))).put("opacity", 31).put("size", 125)

    @Test fun loadingPresetReplacesOnlyControlsAndPreservesEngineStorageAndGraphics() {
        val destination = JSONObject().put("runtime", "WINLATOR").put("storageMode", "AUTO").put("resolution", "1920x1080")
            .put("smooth", false).put("key_accept", "KEY_Z").put("position_false_accept", JSONArray(listOf(.1, .2)))
        WolfControlLayout.apply(destination, source())
        assertEquals("WINLATOR", destination.getString("runtime")); assertFalse(destination.getBoolean("smooth"))
        assertEquals("1920x1080", destination.getString("resolution")); assertEquals("AUTO", destination.getString("storageMode"))
        assertFalse(destination.has("key_accept")); assertFalse(destination.has("position_false_accept"))
        assertEquals(.8, destination.getJSONArray("position_true_$customId").getDouble(0), .0001)
        assertEquals(31, destination.getInt("opacity")); assertEquals(125, destination.getInt("size"))
        assertEquals(XKeycode.KEY_F8, WolfControlLayout.custom(destination).single().code)
    }
    @Test fun invalidIdsKeysAndDuplicateRowsNeverBecomeControls() {
        val data = source()
        val rows = data.getJSONArray("customControls")
        rows.put(rows.getJSONObject(0)).put(JSONObject().put("id", "../../outside").put("label", "bad").put("code", "KEY_A"))
            .put(JSONObject().put("id", "custom_" + "b".repeat(32)).put("label", "bad").put("code", "KEY_CUSTOM_1"))
        data.put("key_back", "KEY_MAX").put("key_unknown", "KEY_A")
        val result = WolfControlLayout.normalized(data)
        assertEquals(1, WolfControlLayout.custom(result).size)
        assertFalse(result.has("key_back")); assertFalse(result.has("key_unknown"))
    }
    @Test fun positionsNumbersAndLabelsAreBoundedAndInvalidPositionIsAbsent() {
        val data = source().put("size", 999).put("opacity", -9)
            .put("position_false_up", JSONArray(listOf(-20, 99)))
            .put("position_true_up", JSONArray().put("NaN").put(1))
        val result = WolfControlLayout.normalized(data)
        assertEquals(150, result.getInt("size")); assertEquals(15, result.getInt("opacity"))
        assertEquals(0.0, result.getJSONArray("position_false_up").getDouble(0), 0.0)
        assertEquals(1.0, result.getJSONArray("position_false_up").getDouble(1), 0.0)
        assertFalse(result.has("position_true_up"))
        assertEquals("abcd", WolfControlLayout.label("\n ab\tcd \u0000"))
    }
    @Test fun unknownVersionCannotPartiallyReplaceTheCurrentLayout() {
        val data = JSONObject().put("key_left", "KEY_A")
        try { WolfControlLayout.apply(data, JSONObject().put("version", 2)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("KEY_A", data.getString("key_left"))
    }
    @Test fun legacyRemapsAndBothOrientationsSurviveWithoutCustomButtons() {
        val legacy = JSONObject().put("key_accept", "KEY_Z").put("position_false_accept", JSONArray(listOf(.4, .9)))
            .put("position_true_accept", JSONArray(listOf(.9, .2)))
        val preset = WolfControlLayout.normalized(legacy)
        assertEquals("KEY_Z", preset.getString("key_accept"))
        assertEquals(.9, preset.getJSONArray("position_true_accept").getDouble(0), 0.0)
        assertTrue(WolfControlLayout.custom(preset).isEmpty())
    }
    @Test fun everyOfferedKeyboardKeyHasAUniqueNativeScanAndExtendedKeysUseDirectInput() {
        assertTrue(WolfKeyboard.keys.size > 95)
        assertTrue(WolfKeyboard.keys.all { WolfKeyboard.scan(it) in 1..255 })
        assertEquals(WolfKeyboard.keys.size, WolfKeyboard.keys.map(WolfKeyboard::scan).toSet().size)
        assertEquals(66, WolfKeyboard.scan(XKeycode.KEY_F8)); assertEquals(156, WolfKeyboard.scan(XKeycode.KEY_KP_ENTER))
        assertEquals(157, WolfKeyboard.scan(XKeycode.KEY_CTRL_R)); assertEquals(203, WolfKeyboard.scan(XKeycode.KEY_LEFT))
        assertNull(WolfKeyboard.scan(XKeycode.KEY_CUSTOM_1)); assertNull(WolfKeyboard.parse("KEY_NONE"))
    }
}
