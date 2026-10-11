package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfPictureEffectsTest {
    @Test fun versionThreeLongShakesMatchIndependentNativeCountersAndRestore() {
        val engine = WolfPictureEffects(WolfPictureEffectProfile.WOLF3)
        val restored = WolfPictureEffects(WolfPictureEffectProfile.WOLF3)
        val base = NativePicture(42, x = 20f, y = 30f)
        var samples = 0
        val vectors = javaClass.getResourceAsStream("/picture-effects/w3-native-shake.tsv") ?: error("Native W3 shake vectors missing")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val a = line.split('\t').map(String::toInt)
            if (a[2] == 0) { engine.reset(); engine.apply(42, 3, 11, -7, a[0], a[1]) }
            val state = engine.state()
            assertEquals("Count ${a[0]} duration ${a[1]} frame ${a[2]}", a[3], state.single().words[17])
            assertEquals(a[4], state.single().words[19])
            assertEquals(base.x + a[5], engine.picture(base).x, 0f)
            assertEquals(base.y + a[6], engine.picture(base).y, 0f)
            restored.restore(state)
            assertEquals(engine.picture(base), restored.picture(base))
            assertEquals(engine.tick(), restored.tick())
            assertEquals(engine.state(), restored.state())
            samples++
        } }
        assertTrue(samples > 500)
    }

    @Test fun extendedEffectsComposeAtTheNativeInsertionPointsWithoutCreatingBasicState() {
        val engine = WolfPictureEffects()
        val base = NativePicture(42, x = 20f, y = 30f, tone = NativeTone(7, 8, 9))
        val callbackOnly = engine.picture(base,
            flashTransform = { id, prior -> assertEquals(42, id); assertEquals(NativeTone(), prior); NativeTone(11, 12, 13) },
            preTransform = { it.copy(x = it.x + 5, y = it.y - 3) })
        assertEquals(25f, callbackOnly.x, 0f)
        assertEquals(27f, callbackOnly.y, 0f)
        assertEquals(NativeTone(18, 20, 22), callbackOnly.tone)
        assertTrue(engine.state().isEmpty())

        engine.apply(42, 2, 7, -9, 0, 0)
        engine.apply(42, 4, 0, 0, 200, 0)
        engine.apply(42, 7, 50, 100, 0, 4)
        repeat(4) { engine.tick() }
        val composed = engine.picture(base, preTransform = {
            // At this point wave7 is already applied, while zoom4/shift2 are still pending.
            assertEquals(1.5f, it.scaleX, 0f); assertEquals(2f, it.scaleY, 0f)
            assertEquals(20f, it.x, 0f); it.copy(x = it.x + 5)
        })
        assertEquals(57f, composed.x, 0f)
        assertEquals(3f, composed.scaleX, 0f)
        assertEquals(4f, composed.scaleY, 0f)
    }

    @Test fun independentlyCompiledX87ReferenceVectorsMatchEveryPhaseAndRawState() {
        val engine = WolfPictureEffects()
        val snapshot = WolfPictureEffects()
        var base = NativePicture(42)
        var scenario = ""
        var samples = 0
        val vectors = javaClass.getResourceAsStream("/picture-effects/w2-native.tsv") ?: error("Native x87 vectors missing")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val a = line.split('\t')
            when (a[0]) {
                "case" -> {
                    engine.reset(); scenario = a[1]
                    base = NativePicture(42, x = a[2].toFloat(), y = a[3].toFloat(), scaleX = a[4].toFloat() / 100, scaleY = a[5].toFloat() / 100, pattern = a[6].toInt())
                }
                "set" -> engine.apply(42, a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt())
                "tick" -> engine.tick()[42]?.let { base = base.copy(pattern = it) }
                "sample" -> {
                    val context = "$scenario frame ${a[1]}"
                    val p = engine.picture(base)
                    assertEquals("$context x", a[2].toFloat(), p.x, .000001f)
                    assertEquals("$context y", a[3].toFloat(), p.y, .000001f)
                    assertEquals("$context scaleX", a[4].toFloat(), p.scaleX, .000002f)
                    assertEquals("$context scaleY", a[5].toFloat(), p.scaleY, .000002f)
                    assertEquals("$context red", a[6].toInt(), p.tone.red)
                    assertEquals("$context green", a[7].toInt(), p.tone.green)
                    assertEquals("$context blue", a[8].toInt(), p.tone.blue)
                    assertEquals("$context pattern", a[9].toInt(), p.pattern)
                    assertEquals("$context original words", a.drop(10).map(String::toInt), engine.state().single().words)
                    snapshot.restore(engine.state())
                    assertEquals("$context restored draw", p, snapshot.picture(base))
                    samples++
                }
                else -> error("Unknown vector directive ${a[0]}")
            }
        } }
        assertTrue("All original phases must be exercised", samples > 3_000)
    }

    @Test fun snapshotRoundTripKeepsInterruptedTweensAndCyclePhases() {
        val original = WolfPictureEffects()
        val restored = WolfPictureEffects()
        val base = NativePicture(5, x = 60f, y = 80f, scaleX = .75f, scaleY = 1.5f, tone = NativeTone(17, -23, 41))
        original.apply(5, 0, 199, -101, 0, 11)
        original.apply(5, 1, -101, 199, 0, 13)
        original.apply(5, 2, 21, -33, 0, 17)
        original.apply(5, 3, 11, 7, 4, 5)
        original.apply(5, 4, 13, -37, 140, 19)
        original.apply(5, 5, 80, 40, -80, 7)
        original.apply(5, 7, 35, -15, 3, 8)
        original.apply(5, 10, 7, 2, 0, 3)
        repeat(9) { original.tick() }
        original.apply(5, 1, 150, -50, 101, 9)
        original.apply(5, 2, -7, 15, 0, 11)
        original.apply(5, 4, 29, 43, 80, 7)
        restored.restore(original.state())
        repeat(40) {
            assertEquals(original.picture(base), restored.picture(base))
            assertEquals(original.state(), restored.state())
            assertEquals(original.tick(), restored.tick())
        }
    }

    @Test fun effectsComposeOnTheCurrentBasePictureWithoutMutatingItsOwnTween() {
        val effects = WolfPictureEffects()
        effects.apply(3, 2, 17, -9, 0, 0)
        effects.apply(3, 4, 20, 30, 200, 0)
        val first = NativePicture(3, x = 40f, y = 50f, scaleX = .5f, scaleY = .75f)
        val later = first.copy(x = 80f, scaleY = 1.5f)
        assertEquals(77f, effects.picture(first).x, 0f)
        assertEquals(157f, effects.picture(later).x, 0f)
        assertEquals(1.5f, effects.picture(first).scaleY, 0f)
        assertEquals(3f, effects.picture(later).scaleY, 0f)
        assertEquals(40f, first.x, 0f)
        effects.erase(3)
        assertSame(later, effects.picture(later))
    }

    @Test fun unknownVersionThreeAndMovieModesCannotSilentlyAliasBasicEffects() {
        val effects = WolfPictureEffects()
        effects.apply(3, 5, 50, 60, 70, 9)
        val state = effects.state()
        for (operation in listOf(-1, 11, 18, 20, 21, 23, 26, 28, 511)) {
            try { effects.apply(3, operation, 1, 2, 3, 4); fail("Accepted unknown operation $operation") }
            catch (_: IllegalArgumentException) { assertEquals(state, effects.state()) }
        }
    }

    @Test fun invalidStateRestoreIsAtomicAndExportedWordsAreDetached() {
        val effects = WolfPictureEffects()
        effects.apply(4, 2, 12, 25, 0, 9)
        val state = effects.state()
        val external = state.single().words.toMutableList()
        effects.restore(listOf(WolfPictureEffectState(4, external)))
        external[7] = 777
        assertEquals(state, effects.state())
        for (invalid in listOf(listOf(WolfPictureEffectState(4, listOf(0))), state + state)) {
            try { effects.restore(invalid); fail("Invalid snapshot accepted") }
            catch (_: IllegalArgumentException) { assertEquals(state, effects.state()) }
        }
        effects.reset()
        assertTrue(effects.state().isEmpty())
    }

    @Test fun corruptCountersCannotOverflowPeriodsOrReachDivisionByZero() {
        val effects = WolfPictureEffects()
        effects.apply(4, 3, 12, 9, 3, 7)
        effects.apply(4, 5, 50, 60, 70, 9)
        val state = effects.state()
        for ((index, invalidValue) in listOf(1 to -1, 2 to 1, 5 to -1, 6 to 1, 11 to Int.MAX_VALUE,
            17 to -1, 18 to 0, 19 to Int.MAX_VALUE, 24 to Int.MIN_VALUE, 29 to Int.MAX_VALUE,
            30 to 19, 31 to 2, 34 to Int.MIN_VALUE, 35 to Int.MAX_VALUE, 39 to 1, 41 to 17,
            0 to 0x01000000, 3 to 0xffffff, 15 to 1)) {
            val words = state.single().words.toMutableList().apply { this[index] = invalidValue }
            try { effects.restore(listOf(WolfPictureEffectState(4, words))); fail("Corrupt counter $index accepted") }
            catch (_: IllegalArgumentException) { assertEquals(state, effects.state()) }
        }
        for (duration in listOf(-1, WolfPictureEffects.MAX_DURATION + 1, Int.MAX_VALUE)) {
            try { effects.apply(4, 5, 1, 2, 3, duration); fail("Invalid duration accepted") }
            catch (_: IllegalArgumentException) { assertEquals(state, effects.state()) }
        }
        repeat(20) { effects.tick(); effects.picture(NativePicture(4)) }
    }
}
