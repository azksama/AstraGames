package fr.astragames.app.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationJsonTest {
    @Test fun rejectsLenientJsonAndDuplicateKeysIncludingEscapedAliases() {
        listOf("{a:1}", "{'a':1}", "{\"a\":1,}", "[1,]", "[01]", "[NaN]", "[true]tail", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "[\"bad\\z\"]", "[\"raw\nline\"]").forEach { input ->
            assertThrows("Must reject $input", IllegalArgumentException::class.java) { TranslationJson.parse(input.toByteArray(), 4096) }
        }
    }

    @Test fun rejectsMalformedUtf8ExcessiveNestingAndOversizedInput() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { TranslationJson.parse(byteArrayOf(0xC3.toByte(), 0x28), 4096) }
        assertThrows(IllegalArgumentException::class.java) { TranslationJson.parse(("[".repeat(66) + "0" + "]".repeat(66)).toByteArray(), 4096) }
        assertThrows(IllegalArgumentException::class.java) { TranslationJson.parse("[1]".toByteArray(), 2) }
    }

    @Test fun validJsonAcceptsUtf8BomNumbersAndEscapes() {
        val value = TranslationJson.parse("\uFEFF{\"a\": [true, false, null, -1.2e+3], \"b\": \"é\\n\\u00e9\"}".toByteArray(), 4096) as org.json.JSONObject
        assertEquals("é\né", value.getString("b"))
        assertEquals(-1200.0, value.getJSONArray("a").getDouble(3), 0.01)
    }
}
