package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WolfImageFiltersTest {
    @Test fun shaderFormulasMatchIndependentCppVectorsIncludingAlphaAndCumulativeOrder() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/image-filters/dxlib-shader-reference.tsv"))
        var cases = 0
        stream.bufferedReader().useLines { lines -> lines.filterNot { it.startsWith('#') || it.isBlank() }.forEach { line ->
            val fields = line.split('|')
            val filters = fields[2].split(';').map { text ->
                val v = text.split(',').map(String::toInt)
                NativeImageFilter(v[0], v[1], v[2], v[3], v[4])
            }
            fun pixels(field: String) = field.split(',').map { it.toLong(16).toInt() }.toIntArray()
            val expected = pixels(fields[4])
            val actual = WolfImageFilters.convert(fields[0].toInt(), fields[1].toInt(), pixels(fields[3]), filters)
            for (i in expected.indices) {
                val ea = expected[i] ushr 24; val aa = actual[i] ushr 24
                assertTrue("case $cases pixel $i alpha: $ea/$aa", abs(ea - aa) <= 1)
                // Compare the stored premultiplied channels. A one-byte
                // difference at alpha=1 has no meaningful straight-RGB bound.
                for (shift in intArrayOf(16, 8, 0)) {
                    val e = ((expected[i] ushr shift and 255) * ea + 127) / 255
                    val a = ((actual[i] ushr shift and 255) * aa + 127) / 255
                    assertTrue("case $cases $filters pixel $i channel $shift: $e/$a", abs(e-a) <= 1)
                }
            }
            cases++
        } }
        assertEquals(1463, cases)
    }

    @Test fun relativeHueUsesSignedWrappingAndAbsoluteHueLocksOnlyChromaticPixels() {
        val palette = intArrayOf(0xffff0000.toInt(), 0xff818181.toInt(), 0xff000000.toInt())
        val green = WolfImageFilters.convert(3, 1, palette, listOf(NativeImageFilter(21, 120)))
        assertEquals(0xff00ff00.toInt(), green[0])
        assertEquals(palette[1], green[1]); assertEquals(palette[2], green[2])
        assertArrayEquals(green, WolfImageFilters.convert(3, 1, palette, listOf(NativeImageFilter(21, 480))))
        val blue = WolfImageFilters.convert(3, 1, palette, listOf(NativeImageFilter(22, 240)))
        assertEquals(0xff0000ff.toInt(), blue[0]); assertEquals(palette[1], blue[1])
    }

    @Test fun positiveSaturationUsesDxLibFivefoldBoostAndLightnessInsteadOfHsvValue() {
        val pixel = intArrayOf(0xff997766.toInt())
        val desaturated = WolfImageFilters.convert(1, 1, pixel, listOf(NativeImageFilter(21, 0, -255)))[0]
        assertEquals(0xff808080.toInt(), desaturated)
        val boost = WolfImageFilters.convert(1, 1, pixel, listOf(NativeImageFilter(21, 0, 255)))[0]
        assertEquals(0xffff5500.toInt(), boost)
    }

    @Test fun inversionPreservesAlphaAndDoesNotLeakHiddenTransparentColors() {
        val actual = WolfImageFilters.convert(3, 1, intArrayOf(0x80ff0000.toInt(), 0x00ffff00, 0xff123456.toInt()), listOf(NativeImageFilter(24)))
        assertEquals(0x8000ffff.toInt(), actual[0]); assertEquals(0, actual[1])
        assertEquals(0xffedcba9.toInt(), actual[2])
    }

    @Test fun levelsClampReversedBoundsAndEqualInputBoundsUseShaderSaturateNanRule() {
        val actual = WolfImageFilters.convert(3, 1, intArrayOf(0xff000000.toInt(), 0xff808080.toInt(), 0xffffffff.toInt()), listOf(NativeImageFilter(26, 128, 128, 0, 255)))
        assertArrayEquals(intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt()), actual)
        val collapsed = WolfImageFilters.convert(1, 1, intArrayOf(-1), listOf(NativeImageFilter(26, 230, 20, 255, 60)))
        assertEquals(0xff3c3c3c.toInt(), collapsed[0])
    }

    @Test fun blurCrossesAtlasPatternsAndFiltersPremultipliedAlphaWithoutAHalo() {
        val input = IntArray(9) { if (it == 4) 0xffff0000.toInt() else 0x000000ff }
        val blurred = WolfImageFilters.convert(9, 1, input, listOf(NativeImageFilter(23, 1)))
        assertTrue(blurred[2] ushr 24 > 0); assertTrue(blurred[6] ushr 24 > 0)
        assertTrue(blurred[4] ushr 24 < 255)
        blurred.filter { it ushr 24 > 0 }.forEach { assertEquals(0x00ff0000, it and 0x00ffffff) }
        assertArrayEquals(input.map { if (it ushr 24 == 0) 0 else it }.toIntArray(), WolfImageFilters.convert(9, 1, input, listOf(NativeImageFilter(23, 0))))
    }

    @Test fun historyOrderIsDestructiveAndCopiesDoNotMutateTheOriginal() {
        val source = intArrayOf(0xffaa6633.toInt())
        val one = WolfImageFilters.convert(1, 1, source, listOf(NativeImageFilter(24), NativeImageFilter(21, 120, 64, 32)))
        val two = WolfImageFilters.convert(1, 1, source, listOf(NativeImageFilter(21, 120, 64, 32), NativeImageFilter(24)))
        assertFalse(one.contentEquals(two)); assertEquals(0xffaa6633.toInt(), source[0])
        val unmodified = WolfImageFilters.convert(1, 1, source, emptyList())
        unmodified[0] = 0; assertEquals(0xffaa6633.toInt(), source[0])
    }

    @Test fun malformedOperationsAndDimensionsFailBeforeTouchingTheSource() {
        val source = intArrayOf(0xff123456.toInt())
        for (action in listOf<() -> Unit>(
            { WolfImageFilters.convert(0, 1, source, emptyList()) },
            { WolfImageFilters.convert(1, 2, source, emptyList()) },
            { WolfImageFilters.convert(1, 1, source, listOf(NativeImageFilter(27))) },
        )) { try { action(); fail("invalid filter accepted") } catch (_: IllegalArgumentException) { } }
        assertEquals(0xff123456.toInt(), source[0])
    }
}
