package fr.astragames.wolf

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class WolfSnapshotCompatibilityTest {
    @Test fun genuineVersion21KeepsPausedTextClocksAndFontCapturesDuringVersion22Migration() {
        val bytes = fixture("v21-text-clock.snap")
        assertEquals("d17ab77e579b05c84c022296e22ab7738ead1b7d14ba7a841c6c1c3731bdc768",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertEquals(21, ByteBuffer.wrap(bytes).getInt(8))
        val state = WolfSnapshot.decode(bytes, "snapshot-v21-text-clock")
        assertEquals(17L, state.ticks); assertEquals(42, state.numbers[0])
        assertNull(state.mapTextures)
        val picture = state.pictures.single()
        assertEquals("A\\sp[60]日\\.BC", picture.text)
        assertEquals(2, picture.visibleCharacters)
        assertEquals(mapOf(0 to "Aozora Mincho Regular"), picture.fontOverrides)
        val timing = requireNotNull(picture.textTiming)
        assertEquals(WolfEncoding.UTF8, timing.encoding); assertEquals(369, timing.compatibilityVersion)
        assertEquals(16, timing.state.waitTicks); assertEquals(6.0, timing.state.progress, 0.0)
        assertEquals(mapOf(4 to 2.0), timing.state.speedAnchors)
        assertTrue(state.tweens.single().to.textTiming!!.state.autoClose)
        assertTrue(state.delayedPictures.single().target!!.textTiming!!.state.autoClose)
        assertEquals(state, WolfSnapshot.decode(WolfSnapshot.encode(state), state.title))
    }

    @Test fun backgroundAndFogSaveIndependentNativeIntegerClocksWithoutClampingTheirAccumulators() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val backdrop = WolfMapTextureState(12, "back.png", WolfMapTextures.State(true, 17, 13,
            Int.MIN_VALUE, Int.MAX_VALUE, 101, 93, -2), stringReloadPending = true)
        val fog = WolfMapTextureState(-2, "fog.png", WolfMapTextures.State(true, 1, 1,
            -700, 900, 8, 8, 0))
        val original = runtime.snapshotState().copy(mapTextures = WolfMapTextureLayers(backdrop, fog))
        val encoded = WolfSnapshot.encode(original)
        assertEquals(22, ByteBuffer.wrap(encoded).getInt(8))
        assertEquals(original, WolfSnapshot.decode(encoded, original.title))
    }

    @Test fun corruptMapTextureDimensionsAndPhasesAreRejectedBeforeRuntimeMutation() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val before = runtime.snapshotState()
        val scroll = WolfMapTextures.State(true, 17, 13, Int.MIN_VALUE, Int.MAX_VALUE, 101, 93, -2)
        val layer = WolfMapTextureState(12, "back.png", scroll)
        val encoded = WolfSnapshot.encode(before.copy(mapTextures = WolfMapTextureLayers(backdrop = layer)))
        val body = encoded.copyOfRange(4, encoded.size - 4)
        val record = ByteBuffer.allocate(29).put(1).putInt(17).putInt(13)
            .putInt(Int.MIN_VALUE).putInt(Int.MAX_VALUE).putInt(101).putInt(93).putInt(-2).array()
        val offset = (0..body.size - record.size).single { position -> record.indices.all { body[position + it] == record[it] } }
        for ((position, value) in listOf(1 to -1, 1 to Int.MAX_VALUE, 5 to -1, 17 to -1, 21 to 105)) {
            val invalid = body.copyOf(); ByteBuffer.wrap(invalid).putInt(offset + position, value)
            assertThrows(IllegalArgumentException::class.java) { runtime.restore(envelope(invalid)) }
            assertEquals(before, runtime.snapshotState())
        }
        for (invalid in listOf(layer.copy(path = null), layer.copy(path = ""), layer.copy(path = "bad\u0000path"),
            layer.copy(scroll = scroll.copy(phaseX = -1)))) {
            assertThrows(IllegalArgumentException::class.java) {
                WolfSnapshot.encode(before.copy(mapTextures = WolfMapTextureLayers(backdrop = invalid)))
            }
        }
    }

    @Test fun signedWolf2MapTexturePhasesRetainTheirProfileAndCannotBecomeWolf3Phases() {
        val before = wolfHostRuntimeFixture(emptyList()).snapshotState()
        val texture = WolfMapTextureState(7, "signed-w2.png",
            WolfMapTextures.State(true, 17, 13, Int.MIN_VALUE, Int.MAX_VALUE, -135, -103, -2),
            behaviorVersion = 2)
        val state = before.copy(mapTextures = WolfMapTextureLayers(texture, texture.copy(id = -2)))
        val decoded = WolfSnapshot.decode(WolfSnapshot.encode(state), before.title)
        assertEquals(state, decoded)
        assertEquals(2, decoded.mapTextures!!.backdrop.behaviorVersion)
        for (invalid in listOf(texture.copy(behaviorVersion = 3), texture.copy(behaviorVersion = 99),
            texture.copy(scroll = texture.scroll.copy(phaseX = -136)))) {
            assertThrows(IllegalArgumentException::class.java) {
                WolfSnapshot.encode(before.copy(mapTextures = WolfMapTextureLayers(backdrop = invalid)))
            }
        }
    }

    @Test fun olderSnapshotsRemainReadableAndUnknownFutureVersionsDoNotAlterTheRuntime() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val current = runtime.snapshot()
        for (version in listOf(15, 16, 17, 18, 19)) {
            val migrated = fixture("v$version-empty.snap")
            val decoded = WolfSnapshot.decode(migrated, "host")
            assertEquals("host", decoded.title); assertEquals(0L, decoded.ticks)
            assertEquals(0, decoded.mapId); assertEquals(2f, decoded.x, 0f); assertEquals(2f, decoded.y, 0f)
            assertTrue(decoded.pictures.isEmpty()); assertTrue(decoded.numbers.isEmpty())
            runtime.restore(migrated)
        }
        val before = runtime.snapshotState()
        try { runtime.restore(previous(current, 100, 0)); fail("Future save admitted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, runtime.snapshotState())
    }

    @Test fun version18TextPicturesKeepTheInitialFontsInsteadOfInheritingLaterOverrides() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val picture = NativePicture(701, text = "legacy-v18-text", kind = 2)
        for (version in listOf(18, 19)) {
            val old = fixture("v$version-picture.snap")
            val decoded = WolfSnapshot.decode(old, "host")
            assertEquals(listOf(picture), decoded.pictures)
            assertEquals("Spectral ExtraLight", decoded.systemStrings[62])
            runtime.restore(old)
            assertTrue(runtime.frame().pictures.single().fontOverrides.isEmpty())
            assertNull(runtime.frame().pictures.single().textTiming)
            assertEquals("Spectral ExtraLight", runtime.frame().fontOverrides[0])
        }
    }

    @Test fun publishedBeta14Version2KeepsItsVariablesCoordinatesAndTextDuringMigration() {
        val bytes = fixture("v2-beta14.snap")
        val decoded = WolfSnapshot.decode(bytes, "host")
        assertEquals(17L, decoded.ticks); assertEquals(42, decoded.numbers[0])
        assertEquals("ancienne sauvegarde", decoded.strings[0]); assertEquals("saved text", decoded.pictures.single().text)
        assertTrue(decoded.pictures.single().fontOverrides.isEmpty())
        val runtime = wolfHostRuntimeFixture(emptyList())
        runtime.restoreSaveData(WolfSaveData.decode(bytes))
        assertEquals(42, runtime.savedNumber(runtime.snapshot(), 2_000_000))
        assertEquals("ancienne sauvegarde", runtime.savedString(runtime.snapshot(), 3_000_000))
        assertEquals(2f, runtime.snapshotState().x, 0f); assertEquals(2f, runtime.snapshotState().y, 0f)
        val hero = runtime.frame().map!!.characters.single { it.id == -2 }
        // Scene coordinates point to the sprite's bottom centre, not the tile origin.
        assertEquals(40f, hero.x, 0f); assertEquals(48f, hero.y, 0f)
    }

    @Test fun pictureAndTweenFontCapturesRoundTripWithoutFollowingTheCurrentFont() {
        val original = NativePicture(8, text = "before", kind = 2,
            fontOverrides = mapOf(0 to "Aozora Mincho Regular", 3 to "Noto Naskh Arabic", 5 to "NanumMyeongjo"))
        val replacement = original.copy(text = "after", fontOverrides = mapOf(0 to "Spectral ExtraLight"))
        val runtime = wolfHostRuntimeFixture(emptyList())
        val state = runtime.snapshotState().copy(systemStrings = mapOf(62 to "Source Han Serif TW ExtraLight"),
            pictures = listOf(original), tweens = listOf(WolfSnapshot.TweenState(original, replacement, 30, 4, false)),
            delayedPictures = listOf(WolfSnapshot.DelayedPictureState(8, 0, 12, replacement, 3)))
        val decoded = WolfSnapshot.decode(WolfSnapshot.encode(state), "host")
        assertEquals(state, decoded)
        assertEquals(original.fontOverrides, decoded.pictures.single().fontOverrides)
        assertEquals(replacement.fontOverrides, decoded.tweens.single().to.fontOverrides)
        assertEquals(replacement.fontOverrides, decoded.delayedPictures.single().target!!.fontOverrides)
    }

    @Test fun autonomousFrequencyAndAlternateMotionSurviveSaveWhileMalformedValuesLeaveTheWorldIntact() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val before = runtime.snapshotState()
        val motion = WolfCharacterMotion.State(17017, 2, true)
        val state = before.copy(hero = before.hero!!.copy(motion = motion))
        val encoded = WolfSnapshot.encode(state)
        assertEquals(state, WolfSnapshot.decode(encoded, "host"))
        val body = encoded.copyOfRange(4, encoded.size - 4)
        val record = ByteBuffer.allocate(9).putInt(17017).putInt(2).put(1).array()
        val offset = (0..body.size - record.size).single { position -> record.indices.all { body[position + it] == record[it] } }
        ByteBuffer.wrap(body).putInt(offset, 40_000)
        try { runtime.restore(envelope(body)); fail("Invalid motion frequency admitted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, runtime.snapshotState())
    }

    @Test fun unknownDuplicatedAndOversizedFontCapturesAreRejectedBeforeRestore() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val before = runtime.snapshotState()
        for (fonts in listOf(mapOf(4 to "unqualified"), mapOf(0 to ""), mapOf(0 to "a\u0000b"),
            mapOf(0 to "あ".repeat(1400)))) {
            try { WolfSnapshot.encode(before.copy(pictures = listOf(NativePicture(9, text = "sample", kind = 2, fontOverrides = fonts))))
                fail("Invalid font capture admitted") }
            catch (_: IllegalArgumentException) { }
        }
        val bytes = WolfSnapshot.encode(before.copy(pictures = listOf(NativePicture(9, text = "sample", kind = 2,
            fontOverrides = linkedMapOf(0 to "uniqueFontOne", 1 to "uniqueFontTwo")))))
        val body = bytes.copyOfRange(4, bytes.size - 4)
        val name = "uniqueFontTwo".toByteArray(Charsets.UTF_8)
        val offset = (0..body.size - name.size).single { position -> name.indices.all { body[position + it] == name[it] } }
        ByteBuffer.wrap(body).putInt(offset - 8, 0)
        try { runtime.restore(envelope(body)); fail("Duplicate font index admitted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, runtime.snapshotState())
    }

    @Test fun textClockPauseAndSpeedAnchorsResumeIdenticallyThroughPicturesTweensAndDelayedCommands() {
        val text = "A\\sp[60]日\\.BC"
        val program = WolfTextPictureTiming.compile(text, Charsets.UTF_8, 369)
        var layout = WolfTextPictureTiming.Layout(WolfTextPictureTiming.initial(program, 30), 0)
        repeat(12) {
            if (layout.state.waitTicks == 0) layout = WolfTextPictureTiming.advance(program, layout.state, 30, 1)
        }
        assertTrue("Fixture must save during a native pause", layout.state.waitTicks > 0)
        assertFalse(layout.state.speedAnchors.isEmpty())
        val clock = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state)
        val original = NativePicture(7, text = text, kind = 2, visibleCharacters = layout.visibleGlyphCount,
            fontOverrides = mapOf(0 to "Aozora Mincho Regular"), textTiming = clock)
        val target = original.copy(x = 50f, textTiming = clock.copy(state = clock.state.copy(autoClose = true)))
        val state = wolfHostRuntimeFixture(emptyList()).snapshotState().copy(pictures = listOf(original),
            tweens = listOf(WolfSnapshot.TweenState(original, target, 30, 4, false)),
            delayedPictures = listOf(WolfSnapshot.DelayedPictureState(7, 0, 12, target, 3)))
        val decoded = WolfSnapshot.decode(WolfSnapshot.encode(state), "host")
        assertEquals(state, decoded)
        assertEquals(clock, decoded.pictures.single().textTiming)
        assertEquals(target.textTiming, decoded.tweens.single().to.textTiming)
        assertEquals(target.textTiming, decoded.delayedPictures.single().target!!.textTiming)
        var originalClock = clock.state
        var restoredClock = decoded.pictures.single().textTiming!!.state
        repeat(40) {
            val a = WolfTextPictureTiming.advance(program, originalClock, 30, 1)
            val b = WolfTextPictureTiming.advance(clock.program(text), restoredClock, 30, 1)
            assertEquals(a, b)
            originalClock = a.state; restoredClock = b.state
        }
        assertEquals(program.glyphCount, WolfTextPictureTiming.layout(program, restoredClock, 30).visibleGlyphCount)
    }

    @Test fun corruptedTextClockEncodingProfileValuesAndAnchorPositionsAreRejectedBeforeWorldMutation() {
        val runtime = wolfHostRuntimeFixture(emptyList())
        val before = runtime.snapshotState()
        val text = "A\\sp[60]B\\sp[80]C"
        val program = WolfTextPictureTiming.compile(text, Charsets.UTF_8, 369)
        var state = WolfTextPictureTiming.initial(program, 240)
        repeat(32) { state = WolfTextPictureTiming.advance(program, state, 240, 1).state }
        assertEquals(2, state.speedAnchors.size)
        val clock = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, state)
        val picture = NativePicture(10, text = text, kind = 2, textTiming = clock)
        val encoded = WolfSnapshot.encode(before.copy(pictures = listOf(picture)))
        val prefix = ByteBuffer.allocate(33).putInt(WolfEncoding.UTF8.ordinal).putInt(369).put(1)
            .putDouble(state.speed).putDouble(state.progress).putDouble(state.previous).array()
        val body = encoded.copyOfRange(4, encoded.size - 4)
        val offset = (0..body.size - prefix.size).single { position -> prefix.indices.all { body[position + it] == prefix[it] } }
        val changes: List<(ByteBuffer) -> Unit> = listOf(
            { it.putInt(offset, 99) },
            { it.putInt(offset + 4, 99) },
            { it.putDouble(offset + 9, Double.NaN) },
            { it.putDouble(offset + 17, Double.POSITIVE_INFINITY) },
            { it.putInt(offset + 37, 17) },
            { it.putInt(offset + 41, program.compiledByteLength + 2) },
            { it.putInt(offset + 50, 99_999) },
            { it.putInt(offset + 62, it.getInt(offset + 50)) },
        )
        for (change in changes) {
            val malformed = body.copyOf()
            change(ByteBuffer.wrap(malformed))
            assertThrows(IllegalArgumentException::class.java) { runtime.restore(envelope(malformed)) }
            assertEquals(before, runtime.snapshotState())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WolfSnapshot.encode(before.copy(pictures = listOf(picture.copy(kind = 0, text = null, path = "image.png"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = picture.copy(textTiming = clock.copy(state = state.copy(speed = Double.NaN)))
            WolfSnapshot.encode(before.copy(pictures = listOf(invalid)))
        }
    }

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.classLoader
        .getResourceAsStream("legacy-snapshots/$name")).use { it.readBytes() }

    private fun envelope(body: ByteArray): ByteArray = ByteBuffer.allocate(body.size + 8)
        .putInt(body.size).put(body).putInt(CRC32().apply { update(body) }.value.toInt()).array()

    private fun previous(bytes: ByteArray, version: Int, removedTail: Int): ByteArray {
        val body = bytes.copyOfRange(4, bytes.size - 4 - removedTail)
        ByteBuffer.wrap(body).putInt(4, version)
        return envelope(body)
    }
}
