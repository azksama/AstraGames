package fr.astragames.wolf

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class WolfTextPictureTimingTest {
    private val utf8 = Charsets.UTF_8
    private fun compile(text: String, version: Int = 369, charset: Charset = utf8) = WolfTextPictureTiming.compile(text, charset, version)

    @Test fun utf8ByteCorrectionPreservesNativeHalfWidthAndFullWidthTiming() {
        val program = compile("Aé日😀")
        assertEquals(10, program.compiledByteLength)
        assertEquals(4, program.glyphCount)
        var state = WolfTextPictureTiming.initial(program, 30)
        val visible = (1..8).map {
            val result = WolfTextPictureTiming.advance(program, state, 30, 1)
            state = result.state; result.visibleGlyphCount
        }
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), visible)
    }

    @Test fun completionIsNotTheLastVisibleGlyphAndOnlyProgressIsTruncated() {
        val program = compile("ABCD")
        var state = WolfTextPictureTiming.initial(program, 240)
        val first = WolfTextPictureTiming.advance(program, state, 240, 1)
        state = first.state
        assertEquals(4, first.visibleGlyphCount)
        assertEquals(0, WolfTextPictureTiming.status(program, state, 1))
        state = WolfTextPictureTiming.advance(program, state, 240, 1).state
        assertEquals(1, WolfTextPictureTiming.status(program, state, 1))
        val fractional = state.copy(progress = 4.99, speed = 30.0, correction = 0)
        assertEquals(1, WolfTextPictureTiming.status(program, fractional, 1))
    }

    @Test fun leadingInstantMarkerBypassesRevealButAnOrdinaryCharacterDoesNot() {
        val plain = compile("ABC")
        assertEquals(0, WolfTextPictureTiming.layout(plain, WolfTextPictureTiming.initial(plain, 0), 0).visibleGlyphCount)
        val instant = compile("\\>ABC\\<D")
        val result = WolfTextPictureTiming.layout(instant, WolfTextPictureTiming.initial(instant, 0), 0)
        assertEquals(4, result.visibleGlyphCount)
        assertEquals(4.0, result.state.progress, 0.0)
    }

    @Test fun pausesAreSixteenNativeUnitsAndDoNotRearmWhenPrefixIsRescanned() {
        val program = compile("A\\.B")
        val first = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 240), 240, 1)
        assertEquals(1, first.visibleGlyphCount)
        assertEquals(16, first.state.waitTicks)
        val waiting = WolfTextPictureTiming.advance(program, first.state, 240, 2)
        assertEquals(14, waiting.state.waitTicks)
        assertEquals(first.state.progress, waiting.state.progress, 0.0)
        var state = waiting.state
        repeat(7) { state = WolfTextPictureTiming.advance(program, state, 240, 2).state }
        assertEquals(0, state.waitTicks)
        val completed = WolfTextPictureTiming.advance(program, state, 240, 2)
        assertEquals(2, completed.visibleGlyphCount)
        assertEquals(0, completed.state.waitTicks)
    }

    @Test fun speedAnchorSurvivesRescansAndUsesLiveSystemFallback() {
        val program = compile("A\\sp[0]BC")
        var state = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 30), 60, 1).state
        val next = WolfTextPictureTiming.advance(program, state, 60, 1)
        state = next.state
        assertEquals(60.0, state.speed, 0.0)
        assertEquals(mapOf(4 to 2.0), state.speedAnchors)
        val changed = WolfTextPictureTiming.advance(program, state, 75, 1)
        assertEquals(75.0, changed.state.speed, 0.0)
        assertEquals(state.speedAnchors, changed.state.speedAnchors)
    }

    @Test fun legacyParserDoesNotInventFirstMarkerExemptionOrSpeedAnchors() {
        val instant = compile("\\>ABC", 229)
        assertEquals(0, WolfTextPictureTiming.layout(instant, WolfTextPictureTiming.initial(instant, 0), 0).visibleGlyphCount)
        val program = compile("A\\sp[60]BC", 229)
        val first = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 30), 30, 1)
        val next = WolfTextPictureTiming.advance(program, first.state, 30, 1)
        assertEquals(60.0, next.state.speed, 0.0)
        assertEquals(7, next.state.correction)
        assertTrue(next.state.speedAnchors.isEmpty())
        assertFalse(WolfTextPictureTiming.validate(program, next.state.copy(speedAnchors = mapOf(4 to 2.0))))
    }

    @Test fun styleAndLineBreakBytesStayInTheCompletionContract() {
        val program = compile("<C>\\c[1]\\f[10]A\r\nB")
        assertEquals(20, program.compiledByteLength)
        assertEquals(2, program.glyphCount)
        val state = WolfTextPictureTiming.initial(program, 30)
        assertEquals(16, state.correction)
        val result = WolfTextPictureTiming.advance(program, state, 30, 1)
        assertEquals(1, result.visibleGlyphCount)
        assertEquals(16, result.state.correction)
    }

    @Test fun inputWaitRubyAndUnexpandedVariablesDoNotPretendToHaveKnownTiming() {
        for (text in listOf("A\\!B", "\\r[word,ruby]", "\\cself[1]", "\\se[sound.wav]"))
            assertThrows(WolfTextPictureUnsupported::class.java) { compile(text) }
        val literal = WolfTextPictureTiming.compile("\\!", utf8, 369, convertSpecial = false)
        assertEquals(2, literal.glyphCount)
        assertEquals(2, literal.compiledByteLength)
    }

    @Test fun immutableSnapshotValidationRejectsBadAnchorsWithoutDestroyingTheState() {
        val program = compile("A\\sp[10]B")
        val state = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 240), 240, 1).state
        assertTrue(WolfTextPictureTiming.validate(program, state))
        assertFalse(WolfTextPictureTiming.validate(program, state.copy(progress = Double.NaN)))
        assertFalse(WolfTextPictureTiming.validate(program, state.copy(waitTicks = 17)))
        assertFalse(WolfTextPictureTiming.validate(program, state.copy(speedAnchors = mapOf(99 to 2.0))))
        assertEquals(8.0, state.progress, 0.0)
    }

    @Test fun independentNativeByteParserOracleMatchesEveryClockAndPrefix() {
        val lines = javaClass.getResourceAsStream("/text-picture/native.tsv")!!.bufferedReader().readLines().filterNot { it.startsWith('#') || it.isBlank() }
        assertEquals(4608, lines.size)
        val states = mutableMapOf<Int, WolfTextPictureTiming.State>()
        val programs = mutableMapOf<Int, WolfTextPictureTiming.Program>()
        for (line in lines) {
            val p = line.split('\t')
            val id = p[0].toInt(); val frame = p[1].toInt(); val version = p[2].toInt(); val step = p[3].toInt()
            val initial = p[4].toInt(); val fallback = p[5].toInt()
            val text = String(p[6].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), utf8)
            val charset = Charset.forName(p[7])
            val program = programs.getOrPut(id) { compile(text, version, charset) }
            val previous = if (frame == 0) WolfTextPictureTiming.initial(program, initial) else states.getValue(id)
            val layout = WolfTextPictureTiming.advance(program, previous, fallback, step)
            states[id] = layout.state
            assertEquals("$id/$frame byte length", p[8].toInt(), program.compiledByteLength)
            assertEquals("$id/$frame visible", p[9].toInt(), layout.visibleGlyphCount)
            assertEquals("$id/$frame status", p[10].toInt(), WolfTextPictureTiming.status(program, layout.state, step))
            fun bits(index: Int) = java.lang.Long.parseUnsignedLong(p[index], 16)
            assertEquals("$id/$frame speed", bits(11), layout.state.speed.toBits())
            assertEquals("$id/$frame progress", bits(12), layout.state.progress.toBits())
            assertEquals("$id/$frame previous", bits(13), layout.state.previous.toBits())
            assertEquals("$id/$frame correction", p[14].toInt(), layout.state.correction)
            assertEquals("$id/$frame wait", p[15].toInt(), layout.state.waitTicks)
            assertEquals("$id/$frame checkpoint", p[16].toInt(), layout.state.pauseCheckpoint)
            assertEquals("$id/$frame autoclose", p[17] == "1", layout.state.autoClose)
        }
    }
}
