package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfStringMeasurementsTest {
    @Test fun countsUtf8BytesRatherThanUtf16CharactersOrDisplayWidth() {
        assertEquals(5, WolfStringMeasurements.byteCount("ABCDE"))
        assertEquals(11, WolfStringMeasurements.byteCount("Aé日😀\n"))
        assertEquals(1, WolfStringMeasurements.byteCount("\\\\"))
        assertEquals(5, WolfStringMeasurements.byteCount("\\\\v[0]"))
        assertEquals(1, WolfStringMeasurements.byteCount("A\u0000B"))
    }

    @Test fun refusesUnqualifiedByteCanonicalizationInsteadOfInventingRawCounts() {
        for (source in listOf("\\c[0003]A", "\\r[漢字,かんじ]", "\\i[1]", "\\!", "\\v[0]")) {
            assertThrows(IllegalArgumentException::class.java) { WolfStringMeasurements.byteCount(source) }
        }
        assertEquals(8, WolfStringMeasurements.byteCount("\\c[0003]", convertSpecial = false))
        assertEquals(2, WolfStringMeasurements.byteCount("\\\\", convertSpecial = false))
    }

    @Test fun reproducesTheShippedUtf8WidthClassifierIncludingItsNarrowException() {
        assertEquals(10, WolfStringMeasurements.widthCount("あああAAAA"))
        assertEquals(6, WolfStringMeasurements.widthCount("é\u0301😀"))
        assertEquals(1, WolfStringMeasurements.widthCount("ｦ")) // FF66
        assertEquals(1, WolfStringMeasurements.widthCount("\uffdf"))
        assertEquals(2, WolfStringMeasurements.widthCount("｡")) // FF61, outside shipped exception
        assertEquals(2, WolfStringMeasurements.widthCount("\uffe0"))
        assertEquals(0, WolfStringMeasurements.widthCount("\r\n"))
        assertEquals(1, WolfStringMeasurements.widthCount("A\u0000B"))
    }

    @Test fun countsOnlyVisibleRubyBaseAndLiteralBackslashesAcrossFormatting() {
        assertEquals(7, WolfStringMeasurements.widthCount("<C>\\c[2]A\\r[漢字,かんじ]\\i[1]\\img[Picture/a.png]\\imgS[Picture/b.png]\\\\B\r\n"))
        assertEquals(0, WolfStringMeasurements.widthCount("\\font[22]\\space[10]\\sp[120]\\!\\E\\N"))
        assertEquals(5, WolfStringMeasurements.widthCount("\\\\v[0]"))
        assertEquals(5, WolfStringMeasurements.widthCount("\\c[1]", convertSpecial = false))
        assertEquals(2, WolfStringMeasurements.widthCount("\\\\", convertSpecial = false))
    }

    @Test fun rejectsUnresolvedVariablesAndUnknownFormattingBeforeMeasuring() {
        assertThrows(IllegalStateException::class.java) { WolfStringMeasurements.widthCount("\\cdb[13:16:3]") }
        assertThrows(IllegalStateException::class.java) { WolfStringMeasurements.widthCount("\\unknown[1]") }
        assertThrows(IllegalArgumentException::class.java) { WolfStringMeasurements.widthCount("a".repeat(1_000_001)) }
        assertThrows(IllegalArgumentException::class.java) { WolfStringMeasurements.byteCount("a".repeat(1_000_001)) }
    }

    @Test fun cutsInclusiveLfLinesAndClampsMissingOrReversedBoundaries() {
        assertEquals("ABC", WolfStringMeasurements.cutLines("ABC\nDEF\nGHI", 0, 0))
        assertEquals("DEF\nGHI", WolfStringMeasurements.cutLines("ABC\nDEF\nGHI", 1, 2))
        assertEquals("DEF", WolfStringMeasurements.cutLines("ABC\nDEF\nGHI", 1, -1))
        assertEquals("ABC", WolfStringMeasurements.cutLines("ABC\nDEF", -5, -1))
        assertEquals("ABC\nDEF", WolfStringMeasurements.cutLines("ABC\nDEF", 0, Int.MAX_VALUE))
        assertEquals("", WolfStringMeasurements.cutLines("ABC\nDEF", Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals("", WolfStringMeasurements.cutLines("ABC\n", 1, 1))
        assertEquals("", WolfStringMeasurements.cutLines("", 0, 0))
        assertEquals("日\r\n😀", WolfStringMeasurements.cutLines("ABC\n日\r\n😀\nDEF", 1, 2))
        assertEquals("\\c[1]A", WolfStringMeasurements.cutLines("\\c[1]A\nB", 0, 0))
    }
}
