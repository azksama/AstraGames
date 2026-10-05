package fr.astragames.app.translation

import org.junit.Assert.*
import org.junit.Test

class ProtectedTextTest {
    @Test fun nestedEngineAndUnknownPluginArgumentsStayOpaque() {
        val text = "Hello \\C[\\V[1]]world\\C[0] \\Custom_Name[Name[2], key](other, nested(7)){\"text\":\"unchanged\"} done"
        val expected = listOf("\\C[\\V[1]]", "\\C[0]", "\\Custom_Name[Name[2], key](other, nested(7)){\"text\":\"unchanged\"}")
        assertEquals(expected, ProtectedText.controls(text))
        assertEquals(listOf("Hello", "world", "done"), ProtectedText.fragments(text))
        val translated = ProtectedText.render(text, mapOf("Hello" to "Bonjour", "world" to "monde", "done" to "fini"))
        assertEquals(expected, ProtectedText.controls(translated))
        assertTrue(translated.contains("unchanged"))
    }

    @Test fun escapedBackslashCurrencyAndUnicodeTextRemainSeparate() {
        val text = "\\\\Hello \\Gゴールド \\N[1]こんにちは \\!Wait\\."
        assertEquals(listOf("\\\\", "\\G", "\\N[1]", "\\!", "\\."), ProtectedText.controls(text))
        assertEquals(listOf("Hello", "ゴールド", "こんにちは", "Wait"), ProtectedText.fragments(text))
    }

    @Test fun malformedPluginExpressionsCannotBePartlyTranslated() {
        assertEquals(listOf("Hello"), ProtectedText.fragments("Hello \\custom[argument, nested[1] missing"))
        val text = "Hello \\custom(\"quoted ) closing\", [1, 2]) world"
        assertEquals(listOf("Hello", "world"), ProtectedText.fragments(text))
        assertEquals(text, ProtectedText.render(text, emptyMap()))
    }

    @Test fun longParagraphsAreTranslatedInBoundedChunksWithoutDroppingWhitespace() {
        val text = "Hello ".repeat(1800) + "\uD83D\uDE80"
        val parts = ProtectedText.fragments(text)
        assertTrue(parts.size > 1); assertTrue(parts.all { it.length <= 3500 })
        assertEquals(text, ProtectedText.render(text, parts.associateWith { it }))
        assertEquals(text.replace("Hello", "Bonjour"), ProtectedText.render(text, parts.associateWith { it.replace("Hello", "Bonjour") }))
    }

    @Test fun maskRoundTripAndTokenBoundaryInjectionsAreValidated() {
        val text = "Hello \\N[1]\r\nworld %1"
        val masked = ProtectedText.mask(text)
        assertEquals(text, ProtectedText.unmask(text, masked.source))
        assertEquals("Bonjour \\N[1]\r\nmonde %1", ProtectedText.unmask(text, masked.source.replace("Hello", "Bonjour").replace("world", "monde")))
        assertThrows(IllegalArgumentException::class.java) { ProtectedText.unmask(text, masked.source + "<script>") }
        val boundary = ProtectedText.mask("\\G gold")
        assertThrows(IllegalArgumentException::class.java) { ProtectedText.unmask("\\G gold", boundary.source.replace(" gold", "old")) }
    }

    @Test fun lineSeparatorsAndLiteralPlaceholderTextRemainIntact() {
        val text = "Hello\u2028world\u2029test\u0085end ⟦ASTRA_literal_0⟧"
        assertEquals(listOf("\u2028", "\u2029", "\u0085"), ProtectedText.controls(text))
        val masked = ProtectedText.mask(text)
        assertEquals(text.replace("Hello", "Bonjour"), ProtectedText.unmask(text, masked.source.replace("Hello", "Bonjour")))
    }

    @Test fun standardPageBreakIsOpaqueInAutomaticAndManualTranslation() {
        // Window_Base.processCharacter treats U+000C as a new page, distinct from a new line.
        val original = "Page one\u000cPage two"
        assertEquals(listOf("\u000c"), ProtectedText.controls(original))
        assertEquals(listOf("Page one", "Page two"), ProtectedText.fragments(original))
        assertEquals("Première page\u000cDeuxième page", ProtectedText.render(original,
            mapOf("Page one" to "Première page", "Page two" to "Deuxième page")))
        val masked = ProtectedText.mask(original)
        assertEquals("Première page\u000cPage two", ProtectedText.unmask(original, masked.source.replace("Page one", "Première page")))
        assertThrows(IllegalArgumentException::class.java) { ProtectedText.unmask(original, "Première page Deuxième page") }
    }

    @Test fun allDocumentedMvMzControlsRemainExact() {
        val controls = listOf("\\V[1]", "\\N[2]", "\\P[3]", "\\G", "\\C[4]", "\\I[5]", "\\{", "\\}", "\\\\",
            "\\$", "\\.", "\\|", "\\!", "\\>", "\\<", "\\^", "\\PX[6]", "\\PY[7]", "\\FS[8]", "%1", "%2")
        val original = controls.joinToString(" text ") + " text"
        assertEquals(controls, ProtectedText.controls(original))
        assertEquals(original.replace("text", "texte"), ProtectedText.render(original, mapOf("text" to "texte")))
        val masked = ProtectedText.mask(original)
        assertEquals(original.replace("text", "texte"), ProtectedText.unmask(original, masked.source.replace("text", "texte")))
    }
}
