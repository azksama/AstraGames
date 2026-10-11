package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfCharacterEffectsTest {
    @Test fun nativeW3UpdaterVectorsMatchItsDistinctInfiniteShakeAndRestore() {
        val effects = WolfCharacterEffects(WolfCharacterEffectProfile.WOLF3)
        val restored = WolfCharacterEffects(WolfCharacterEffectProfile.WOLF3)
        val base = NativeCharacter(42, "actor.png", 20f, 30f, 16f, 16f)
        var scenario = ""
        var count = 0
        val vectors = javaClass.getResourceAsStream("/character-effects/w3-native.tsv") ?: error("Missing W3 updater reference")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val a = line.split('\t')
            when (a[0]) {
                "case" -> { scenario = a[1]; effects.reset() }
                "set" -> effects.apply(42, a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt())
                "tick" -> { effects.tick(); restored.tick() }
                "sample" -> {
                    val label = "$scenario frame ${a[1]}"
                    // W3 draw009c4a70 independently confirms the same quantized colour equation as W2.
                    assertEquals("$label native words", a.drop(7).map(String::toInt), effects.state().single().words)
                    assertEquals("$label RGB", NativeTone(a[4].toInt(), a[5].toInt(), a[6].toInt()), effects.character(base).tone)
                    restored.restore(effects.state())
                    effects.tick(); restored.tick()
                    assertEquals("$label restored next phase", effects.state(), restored.state())
                    effects.restore(listOf(WolfCharacterEffectState(42, a.drop(7).map(String::toInt))))
                    count++
                }
                else -> error("Unknown reference directive ${a[0]}")
            }
        } }
        assertTrue("All W3 updater phases must be exercised", count > 2950)
    }

    @Test fun nativeX87DrawAndUpdaterVectorsMatchEveryPhaseIncludingTrailingOffset() {
        val effects = WolfCharacterEffects()
        val restored = WolfCharacterEffects()
        val base = NativeCharacter(42, "actor.png", 20f, 30f, 16f, 16f)
        var scenario = ""
        var count = 0
        val vectors = javaClass.getResourceAsStream("/character-effects/w2-native.tsv") ?: error("Missing native character reference")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val a = line.split('\t')
            when (a[0]) {
                "case" -> { scenario = a[1]; effects.reset() }
                "set" -> effects.apply(42, a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt())
                "tick" -> effects.tick()
                "sample" -> {
                    val label = "$scenario frame ${a[1]}"
                    val frame = effects.character(base)
                    assertEquals("$label x", a[2].toFloat(), frame.x, 0f)
                    assertEquals("$label y", a[3].toFloat(), frame.y, 0f)
                    assertEquals("$label RGB", NativeTone(a[4].toInt(), a[5].toInt(), a[6].toInt()), frame.tone)
                    assertEquals("$label native words", a.drop(7).map(String::toInt), effects.state().single().words)
                    restored.restore(effects.state())
                    assertEquals("$label restored frame", frame, restored.character(base))
                    count++
                }
                else -> error("Unknown reference directive ${a[0]}")
            }
        } }
        assertTrue("All native character phases must be exercised", count > 2900)
    }

    @Test fun interruptedEffectsAndTheirGroundOffsetsSurviveSnapshotWithoutChangingActorState() {
        val effects = WolfCharacterEffects()
        val copy = WolfCharacterEffects()
        val base = NativeCharacter(-2, "hero.png", 20f, 30f, 16f, 24f, heightOffset = 9f, tone = NativeTone(7, 11, 13))
        effects.apply(-2, 0, -90, 17, 80, 20)
        effects.apply(-2, 1, 13, -7, 999999, 5)
        effects.apply(-2, 3, 27, -73, 50, 7)
        repeat(8) { effects.tick() }
        copy.restore(effects.state())
        repeat(60) {
            assertEquals(effects.character(base), copy.character(base))
            assertEquals(effects.state(), copy.state())
            effects.tick(); copy.tick()
        }
        val frame = effects.character(base)
        assertEquals(base.heightOffset, frame.heightOffset)
        assertEquals(base.width, frame.width)
        assertEquals(base.height, frame.height)
        assertEquals(20f, base.x, 0f)
        effects.erase(-2)
        assertSame(base, effects.character(base))
    }

    @Test fun unknownModesAndMalformedSnapshotsAreRejectedAtomically() {
        val effects = WolfCharacterEffects()
        effects.apply(3, 1, 12, 9, 3, 7)
        effects.apply(3, 2, 50, 60, 70, 9)
        val initial = effects.state()
        for (mode in listOf(-1, 4, 7, 8, 12, 50, 255)) {
            try { effects.apply(3, mode, 0, 0, 0, 4); fail("Accepted unproved mode $mode") }
            catch (_: IllegalArgumentException) { assertEquals(initial, effects.state()) }
        }
        for ((index, invalid) in listOf(0 to -1, 1 to -1, 2 to 1, 5 to -1, 6 to 0, 7 to Int.MAX_VALUE,
            8 to Int.MIN_VALUE, 10 to 0xffffff, 11 to Int.MAX_VALUE, 12 to 18, 13 to 2)) {
            val words = initial.single().words.toMutableList().apply { this[index] = invalid }
            try { effects.restore(listOf(WolfCharacterEffectState(3, words))); fail("Accepted corrupt word $index") }
            catch (_: IllegalArgumentException) { assertEquals(initial, effects.state()) }
        }
        for (invalid in listOf(initial + initial, listOf(WolfCharacterEffectState(3, listOf(0))))) {
            try { effects.restore(invalid); fail("Accepted malformed snapshot") }
            catch (_: IllegalArgumentException) { assertEquals(initial, effects.state()) }
        }
    }
}
