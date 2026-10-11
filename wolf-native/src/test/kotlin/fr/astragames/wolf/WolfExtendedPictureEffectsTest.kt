package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfExtendedPictureEffectsTest {
    @Test fun independentX86SseReferenceMatchesEndpointsPreTransformFlashAndRawWords() {
        val original = WolfExtendedPictureEffects()
        val restored = WolfExtendedPictureEffects()
        var base = NativePicture(42)
        var prior = NativeTone()
        var waveX = 0f
        var waveY = 0f
        var scenario = ""
        var samples = 0
        val vectors = javaClass.getResourceAsStream("/extended-picture-effects/w3-native.tsv") ?: error("Missing W3 SSE vectors")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val a = line.split('\t')
            when (a[0]) {
                "case" -> {
                    original.reset(); scenario = a[1]
                    base = NativePicture(42, x = a[2].toFloat(), y = a[3].toFloat(), scaleX = a[4].toFloat() / 100f,
                        scaleY = a[5].toFloat() / 100f, rotation = a[6].toInt() / 10f, opacity = a[7].toInt())
                    prior = NativeTone(a[8].toInt(), a[9].toInt(), a[10].toInt())
                    waveX = a[11].toFloat() / 100f; waveY = a[12].toFloat() / 100f
                }
                "set" -> original.apply(42, a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt())
                "tick" -> original.tick()
                "sample" -> {
                    val context = "$scenario frame ${a[1]}"
                    val endpoint = original.endpoints(base)
                    assertEquals("$context scaleX", a[2].toFloat().toRawBits(), endpoint.scaleX.toRawBits())
                    assertEquals("$context scaleY", a[3].toFloat().toRawBits(), endpoint.scaleY.toRawBits())
                    assertEquals("$context angle", a[4].toFloat().toRawBits(), endpoint.rotation.toRawBits())
                    assertEquals("$context alpha", a[5].toInt(), endpoint.opacity)
                    val waved = endpoint.copy(scaleX = endpoint.scaleX + waveX, scaleY = endpoint.scaleY + waveY)
                    val picture = original.preTransform(waved)
                    // CRT sin/cos and JVM sin/cos are independently implemented;
                    // compare at the NativePicture float-coordinate boundary.
                    val expectedX = a[6].toFloat(); val expectedY = a[7].toFloat()
                    assertEquals("$context x", expectedX, picture.x, maxOf(.00001f, Math.ulp(expectedX)))
                    assertEquals("$context y", expectedY, picture.y, maxOf(.00001f, Math.ulp(expectedY)))
                    assertEquals("$context flash", NativeTone(a[8].toInt(), a[9].toInt(), a[10].toInt()), original.flash(42, prior))
                    assertEquals("$context native words", a.drop(11).map(String::toInt), original.state().single().words)
                    restored.restore(original.state())
                    assertEquals("$context restored endpoint", endpoint, restored.endpoints(base))
                    assertEquals("$context restored preTransform", picture, restored.preTransform(waved))
                    assertEquals("$context restored flash", original.flash(42, prior), restored.flash(42, prior))
                    samples++
                }
                else -> error("Unknown vector directive ${a[0]}")
            }
        } }
        assertEquals(5_555, samples)
    }

    @Test fun endpointCorrectionsTruncateAfterAddingTheBaseAndDoNotOverwriteIt() {
        val effects = WolfExtendedPictureEffects()
        val base = NativePicture(5, rotation = .1f, opacity = 1)
        effects.apply(5, 13, -1, 0, 0, 3)
        effects.apply(5, 14, -1, 0, 0, 3)
        assertEquals(base, effects.endpoints(base)) // Native gates change on tick.
        effects.tick()
        val result = effects.endpoints(base)
        assertEquals(0f, result.rotation, 0f)
        assertEquals(0, result.opacity)
        assertEquals(.1f, base.rotation, 0f)
        assertEquals(1, base.opacity)
        val oppositeEndpoint = base.copy(rotation = 10.1f, opacity = 101)
        val other = effects.endpoints(oppositeEndpoint)
        assertEquals(10f, other.rotation, 0f)
        assertEquals(100, other.opacity)
    }

    @Test fun flashPreservesOddPeriodsAndNativeZeroHalfConversionBeforeTint() {
        val effects = WolfExtendedPictureEffects()
        effects.apply(9, 17, 100, 0, 0, 5)
        assertEquals(NativeTone(-63, 0, 0), effects.flash(9, NativeTone()))
        effects.tick(); assertEquals(NativeTone(), effects.flash(9, NativeTone()))
        effects.tick(); assertEquals(NativeTone(63, 0, 0), effects.flash(9, NativeTone()))
        effects.tick(); assertEquals(NativeTone(127, 0, 0), effects.flash(9, NativeTone()))
        // Truncate the combined accumulator: trunc(127.5 -127) is0.
        assertEquals(NativeTone(0, 17, -29), effects.flash(9, NativeTone(-127, 17, -29)))
        effects.apply(9, 17, 100, 0, 0, 1)
        assertEquals(NativeTone(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE), effects.flash(9, NativeTone()))
        effects.tick(); assertEquals(NativeTone(17, -23, 41), effects.flash(9, NativeTone(17, -23, 41)))
    }

    @Test fun commonAndExtendedCallbacksComposeWaveThenRotatedShiftThenZoomAndTranslation() {
        val common = WolfPictureEffects(WolfPictureEffectProfile.WOLF3)
        val extended = WolfExtendedPictureEffects()
        val base = NativePicture(7, x = 30f, y = 40f)
        extended.apply(7, 11, 10, 20, 0, 0)
        extended.apply(7, 12, 200, 100, 0, 0)
        extended.apply(7, 13, 900, 0, 0, 0)
        extended.apply(7, 14, -55, 0, 0, 0)
        common.apply(7, 2, 7, -9, 0, 0)
        common.apply(7, 4, 0, 0, 200, 0)
        common.apply(7, 7, 100, 0, 0, 4)
        repeat(2) { extended.tick(); common.tick() }
        val result = common.picture(extended.endpoints(base), extended::flash, extended::preTransform)
        assertEquals(27f, result.x, .00001f)
        assertEquals(121f, result.y, .00001f)
        assertEquals(5f, result.scaleX, 0f)
        assertEquals(2f, result.scaleY, 0f)
        assertEquals(90f, result.rotation, 0f)
        assertEquals(200, result.opacity)
        val noCommonState = WolfPictureEffects(WolfPictureEffectProfile.WOLF3)
        val onlyExtended = noCommonState.picture(extended.endpoints(base), extended::flash, extended::preTransform)
        assertEquals(10f, onlyExtended.x, .00001f)
        assertEquals(60f, onlyExtended.y, .00001f)
        assertTrue(noCommonState.state().isEmpty())
    }

    @Test fun restoreValidationIsAtomicAndStateDoesNotShareMutableWords() {
        val effects = WolfExtendedPictureEffects()
        effects.apply(11, 11, 27, -41, 0, 13); effects.tick()
        val before = effects.state()
        val detached = effects.state().single().words.toMutableList()
        detached[0] = 12345
        assertEquals(before, effects.state())
        val badGate = before.single().words.toMutableList().apply { this[23] = 2 }
        val badLeft = before.single().words.toMutableList().apply { this[5] = 14 }
        for (invalid in listOf(
            before + before,
            listOf(before.single().copy(words = before.single().words.dropLast(1))),
            listOf(before.single().copy(words = badGate)),
            listOf(before.single().copy(words = badLeft)),
        )) {
            try { effects.restore(invalid); fail("Invalid extended snapshot accepted") }
            catch (_: IllegalArgumentException) { assertEquals(before, effects.state()) }
        }
        for (operation in listOf(-1, 0, 10, 15, 16, 18, 21, 25, 28, 511)) {
            try { effects.apply(11, operation, 1, 2, 3, 4); fail("Unsupported operation accepted") }
            catch (_: IllegalArgumentException) { assertEquals(before, effects.state()) }
        }
        val untouched = NativePicture(99)
        assertSame(untouched, effects.endpoints(untouched)); assertSame(untouched, effects.preTransform(untouched))
        val prior = NativeTone(17, -23, 41)
        assertSame(prior, effects.flash(99, prior))
        effects.erase(11); assertTrue(effects.state().isEmpty())
    }
}
