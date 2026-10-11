package fr.astragames.wolf

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WolfCharacterMotionTest {
    @Test fun quarterPixelStepsMatch448IndependentNativeIntegerVectors() {
        val root = File(System.getProperty("wolf.projectRoot", ".."))
        val vectors = File(root, "wolf-native/src/test/resources/motion/native.tsv").readLines().drop(1)
        assertEquals(448, vectors.size)
        val settings = IntArray(15) { it }
        vectors.forEach { line ->
            val fields = line.split('\t'); val name = fields[0]
            val v = fields.drop(1).map(String::toInt)
            val increment = WolfCharacterMotion.increment(settings, v[0], v[1] != 0, v[2])
            assertEquals(name, v[12], increment)
            val result = WolfCharacterMotion.step(v[4], v[5], v[6], v[7], increment, v[3] != 0, v[8], v[9])
            assertEquals(name, v[13], result.xQuarter); assertEquals(name, v[14], result.yQuarter)
            assertEquals(name, v[15] != 0, result.arrived); assertEquals(name, v[16] != 0, result.moved)
            assertEquals(name, v[17] != 0, WolfCharacterMotion.autonomousReady(WolfCharacterMotion.State(v[10]), v[11]))
        }
    }

    @Test fun gameSettingsAreSignedShortsAndNormalAndPlayerTablesStayDistinct() {
        val words = IntArray(15).also { it[7] = 65_534; it[8] = 9 }
        assertEquals(-2, WolfCharacterMotion.increment(words, 3, true, 1))
        assertEquals(18, WolfCharacterMotion.increment(words, 3, false, 2))
        assertFalse(WolfCharacterMotion.step(1, 2, 4, 2, 0, modern = false).moved)
        try { WolfCharacterMotion.increment(intArrayOf(), 3, true, 1); fail("Missing Game.dat speed table") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun autonomousFrequencyUsesTheStrictNativeComparisonAndSignedOverflow() {
        val s = WolfCharacterMotion.State(frequency = 3, autonomousMode = 1)
        assertFalse(WolfCharacterMotion.autonomousReady(s, 180))
        assertTrue(WolfCharacterMotion.autonomousReady(s, 181))
        assertEquals(WolfCharacterMotion.autonomousReady(s, 181),
            WolfCharacterMotion.autonomousReady(s.copy(frequency = -3), 181))
        assertTrue(WolfCharacterMotion.autonomousReady(s.copy(frequency = Short.MIN_VALUE.toInt()), 1))
        try { WolfCharacterMotion.validate(s.copy(autonomousMode = 4)); fail("Unknown autonomous mode") }
        catch (_: IllegalArgumentException) { }
    }
}
