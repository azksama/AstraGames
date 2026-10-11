package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfMapTexturesTest {
    @Test fun phaseAndFourClippedPartsMatch346IndependentNativeIntegerVectors() {
        checkNativeVectors("/wolf-map-textures-native.tsv", 3, 346)
    }

    @Test fun wolf2SignedPhaseMatches394IndependentNativeIntegerVectors() {
        checkNativeVectors("/wolf-map-textures-w2-native.tsv", 2, 394)
    }

    private fun checkNativeVectors(path: String, behaviorVersion: Int, count: Int) {
        val stream = requireNotNull(javaClass.getResourceAsStream(path))
        val rows = stream.bufferedReader().use { it.readLines() }.drop(1)
        assertEquals(count, rows.size)
        rows.forEach { row ->
            val fields = row.split('\t')
            val name = fields.first()
            val v = fields.drop(1).map(String::toInt)
            assertEquals(name, 59, v.size)
            val kind = if (v[1] == 0) WolfMapTextures.Kind.BACKGROUND else WolfMapTextures.Kind.FOG
            val state = WolfMapTextures.State(v[2] != 0, v[3], v[4], v[5], v[6], v[7], v[8], v[9])
            val parameters = WolfMapTextures.Parameters(v[10], v[11], v[12], v[13], v[14], v[15], v[16])
            val advanced = if (v[0] != 0) WolfMapTextures.advance(state, kind, parameters, behaviorVersion) else state
            assertEquals(name, v[21], advanced.accumulatedX)
            assertEquals(name, v[22], advanced.accumulatedY)
            assertEquals(name, v[23], advanced.phaseX)
            assertEquals(name, v[24], advanced.phaseY)
            val geometry = WolfMapTextures.layout(advanced, v[17], v[18], v[19], v[20], behaviorVersion)
            assertEquals(name, v[25], geometry.phaseX)
            assertEquals(name, v[26], geometry.phaseY)
            assertEquals(name, 4, geometry.rectangles.size)
            geometry.rectangles.forEachIndexed { index, r ->
                val actual = listOf(r.sourceLeft, r.sourceTop, r.sourceRight, r.sourceBottom,
                    r.destinationLeft, r.destinationTop, r.destinationRight, r.destinationBottom)
                assertEquals("$name rectangle $index", v.subList(27 + index * 8, 35 + index * 8), actual)
            }
        }
    }

    @Test fun signedPhaseRestoreIsOnlyAdmittedByWolf2Profile() {
        val negative = WolfMapTextures.reset(32, 24).copy(phaseX = -1, phaseY = -191)
        WolfMapTextures.validate(negative, 2)
        try { WolfMapTextures.validate(negative, 3); fail("Negative W3 phase accepted") }
        catch (_: IllegalArgumentException) { }
        try { WolfMapTextures.validate(negative, 1); fail("Unknown profile accepted") }
        catch (_: IllegalArgumentException) { }
        try { WolfMapTextures.validate(negative.copy(phaseX = -256), 2); fail("Impossible W2 remainder accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(-1, negative.phaseX)
    }

    @Test fun backgroundAndFogUseOppositeNativeFpsOperations() {
        val p = WolfMapTextures.Parameters(speedX = 3, speedY = -3, fpsDivisor = 2)
        val backdrop = WolfMapTextures.reload(32, 24, WolfMapTextures.Kind.BACKGROUND, p)
        val fog = WolfMapTextures.reload(32, 24, WolfMapTextures.Kind.FOG, p)
        assertEquals(6, backdrop.phaseX)
        assertEquals(186, backdrop.phaseY)
        assertEquals(1, fog.phaseX)
        assertEquals(191, fog.phaseY)
        assertEquals(0, WolfMapTextures.reset(32, 24).phaseX)
        assertEquals(WolfMapTextures.State(), WolfMapTextures.cold())
    }

    @Test fun aBitmapWrapsIntoItsOwnExtentWithoutFillingTheViewport() {
        val g = WolfMapTextures.layout(3, 2, 13, 9)
        assertEquals(WolfMapTextures.Rectangle(0, 0, 2, 1, 1, 1, 3, 2), g.rectangles[0])
        assertEquals(6, g.rectangles.sumOf {
            (it.sourceRight - it.sourceLeft) * (it.sourceBottom - it.sourceTop)
        })
        assertEquals(0, g.rectangles.minOf { it.destinationLeft })
        assertEquals(3, g.rectangles.maxOf { it.destinationRight })
        assertEquals(0, g.rectangles.minOf { it.destinationTop })
        assertEquals(2, g.rectangles.maxOf { it.destinationBottom })
    }

    @Test fun zoomNormalizationIsReturnedExplicitlyAndDoesNotAdvanceClock() {
        val state = WolfMapTextures.State(true, 1, 1, 29, 35, 4, 7)
        val normal = WolfMapTextures.layout(state)
        assertEquals(4, normal.phaseX)
        assertEquals(7, normal.phaseY)
        val zoomed = WolfMapTextures.layout(state, zoom = 151, originX = 1, originY = 1)
        assertEquals(8, zoomed.phaseX)
        assertEquals(8, zoomed.phaseY)
        assertEquals(29, state.accumulatedX)
        assertEquals(4, state.phaseX)
        WolfMapTextures.validate(state.copy(phaseX = zoomed.phaseX, phaseY = zoomed.phaseY))
    }

    @Test fun inactiveAndZeroDimensionsDoNotAdvanceOrDraw() {
        val state = WolfMapTextures.cold()
        assertEquals(state, WolfMapTextures.advance(state, WolfMapTextures.Kind.FOG,
            WolfMapTextures.Parameters(speedX = 999)))
        assertTrue(WolfMapTextures.layout(state).rectangles.isEmpty())
        val zero = WolfMapTextures.reset(0, 24)
        assertEquals(zero, WolfMapTextures.advance(zero, WolfMapTextures.Kind.BACKGROUND,
            WolfMapTextures.Parameters(speedY = 999)))
        assertTrue(WolfMapTextures.layout(zero).rectangles.isEmpty())
    }

    @Test fun restoreValidationAndNativeDivideOverflowPreserveThePriorImmutableState() {
        val good = WolfMapTextures.reload(32, 24, WolfMapTextures.Kind.FOG,
            WolfMapTextures.Parameters(speedX = 8))
        fun reject(block: () -> Unit) {
            try { block(); fail("Invalid native state was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        reject { WolfMapTextures.validate(good.copy(width = Int.MAX_VALUE / 8 + 1)) }
        reject { WolfMapTextures.validate(good.copy(phaseX = -1)) }
        reject { WolfMapTextures.validate(good.copy(phaseY = 24 * 8 + 1)) }
        reject { WolfMapTextures.advance(good, WolfMapTextures.Kind.FOG, WolfMapTextures.Parameters(fpsDivisor = 0)) }
        assertEquals(8, good.phaseX)
        assertEquals(32, good.width)
        try {
            WolfMapTextures.advance(good.copy(parallaxDivisor = -1), WolfMapTextures.Kind.FOG,
                WolfMapTextures.Parameters(cameraQuarterX = 1_073_741_824))
            fail("Unrepresentable native IDIV quotient was accepted")
        } catch (_: ArithmeticException) { }
        assertEquals(8, good.phaseX)
    }
}
