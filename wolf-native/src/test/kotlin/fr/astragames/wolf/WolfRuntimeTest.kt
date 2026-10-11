package fr.astragames.wolf

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WolfRuntimeTest {
    @Test fun isolatedSaveReadCacheOwnsItsBytesAndRejectsMutationBeforeAnyReuse() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 7, 0, 0)),
            command(122, intArrayOf(3_000_000, 0), listOf("saved")), command(173)))
        runtime.tick(); val bytes = runtime.snapshot()
        assertEquals(7, runtime.savedNumber(bytes, 2_000_000))
        val cacheField = runtime.javaClass.getDeclaredField("isolatedSaveCache").also { it.isAccessible = true }
        val cached = cacheField.get(runtime)
        assertEquals("saved", runtime.savedString(bytes.copyOf(), 3_000_000)); assertSame(cached, cacheField.get(runtime))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try { runtime.savedNumber(bytes, 2_000_000); fail("Mutation must invalidate the cached payload and fail CRC") }
        catch (_: IllegalArgumentException) { }
        assertSame(cached, cacheField.get(runtime))
        val other = fixture(listOf(command(121, intArrayOf(2_000_000, 11, 0, 0)), command(173)))
        other.tick(); assertEquals(11, runtime.savedNumber(other.snapshot(), 2_000_000)); assertNotSame(cached, cacheField.get(runtime))
        // Full restoration still uses its own decoder and rejects this corrupted input.
        val before = runtime.snapshot()
        try { runtime.restore(bytes); fail("Cached221 state must not bypass restore validation") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, runtime.snapshot())
    }

    @Test fun aConsumedBranchDoesNotReenterAfterJumpingToALabelInsideAnotherArm() {
        val runtime = fixture(listOf(command(111, intArrayOf(2, 0, 1, 18, 1, 1, 18)), command(401, intArrayOf(1)),
            command(212, strings = listOf("set"), indent = 1), command(121, intArrayOf(2_000_000, 7, 0, 0), indent = 1),
            command(401, intArrayOf(2)), command(213, strings = listOf("set"), indent = 1), command(499), command(173)))
        runtime.tick(); assertEquals(7, runtime.snapshotState().numbers[0]); assertNull(runtime.frame().diagnostic)
    }

    @Test fun theElseArmIsConsumedBeforeALabelJumpReachesAnotherElseMarker() {
        val runtime = fixture(listOf(command(111, intArrayOf(1, 0, 1, 18)), command(401, intArrayOf(1)),
            command(212, strings = listOf("body"), indent = 1), command(121, intArrayOf(2_000_000, 3, 0, 0), indent = 1),
            command(420), command(213, strings = listOf("body"), indent = 1), command(499), command(173)))
        runtime.tick(); assertEquals(3, runtime.snapshotState().numbers[0])
    }

    @Test fun numericConditionsKeepFlag16RightOperandsLiteralAndPreflightTheirModes() {
        val commands = listOf(command(121, intArrayOf(2_000_000, 9_000_199, 0, 4)),
            command(111, intArrayOf(1, 2_000_000, 9_000_199, 18)), command(401, intArrayOf(1)),
            command(121, intArrayOf(2_000_001, 7, 0, 0), indent = 1), command(420),
            command(121, intArrayOf(2_000_001, -1, 0, 0), indent = 1), command(499), command(173))
        val runtime = fixture(commands)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals(7, runtime.snapshotState().numbers[1])
        assertFalse(fixture(commands.map { if (it.opcode == 111) it.copy(arguments = intArrayOf(1, 2_000_000, 9_000_199, 2)) else it }).inspectCapabilities().supported)
        val unknown = fixture(listOf(command(111, intArrayOf(1, 0, 0, 23), offset = 0xd8ce1)))
        assertFalse(unknown.inspectCapabilities().supported)
        val failure = rejectsRuntime { unknown.tick() }
        assertEquals(111, failure.opcode); assertEquals(0xd8ce1, failure.offset)
    }

    @Test fun aZeroCountNumericConditionTakesTheElseBranch() {
        val runtime = fixture(listOf(command(111, intArrayOf(0)), command(401, intArrayOf(1)),
            command(121, intArrayOf(2_000_000, -1, 0, 0), indent = 1), command(420),
            command(121, intArrayOf(2_000_000, 9, 0, 0), indent = 1), command(499), command(173)))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals(9, runtime.snapshotState().numbers[0])
    }

    @Test fun packedStringConditionsAndDynamicCommonTargetsUseTheirDecodedVariableRoles() {
        val called = common(0, listOf(command(122, intArrayOf(1_600_006, 0), listOf("left")),
            command(112, intArrayOf(1, 0x10000000 or 1_600_006), listOf("right")), command(401, intArrayOf(1)),
            command(121, intArrayOf(2_000_001, 81, 0, 0), indent = 1), command(499), command(172)))
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 500_000, 0, 0)),
            command(210, intArrayOf(2_000_000, 0)), command(173)), listOf(called))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals(81, runtime.snapshotState().numbers[1])
    }

    @Test fun nestedNumericTextParametersArePreflightedAfterVariablePlaceholders() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 16, 0, 0)),
            textPicture(1, "\\f[\\v[0]]Hello"), command(173)))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals("\\f[16]Hello", runtime.frame().pictures.single().text)
        assertNull(runtime.frame().diagnostic)
        assertFalse(fixture(listOf(textPicture(1, "\\f[bad]Hello"))).inspectCapabilities().supported)
    }

    @Test fun anAutonomousPageRouteUsesItsProvenFrequencyInsteadOfACompatibilityRefusal() {
        val npc = WolfEvent(1, "walker", 3, 2, listOf(page(emptyList(), trigger = 0).copy(graphicFile = "Npc.png", flags = 10,
            movement = byteArrayOf(3, 3, 1, 1), route = WolfRoute(1, listOf(WolfRouteCommand(2, intArrayOf()))))))
        val runtime = fixture(emptyList(), initialMap = map("autonomous", listOf(npc)))
        assertTrue(runtime.inspectCapabilities().supported)
        repeat(50) { runtime.tick() }
        assertTrue(runtime.snapshotState().actors.single { it.id == 1 }.x > 3f)
    }

    @Test fun eightDirectionMovementIsAdmittedAndPerformedWithoutAxisSuppression() {
        val runtime = fixture(listOf(command(173)), movementDirections = 8)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); runtime.key(NativeKey.RIGHT, true); runtime.key(NativeKey.DOWN, true); runtime.tick()
        runtime.key(NativeKey.RIGHT, false); runtime.key(NativeKey.DOWN, false)
        repeat(16) { runtime.tick() }
        assertEquals(3f, runtime.snapshotState().x, 0f); assertEquals(3f, runtime.snapshotState().y, 0f)
    }

    @Test fun textPicturesRevealEncodingCharactersAndCompletionUsesTheNativeByteClock() {
        val reader = common(0, listOf(command(124, intArrayOf(2_000_000, 0x4000, 1, 10)))).copy(conditionWord = 3)
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_011, 30, 0, 0)), textPicture(1, "Aé日😀"), command(173)),
            listOf(reader), compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals(0, runtime.frame().pictures.single().visibleCharacters)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), (1..8).map { runtime.tick(); runtime.frame().pictures.single().visibleCharacters })
        assertEquals(1, runtime.snapshotState().numbers[0])
        val fast = fixture(listOf(command(121, intArrayOf(9_000_011, 240, 0, 0)), textPicture(1, "ABCD"), command(173)),
            listOf(reader), compatibilityVersion = 369)
        fast.tick(); fast.tick()
        assertEquals(4, fast.frame().pictures.single().visibleCharacters)
        assertEquals(0, fast.snapshotState().numbers[0]) // Last glyph precedes native GetInfo10 completion.
        fast.tick(); assertEquals(1, fast.snapshotState().numbers[0])
        val legacy = fixture(listOf(command(121, intArrayOf(9_000_011, 30, 0, 0)), textPicture(1, "日"), command(173)),
            compatibilityVersion = 225, encoding = WolfEncoding.CP932)
        legacy.tick(); legacy.tick(); assertEquals(0, legacy.frame().pictures.single().visibleCharacters)
        legacy.tick(); assertEquals(1, legacy.frame().pictures.single().visibleCharacters)
    }

    @Test fun moveAndRestartPreservePausedTextClockWhileGeometryKeepsAnimating() {
        val move = command(150, intArrayOf(1, 1, 20, 1, 1, 1, 255, 30, 0, 100, 0))
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_011, 240, 0, 0)), textPicture(1, "A\\.BC"),
            command(180, intArrayOf(1)), move, command(173)), compatibilityVersion = 369)
        runtime.tick(); runtime.tick(); runtime.tick()
        val picture = runtime.frame().pictures.single()
        assertEquals(1, picture.visibleCharacters)
        assertEquals(15, picture.textTiming!!.state.waitTicks)
        assertEquals(picture.textTiming, runtime.snapshotState().tweens.single().to.textTiming)
        val cache = runtime.javaClass.getDeclaredField("textPicturePrograms").also { it.isAccessible = true }
        val program = (cache.get(runtime) as Map<*, *>).values.single()
        val restarted = fixture(emptyList(), compatibilityVersion = 369)
        restarted.restore(runtime.snapshot())
        assertEquals(picture, restarted.frame().pictures.single())
        repeat(20) { runtime.tick(); restarted.tick(); assertEquals(runtime.frame().pictures, restarted.frame().pictures) }
        assertEquals(3, runtime.frame().pictures.single().visibleCharacters)
        assertEquals(30f, runtime.frame().pictures.single().x, 0f)
        assertEquals(1, (cache.get(runtime) as Map<*, *>).size)
        assertSame(program, (cache.get(runtime) as Map<*, *>).values.single())
    }

    @Test fun identicalShowResetsItsClockAndDeferredShowDoesNotRevealBeforeActivation() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_011, 30, 0, 0)), textPicture(1, "ABC"),
            command(180, intArrayOf(2)), textPicture(1, "ABC", duration = 5),
            textPicture(2, "XYZ", delay = 5), command(173)), compatibilityVersion = 369)
        runtime.tick(); runtime.tick(); runtime.tick()
        assertEquals(2, runtime.frame().pictures.single().visibleCharacters)
        runtime.tick()
        assertEquals(0.0, runtime.frame().pictures.single().textTiming!!.state.progress, 0.0)
        assertEquals(0, runtime.snapshotState().delayedPictures.single().target!!.visibleCharacters)
        repeat(4) { runtime.tick() }
        assertEquals(listOf(1), runtime.frame().pictures.map { it.id })
        runtime.tick()
        // A delayed SHOW is activated at the frame's picture-update phase, then ticks once.
        assertEquals(1.0, runtime.frame().pictures.single { it.id == 2 }.textTiming!!.state.progress, 0.0)
    }

    @Test fun fullTextMeasurementIsReusedAcrossRevealGeometryAndSameFrameHostContinuations() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_011, 30, 0, 0)), textPicture(1, "ABCDE"),
            command(124, intArrayOf(2_000_000, 0x4000, 1, 2)), command(180, intArrayOf(1)),
            command(150, intArrayOf(1, 1, 10, 1, 1, 1, 255, 40, 0, 100, 0)),
            command(124, intArrayOf(2_000_001, 0x4000, 1, 3)), command(180, intArrayOf(1)),
            command(124, intArrayOf(2_000_002, 0x4000, 1, 2)), command(173)), compatibilityVersion = 369)
        runtime.tick(); assertTrue(runtime.hasPendingTick)
        val measure = runtime.drainEffects().filterIsInstance<NativeEffect.MeasurePicture>().single()
        runtime.completePictureMeasure(measure.requestId, 31, 17); runtime.tick()
        assertEquals(1L, runtime.ticks); assertFalse(runtime.hasPendingTick)
        assertEquals(0.0, runtime.frame().pictures.single().textTiming!!.state.progress, 0.0)
        repeat(4) { runtime.tick(); assertTrue(runtime.drainEffects().none { it is NativeEffect.MeasurePicture }) }
        assertEquals(mapOf(0 to 31, 1 to 17, 2 to 31), runtime.snapshotState().numbers)
        assertTrue(runtime.frame().pictures.single().x > 0f)
    }

    @Test fun specialConversionIsCapturedForEachPictureAndDialogAndAlsoControlsStringExpansion() {
        val literal = "\\v[0]\\c[1]A"
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 7, 0, 0)), command(121, intArrayOf(9_000_150, 17, 0, 0)),
            command(122, intArrayOf(3_000_000, 0), listOf(literal)), textPicture(1, literal),
            command(101, strings = listOf(literal)), command(121, intArrayOf(9_000_150, -3, 0, 0)),
            command(122, intArrayOf(3_000_001, 0), listOf("\\v[0]")), textPicture(2, literal), command(173)), compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        assertEquals(literal, runtime.snapshotState().strings[0]); assertEquals(literal, runtime.frame().dialog!!.text)
        assertFalse(runtime.frame().dialog!!.convertSpecial)
        assertFalse(runtime.frame().pictures.single().textTiming!!.convertSpecial)
        assertEquals(1, runtime.snapshotState().system[150])
        runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false); runtime.tick()
        assertEquals("7", runtime.snapshotState().strings[1]); assertEquals(0, runtime.snapshotState().system[150])
        assertEquals(literal, runtime.frame().pictures.single { it.id == 1 }.text)
        assertEquals("7\\c[1]A", runtime.frame().pictures.single { it.id == 2 }.text)
        assertFalse(runtime.frame().pictures.single { it.id == 1 }.textTiming!!.convertSpecial)
        assertTrue(runtime.frame().pictures.single { it.id == 2 }.textTiming!!.convertSpecial)
    }

    @Test fun malformedClockCannotMutateARunningWorldAndOlderTextRastersDoNotRestartReveal() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_011, 30, 0, 0)), textPicture(1, "ABCDE"), command(173)), compatibilityVersion = 369)
        runtime.tick(); runtime.tick()
        val before = runtime.snapshot()
        val p = runtime.snapshotState().pictures.single()
        val invalid = p.copy(textTiming = p.textTiming!!.copy(state = p.textTiming.state.copy(speedAnchors = mapOf(9999 to 2.0))))
        try { runtime.restoreState(runtime.snapshotState().copy(pictures = listOf(invalid))); fail("Corrupt clock accepted") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, runtime.snapshot())
        runtime.restoreState(runtime.snapshotState().copy(pictures = listOf(p.copy(textTiming = null, visibleCharacters = Int.MAX_VALUE))))
        repeat(3) { runtime.tick() }
        assertEquals(Int.MAX_VALUE, runtime.frame().pictures.single().visibleCharacters)
        assertNull(runtime.frame().pictures.single().textTiming)
    }

    @Test fun pairedBackslashesRemainLiteralAcrossVariableExpansionAndRecursiveStoredStrings() {
        val literal = """\\cself[1] \\v[9000199]"""
        val recursive = """\s[0] / \\\cself[1]"""
        for (profile in listOf(225, 369)) {
            val runtime = fixture(listOf(command(121, intArrayOf(1_600_001, 7, 0, 0)),
                command(122, intArrayOf(3_000_000, 0), listOf(literal)),
                command(122, intArrayOf(3_000_001, 0), listOf(recursive)),
                textPicture(1, literal), command(101, strings = listOf("""\s[1]"""))), compatibilityVersion = profile)
            assertTrue(runtime.inspectCapabilities().supported)
            runtime.tick(); runtime.tick()
            assertEquals(literal, runtime.snapshotState().strings[0])
            assertEquals(literal + " / " + """\\7""", runtime.snapshotState().strings[1])
            assertEquals(literal, runtime.frame().pictures.single().text)
            assertEquals(literal + " / " + """\\7""", runtime.frame().dialog!!.text)
            assertEquals("\\cself[1] \\v[9000199]".codePointCount(0, "\\cself[1] \\v[9000199]".length), runtime.frame().pictures.single().visibleCharacters)
        }
    }

    @Test fun sourceCarriageReturnsAreRemovedBeforeConversionButInjectedVariableReturnsArePreserved() {
        val runtime = fixture(listOf(command(122, intArrayOf(3_000_001, 0), listOf("""\s[0]""")),
            command(122, intArrayOf(3_000_002, 0), listOf("A\rB\r\nC")),
            command(121, intArrayOf(9_000_150, 1, 0, 0)),
            command(122, intArrayOf(3_000_003, 0), listOf("A\rB\r\nC")), command(173)), compatibilityVersion = 369)
        runtime.restoreState(runtime.snapshotState().copy(strings = mapOf(0 to "X\rY")))
        runtime.tick()
        assertEquals("X\rY", runtime.snapshotState().strings[1])
        assertEquals("AB\nC", runtime.snapshotState().strings[2])
        assertEquals("AB\nC", runtime.snapshotState().strings[3])
    }

    @Test fun unqualifiedPictureInputAndRubyTimingProducePreciseAdmissionAndRuntimeDiagnostics() {
        for (text in listOf("A\\!B", "\\r[漢字,かんじ]")) {
            val runtime = fixture(listOf(textPicture(1, text).copy(offset = 0x55)), compatibilityVersion = 369)
            assertFalse(runtime.inspectCapabilities().supported)
            val failure = rejectsRuntime { runtime.tick() }
            assertEquals(150, failure.opcode); assertEquals(0x55, failure.offset)
        }
        for (profile in listOf(225, 369)) {
            val cold = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_011, 0, 0)), command(173)), compatibilityVersion = profile)
            cold.tick(); assertEquals(1_000_000, cold.snapshotState().numbers[0])
        }
    }

    @Test fun eventRunningRemainsTrueAfterAMessageAndIsCurrentEvenForAFrozenFrame() {
        val runtime = fixture(listOf(command(101, strings = listOf("End of introduction")), command(180, intArrayOf(2)),
            command(161), command(180, intArrayOf(2)), command(173)))
        runtime.tick(); assertTrue(runtime.frame().eventRunning)
        runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false); runtime.tick()
        assertNull(runtime.frame().dialog); assertTrue(runtime.frame().eventRunning)
        repeat(7) { runtime.tick() }
        assertFalse(runtime.frame().eventRunning)
        assertNull(runtime.frame().dialog)
    }

    @Test fun reloadedKeyboardConfigurationSwapsRolesAndOnlyTheConfiguredScanConfirms() {
        val commands = listOf(command(221, intArrayOf(2_000_003, 99, 2_000_099)),
            command(121, intArrayOf(9_000_052, 2_000_003, 0, 0)),
            command(121, intArrayOf(2_000_000, 9_000_052, 0, 0)),
            command(121, intArrayOf(2_000_001, 9_000_054, 0, 0)),
            command(123, intArrayOf(2_000_002, 0x90)), command(173))
        val runtime = fixture(commands, compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); val read = runtime.drainEffects().filterIsInstance<NativeEffect.ReadSaveValue>().single()
        runtime.completeSaveValue(read.requestId, true, number = 146); runtime.tick()
        assertEquals(146, runtime.snapshotState().numbers[0]); assertEquals(144, runtime.snapshotState().numbers[1])
        runtime.physicalKey(44, true); runtime.physicalKey(44, false); runtime.tick()
        assertFalse(runtime.snapshotState().numbers.containsKey(2))
        runtime.physicalKey(46, true); runtime.physicalKey(46, false); runtime.tick()
        assertEquals(10, runtime.snapshotState().numbers[2])
    }

    @Test fun controllerBitAssignmentsSurviveRestartAndAcceptTheTwentiethPhysicalButton() {
        val commands = listOf(command(121, intArrayOf(9_000_055, 320, 0, 0)),
            command(121, intArrayOf(2_000_000, 9_000_055, 0, 0)),
            command(123, intArrayOf(2_000_001, 0x90)), command(173))
        val runtime = fixture(commands, compatibilityVersion = 369)
        runtime.tick(); assertEquals(320, runtime.snapshotState().numbers[0])
        assertEquals(0x800000, runtime.snapshotState().system[55])
        val fresh = fixture(commands.drop(1), compatibilityVersion = 369)
        fresh.restore(runtime.snapshot()); fresh.tick()
        fresh.controllerButton(301, true); fresh.controllerButton(301, false); fresh.tick()
        assertFalse(fresh.snapshotState().numbers.containsKey(1))
        fresh.controllerButton(320, true); fresh.controllerButton(320, false); fresh.tick()
        assertEquals(10, fresh.snapshotState().numbers[1])
    }

    @Test fun desktopWindowPreferencePersistsForNextLaunchWithoutChangingAdaptiveOrSavedWorldGeometry() {
        val commands = listOf(command(121, intArrayOf(9_000_148, 1250, 0, 0)),
            command(121, intArrayOf(2_000_000, 9_000_148, 0, 0)), command(230, intArrayOf(1)))
        val runtime = fixture(commands, compatibilityVersion = 369)
        runtime.initialNextLaunchScale(1000)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        assertEquals(1250, runtime.snapshotState().numbers[0])
        assertEquals(listOf(NativeEffect.NextLaunchScale(1250)), runtime.drainEffects().filterIsInstance<NativeEffect.NextLaunchScale>())
        assertEquals(96, runtime.frame().logicalWidth); assertEquals(1f, runtime.frame().map!!.zoom, 0f)
        val saved = runtime.snapshot()
        val fresh = fixture(emptyList(), compatibilityVersion = 369)
        fresh.initialNextLaunchScale(1500); fresh.restore(saved)
        assertTrue(fresh.drainEffects().none { it is NativeEffect.NextLaunchScale })
        // A subsequent game command reads the host setting, not the Sys148 copied from the old save.
        val resumed = fixture(listOf(command(121, intArrayOf(2_000_001, 9_000_148, 0, 0)), command(173)), compatibilityVersion = 369)
        resumed.initialNextLaunchScale(1500); resumed.restore(saved); resumed.tick()
        assertEquals(1500, resumed.snapshotState().numbers[1])
        try { runtime.initialNextLaunchScale(500); fail("Initial host setting after execution") } catch (_: IllegalArgumentException) { }
    }

    @Test fun proFontResolutionIsSynchronousForTheVmAndCapturesEachTextRaster() {
        val runtime = fixture(listOf(
            command(122, intArrayOf(9_900_062, 0), listOf("Aozora Mincho Regular")),
            command(150, intArrayOf(0x20, 1, 0, 1, 1, 1, 255, 0, 0, 100, 0), listOf("first")),
            command(122, intArrayOf(9_900_062, 0), listOf("Spectral ExtraLight")),
            command(124, intArrayOf(2_000_000, 0x4000, 1, 2)),
            command(101, strings = listOf("dialogue"))), compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        val first = runtime.drainEffects().filterIsInstance<NativeEffect.FontOverride>().single()
        assertEquals(0, first.index); assertEquals("Aozora Mincho Regular", first.name)
        assertFalse(runtime.snapshotState().systemStrings.containsKey(62))
        val waitingTick = runtime.frame().tick
        runtime.tick(); assertEquals(waitingTick, runtime.frame().tick)
        runtime.completeFontOverride(first.requestId, true); runtime.tick()
        val second = runtime.drainEffects().filterIsInstance<NativeEffect.FontOverride>().single()
        assertEquals(mapOf(0 to "Aozora Mincho Regular"), runtime.frame().pictures.single().fontOverrides)
        runtime.completeFontOverride(second.requestId, false); runtime.tick()
        assertEquals("<<?>>Spectral ExtraLight", runtime.snapshotState().systemStrings[62])
        val measure = runtime.drainEffects().filterIsInstance<NativeEffect.MeasurePicture>().single()
        assertEquals(mapOf(0 to "Aozora Mincho Regular"), measure.fontOverrides)
        runtime.completePictureMeasure(measure.requestId, 31, 17); runtime.tick()
        assertEquals(31, runtime.snapshotState().numbers[0])
        assertEquals(mapOf(0 to "Spectral ExtraLight"), runtime.frame().dialog!!.fontOverrides)
        assertEquals(mapOf(0 to "Aozora Mincho Regular"), runtime.frame().pictures.single().fontOverrides)
        assertEquals(mapOf(0 to "Spectral ExtraLight"), runtime.frame().fontOverrides)
    }

    @Test fun fontResetFailedKeyboardFontAndLateResolutionFollowTheirExplicitProtocols() {
        val runtime = fixture(listOf(
            command(122, intArrayOf(9_900_067, 0), listOf("missing keyboard font")),
            command(122, intArrayOf(9_900_062, 0), listOf("")), command(173)), compatibilityVersion = 369)
        runtime.tick()
        val keyboard = runtime.drainEffects().filterIsInstance<NativeEffect.FontOverride>().single()
        assertEquals(5, keyboard.index)
        runtime.completeFontOverride(keyboard.requestId, false); runtime.tick()
        assertEquals("<<NotFound>>", runtime.snapshotState().systemStrings[67])
        val reset = runtime.drainEffects().filterIsInstance<NativeEffect.FontOverride>().single()
        runtime.completeFontOverride(reset.requestId, true); runtime.tick()
        assertEquals("", runtime.snapshotState().systemStrings[62]); assertTrue(runtime.frame().fontOverrides.isEmpty())
        val saved = runtime.snapshot()
        runtime.restore(saved)
        runtime.completeFontOverride(keyboard.requestId, true)
        assertEquals("<<NotFound>>", runtime.snapshotState().systemStrings[67])
        val old = fixture(listOf(command(122, intArrayOf(9_900_062, 0), listOf("missing"))), compatibilityVersion = 330)
        old.tick(); val request = old.drainEffects().filterIsInstance<NativeEffect.FontOverride>().single()
        old.completeFontOverride(request.requestId, false)
        assertEquals("<<NotFound>>", old.snapshotState().systemStrings[62])
        assertTrue(old.frame().fontOverrides.isEmpty())
    }

    @Test fun databaseStringDestinationsAndViewportFilterRespectTheirProfileAndMissingValues() {
        val database = saveDatabase().getValue(WolfDatabaseKind.MUTABLE)
        val unqualified = fixture(listOf(command(250, intArrayOf(0, 0, 1, 0x1200, 9_900_066), List(4) { "" })),
            databases = mapOf(WolfDatabaseKind.USER to database), compatibilityVersion = 369)
        assertFalse(unqualified.inspectCapabilities().supported)
        assertTrue(rejectsRuntime { unqualified.tick() }.message!!.contains("chaîne système"))
        val older = fixture(listOf(command(122, intArrayOf(9_900_062, 0), listOf("font"))), compatibilityVersion = 225)
        assertFalse(older.inspectCapabilities().supported)
        assertTrue(rejectsRuntime { older.tick() }.message!!.contains("profil Wolf"))
        val filter = fixture(listOf(command(121, intArrayOf(9_000_140, 1, 0, 0)), command(173)), compatibilityVersion = 369)
        assertNull(filter.frame().windowSmooth); filter.tick(); assertEquals(true, filter.frame().windowSmooth)
        val restored = fixture(emptyList(), compatibilityVersion = 369)
        restored.restore(filter.snapshot()); assertEquals(true, restored.frame().windowSmooth)
        val cold = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_140, 0, 0))), compatibilityVersion = 369)
        assertTrue(rejectsRuntime { cold.tick() }.message!!.contains("Valeur initiale système"))
        val invalid = fixture(listOf(command(121, intArrayOf(9_000_140, 2, 0, 0))), compatibilityVersion = 369)
        assertTrue(rejectsRuntime { invalid.tick() }.message!!.contains("Filtre d'affichage"))
    }

    @Test fun positionPictureQueriesUseTheNativeIdZOrderAndDoNotRejectTransparentPictures() {
        val queries = listOf(command(124, intArrayOf(2_000_000, 0x162000, 15, 15)),
            command(124, intArrayOf(2_000_001, 0x172000, 15, 15)),
            command(124, intArrayOf(2_000_002, 0x162000, 30, 15)), command(173))
        val active = listOf(NativePicture(99, path = "<SQUARE>", x = 10f, y = 10f, width = 20f, height = 20f, opacity = 0, z = 2, kind = 3),
            NativePicture(10, path = "<SQUARE>", x = 10f, y = 10f, width = 20f, height = 20f, z = 5, kind = 3),
            NativePicture(50, path = "<SQUARE>", x = 10f, y = 10f, width = 20f, height = 20f, z = 5, kind = 3))
        val modern = fixture(queries, compatibilityVersion = 372)
        assertTrue(modern.inspectCapabilities().supported)
        modern.restoreState(modern.snapshotState().copy(pictures = active)); modern.tick()
        assertEquals(99, modern.snapshotState().numbers[0]); assertEquals(50, modern.snapshotState().numbers[1])
        assertEquals(-2_000_000_000, modern.snapshotState().numbers[2])
        val older = fixture(queries, compatibilityVersion = 350)
        older.restoreState(older.snapshotState().copy(pictures = active)); older.tick()
        assertEquals(99, older.snapshotState().numbers[2])
    }

    @Test fun positionPictureQueriesWaitForRealTextDimensionsAndAbandonOldMeasurementsOnRestore() {
        val runtime = fixture(listOf(command(124, intArrayOf(2_000_000, 0x162000, 3, 3)), command(173)), compatibilityVersion = 372)
        runtime.restoreState(runtime.snapshotState().copy(pictures = listOf(NativePicture(7, text = "Measured text", kind = 2),
            NativePicture(9, path = "<SQUARE>", kind = 3, width = 2f, height = 2f))))
        runtime.tick(); val request = runtime.drainEffects().filterIsInstance<NativeEffect.MeasurePicture>().single()
        assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.completePictureMeasure(request.requestId, 10, 8); runtime.tick()
        assertEquals(7, runtime.snapshotState().numbers[0])
        runtime.restore(runtime.snapshot()); runtime.completePictureMeasure(request.requestId, 20, 20)
        assertEquals(7, runtime.snapshotState().numbers[0])
        assertFalse(WolfPictureGeometry.contains(NativePicture(0, width = 20f, height = 10f, rotation = 90f),
            20, 10, 0, 0, 0f, 0f, 372))
        assertTrue(WolfPictureGeometry.contains(NativePicture(0, width = 20f, height = 10f, rotation = 90f),
            20, 10, 10, 10, 0f, 0f, 372))
    }

    @Test fun forcedChoiceFinishResumesTheSelectedBranchOrTheExplicitDefaultWithoutAKey() {
        fun run(useDefault: Boolean): WolfRuntime {
            val closer = common(0, listOf(command(180, intArrayOf(1)), command(104), command(180, intArrayOf(9999)))).copy(conditionWord = 3)
            val runtime = fixture(listOf(command(121, intArrayOf(9_000_007, 1, 0, 0)),
                command(102, intArrayOf(if (useDefault) 0x422 else 0x22), listOf("First", "Second")),
                command(401, intArrayOf(2)), command(121, intArrayOf(2_000_000, 11, 0, 0), indent = 1),
                command(401, intArrayOf(3)), command(121, intArrayOf(2_000_000, 22, 0, 0), indent = 1),
                command(401, intArrayOf(102)), command(121, intArrayOf(2_000_000, 33, 0, 0), indent = 1),
                command(499), command(173)), listOf(closer), compatibilityVersion = 369)
            assertTrue(runtime.inspectCapabilities().supported)
            runtime.tick(); assertEquals(1, runtime.frame().dialog!!.selectedChoice)
            repeat(6) { runtime.tick() }
            assertNull(runtime.frame().dialog); assertEquals(0, runtime.snapshotState().system[7])
            return runtime
        }
        assertEquals(22, run(false).snapshotState().numbers[0])
        assertEquals(33, run(true).snapshotState().numbers[0])
    }

    @Test fun interactionRangeUsesTheNativeAsymmetricSquareFoot() {
        assertTrue(WolfCounterInteraction.inRange(1, 1, 0, 0, squareFoot = true))
        assertFalse(WolfCounterInteraction.inRange(1, -1, 0, 0, squareFoot = true))
        assertFalse(WolfCounterInteraction.inRange(0, 1, 0, 0, squareFoot = false))
        assertTrue(WolfCounterInteraction.inRange(0, -1, 0, 0, squareFoot = false, horizontalExtra = 1))
        assertTrue(WolfCounterInteraction.inRange(-3, 4, 2, 3, squareFoot = true))
        assertFalse(WolfCounterInteraction.inRange(-4, 4, 2, 3, squareFoot = true))
    }

    @Test fun directedPointRouteMakesOneNativeStepThenPlaysItsSeAndCompletesTheWaitingEvent() {
        val soundFields = listOf(WolfDatabaseField("file", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0),
            WolfDatabaseField("volume", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 100),
            WolfDatabaseField("pitch", WolfValueType.NUMBER, 1, 0, "", emptyList(), intArrayOf(), 100),
            WolfDatabaseField("loop", WolfValueType.NUMBER, 2, 0, "", emptyList(), intArrayOf(), 0))
        val blank = saveDatabase().getValue(WolfDatabaseKind.MUTABLE).types.single()
        val se = WolfDatabaseType(3, "SE", "", soundFields,
            listOf(WolfDatabaseRow(0, "footstep", intArrayOf(60, 125, 0), listOf("Sound/step.wav"))), 0, null)
        val databases = mapOf(WolfDatabaseKind.SYSTEM to WolfDatabase("System", WolfEncoding.UTF8, 1,
            List(3) { blank.copy(id = it) } + se))
        val route = WolfRoute(4, listOf(WolfRouteCommand(54, intArrayOf(4, 2)), WolfRouteCommand(46, intArrayOf(0))),
            byteArrayOf(3, 3, 3, 0, 3))
        val runtime = fixture(listOf(command(201, intArrayOf(-2)).copy(route = route),
            command(121, intArrayOf(2_000_000, 1, 0, 0)), command(173)), databases = databases, compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); runtime.tick()
        assertTrue("The route must move through frames instead of teleporting", runtime.snapshotState().x in 2f..2.999f)
        assertFalse(runtime.snapshotState().numbers.containsKey(0))
        repeat(80) { runtime.tick() }
        assertEquals(3f, runtime.snapshotState().x, 0f)
        assertEquals(2f, runtime.snapshotState().y, 0f)
        assertEquals(1, runtime.snapshotState().numbers[0])
        val sound = runtime.drainEffects().filterIsInstance<NativeEffect.Audio>().single { it.path == "Data/Sound/step.wav" }
        assertEquals(2, sound.channel); assertEquals(60, sound.volume); assertEquals(125, sound.pitch)
        assertNull(runtime.frame().diagnostic)
    }

    @Test fun actorApproachMakesOneStepAndSkipsTheExplicitMinusOneSentinelInWolf3() {
        for (profile in listOf(225, 369)) {
            val route = WolfRoute(6, listOf(WolfRouteCommand(53, intArrayOf(1)), WolfRouteCommand(53, intArrayOf(-1))), byteArrayOf(3,3,3,0,3))
            val commands = listOf(command(201, intArrayOf(-2)).copy(route = route), command(121, intArrayOf(2_000_000, 1, 0, 0)), command(173))
            val scene = map("approach", listOf(WolfEvent(0, "runner", 2, 2, listOf(page(commands))), WolfEvent(1, "target", 4, 2, listOf(page(emptyList(), trigger = 0)))))
            val runtime = fixture(emptyList(), initialMap = scene, compatibilityVersion = profile)
            assertTrue(runtime.inspectCapabilities().supported)
            repeat(40) { runtime.tick() }
            assertEquals(3f, runtime.snapshotState().x, 0f)
            assertEquals(2f, runtime.snapshotState().y, 0f)
            assertEquals(1, runtime.snapshotState().numbers[0])
        }
        val missing = fixture(listOf(command(201, intArrayOf(-2)).copy(route = WolfRoute(4, listOf(WolfRouteCommand(53, intArrayOf(-8)))))))
        assertTrue(rejectsRuntime { missing.tick() }.message!!.contains("Cible de route53 absente"))
    }

    @Test fun approachClearsOnlyTheMovingAxisPixelResidualAndKeepsTheInterpolatedPosition() {
        val route = WolfRoute(4, listOf(WolfRouteCommand(54, intArrayOf(4, 2))), byteArrayOf(3,3,3,0,3))
        val runtime = fixture(listOf(command(201, intArrayOf(-2)).copy(route = route), command(173)), compatibilityVersion = 369)
        val base = runtime.snapshotState()
        runtime.restoreState(base.copy(x = 2.25f, y = 2.125f, hero = base.hero!!.copy(x = 2.25f, y = 2.125f, targetX = 2.25f, targetY = 2.125f,
            pixelMotion = WolfCharacterPixelMotion.State(4, 4, 4, 2))))
        runtime.tick()
        val underway = runtime.snapshotState()
        assertEquals(2.25f, underway.x, 0f)
        assertEquals(0, underway.hero!!.pixelMotion!!.offsetX)
        assertEquals(2, underway.hero.pixelMotion!!.offsetY)
        repeat(30) { runtime.tick() }
        assertEquals(3f, runtime.snapshotState().x, 0f)
        assertEquals(2.125f, runtime.snapshotState().y, 0f)
    }

    @Test fun historicNativeMarkersContinueAndDirectionalModeOneUsesTheNormalReader() {
        val runtime = fixture(listOf(command(4, strings = listOf("Historic comment")), command(1001),
            command(123, intArrayOf(2_000_000, 0x100d8)), command(173)), compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.logicalKey(NativeKey.ACCEPT, true); runtime.tick(); runtime.logicalKey(NativeKey.ACCEPT, false)
        assertEquals(10, runtime.snapshotState().numbers[0])
        assertNull(runtime.frame().diagnostic)
        val unknown = fixture(listOf(command(1003, offset = 77)))
        assertFalse(unknown.inspectCapabilities().supported)
        assertEquals(77, rejectsRuntime { unknown.tick() }.offset)
    }

    @Test fun csvUserDatabaseImportResumesItsEventAndPersistsAcrossAFreshRuntime() {
        val data = saveDatabase().getValue(WolfDatabaseKind.MUTABLE)
        val db = mapOf(WolfDatabaseKind.USER to data)
        val reads = listOf(command(121, intArrayOf(2_000_000, 1_000_000_000, 0, 0)),
            command(122, intArrayOf(3_000_000, 1, 1_000_000_001)),
            command(250, intArrayOf(0, 0, -3, 0x1200, 3_000_001)), command(173))
        val runtime = fixture(listOf(command(251, intArrayOf(0, 0, 0, 0x200, 5), listOf("Data/text.csv"))) + reads,
            databases = db, compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        val request = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        assertEquals(5, request.operation); assertFalse(request.windowsTextMode); assertEquals("Data/text.csv", request.path)
        assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.completeFileOperation(request.requestId, "\ufeffheader\r\n42,\"new,text\",<<!--DATANAME--!>>language,\r\n")
        runtime.tick()
        assertEquals(42, runtime.snapshotState().numbers[0]); assertEquals("new,text", runtime.snapshotState().strings[0])
        assertEquals("language", runtime.snapshotState().strings[1])
        assertEquals("initial", data.types.single().string(0, 1))
        val restored = fixture(reads, databases = db, compatibilityVersion = 369)
        restored.restore(runtime.snapshot()); restored.tick()
        assertEquals(runtime.snapshotState().databaseChanges, restored.snapshotState().databaseChanges)
        assertEquals("new,text", restored.snapshotState().strings[0])
        val bad = restored.snapshotState().databaseChanges!!.copy(rows = mapOf(WolfDatabaseChanges.RowKey(WolfDatabaseKind.USER, 99) to 1))
        val before = restored.snapshot()
        try { restored.restoreState(restored.snapshotState().copy(databaseChanges = bad)); fail("Unknown type") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(before, restored.snapshot())
    }

    @Test fun csvExportUsesTheCurrentImportedValuesAndAbandonsLateCallbacksOnRestore() {
        val db = saveDatabase()
        val commands = listOf(command(251, intArrayOf(0, 0, 0, 0, 2), listOf("Data/import.csv")),
            command(251, intArrayOf(0, 0, 0, 0x1000, 2), listOf("Save/export.csv")), command(173))
        val runtime = fixture(commands, databases = db, compatibilityVersion = 369)
        val initial = runtime.snapshot()
        runtime.tick(); val incoming = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        runtime.completeFileOperation(incoming.requestId, "h\n11,\"a\"\"b\",<<!--DATANAME--!>>first,\n12,text,\n")
        runtime.tick(); val outgoing = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        assertEquals(6, outgoing.operation); assertFalse(outgoing.windowsTextMode)
        assertTrue(outgoing.text.contains("11,\"a\"\"b\",<<!--DATANAME--!>>first,"))
        assertEquals(2, runtime.snapshotState().mutableRows[0])
        runtime.restore(initial)
        assertFalse(runtime.completeDatabaseFile(outgoing.requestId, ""))
        assertNull(runtime.snapshotState().databaseChanges)
    }

    @Test fun absentOptionalCsvKeepsTheExistingDatabaseAndContinuesItsEvent() {
        val original = saveDatabase().getValue(WolfDatabaseKind.MUTABLE)
        val runtime = fixture(listOf(command(251, intArrayOf(0, 0, 0, 0x200, 1), listOf("Data/TCM_Other texts_.csv")),
            command(121, intArrayOf(2_000_000, 1_000_000_000, 0, 0)),
            command(122, intArrayOf(3_000_000, 1, 1_000_000_001)), command(173)),
            databases = mapOf(WolfDatabaseKind.USER to original), compatibilityVersion = 369)
        runtime.tick()
        val request = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        assertEquals("Data/TCM_Other texts_.csv", request.path)
        assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.completeFileOperation(request.requestId, "<NoFile>"); runtime.tick()
        assertEquals(7, runtime.snapshotState().numbers[0]); assertEquals("initial", runtime.snapshotState().strings[0])
        assertNull(runtime.snapshotState().databaseChanges); assertNull(runtime.frame().diagnostic)
        assertEquals(7, original.types.single().number(0, 0)); assertEquals("initial", original.types.single().string(0, 1))
    }

    @Test fun actionAcrossCounterExtendsOnlyOnceAndDirectEventWins() {
        val talk = listOf(command(121, intArrayOf(2_000_000, 7, 0, 0)), command(173))
        fun run(counter: Boolean, direct: Boolean = false, far: Boolean = false): Int? {
            val events = mutableListOf(WolfEvent(0, "behind counter", 2, if (far) 5 else 4, listOf(page(talk, trigger = 0))))
            if (direct) events.add(WolfEvent(1, "direct", 2, 3, listOf(page(listOf(command(121, intArrayOf(2_000_000, 9, 0, 0)), command(173)), trigger = 0))))
            val scene = map("counter", events)
            scene.tiles[(2 * scene.height + 3) * scene.width + 2] = 0
            if (far) scene.tiles[(2 * scene.height + 4) * scene.width + 2] = 0
            val tileset = WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64).also { it[1] = if (counter) 0x80 else 0 })
            val runtime = fixture(emptyList(), initialMap = scene, tileset = tileset)
            assertTrue(runtime.inspectCapabilities().supported)
            runtime.tick(); runtime.key(NativeKey.ACCEPT, true); runtime.tick(); runtime.key(NativeKey.ACCEPT, false); runtime.tick()
            return runtime.snapshotState().numbers[0]
        }
        assertNull(run(false)); assertEquals(7, run(true)); assertEquals(9, run(true, direct = true)); assertNull(run(true, far = true))
    }

    @Test fun pixelMotionKeepsLogicalHalfCellsResidualPixelsAndBlockedPositiveCorrection() {
        val initial = WolfCharacterPixelMotion.State(4, 4, 2, 1)
        val left = WolfCharacterPixelMotion.move(initial, -3, 0, 16, 136, 132) { _, _, _ -> true }
        assertEquals(3, left.state.halfX); assertEquals(7, left.state.offsetX); assertEquals(124, left.realXQuarter)
        assertEquals(-3, left.state.requestedX); assertEquals(-3, left.state.actualX); assertTrue(left.state.crossed)
        val blocked = WolfCharacterPixelMotion.move(initial, 3, 0, 16, 136, 132) { _, _, dir -> dir != 6 }
        assertEquals(4, blocked.state.halfX); assertEquals(0, blocked.state.offsetX); assertEquals(128, blocked.realXQuarter)
        assertEquals(3, blocked.state.requestedX); assertEquals(-2, blocked.state.actualX)
        val corner = WolfCharacterPixelMotion.move(initial, -4, 0, 16, 136, 132) { _, _, dir -> dir != 2 }
        assertEquals(4, corner.state.halfX); assertEquals(0, corner.state.offsetX); assertEquals(128, corner.realXQuarter)
        val crossed = WolfCharacterPixelMotion.move(initial, 20, -18, 16, 136, 132) { _, _, _ -> true }
        assertEquals(6, crossed.state.halfX); assertEquals(6, crossed.state.offsetX)
        assertEquals(1, crossed.state.halfY); assertEquals(7, crossed.state.offsetY)
        assertEquals(216, crossed.realXQuarter); assertEquals(60, crossed.realYQuarter)
    }

    @Test fun wolf3PixelCommandPreservesFacingAndPersistsItsResidualsAcrossRestart() {
        val runtime = fixture(listOf(command(290, intArrayOf(0x71, 0, -2, 0, -3, 5, 0)),
            command(121, intArrayOf(2_000_000, 9_180_002, 0, 0)), command(173)), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); val hero = runtime.snapshotState().hero!!
        assertEquals(1.8125f, hero.x, 0f); assertEquals(2.3125f, hero.y, 0f)
        assertEquals(3, hero.pixelMotion!!.halfX); assertEquals(5, hero.pixelMotion.offsetX)
        assertEquals(3, runtime.snapshotState().numbers[0]); assertEquals(2, hero.direction)
        val restored = fixture(emptyList(), compatibilityVersion = 372)
        restored.restore(runtime.snapshot()); assertEquals(hero.pixelMotion, restored.snapshotState().hero!!.pixelMotion)
        val before = restored.snapshot()
        try { restored.restoreState(restored.snapshotState().copy(hero = hero.copy(pixelMotion = hero.pixelMotion.copy(offsetX = 16)))); fail("Invalid pixel state") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, restored.snapshot())
        val old = fixture(listOf(command(290, intArrayOf(0x71, 0, -2, 0, 1, 0, 0))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported)
    }

    @Test fun speechMacrosCorrectVolumeReturnImmediatelyAndIgnoreOldUtteranceCallbacks() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_102, 50, 0, 0)),
            command(122, intArrayOf(3_000_000, 0x40200), listOf("<<MS_VOICE_PLAY/SPEED=1/VOL=80/VOICE=Microsoft Haruka Desktop - Japanese>>こんにちは")),
            command(122, intArrayOf(3_000_001, 0x40200), listOf("<<GET_MS_VOICE_PLAYING>>")), command(180, intArrayOf(1)),
            command(122, intArrayOf(3_000_002, 0x40200), listOf("<<MS_VOICE_PLAY/ABS/VOL=80>>Second")),
            command(122, intArrayOf(3_000_003, 0x40200), listOf("<<GET_MS_VOICE_LIST>>")),
            command(122, intArrayOf(3_000_004, 0x40200), listOf("<<GET_MS_VOICE_PLAYING>>")), command(173)), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); val first = runtime.drainEffects().filterIsInstance<NativeEffect.Speech>().single()
        assertEquals(40, first.volume); assertEquals(1, first.speed); assertEquals("こんにちは", first.text)
        assertEquals("1", runtime.snapshotState().strings[0]); assertEquals("1", runtime.snapshotState().strings[1])
        runtime.tick(); runtime.tick(); val next = runtime.drainEffects()
        val second = next.filterIsInstance<NativeEffect.Speech>().single(); assertEquals(80, second.volume)
        runtime.speechPlaying(first.requestId, false)
        runtime.completeSpeechVoices(next.filterIsInstance<NativeEffect.SpeechVoices>().single().requestId, listOf("Android Japanese", "Android English"))
        runtime.tick(); assertEquals("Android Japanese\nAndroid English", runtime.snapshotState().strings[3])
        assertEquals("1", runtime.snapshotState().strings[4])
        runtime.restore(runtime.snapshot()); assertTrue(runtime.drainEffects().contains(NativeEffect.StopSpeech))
        val old = fixture(listOf(command(122, intArrayOf(3_000_000, 0x40200), listOf("<<MS_VOICE_PLAY>>x"))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported)
    }

    @Test fun authoredGameRunsTitleNewGameDialogMovementAndTeleport() {
        val title = listOf(
            command(150, intArrayOf(0, 1, 0, 0, 0, 1, 255, 0, 0, 100, 0), listOf("Picture/title.png")),
            command(102, intArrayOf(19), listOf("New game", "Quit")),
            command(401, intArrayOf(2)),
            command(150, intArrayOf(2, 1, 0), indent = 1),
            command(130, intArrayOf(-1, 1, 1, 1, 32), indent = 1),
            command(101, strings = listOf("Bienvenue !"), indent = 1),
            command(173, indent = 1),
            command(401, intArrayOf(3)), command(175, indent = 1), command(499),
        )
        val runtime = fixture(title, extraMaps = mapOf(1 to map("field", emptyList(), width = 8, height = 6)))
        runtime.tick()
        assertEquals("Data/Picture/title.png", runtime.frame().pictures.single().path)
        assertEquals(listOf("New game", "Quit"), runtime.frame().dialog!!.choices)
        runtime.choose(0); runtime.tick()
        assertEquals(1, runtime.mapId); assertTrue(runtime.frame().pictures.isEmpty())
        assertEquals("Bienvenue !", runtime.frame().dialog!!.text)
        runtime.key(NativeKey.ACCEPT, true); runtime.tick(); runtime.key(NativeKey.ACCEPT, false)
        assertNull(runtime.frame().dialog)
        val before = runtime.snapshotState().x
        runtime.key(NativeKey.RIGHT, true); repeat(12) { runtime.tick() }; runtime.key(NativeKey.RIGHT, false)
        assertTrue(runtime.snapshotState().x > before)
    }

    @Test fun waitsYieldWithoutBlockingAndUseLogicalTicks() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 1, 0, 0)), command(180, intArrayOf(2)),
            command(121, intArrayOf(2_000_000, 2, 0, 0)), command(173)))
        runtime.tick(); assertEquals(1, runtime.snapshotState().numbers[0])
        runtime.tick(); runtime.tick(); assertEquals(1, runtime.snapshotState().numbers[0])
        runtime.tick(); assertEquals(2, runtime.snapshotState().numbers[0])
    }

    @Test fun nestedConditionsLoopsCommonCallsAndReturnAreExecuted() {
        val common = common(0, listOf(command(121, intArrayOf(1_600_003, 1_600_000, 2, 0)), command(172)), returnValue = 3)
        val commands = listOf(command(121, intArrayOf(2_000_000, 0, 0, 0)), command(179, intArrayOf(3)),
            command(121, intArrayOf(2_000_000, 1, 0, 256), indent = 1), command(498),
            command(111, intArrayOf(1, 2_000_000, 3, 2)), command(401, intArrayOf(1)),
            command(210, intArrayOf(500_000, 0x01000001, 2_000_000, 2_000_001), indent = 1),
            command(420), command(121, intArrayOf(2_000_001, -1, 0, 0), indent = 1), command(499), command(173))
        val runtime = fixture(commands, listOf(common))
        runtime.tick()
        assertEquals(3, runtime.snapshotState().numbers[0])
        assertEquals(5, runtime.snapshotState().numbers[1])
    }

    @Test fun commonReturnUsesItsCommonSelfIndexAndDisabledReturnsPreserveTheCaller() {
        val returning = common(0, listOf(command(121, intArrayOf(1_600_011, 37, 0, 0))), returnValue = 11)
        val disabled = common(1, listOf(command(121, intArrayOf(1_600_011, 99, 0, 0))), returnValue = -1)
        val runtime = fixture(listOf(command(210, intArrayOf(500_000, 0x01000000, 2_000_000)),
            command(210, intArrayOf(500_001, 0x01000000, 2_000_000)), command(173)), listOf(returning, disabled))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); assertEquals(37, runtime.snapshotState().numbers[0])
        assertEquals(37, runtime.snapshotState().common[0 to 11]); assertEquals(99, runtime.snapshotState().common[1 to 11])
    }

    @Test fun wolf3HiddenPathMacrosUseFullTilePassageAndMovingActorFootprints() {
        val obstacle = WolfEvent(1, "obstacle", 3, 3, listOf(page(emptyList(), trigger = 0).copy(graphicFile = "Event.png", flags = 32)))
        val commands = listOf(
            command(122, intArrayOf(3_000_000, 0x40200), listOf("<<FIND_PATH_DIR8/v2/SX=2/SY=2/EX=4/EY=4/LEN=1/EXE=20000>>")),
            command(122, intArrayOf(3_000_001, 0x40200), listOf("<<FIND_PATH_DIR8/v2/SX=2/SY=2/EX=4/EY=4/LEN=1/EXE=20000/MOVE_EV=-2>>")), command(173))
        val runtime = fixture(commands, compatibilityVersion = 372,
            initialMap = map("path", listOf(WolfEvent(0, "driver", 1, 1, listOf(page(commands))), obstacle)))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick(); val strings = runtime.snapshotState().strings
        assertEquals("3", strings[0]); assertNotEquals("3", strings[1]); assertNotEquals("<<NoPath>>", strings[1])
    }

    @Test fun nativeKeyRepeatUsesFrameCountersInitialDelayAndScaledInterval() {
        val repeat = WolfKeyRepeats()
        val accept = mutableListOf<Int>(); val directions = mutableListOf<Int>()
        for (frame in 1..12) {
            repeat.tick(true, false, 2)
            if (repeat.pulse(0, 4, 3, 1)) accept += frame
            if (repeat.pulse(2, 4, 3, 1)) directions += frame
        }
        assertEquals(listOf(4, 7, 10), accept); assertEquals(listOf(5, 8, 11), directions)
        repeat.reset(); val at30fps = mutableListOf<Int>()
        for (frame in 1..6) { repeat.tick(true, false, 2); if (repeat.pulse(0, 7, 5, 2)) at30fps += frame }
        assertEquals(listOf(3, 5), at30fps)
        repeat.tick(false, false, 0); repeat.tick(true, false, 4)
        assertFalse(repeat.pulse(0, 4, 3, 1)); assertFalse(repeat.pulse(2, 4, 3, 1))
    }

    @Test fun enabledAcceptAutoRepeatCanCompleteAnotherWaitingInputAndReleaseClearsItsState() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_015, 4, 0, 0)),
            command(121, intArrayOf(9_000_016, 3, 0, 0)), command(121, intArrayOf(9_000_059, 1, 0, 0)),
            command(123, intArrayOf(2_000_000, 0x90)), command(180, intArrayOf(2)),
            command(123, intArrayOf(2_000_001, 0x90)), command(173)))
        runtime.tick(); runtime.logicalKey(NativeKey.ACCEPT, true); runtime.tick()
        assertEquals(10, runtime.snapshotState().numbers[0]); assertNull(runtime.snapshotState().numbers[1])
        // The second waiting command installs its wait at the first repeat frame;
        // its reader receives the following repeat, not the edge of its own entry.
        repeat(3) { runtime.tick() }; assertNull(runtime.snapshotState().numbers[1])
        repeat(3) { runtime.tick() }; assertEquals(10, runtime.snapshotState().numbers[1])
        runtime.releaseInput()
        val variables = WolfVariables(emptyMap()) { _, _ -> null }
        variables.write(9_000_015, -20, EventAddress(0)); variables.write(9_000_016, 0, EventAddress(0))
        assertEquals(1, variables.read(9_000_015, EventAddress(0))); assertEquals(1, variables.read(9_000_016, EventAddress(0)))
    }

    @Test fun wolf3TeleportSixthArgumentChangesFacingAndZeroRetainsItsDirection() {
        val runtime = fixture(listOf(command(130, intArrayOf(-1, 3, 3, 0, 0, 8)),
            command(130, intArrayOf(-1, 4, 3, 0, 0, 0)), command(173)), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported); runtime.tick()
        assertEquals(4f, runtime.snapshotState().x); assertEquals(8, runtime.snapshotState().direction)
        assertFalse(fixture(listOf(command(130, intArrayOf(-1, 3, 3, 0, 0, 8))), compatibilityVersion = 225).inspectCapabilities().supported)
    }

    @Test fun inactiveConditionalPageFallsBackToEarlierPage() {
        val inactive = page(listOf(command(101, strings = listOf("bad"))), index = 1,
            conditions = ByteArray(37).also { it[0] = 1; it[1] = 0x21; putInt(it, 5, 2_000_000); putInt(it, 21, 1) })
        val event = WolfEvent(0, "page", 2, 2, listOf(page(listOf(command(101, strings = listOf("good")))), inactive))
        val runtime = fixture(emptyList(), initialMap = map("title", listOf(event)))
        runtime.tick(); assertEquals("good", runtime.frame().dialog!!.text)
    }

    @Test fun selfCommonNormalAndSystemVariablesRemainSeparate() {
        val vars = WolfVariables(emptyMap()) { _, _ -> null }
        val first = EventAddress(1, 5, 7); val second = EventAddress(2, 5, 8)
        vars.write(1_100_001, 11, first); vars.write(1_600_001, 21, first); vars.write(2_000_001, 31, first); vars.write(9_000_007, 41, first)
        assertEquals(11, vars.read(1_000_051, first)); assertEquals(0, vars.read(1_100_001, second))
        assertEquals(21, vars.read(1_600_001, first)); assertEquals(0, vars.read(1_600_001, second))
        assertEquals(31, vars.read(2_000_001, second)); assertEquals(41, vars.read(9_000_007, second))
        vars.writeString(3_000_000, "82g1A", first); assertEquals(82, vars.read(3_000_000, second))
    }

    @Test fun integerFloatIndirectAndClampAssignmentModesHaveDefinedResults() {
        val runtime = fixture(listOf(
            command(121, intArrayOf(2_000_000, 100, 0, 0)),
            command(121, intArrayOf(2_000_000, 3, 2, 0x3302)),
            command(121, intArrayOf(2_000_003, 2_000_000, 0, 0)),
            command(121, intArrayOf(2_000_001, 2_000_000, 0, 4)),
            command(121, intArrayOf(2_000_001, 7, 0, 16)),
            command(121, intArrayOf(2_000_002, 1_500_000, 0, 5)), command(173)))
        runtime.tick()
        val numbers = runtime.snapshotState().numbers
        assertEquals(150, numbers[3]); assertEquals(2_000_000, numbers[1]); assertEquals(999_999, numbers[2])
        assertEquals(7, numbers[0]) // V1 contains the literal value code for V0.
    }

    @Test fun divisionAndModuloZeroUseOneAndArithmeticIsDeterministic() {
        assertEquals(5L, WolfVariables.calculate(5, 0, 3) { _, _ -> 0 })
        assertEquals(0L, WolfVariables.calculate(5, 0, 4) { _, _ -> 0 })
        assertEquals(-2L, WolfVariables.calculate(-5, 2, 3) { _, _ -> 0 })
        assertEquals(7L, WolfVariables.calculate(9, 5, 6) { low, high -> assertEquals(5, low); assertEquals(9, high); 7 })
    }

    @Test fun unsupportedCommandFailsWithMapEventOffsetAndPreflightCatchesIt() {
        val runtime = fixture(listOf(command(1000, offset = 0x41)))
        assertFalse(runtime.inspectCapabilities().supported)
        val failure = rejectsRuntime { runtime.tick() }
        assertEquals(1000, failure.opcode); assertEquals(0x41, failure.offset); assertEquals(0, failure.eventId)
        assertNotNull(runtime.frame().diagnostic); assertTrue(runtime.drainEffects().single() is NativeEffect.Diagnostic)
    }

    @Test fun infiniteLoopProducesBoundedDiagnostic() {
        val runtime = fixture(listOf(command(170), command(498)))
        val failure = rejectsRuntime { runtime.tick() }
        assertTrue(failure.message!!.contains("50 000"))
    }

    @Test fun unknownVariableAndTextEscapesDoNotSilentlyBecomeZeroOrEmpty() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 7_000_000, 0, 0))))
        assertTrue(rejectsRuntime { runtime.tick() }.message!!.contains("variable"))
        val textRuntime = fixture(listOf(command(101, strings = listOf("\\unknown[17]"))))
        assertTrue(rejectsRuntime { textRuntime.tick() }.message!!.contains("texte"))
    }

    @Test fun saveLoadRestoresWorldVariablesWithoutInProgressEvents() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 7, 0, 0)), command(101, strings = listOf("Saving"))))
        runtime.tick(); val saved = runtime.snapshot()
        runtime.restore(saved)
        assertEquals(7, runtime.snapshotState().numbers[0]); assertNull(runtime.frame().dialog)
        assertArrayEquals(saved, runtime.snapshot())
        val corrupt = saved.clone().also { it[20] = (it[20].toInt() xor 1).toByte() }
        try { runtime.restore(corrupt); fail("Checksum accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(7, runtime.snapshotState().numbers[0])
    }

    @Test fun movementRespectsTileCollisionAndTouchTriggers() {
        val tiles = IntArray(6 * 6 * 3) { 100000 }
        tiles[2 * 6 + 3] = 0
        val trigger = WolfEvent(1, "touch", 2, 3, listOf(page(listOf(command(101, strings = listOf("touch"))), trigger = 3)))
        val map = map("field", listOf(trigger), width = 6, height = 6).copy(tiles = tiles)
        val blocked = WolfTileset(0, "", "", List(15) { "" }, ByteArray(17), IntArray(17).also { it[16] = 15 })
        val runtime = fixture(emptyList(), initialMap = map, tileset = blocked)
        runtime.key(NativeKey.RIGHT, true); repeat(20) { runtime.tick() }; runtime.key(NativeKey.RIGHT, false)
        assertEquals(2f, runtime.snapshotState().x, .01f)
        runtime.key(NativeKey.DOWN, true); repeat(20) { if (runtime.frame().dialog == null) runtime.tick() }; runtime.key(NativeKey.DOWN, false)
        assertEquals("touch", runtime.frame().dialog!!.text)
    }

    @Test fun independentSessionsAndForceClosedReloadHaveNoSharedState() {
        val a = fixture(listOf(command(121, intArrayOf(2_000_000, 5, 0, 0)), command(180, intArrayOf(999_999))))
        a.tick(); val b = fixture(listOf(command(101, strings = listOf("Fresh"))))
        b.tick(); assertEquals("Fresh", b.frame().dialog!!.text); assertTrue(b.snapshotState().numbers.isEmpty())
    }

    @Test fun actualOfficialSamplesReachNewGameDialogueMovementAndRestartWithNativeState() {
        listOf("sample22961/WOLF_RPG_Editor2", "sample3729/WOLF_RPG_Editor3").forEach { folder ->
            val root = File(System.getProperty("wolf.fixtureRoot"), folder)
            assumeTrue(File(root, "Data/BasicData/Game.dat").isFile)
            val source = object : WolfAssetSource {
                override fun read(path: String) = File(root, path).readBytes()
                override fun list(path: String) = File(root, path).list()?.toList().orEmpty()
                override fun exists(path: String) = File(root, path).exists()
            }
            val runtime = WolfRuntime.load(source)
            val capabilities = runtime.inspectCapabilities(includeAllMaps = true)
            assertTrue("$folder: ${capabilities.issues}", capabilities.supported)
            fun tick() { runtime.tick(); runtime.drainEffects().forEach { effect -> when (effect) {
                is NativeEffect.ReadSaveValue -> runtime.completeSaveValue(effect.requestId, false)
                is NativeEffect.WriteSaveValue -> runtime.completeSaveWrite(effect.requestId, true)
                is NativeEffect.Audio, is NativeEffect.AudioGain -> Unit // JVM tests prove requests; the SDK tests prove actual playback.
                else -> fail("Unexpected host request before the introductory tutorial: $effect")
            } } }
            repeat(40) { if (runtime.frame().dialog?.choices?.isEmpty() != false) tick() }
            assertEquals(0, runtime.mapId)
            assertEquals(3, runtime.frame().dialog!!.choices.size)
            assertTrue(runtime.frame().pictures.any { it.path == "Data/SystemFile/TitleGraphic.png" && it.opacity > 0 })
            runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false); tick()
            repeat(150) { if (runtime.mapId == 0 || runtime.frame().dialog?.text.isNullOrEmpty()) tick() }
            assertEquals(1, runtime.mapId)
            assertTrue(runtime.frame().dialog!!.text.isNotBlank())
            repeat(16) { tick() }
            assertTrue("$folder uses its own Common63 window", runtime.frame().pictures.any { it.id == 19500 && it.opacity > 0 })
            val messages = linkedSetOf<String>()
            repeat(480) { index ->
                runtime.frame().dialog?.let { messages.add(it.text) }
                if (index % 8 == 0 && runtime.frame().dialog != null) { runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false) }
                tick()
            }
            assertTrue("$folder introductory dialogue advances through several commands", messages.size >= 2)
            assertNull(runtime.frame().dialog)
            val before = runtime.snapshotState().x
            runtime.key(NativeKey.LEFT, true); repeat(18) { tick() }; runtime.key(NativeKey.LEFT, false)
            assertTrue("$folder player moves on its real map", runtime.snapshotState().x < before)
            val state = runtime.snapshotState(); val snapshot = runtime.snapshot()
            val restarted = WolfRuntime.load(source)
            restarted.restore(snapshot)
            val restored = restarted.snapshotState()
            assertEquals(state.mapId, restored.mapId); assertEquals(state.x, restored.x, .0001f); assertEquals(state.y, restored.y, .0001f)
            assertEquals(state.numbers, restored.numbers); assertEquals(state.strings, restored.strings)
            assertEquals(state.mutableNumbers, restored.mutableNumbers); assertEquals(state.mutableStrings, restored.mutableStrings)
            assertEquals(state.companions.map { listOf(it.id, it.x, it.y, it.path, it.direction, it.flags) }, restored.companions.map { listOf(it.id, it.x, it.y, it.path, it.direction, it.flags) })
            assertEquals(state.actors.map { listOf(it.id, it.x, it.y, it.path, it.direction, it.flags, it.routeIndex) }, restored.actors.map { listOf(it.id, it.x, it.y, it.path, it.direction, it.flags, it.routeIndex) })
            assertEquals(state.actors.map { actor -> actor.route?.commands?.map { it.opcode to it.args.toList() } }, restored.actors.map { actor -> actor.route?.commands?.map { it.opcode to it.args.toList() } })
            assertNull(restarted.frame().diagnostic)
            println("$folder: actual native title, new game, ${messages.size} messages, movement and restart passed")
        }
    }

    @Test fun bitMaskConditionRequiresEveryRequestedBitIncludingEmptyMask() {
        assertFalse(WolfVariables.compare(5, 3, 6))
        assertTrue(WolfVariables.compare(7, 3, 6))
        assertTrue(WolfVariables.compare(0, 0, 6))
    }

    @Test fun physicalKeyboardCodesControllerButtonsAndAutomaticInputUseDxLibCodes() {
        val runtime = fixture(listOf(command(123, intArrayOf(2_000_000, 0x101)),
            command(123, intArrayOf(2_000_001, 0x1100, 128)),
            command(123, intArrayOf(2_000_002, 0x200)), command(173)))
        runtime.physicalKey(46, true); runtime.physicalKey(28, true); runtime.controllerButton(303, true)
        runtime.tick()
        assertEquals(146, runtime.snapshotState().numbers[0])
        assertEquals(128, runtime.snapshotState().numbers[1])
        assertEquals(303, runtime.snapshotState().numbers[2])
        val injected = fixture(listOf(command(125, intArrayOf(0x10000000, 144)), command(180, intArrayOf(0)),
            command(123, intArrayOf(2_000_000, 0x1100, 144)), command(173)))
        injected.tick(); assertNull(injected.snapshotState().numbers[0])
        injected.tick(); assertEquals(144, injected.snapshotState().numbers[0])
        injected.releaseInput(); assertNull(injected.frame().diagnostic)
    }

    @Test fun physicalWaitingReaderDoesNotAcceptTheKeyThatWasAlreadyHeld() {
        val runtime = fixture(listOf(command(123, intArrayOf(2_000_000, 0x180)), command(173)))
        runtime.physicalKey(46, true); runtime.tick(); runtime.tick()
        assertNull(runtime.snapshotState().numbers[0])
        runtime.physicalKey(46, false); runtime.tick()
        runtime.physicalKey(44, true); runtime.tick()
        assertEquals(144, runtime.snapshotState().numbers[0])
    }

    @Test fun waitingLogicalReaderYieldsBeforeALabelCanReadTheSamePressAgain() {
        // The real third-corpus language selector jumps to Cursor then waits on0x10091.
        val runtime = fixture(listOf(command(212, strings = listOf("Cursor")),
            command(123, intArrayOf(2_000_001, 0x10091)),
            command(121, intArrayOf(2_000_000, 1, 0, 0x100)),
            command(213, strings = listOf("Cursor"))), compatibilityVersion = 369)
        runtime.tick()
        runtime.key(NativeKey.DOWN, true); runtime.key(NativeKey.DOWN, false); runtime.tick()
        assertEquals(1, runtime.snapshotState().numbers[0])
        runtime.tick(); assertEquals(1, runtime.snapshotState().numbers[0])
        runtime.key(NativeKey.DOWN, true); runtime.key(NativeKey.DOWN, false); runtime.tick()
        assertEquals(2, runtime.snapshotState().numbers[0]); assertNull(runtime.frame().diagnostic)
    }

    @Test fun devicePermissionMasksBlockOnlySelectedPhysicalDevicesAndPersistOnRestart() {
        val runtime = fixture(listOf(command(126, intArrayOf(0x10000100, 146)),
            command(123, intArrayOf(2_000_000, 0x1100, 146)), command(123, intArrayOf(2_000_001, 0x1100, 144)),
            command(126, intArrayOf(0x10000102)), command(123, intArrayOf(2_000_002, 0x200)), command(173)))
        runtime.physicalKey(46, true); runtime.physicalKey(44, true); runtime.controllerButton(301, true); runtime.tick()
        assertEquals(0, runtime.snapshotState().numbers[0]); assertEquals(144, runtime.snapshotState().numbers[1]); assertEquals(0, runtime.snapshotState().numbers[2])
        val snapshot = runtime.snapshot(); runtime.restore(snapshot)
        assertEquals(1, runtime.snapshotState().system[-2_000_046]); assertEquals(1, runtime.snapshotState().system[-2_100_000])
    }

    @Test fun titleResetDropsEveryForcedRouteAndAllCharacterTraits() {
        val route = WolfRoute(0, listOf(WolfRouteCommand(58, intArrayOf(20))), byteArrayOf(3, 6, 3, 0, 3))
        val runtime = fixture(listOf(command(201, intArrayOf(-2)).copy(route = route), command(174)))
        runtime.tick()
        val hero = runtime.snapshotState().hero!!
        assertEquals(3, hero.speed); assertEquals(2, hero.flags); assertFalse(hero.routeForced)
        assertNull(hero.route); assertNull(hero.savedRoute); assertEquals(-1, hero.routeCommon); assertEquals(0, hero.elevation)
    }

    @Test fun continueConsumesTheCountOfTheInnermostFiniteLoopAndLastReservationWins() {
        val runtime = fixture(listOf(command(179, intArrayOf(3)),
            command(121, intArrayOf(2_000_000, 1, 0, 0x100), indent = 1),
            command(176, indent = 1), command(121, intArrayOf(2_000_000, 100, 0, 0x100), indent = 1),
            command(498), command(211, intArrayOf(500000, 0)), command(211, intArrayOf(500001, 0)), command(173)),
            commons = listOf(common(0, listOf(command(121, intArrayOf(2_000_001, 1, 0, 0x100)))),
                common(1, listOf(command(121, intArrayOf(2_000_001, 10, 0, 0x100))))))
        runtime.tick(); assertEquals(3, runtime.snapshotState().numbers[0])
        runtime.tick(); runtime.tick(); assertEquals(10, runtime.snapshotState().numbers[1])
    }

    @Test fun stringSourceAssignmentConcatenationLineCutReplacementAndIndirectTargetsAreDistinct() {
        val runtime = fixture(listOf(
            command(122, intArrayOf(3_000_000, 0), listOf("first\nsecond")),
            command(122, intArrayOf(3_000_001, 0x201, 3_000_000)),
            command(122, intArrayOf(3_000_002, 0x301, 3_000_000)),
            command(122, intArrayOf(3_000_002, 0x100), listOf("!")),
            command(122, intArrayOf(3_000_002, 0x900), listOf("first", "entry")),
            command(122, intArrayOf(3_000_002, 0x800), listOf("!")),
            command(121, intArrayOf(2_000_000, 3_000_000, 0, 4)),
            command(121, intArrayOf(2_000_001, 3_000_003, 0, 4)),
            command(122, intArrayOf(2_000_001, 0x12, 2_000_000)),
            command(122, intArrayOf(2_000_005, 0), listOf("42 suffix")), command(173)))
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals("second", state.strings[0]); assertEquals("first", state.strings[1]); assertEquals("entry", state.strings[2]); assertEquals("second", state.strings[3])
        assertEquals(42, state.numbers[5])
    }

    @Test fun nativeKeyboardInputWaitsForCompletionAndIgnoresAnAbandonedRequest() {
        val runtime = fixture(listOf(command(122, intArrayOf(3_000_000, 0), listOf("prior")),
            command(122, intArrayOf(3_000_000, 0x3003, 10)), command(121, intArrayOf(2_000_000, 9_000_039, 0, 0)), command(173)))
        runtime.tick()
        val request = runtime.drainEffects().single() as NativeEffect.TextInput
        assertEquals("prior", request.initialText); assertEquals(10, request.maxLength); assertTrue(request.cancellable)
        runtime.tick(); assertEquals(2L, runtime.ticks)
        runtime.completeTextInput(request.requestId, "新しい名前"); runtime.tick()
        assertEquals("新しい名前", runtime.snapshotState().strings[0]); assertEquals(0, runtime.snapshotState().numbers[0])
        val cancelled = fixture(listOf(command(122, intArrayOf(3_000_000, 0), listOf("kept")),
            command(122, intArrayOf(3_000_000, 0x1003, 10)), command(121, intArrayOf(2_000_000, 9_000_039, 0, 0)), command(173)))
        cancelled.tick(); val cancelRequest = cancelled.drainEffects().single() as NativeEffect.TextInput
        cancelled.completeTextInput(cancelRequest.requestId, null); cancelled.tick()
        assertEquals("kept", cancelled.snapshotState().strings[0]); assertEquals(-1, cancelled.snapshotState().numbers[0])
        cancelled.restore(cancelled.snapshot()); cancelled.completeTextInput(cancelRequest.requestId, "late")
        assertEquals("kept", cancelled.snapshotState().strings[0])
    }

    @Test fun heroControlBansDoNotBlockTheMessageReaderAndSelectedPermissionsRemainSeparate() {
        val runtime = fixture(listOf(command(126, intArrayOf(0x101)), command(101, strings = listOf("one")),
            command(126, intArrayOf(0x301)), command(101, strings = listOf("two")), command(173)))
        runtime.tick(); runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false); runtime.tick()
        assertEquals("two", runtime.frame().dialog!!.text)
        runtime.key(NativeKey.ACCEPT, true); runtime.key(NativeKey.ACCEPT, false); runtime.tick()
        assertEquals("two", runtime.frame().dialog!!.text)
        assertEquals(setOf(NativeKey.ACCEPT), runtime.snapshotState().movementBannedKeys)
        assertEquals(setOf(NativeKey.ACCEPT), runtime.snapshotState().bannedKeys)
        runtime.key(NativeKey.BACK, true); runtime.key(NativeKey.BACK, false); runtime.tick()
        assertNull(runtime.frame().dialog)
    }

    @Test fun textInputAllowsParallelEventsButFileIoCompletionDoesNotFinishTextEditing() {
        val typer = common(0, listOf(command(122, intArrayOf(3_000_000, 3, 30)),
            command(121, intArrayOf(2_000_000, 99, 0, 0)), command(180, intArrayOf(9999)))).copy(conditionWord = 3)
        val saver = common(1, listOf(command(220, intArrayOf(0, 0)), command(180, intArrayOf(9999)))).copy(conditionWord = 3)
        val observer = common(2, listOf(command(121, intArrayOf(2_000_001, 7, 0, 0)), command(180, intArrayOf(9999)))).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(typer, saver, observer))
        runtime.tick()
        val requests = runtime.drainEffects()
        val typing = requests.filterIsInstance<NativeEffect.TextInput>().single()
        assertEquals(1, requests.filterIsInstance<NativeEffect.Save>().size)
        assertFalse(runtime.snapshotState().numbers.containsKey(1)) // File instructions still block later events in this tick.
        runtime.completeHostRequest(true); runtime.tick()
        assertEquals(7, runtime.snapshotState().numbers[1]); assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.tick(); assertEquals(2L, runtime.ticks)
        runtime.completeTextInput(typing.requestId, "Typed while the map is running"); runtime.tick()
        assertEquals(99, runtime.snapshotState().numbers[0]); assertEquals("Typed while the map is running", runtime.snapshotState().strings[0])
    }

    @Test fun automaticEnterConfirmsTheHostsEditedTextWithoutReplacingItWithTheInitialValue() {
        val typer = common(0, listOf(command(122, intArrayOf(3_000_000, 3, 30)),
            command(121, intArrayOf(2_000_000, 9_000_039, 0, 0)), command(180, intArrayOf(9999)))).copy(conditionWord = 3)
        val timer = common(1, listOf(command(180, intArrayOf(2)), command(125, intArrayOf(0x10000000, 128)),
            command(180, intArrayOf(9999)))).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(typer, timer))
        runtime.tick(); val typing = runtime.drainEffects().filterIsInstance<NativeEffect.TextInput>().single()
        val requests = mutableListOf<NativeEffect>()
        repeat(8) { runtime.tick(); requests += runtime.drainEffects() }
        val confirm = requests.filterIsInstance<NativeEffect.ConfirmTextInput>().single()
        assertEquals(typing.requestId, confirm.requestId)
        assertFalse(runtime.snapshotState().numbers.containsKey(0))
        runtime.completeTextInput(confirm.requestId, "Current editor text"); runtime.tick()
        assertEquals("Current editor text", runtime.snapshotState().strings[0]); assertEquals(0, runtime.snapshotState().numbers[0])
    }

    @Test fun asynchronousSaveReadPreservesSameTickOrderingBeforeLaterParallelEvents() {
        val reader = common(0, listOf(command(221, intArrayOf(2_000_000, 0, 2_000_004)),
            command(121, intArrayOf(1_600_000, 7, 0, 0)), command(180, intArrayOf(10)))).copy(conditionWord = 3)
        val observer = common(1, listOf(command(121, intArrayOf(2_000_001, 15_000_000, 0, 0)), command(180, intArrayOf(10)))).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(reader, observer))
        runtime.tick(); val request = runtime.drainEffects().single() as NativeEffect.ReadSaveValue
        assertFalse(runtime.snapshotState().numbers.containsKey(1))
        runtime.tick(); assertEquals(1L, runtime.ticks)
        runtime.completeSaveValue(request.requestId, true, number = 9); runtime.tick()
        assertEquals(1L, runtime.ticks); assertEquals(9, runtime.snapshotState().numbers[0]); assertEquals(7, runtime.snapshotState().numbers[1])
    }

    @Test fun shortDialogPulseDuringAsyncFileReadIsConsumedOnTheNextWorldTickExactlyOnce() {
        fun run(cancel: Boolean): WolfRuntime {
            val reader = common(0, listOf(command(180, intArrayOf(2)), command(221, intArrayOf(2_000_004, 0, 2_000_004)),
                command(180, intArrayOf(100)))).copy(conditionWord = 3)
            val runtime = fixture(listOf(command(101, strings = listOf("First")), command(101, strings = listOf("Second")), command(173)), listOf(reader))
            runtime.tick(); assertEquals("First", runtime.frame().dialog!!.text)
            var request: NativeEffect.ReadSaveValue? = null
            repeat(4) { if (request == null) { runtime.tick(); request = runtime.drainEffects().filterIsInstance<NativeEffect.ReadSaveValue>().singleOrNull() } }
            assertNotNull(request)
            runtime.logicalKey(NativeKey.ACCEPT, true); runtime.logicalKey(NativeKey.ACCEPT, false)
            if (cancel) runtime.releaseInput()
            runtime.completeSaveValue(request!!.requestId, false)
            runtime.tick(); assertEquals("First", runtime.frame().dialog!!.text) // Finish the suspended frame.
            runtime.tick()
            assertEquals(if (cancel) "First" else "Second", runtime.frame().dialog!!.text)
            runtime.tick(); assertEquals(if (cancel) "First" else "Second", runtime.frame().dialog!!.text)
            return runtime
        }
        assertNull(run(false).frame().diagnostic); assertNull(run(true).frame().diagnostic)
    }

    @Test fun physicalAndMouseEdgesDuringAsyncReadReachTheirWaitingReaderAfterRelease() {
        listOf(0x180 to 144, 0x280 to 303, 0x390 to 20).forEach { (flags, expected) ->
            val reader = common(0, listOf(command(180, intArrayOf(2)), command(221, intArrayOf(2_000_004, 0, 2_000_004)),
                command(180, intArrayOf(100)))).copy(conditionWord = 3)
            val runtime = fixture(listOf(command(123, intArrayOf(2_000_000, flags)), command(173)), listOf(reader), compatibilityVersion = 369)
            runtime.tick()
            var request: NativeEffect.ReadSaveValue? = null
            repeat(4) { if (request == null) { runtime.tick(); request = runtime.drainEffects().filterIsInstance<NativeEffect.ReadSaveValue>().singleOrNull() } }
            assertNotNull(request)
            when (expected) {
                144 -> { runtime.physicalKey(44, true); runtime.physicalKey(44, false) }
                303 -> { runtime.controllerButton(303, true); runtime.controllerButton(303, false) }
                else -> { runtime.pointer(20f, 21f, true); runtime.pointer(20f, 21f, false) }
            }
            runtime.completeSaveValue(request!!.requestId, false)
            runtime.tick(); assertFalse(runtime.snapshotState().numbers.containsKey(0))
            runtime.tick(); assertEquals("flags=$flags", expected, runtime.snapshotState().numbers[0])
            assertNull(runtime.frame().diagnostic)
        }
    }

    @Test fun isolatedSavedDatabaseReadsSelectDestinationReaderAndKeepNativeTypeMismatchDefaults() {
        val db = saveDatabase()
        val original = fixture(listOf(command(121, intArrayOf(1_100_000_200, 73, 0, 0)),
            command(122, intArrayOf(1_100_000_201, 0), listOf("42 suffix")), command(173)), databases = db)
        original.tick(); val saved = original.snapshot()
        val reader = fixture(listOf(command(221, intArrayOf(3_000_000, 1, 1_100_000_200)),
            command(221, intArrayOf(2_000_000, 1, 1_100_000_201)),
            command(221, intArrayOf(3_000_001, 1, 1_100_000_201)), command(173)), databases = db)
        reader.tick(); val numeric = reader.drainEffects().single() as NativeEffect.ReadSaveValue
        assertTrue(numeric.stringValue)
        reader.completeSaveValue(numeric.requestId, true, snapshot = saved); reader.tick()
        val text = reader.drainEffects().single() as NativeEffect.ReadSaveValue
        assertFalse(text.stringValue); assertEquals("", reader.snapshotState().strings[0])
        reader.completeSaveValue(text.requestId, true, snapshot = saved); reader.tick()
        assertEquals(0, reader.snapshotState().numbers[0])
        val stringToString = reader.drainEffects().single() as NativeEffect.ReadSaveValue
        reader.completeSaveValue(stringToString.requestId, true, snapshot = saved); reader.tick()
        assertEquals("42 suffix", reader.snapshotState().strings[1])
        val writer = fixture(listOf(command(222, intArrayOf(37, 1, 1_100_000_001)), command(173)), databases = db)
        writer.tick(); val write = writer.drainEffects().single() as NativeEffect.WriteSaveValue
        assertEquals("37", write.text); assertNull(write.number)
    }

    @Test fun isolatedSaveReadsNeverConvertGlobalNumbersAndStringsToTheDestinationType() {
        val original = fixture(listOf(command(121, intArrayOf(2_000_004, 73, 0, 0)),
            command(122, intArrayOf(3_000_005, 0), listOf("42 suffix")), command(173)))
        original.tick(); val saved = original.snapshot()
        assertEquals(-1, original.savedNumber(saved, 3_000_005))
        assertEquals("<<ERROR>>", original.savedString(saved, 2_000_004))
        val reader = fixture(listOf(command(221, intArrayOf(3_000_000, 1, 2_000_004)),
            command(221, intArrayOf(2_000_000, 1, 3_000_005)), command(173)))
        reader.tick(); val text = reader.drainEffects().single() as NativeEffect.ReadSaveValue
        assertTrue(text.stringValue)
        reader.completeSaveValue(text.requestId, true, number = 73, snapshot = saved); reader.tick()
        assertEquals("<<ERROR>>", reader.snapshotState().strings[0])
        val number = reader.drainEffects().single() as NativeEffect.ReadSaveValue
        assertFalse(number.stringValue)
        reader.completeSaveValue(number.requestId, true, text = "42 suffix"); reader.tick()
        assertEquals(-1, reader.snapshotState().numbers[0])
    }

    @Test fun savedDatabaseOverlaysGrowRowsAndRejectAnIncompatibleFieldBeforeMutation() {
        val runtime = fixture(emptyList(), databases = saveDatabase())
        val original = runtime.snapshot()
        val data = WolfSaveData(original, mapOf(1_100_000_400 to 91), mapOf(1_100_000_401 to "new row"))
        runtime.restoreSaveData(WolfSaveData.decode(data.encode()))
        val saved = runtime.snapshot(); val state = runtime.snapshotState()
        assertEquals(5, state.mutableRows[0]); assertEquals(91, runtime.savedNumber(saved, 1_100_000_400))
        assertEquals("new row", runtime.savedString(saved, 1_100_000_401))
        assertEquals(42, runtime.savedNumber(saved, 1_100_000_300)); assertEquals("", runtime.savedString(saved, 1_100_000_301))
        try { runtime.restoreSaveData(data.copy(strings = mapOf(1_100_000_400 to "wrong field"))); fail("wrong type accepted") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(saved, runtime.snapshot())
        try { runtime.restoreSaveData(WolfSaveData(numbers = mapOf(2_000_000 to 7))); fail("partial data restored as a world") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(saved, runtime.snapshot())
    }

    @Test fun wolf3WindowScaleIsOneInLogicalCoordinatesAndDoesNotExistInWolf2() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_119, 0, 0)), command(173)), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported); runtime.tick()
        assertEquals(1, runtime.snapshotState().numbers[0]); assertEquals(1, runtime.savedNumber(runtime.snapshot(), 9_000_119))
        val old = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_119, 0, 0))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported); assertTrue(rejectsRuntime { old.tick() }.message!!.contains("profil Wolf"))
    }

    @Test fun wolf3LogicalAndPhysicalPressAndReleaseModesReadTheirOwnEdges() {
        val readers = listOf(0x20010, 0x30010, 0x20002, 0x30002, 0x21100, 0x31100, 0x21200, 0x31200)
        val reader = common(0, readers.mapIndexed { i, flags -> command(123, intArrayOf(2_000_000 + i, flags, if (i < 6) 166 else 302)) }).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(reader), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.logicalKey(NativeKey.ACCEPT, true); runtime.logicalKey(NativeKey.UP, true)
        runtime.physicalKey(66, true); runtime.controllerButton(302, true); runtime.tick()
        val pressed = runtime.snapshotState().numbers
        assertEquals(10, pressed[0]); assertEquals(0, pressed[1]); assertEquals(8, pressed[2]); assertEquals(0, pressed[3])
        assertEquals(166, pressed[4]); assertEquals(0, pressed[5]); assertEquals(302, pressed[6]); assertEquals(0, pressed[7])
        runtime.tick(); readers.indices.forEach { assertEquals(0, runtime.snapshotState().numbers[it]) }
        runtime.logicalKey(NativeKey.ACCEPT, false); runtime.logicalKey(NativeKey.UP, false)
        runtime.physicalKey(66, false); runtime.controllerButton(302, false); runtime.tick()
        val released = runtime.snapshotState().numbers
        assertEquals(10, released[1]); assertEquals(8, released[3]); assertEquals(166, released[5]); assertEquals(302, released[7])
        runtime.tick(); readers.indices.forEach { assertEquals(0, runtime.snapshotState().numbers[it]) }
    }
    @Test fun wolf3PhysicalModeOneKeepsReadingHeldInputsLikeModeZero() {
        val flags = listOf(0x1100, 0x11100, 0x1200, 0x11200, 0x310, 0x10310)
        val reader = common(0, flags.mapIndexed { i, value ->
            command(123, intArrayOf(2_000_000 + i, value, if (i < 2) 166 else 302))
        }).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(reader), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.physicalKey(66, true); runtime.controllerButton(302, true); runtime.pointer(20f, 25f, true)
        repeat(2) {
            runtime.tick()
            flags.indices.forEach { index ->
                assertEquals(when (index) { 0, 1 -> 166; 2, 3 -> 302; else -> 20 }, runtime.snapshotState().numbers[index])
            }
        }
        runtime.physicalKey(66, false); runtime.controllerButton(302, false); runtime.pointer(20f, 25f, false)
        runtime.tick(); flags.indices.forEach { assertEquals(0, runtime.snapshotState().numbers[it]) }
    }
    @Test fun wolf3CombinedDirectionEdgeComparesResolvedDirectionAndCancellationResetsHistory() {
        val reader = common(0, listOf(command(123, intArrayOf(2_000_000, 0x20002)), command(123, intArrayOf(2_000_001, 0x30002)))).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(reader), compatibilityVersion = 372)
        runtime.logicalKey(NativeKey.UP, true); runtime.tick(); assertEquals(8, runtime.snapshotState().numbers[0])
        runtime.logicalKey(NativeKey.RIGHT, true); runtime.tick(); assertEquals(9, runtime.snapshotState().numbers[0]); assertEquals(0, runtime.snapshotState().numbers[1])
        runtime.logicalKey(NativeKey.UP, false); runtime.tick(); assertEquals(6, runtime.snapshotState().numbers[0]); assertEquals(0, runtime.snapshotState().numbers[1])
        runtime.releaseInput(); runtime.tick(); assertEquals(0, runtime.snapshotState().numbers[1])
    }
    @Test fun wolf3MousePressAndReleaseModesReadEdgesExactlyOnce() {
        val reader = common(0, listOf(command(123, intArrayOf(2_000_000, 0x20310)),
            command(123, intArrayOf(2_000_001, 0x30310)))).copy(conditionWord = 3)
        val runtime = fixture(emptyList(), listOf(reader), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.pointer(20f, 25f, true); runtime.tick()
        assertEquals(20, runtime.snapshotState().numbers[0]); assertEquals(0, runtime.snapshotState().numbers[1])
        runtime.tick(); assertEquals(0, runtime.snapshotState().numbers[0])
        runtime.pointer(22f, 25f, false); runtime.tick()
        assertEquals(0, runtime.snapshotState().numbers[0]); assertEquals(20, runtime.snapshotState().numbers[1])
        runtime.tick(); assertEquals(0, runtime.snapshotState().numbers[1])
        val old = fixture(listOf(command(123, intArrayOf(2_000_000, 0x20310))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported); assertTrue(rejectsRuntime { old.tick() }.message!!.contains("profil Wolf"))
    }

    @Test fun wolf3HiddenCommonLookupsChooseTheHighestDuplicateIdAndRemainSeparateFromPlainStrings() {
        val commands = listOf(command(122, intArrayOf(3_000_000, 0x40200), listOf("<<GET_COMMONEVENT_ID_FROM_NAME>>same")),
            command(122, intArrayOf(3_000_001, 0x40200), listOf("<<GET_COMMONEVENT_ID_FROM_NAME>>absent")),
            command(122, intArrayOf(3_000_002, 0x40200), listOf("<<GET_COMMONEVENT_NAME_FROM_ID>>2")),
            command(122, intArrayOf(3_000_003, 0x40200), listOf("<<GET_COMMONEVENT_NAME_FROM_ID>>-1")),
            command(122, intArrayOf(3_000_004, 0), listOf("<<GET_TITLE>>")), command(173))
        val runtime = fixture(commands, listOf(common(0, emptyList()).copy(name = "same"),
            common(1, emptyList()).copy(name = "other"), common(2, emptyList()).copy(name = "same")), compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported); runtime.tick()
        assertEquals("2", runtime.snapshotState().strings[0]); assertEquals("-1", runtime.snapshotState().strings[1])
        assertEquals("same", runtime.snapshotState().strings[2]); assertEquals("<<NotFound>>", runtime.snapshotState().strings[3])
        assertEquals("<<GET_TITLE>>", runtime.snapshotState().strings[4])
        val unsupported = fixture(listOf(command(122, intArrayOf(3_000_000, 0x40200), listOf("<<UNKNOWN>>"))), compatibilityVersion = 372)
        assertFalse(unsupported.inspectCapabilities().supported); assertTrue(rejectsRuntime { unsupported.tick() }.message!!.contains("UNKNOWN"))
    }

    @Test fun rectangularTouchExpansionStartsEventsAwayFromTheirCenterAndIsAdmitted() {
        val expanded = page(listOf(command(101, strings = listOf("region")), command(173)), trigger = 3)
            .copy(features = byteArrayOf(0, 2, 2))
        val inside = fixture(emptyList(), initialMap = map("region", listOf(WolfEvent(7, "region", 4, 4, listOf(expanded)))))
        assertTrue(inside.inspectCapabilities().supported)
        inside.logicalKey(NativeKey.DOWN, true)
        repeat(12) { inside.tick() }
        inside.logicalKey(NativeKey.DOWN, false)
        assertEquals("region", inside.frame().dialog!!.text)
        assertEquals(3f, inside.snapshotState().y, .001f)
        val outside = fixture(emptyList(), initialMap = map("region", listOf(WolfEvent(7, "region", 4, 4,
            listOf(expanded.copy(features = byteArrayOf(0, 1, 0)))))))
        outside.logicalKey(NativeKey.DOWN, true); repeat(12) { outside.tick() }; outside.releaseInput()
        assertNull(outside.frame().dialog)
    }

    @Test fun timerAssignmentsUseTheirAssignmentTickAndSurviveRestart() {
        val runtime = fixture(listOf(command(180, intArrayOf(5)), command(121, intArrayOf(9_000_028, 100, 0, 0)),
            command(121, intArrayOf(9_000_029, 20, 0, 0)), command(121, intArrayOf(2_000_000, 9_000_028, 0, 0)),
            command(121, intArrayOf(2_000_001, 9_000_029, 0, 0)), command(173)))
        repeat(7) { runtime.tick() }
        assertEquals(100, runtime.snapshotState().numbers[0]); assertEquals(20, runtime.snapshotState().numbers[1])
        val saved = runtime.snapshot(); runtime.restore(saved)
        assertArrayEquals(saved, runtime.snapshot())
        assertEquals(7L, runtime.snapshotState().timerFrameOrigin); assertEquals(7L, runtime.snapshotState().timerSecondOrigin)
    }

    @Test fun audioDescriptionsOverrideCommandValuesWithoutPuttingNewlinesInTheAssetPath() {
        val runtime = fixture(listOf(command(140, intArrayOf(0x02000000, 0, 0, 0, 100, 100), listOf("BGM/theme.mid\n80\n108")), command(173)))
        runtime.tick(); val audio = runtime.drainEffects().single() as NativeEffect.Audio
        assertEquals("Data/BGM/theme.mid", audio.path); assertEquals(80, audio.volume); assertEquals(108, audio.pitch); assertTrue(audio.loop)
    }

    @Test fun movingAFadingWindowPreservesItsOpacityDestinationAndWindowType() {
        val runtime = fixture(listOf(
            command(150, intArrayOf(0x1030, 19500, 5, 80, 30, 1, 255, 40, 80, 100, 0), listOf("SystemFile/WindowBase.png")),
            command(150, intArrayOf(0x400f01, 19500, 5, 0, 0, -1000000, -1000000, 40, 60, -1000000, -1000000, 0, 0x1000000)), command(173)))
        runtime.tick(); assertEquals(0, runtime.frame().pictures.single().opacity)
        repeat(5) { runtime.tick() }
        val window = runtime.frame().pictures.single()
        assertEquals(255, window.opacity); assertEquals(3, window.kind); assertEquals(80f, window.width); assertEquals(30f, window.height)
        assertEquals(40f, window.x); assertEquals(60f, window.y); assertEquals(0, window.z)
        assertEquals(1, window.anchor)
    }

    @Test fun mutableDatabaseGrowthDefaultsCountsNamesResetAndIndirectTargetsArePreserved() {
        val fields = listOf(WolfDatabaseField("number", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 42),
            WolfDatabaseField("text", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0))
        val db = WolfDatabase("fixture", WolfEncoding.UTF8, 1, listOf(WolfDatabaseType(0, "stats", "", fields,
            listOf(WolfDatabaseRow(0, "original", intArrayOf(7), listOf("initial"))), 0, null)))
        val runtime = fixture(listOf(
            command(121, intArrayOf(2_000_000, 1_100_000_300, 0, 0)), // read extends to row3 with numeric project default
            command(250, intArrayOf(0, 3, 1, 2, 0), listOf("changed")),
            command(250, intArrayOf(0, -1, 0, 0x1000, 2_000_001)),
            command(250, intArrayOf(0, 0, -1, 0x1000, 2_000_002)),
            command(250, intArrayOf(0, 0, -3, 0x1000, 3_000_000)),
            command(121, intArrayOf(2_000_004, 2_000_003, 0, 4)),
            command(250, intArrayOf(0, 0, 0, 0x1001, 2_000_004)),
            command(250, intArrayOf(0, -2, -2, 0, 0)), command(173)), databases = mapOf(WolfDatabaseKind.MUTABLE to db))
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals(42, state.numbers[0]); assertEquals(4, state.numbers[1]); assertEquals(2, state.numbers[2]); assertEquals(7, state.numbers[3])
        assertEquals("original", state.strings[0]); assertEquals(4, state.mutableRows[0]); assertEquals(42, state.mutableNumbers[Triple(0, 0, 0)])
        assertEquals("", state.mutableStrings[Triple(0, 3, 1)])
        val snapshot = runtime.snapshot(); runtime.restore(snapshot); assertEquals(state.mutableRows, runtime.snapshotState().mutableRows)
    }

    @Test fun randomReferencesAreBoundedAndRoundTripTheSessionSeed() {
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 8_000_009, 0, 0)), command(173)))
        runtime.tick(); assertTrue(runtime.snapshotState().numbers.getValue(0) in 0..9)
        val snapshot = runtime.snapshot(); runtime.restore(snapshot); assertArrayEquals(snapshot, runtime.snapshot())
    }

    @Test fun mapZoomInterpolatesThePivotAndSurvivesRestartWithoutDoubleAudioGain() {
        val runtime = fixture(listOf(command(290, intArrayOf(2, 0, 0, 0, 20, 30, 200)),
            command(290, intArrayOf(2, 10, 0, 0, 40, 50, 300)), command(173)))
        runtime.tick(); runtime.tick()
        assertEquals(2.1f, runtime.frame().map!!.zoom, .001f); assertEquals(22f, runtime.frame().map!!.zoomCenterX, .001f)
        val saved = runtime.snapshot(); val expected = runtime.frame().map!!
        runtime.restore(saved); assertEquals(expected.zoom, runtime.frame().map!!.zoom, .001f); assertEquals(expected.zoomCenterY, runtime.frame().map!!.zoomCenterY, .001f)
        repeat(9) { runtime.tick() }; assertEquals(3f, runtime.frame().map!!.zoom, .001f); assertEquals(40f, runtime.frame().map!!.zoomCenterX, .001f)
    }

    @Test fun partyGraphicsAndRememberedPartySurviveACoreRestart() {
        val runtime = fixture(listOf(command(270, intArrayOf(2, 2), listOf("Ally.png")), command(270, intArrayOf(116)),
            command(270, intArrayOf(164)), command(122, intArrayOf(9_900_012, 0), listOf("Second.png")), command(173)))
        runtime.tick(); val snapshot = runtime.snapshot(); val before = runtime.snapshotState()
        runtime.restore(snapshot)
        assertEquals(before.companions, runtime.snapshotState().companions); assertEquals(before.rememberedParty, runtime.snapshotState().rememberedParty)
        assertFalse(runtime.snapshotState().partyFollow); assertEquals("Data/Second.png", runtime.frame().map!!.characters.single { it.id == -4 }.path)
    }

    @Test fun unknownSystemReadsAndWritesFailAndAreRejectedBeforeStateMutation() {
        val read = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_199, 0, 0), offset = 32)))
        assertTrue(read.inspectCapabilities().issues.any { it.reason.contains("9000199") })
        assertTrue(read.snapshotState().numbers.isEmpty())
        assertEquals(32, rejectsRuntime { read.tick() }.offset)
        val write = fixture(listOf(command(121, intArrayOf(9_000_084, 4, 0, 0))))
        assertTrue(rejectsRuntime { write.tick() }.message!!.contains("Écriture système"))
        val undefined = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_008, 0, 0))))
        assertTrue(rejectsRuntime { undefined.tick() }.message!!.contains("Valeur initiale"))
    }

    @Test fun variableExpansionPreservesRichTextAndDoesNotAlterStoredStrings() {
        val formatted = "\\c[1]Salut \\v[0] \\f[22]\\r[漢字,かんじ]"
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 7, 0, 0)),
            command(122, intArrayOf(3_000_000, 0), listOf(formatted)), command(101, strings = listOf("\\s[0]"))))
        runtime.tick()
        val expected = "\\c[1]Salut 7 \\f[22]\\r[漢字,かんじ]"
        assertEquals(expected, runtime.snapshotState().strings[0]); assertEquals(expected, runtime.frame().dialog!!.text)
        val timing = fixture(listOf(command(101, strings = listOf("Wait\\!now"))))
        timing.tick(); assertEquals(4, timing.frame().dialog!!.visibleCharacters); assertTrue(timing.frame().dialog!!.waiting)
        repeat(10) { timing.tick() }; assertEquals(4, timing.frame().dialog!!.visibleCharacters)
        timing.key(NativeKey.ACCEPT, true); timing.tick(); timing.key(NativeKey.ACCEPT, false)
        assertEquals(7, timing.frame().dialog!!.visibleCharacters)
        timing.key(NativeKey.ACCEPT, true); timing.tick(); assertNull(timing.frame().dialog)
    }

    @Test fun pointerEdgesAdvanceExactlyOnceAndReleaseCancelsTargetAndCoordinates() {
        val runtime = fixture(listOf(command(121, intArrayOf(9_000_073, 1, 0, 0)), command(121, intArrayOf(9_000_008, 22, 0, 0)),
            command(101, strings = listOf("First")), command(101, strings = listOf("Second")), command(173)))
        runtime.tick(); assertEquals(22f, runtime.frame().dialog!!.fontSize)
        runtime.pointer(10f, 10f, true); runtime.tick()
        assertEquals("Second", runtime.frame().dialog!!.text)
        runtime.pointer(11f, 11f, true); runtime.tick(); assertEquals("Second", runtime.frame().dialog!!.text)
        runtime.pointer(11f, 11f, false); runtime.pointer(11f, 11f, true); runtime.tick(); assertNull(runtime.frame().dialog)
        runtime.releaseInput()
        val input = fixture(listOf(command(101, strings = listOf("Pulse")), command(173)))
        input.tick(); input.key(NativeKey.Z, true); input.key(NativeKey.Z, false); input.tick(); assertNull(input.frame().dialog)
    }

    @Test fun nativeSaveRestoresPicturesEventPositionsAndRejectsUnsafeResourceWithoutMutation() {
        val runtime = fixture(listOf(command(150, intArrayOf(0, 1, 0, 5, 6, 1, 255, 0, 0, 100, 0), listOf("Picture/title.png")),
            command(121, intArrayOf(2_000_000, 7, 0, 0)), command(130, intArrayOf(-2, 3, 2, 0, 32)), command(101, strings = listOf("Save"))))
        runtime.tick(); assertEquals(2f, runtime.snapshotState().x, .001f); assertEquals(3f, runtime.snapshotState().actors.single().x, .001f)
        val before = runtime.frame().pictures.single(); val saved = runtime.snapshot()
        runtime.restore(saved)
        assertEquals(before, runtime.frame().pictures.single()); assertEquals(3f, runtime.snapshotState().actors.single().x, .001f)
        assertNull(runtime.frame().dialog); assertEquals(7, runtime.snapshotState().numbers[0])
        val malformed = WolfSnapshot.encode(runtime.snapshotState().copy(pictures = listOf(NativePicture(0, path = "../outside.png"))))
        try { runtime.restore(malformed); fail("unsafe resource restored") } catch (_: IllegalArgumentException) { }
        assertEquals(before, runtime.frame().pictures.single()); assertEquals(7, runtime.snapshotState().numbers[0])
    }

    @Test fun pageSystemConditionIsPreflightedWithoutBootAndHasExactFailureLocation() {
        val conditions = ByteArray(37).also { it[0] = 1; it[1] = 0x21; putInt(it, 5, 9_000_199); putInt(it, 21, 1) }
        val event = WolfEvent(12, "guard", 2, 2, listOf(page(emptyList(), conditions = conditions).copy(offset = 0x27)))
        val runtime = fixture(emptyList(), initialMap = map("guard-map", listOf(event)))
        assertTrue(runtime.inspectCapabilities().issues.any { it.eventId == 12 && it.reason.contains("9000199") })
        val failure = rejectsRuntime { runtime.tick() }
        assertEquals(12, failure.eventId); assertEquals(0x27, failure.offset)
    }

    @Test fun downwardPassageContinuesToBlockingLowerLayerAndUnsupportedPhysicsIsGated() {
        val tiles = IntArray(6 * 6 * 3) { 100000 }.also { it[2 * 6 + 3] = 0; it[6 * 6 + 2 * 6 + 3] = 1 }
        val tileset = WolfTileset(0, "", "", emptyList(), byteArrayOf(), intArrayOf(0, 0x200, 15))
        val runtime = fixture(emptyList(), initialMap = map("passage", emptyList()).copy(tiles = tiles), tileset = tileset)
        runtime.key(NativeKey.RIGHT, true); repeat(20) { runtime.tick() }
        assertEquals(2f, runtime.snapshotState().x, .001f)
        val p = page(emptyList()).copy(flags = 0x83, graphicBlend = 3, movement = byteArrayOf(3, 3, 3, 2))
        val game = fixture(emptyList(), initialMap = map("unsupported", listOf(WolfEvent(3, "moving", 2, 2, listOf(p)))))
        val report = game.inspectCapabilities().description()
        assertTrue(report.contains("autonome"))
        assertEquals(3, rejectsRuntime { game.tick() }.eventId)
    }

    @Test fun collisionUsesTheHighestNontransparentTileBeforeTheFloor() {
        val tiles = IntArray(6 * 6 * 3) { 100000 }.also {
            for (index in 0 until 36) it[index] = 0
            it[2 * 36 + 2 * 6 + 3] = 1
        }
        val tileset = WolfTileset(0, "", "", emptyList(), byteArrayOf(), intArrayOf(0, 0, 15))
        val runtime = fixture(emptyList(), initialMap = map("top-wall", emptyList()).copy(tiles = tiles), tileset = tileset)
        runtime.logicalKey(NativeKey.RIGHT, true); repeat(20) { runtime.tick() }
        assertEquals(2f, runtime.snapshotState().x, .001f)
    }

    @Test fun nativeCharacterAnimationKeepsRestMovementSeparateAndPersistsItsPhase() {
        val route = WolfRoute(0, listOf(WolfRouteCommand(31, intArrayOf(0)), WolfRouteCommand(47, intArrayOf(100))), byteArrayOf(0, 3, 3, 0, 2))
        val runtime = fixture(listOf(command(201, intArrayOf(-2)).copy(route = route),
            command(290, intArrayOf(0x81, 0, -2, -2, 1, 0, 0)), command(173)), compatibilityVersion = 372)
        runtime.tick(); val before = runtime.snapshotState().hero!!
        runtime.tick(); val animated = runtime.snapshotState().hero!!
        assertEquals(2, animated.pattern); assertFalse(animated.moving)
        assertEquals(before.x, animated.x, 0f); assertEquals(before.y, animated.y, 0f)
        assertTrue(animated.animation!!.animateOnStop); assertEquals(1, animated.animation.phase)
        val restored = fixture(emptyList(), compatibilityVersion = 372)
        restored.restore(runtime.snapshot())
        repeat(8) { runtime.tick(); restored.tick(); assertEquals(runtime.snapshotState().hero!!.animation, restored.snapshotState().hero!!.animation); assertEquals(runtime.snapshotState().hero!!.pattern, restored.snapshotState().hero!!.pattern) }
    }

    @Test fun nativeCharacterDirectionLockAppliesToExplicitRouteFacingAndCanBeReleased() {
        val route = WolfRoute(0, listOf(WolfRouteCommand(10, intArrayOf()), WolfRouteCommand(47, intArrayOf(1)), WolfRouteCommand(37, intArrayOf()), WolfRouteCommand(10, intArrayOf())))
        val runtime = fixture(listOf(command(290, intArrayOf(0xc1, 0, -2, -2, 1, 0, 0)), command(201, intArrayOf(-2)).copy(route = route), command(173)), compatibilityVersion = 372)
        runtime.tick(); runtime.tick(); assertEquals(2, runtime.snapshotState().hero!!.direction)
        repeat(6) { runtime.tick() }; assertEquals(6, runtime.snapshotState().hero!!.direction)
        val old = fixture(listOf(command(290, intArrayOf(0x81, 0, -2, -2, 1, 0, 0))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported)
        assertTrue(rejectsRuntime { old.tick() }.message!!.contains("profil Wolf"))
    }

    @Test fun stoppedWolf3HeroHasItsIdlePatternAndCyclicAnimationUsesTheGameSetting() {
        val resting = fixture(listOf(command(173)), compatibilityVersion = 372)
        repeat(30) { resting.tick() }; assertEquals(1, resting.snapshotState().hero!!.pattern)
        val route = WolfRoute(0, listOf(WolfRouteCommand(31, intArrayOf(0)), WolfRouteCommand(47, intArrayOf(100))), byteArrayOf(0, 3, 3, 0, 2))
        val cyclic = fixture(listOf(command(201, intArrayOf(-2)).copy(route = route),
            command(290, intArrayOf(0x81, 0, -2, -2, 1, 0, 0)), command(173)), compatibilityVersion = 372, cyclic = true)
        cyclic.tick(); cyclic.tick(); assertEquals(2, cyclic.snapshotState().hero!!.pattern)
        cyclic.tick(); assertEquals(0, cyclic.snapshotState().hero!!.pattern)
    }

    @Test fun pictureSizeReadsIntrinsicBitmapAtZeroZoomButScaledEasyWindowDimensions() {
        // An independent GIF logical-screen header suffices for size reading; the VM never decodes its pixels.
        val gif = "GIF87a".toByteArray(Charsets.US_ASCII) + byteArrayOf(48, 0, 32, 0, 0, 0, 0)
        val runtime = fixture(listOf(
            command(150, intArrayOf(0, 1, 0, 1, 1, 1, 255, 0, 0, 0, 0), listOf("Picture/portrait.gif")),
            command(124, intArrayOf(2_000_000, 0x4000, 1, 2)),
            command(124, intArrayOf(2_000_001, 0x4000, 1, 3)),
            command(150, intArrayOf(0x30, 2, 0, 48, 32, 1, 255, 0, 0, 50, 0), listOf("<SQUARE>")),
            command(124, intArrayOf(2_000_002, 0x4000, 2, 2)),
            command(124, intArrayOf(2_000_003, 0x4000, 2, 3)), command(173)),
            assets = mapOf("Data/Picture/portrait.gif" to gif))
        runtime.tick()
        assertEquals(mapOf(0 to 48, 1 to 32, 2 to 24, 3 to 16), runtime.snapshotState().numbers)
        assertEquals(0f, runtime.frame().pictures.single { it.id == 1 }.scaleX, 0f)
    }

    @Test fun loopingMovesKeepContinuousPixelsAndWrappedReadCoordinatesAcrossRestart() {
        val reads = WolfEvent(0, "read", 2, 2, listOf(page(listOf(
            command(124, intArrayOf(2_000_000, 0x1000, -2, 0)), command(180, intArrayOf(1))), trigger = 2)))
        val looping = map("loop", listOf(reads)).copy(loopX = true)
        val runtime = fixture(emptyList(), initialMap = looping)
        // Tile16 × four internal units / speed3 increment8 = eight logical ticks.
        fun left() { runtime.logicalKey(NativeKey.LEFT, true); runtime.tick(); runtime.logicalKey(NativeKey.LEFT, false); repeat(8) { runtime.tick() } }
        repeat(3) { left() }
        assertEquals(-1f, runtime.snapshotState().x, 0f)
        assertEquals(5, runtime.snapshotState().numbers[0])
        val restored = fixture(emptyList(), initialMap = looping)
        restored.restore(runtime.snapshot()); assertEquals(-1f, restored.snapshotState().x, 0f)
        assertEquals(runtime.snapshotState().hero, restored.snapshotState().hero)
        restored.logicalKey(NativeKey.LEFT, true); restored.tick(); restored.logicalKey(NativeKey.LEFT, false)
        repeat(8) { restored.tick() }; assertEquals(-2f, restored.snapshotState().x, 0f)

        val bounded = fixture(emptyList(), initialMap = looping.copy(loopX = false, events = emptyList()))
        bounded.logicalKey(NativeKey.LEFT, true); repeat(40) { bounded.tick() }
        assertEquals(0f, bounded.snapshotState().x, 0f)
    }

    @Test fun loopSeamRespectsAnActualWallAndActionEventsAcrossTheEdge() {
        val tiles = IntArray(6 * 6 * 3) { 100000 }.also {
            for (index in 0 until 36) it[index] = 0
            it[2 * 36 + 2 * 6 + 5] = 1
        }
        val tileset = WolfTileset(0, "", "", emptyList(), byteArrayOf(), intArrayOf(0, 0, 15))
        val blocked = fixture(emptyList(), initialMap = map("loop-wall", emptyList()).copy(tiles = tiles, loopX = true), tileset = tileset)
        blocked.logicalKey(NativeKey.LEFT, true); repeat(40) { blocked.tick() }
        assertEquals(0f, blocked.snapshotState().x, 0f)
        val event = WolfEvent(1, "across", 5, 2, listOf(page(listOf(command(101, strings = listOf("Across the edge"))), trigger = 0)))
        val action = fixture(emptyList(), initialMap = map("loop-event", listOf(event)).copy(loopX = true), start = WolfStartPosition(0, 0, 2))
        action.logicalKey(NativeKey.LEFT, true); action.tick(); action.logicalKey(NativeKey.LEFT, false)
        // The invisible event does not block motion, so snap back to the edge while retaining facing.
        action.restoreState(action.snapshotState().let { it.copy(x = 0f, hero = it.hero!!.copy(x = 0f, targetX = 0f, moveTicks = 0, moving = false)) })
        action.logicalKey(NativeKey.ACCEPT, true); action.tick(); action.logicalKey(NativeKey.ACCEPT, false)
        assertEquals("Across the edge", action.frame().dialog!!.text)
    }

    @Test fun touchPathAndPixelMotionCrossTheLoopSeamWithTheirActualResiduals() {
        val looping = map("loop-touch", emptyList()).copy(loopX = true)
        val touch = fixture(emptyList(), initialMap = looping, start = WolfStartPosition(0, 0, 2))
        val camera = touch.frame().map!!.cameraX
        val screenX = (5 * 16f - camera + 96) % 96
        touch.pointer(screenX, 40f, true); touch.pointer(screenX, 40f, false)
        repeat(10) { touch.tick() }
        assertEquals(-1f, touch.snapshotState().x, 0f)

        val pixelMap = looping.copy(events = listOf(WolfEvent(0, "pixel", 0, 2, listOf(page(listOf(
            command(290, intArrayOf(0x71, 0, -2, 0, -3, 0, 0)), command(173)))))))
        val pixel = fixture(emptyList(), initialMap = pixelMap,
            start = WolfStartPosition(0, 0, 2), compatibilityVersion = 372)
        pixel.tick(); assertEquals(-.1875f, pixel.snapshotState().x, 0f)
        assertEquals(-1, pixel.snapshotState().hero!!.pixelMotion!!.halfX)
        assertEquals(5, pixel.snapshotState().hero!!.pixelMotion!!.offsetX)
        val restored = fixture(emptyList(), initialMap = pixelMap, compatibilityVersion = 372)
        restored.restore(pixel.snapshot()); assertEquals(pixel.snapshotState().hero!!.pixelMotion, restored.snapshotState().hero!!.pixelMotion)
    }

    @Test fun wolf3ScreenStateConversionUsesCameraInverseZoomAndTheFullEightBitField() {
        val commands = listOf(
            command(290, intArrayOf(2, 0, 0, 0, 32, 32, 200)),
            command(121, intArrayOf(9_000_104, 64, 0, 0)), command(121, intArrayOf(9_000_105, 48, 0, 0))) +
            listOf(152, 153, 154, 155, 161, 162).mapIndexed { index, field ->
                command(124, intArrayOf(2_000_000 + index, (field shl 16) or 0x2000, 80, 64))
            } + command(173)
        val map = map("coordinate", listOf(WolfEvent(0, "conversion", 2, 2, listOf(page(commands)))), width = 20, height = 20)
        val runtime = fixture(emptyList(), initialMap = map, compatibilityVersion = 372)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        assertEquals(mapOf(0 to 7, 1 to 6, 2 to 15, 3 to 12, 4 to 120, 5 to 96), runtime.snapshotState().numbers)
        val old = fixture(listOf(command(124, intArrayOf(2_000_000, 0x982000, 80, 64))), compatibilityVersion = 225)
        assertFalse(old.inspectCapabilities().supported)
        assertTrue(rejectsRuntime { old.tick() }.message!!.contains("profil Wolf"))
        val unknown = fixture(listOf(command(124, intArrayOf(2_000_000, 0xa82000, 80, 64), offset = 0x34)), compatibilityVersion = 372)
        assertFalse(unknown.inspectCapabilities().supported)
        assertEquals(0x34, rejectsRuntime { unknown.tick() }.offset)
        val absolute = fixture(listOf(command(124, intArrayOf(2_000_000, 0x1800, 99, 0)), command(173)))
        absolute.tick(); assertEquals(1, absolute.snapshotState().numbers[0])
    }

    @Test fun mapCommandsReadPropertyIndicesRestoreTheirChangesAndResetOnTransfer() {
        val set = WolfTileset(0, "set", "base.png", List(31) { "auto$it.png" }, ByteArray(64), IntArray(64))
        val original = map("overwrite", listOf(WolfEvent(0, "modify", 2, 2, listOf(page(listOf(
            command(242, intArrayOf(3, 3, 2, 1, 1, 8)),
            command(124, intArrayOf(2_000_000, 0x42000, 3, 2)),
            command(121, intArrayOf(2_000_001, 33, 0, 0)),
            command(242, intArrayOf(3, 3, 2, 1, 1, 2_000_001)),
            command(124, intArrayOf(2_000_002, 0x72000, 3, 2)),
            command(240, intArrayOf(9, 15)), command(173)))))))
        val runtime = fixture(emptyList(), initialMap = original, tileset = set, compatibilityVersion = 369)
        assertTrue(runtime.inspectCapabilities().supported)
        val published = runtime.frame().map!!
        runtime.tick()
        assertEquals(32, runtime.snapshotState().numbers[0])
        assertEquals(33, runtime.snapshotState().numbers[2])
        assertEquals(1, runtime.frame().map!!.map.tile(3, 2, 2))
        assertEquals(15, runtime.frame().map!!.tileset.passage[33])
        assertEquals(100000, published.map.tile(3, 2, 2))
        assertEquals(0, published.tileset.passage[33])
        runtime.logicalKey(NativeKey.RIGHT, true); repeat(12) { runtime.tick() }; runtime.logicalKey(NativeKey.RIGHT, false)
        assertEquals(2f, runtime.snapshotState().x, 0f)
        val fresh = fixture(emptyList(), initialMap = original, tileset = set, compatibilityVersion = 369)
        fresh.restore(runtime.snapshot())
        assertEquals(runtime.snapshotState().mapChanges, fresh.snapshotState().mapChanges)
        assertArrayEquals(runtime.frame().map!!.map.tiles, fresh.frame().map!!.map.tiles)
        assertArrayEquals(runtime.frame().map!!.tileset.passage, fresh.frame().map!!.tileset.passage)

        val transferCommands = listOf(command(242, intArrayOf(1, 1, 1, 1, 1, 8)), command(240, intArrayOf(8, 15)),
            command(130, intArrayOf(-1, 2, 2, 1, 0)), command(173))
        val transfer = fixture(transferCommands, extraMaps = mapOf(1 to map("other", emptyList())), tileset = set, compatibilityVersion = 369)
        transfer.tick()
        assertNull(transfer.snapshotState().mapChanges)
        assertEquals(100000, transfer.frame().map!!.map.tile(1, 1, 0))
        assertEquals(0, transfer.frame().map!!.tileset.passage[32])
    }

    @Test fun encryptedCsvAliasRefusesAdmissionAndRuntimeBeforeAnyHostRequestOrDatabaseMutation() {
        val original = saveDatabase()
        val path = "Data/text.csv"
        val runtime = fixture(listOf(command(251, intArrayOf(0, 0, 0, 0, 1), listOf(path), offset = 0x1234), command(173)),
            databases = original, compatibilityVersion = 369, assets = mapOf(path to "h\n42,text".toByteArray(),
                "$path.wolfx" to "WOLFX".toByteArray()))
        assertTrue(runtime.inspectCapabilities().issues.any { it.opcode == 251 && it.reason.contains("WOLFX") })
        val failure = rejectsRuntime { runtime.tick() }
        assertEquals(251, failure.opcode); assertEquals(0x1234, failure.offset)
        assertTrue(failure.message!!.contains("CSV WOLFX"))
        assertTrue(runtime.drainEffects().none { it is NativeEffect.FileOperation })
        assertNull(runtime.snapshotState().databaseChanges)
        assertTrue(runtime.snapshotState().mutableNumbers.isEmpty())
        assertEquals(7, original.getValue(WolfDatabaseKind.MUTABLE).types[0].number(0, 0))
    }

    @Test fun pictureSizeUsesThePreferredEncryptedImageAndRefusesUnavailableKeyVariables() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/wolf-map-textures/$name")).use { it.readBytes() }
        val commands = listOf(command(150, intArrayOf(0, 1, 0, 1, 1, 1, 255, 0, 0, 100, 0), listOf("Picture/alias.png")),
            command(124, intArrayOf(2_000_000, 0x4000, 1, 2)), command(124, intArrayOf(2_000_001, 0x4000, 1, 3)), command(173))
        val expected = WolfImageHeader.dimensions(resource("quadrants.png"))!!
        val encrypted = resource("quadrants.png.wolfx")
        val assets = mapOf("Data/Picture/alias.png" to WolfMapTextureControllerTest.png(17, 13),
            "Data/Picture/alias.png.wolfx" to encrypted)
        val runtime = fixture(commands, compatibilityVersion = 369, assets = assets)
        runtime.tick()
        assertEquals(mapOf(0 to expected.first, 1 to expected.second), runtime.snapshotState().numbers)
        val missingKey = fixture(commands, compatibilityVersion = 369, assets = assets +
            ("Data/Picture/alias.png.wolfx" to resource("quadrants.png.string-key.wolfx")))
        assertTrue(rejectsRuntime { missingKey.tick() }.message!!.contains("WOLFX"))
        assertTrue(missingKey.snapshotState().numbers.isEmpty())
    }

    @Test fun systemBindingNamesAreLiveAndSavedRatherThanFixedOverlayLabels() {
        for (version in listOf(225, 369)) {
            val commands = listOf(command(122, intArrayOf(3_000_000, 1, 9_900_001)),
                command(121, intArrayOf(9_000_052, 142, 0, 0)),
                command(121, intArrayOf(9_000_055, 320, 0, 0)),
                command(122, intArrayOf(3_000_001, 1, 9_900_001)),
                command(122, intArrayOf(3_000_002, 1, 9_900_004)), command(173))
            val runtime = fixture(commands, compatibilityVersion = version)
            assertTrue(runtime.inspectCapabilities().supported)
            runtime.tick()
            val expected = if (version >= 300) "ボタン20" else ""
            assertEquals(mapOf(0 to "Z", 1 to "左Shift", 2 to expected), runtime.snapshotState().strings)
            val saved = runtime.snapshot()
            assertEquals("左Shift", runtime.savedString(saved, 9_900_001))
            assertEquals(expected, runtime.savedString(saved, 9_900_004))
            val fresh = fixture(commands, compatibilityVersion = version)
            fresh.restore(saved)
            assertEquals(runtime.snapshotState().system, fresh.snapshotState().system)
            assertEquals("左Shift", fresh.savedString(fresh.snapshot(), 9_900_001))
        }
    }

    @Test fun mapTextureClocksSurviveRestartAndIsolatedFilenameWritesReloadOnlyTheirOwnLayer() {
        val commands = listOf(command(122, intArrayOf(9_900_030, 0), listOf("Back.png\n3\n1")),
            command(122, intArrayOf(9_900_031, 0), listOf("Fog.png\n-1\n2")), command(173))
        val assets = mapOf("Data/Back.png" to WolfMapTextureControllerTest.png(32, 24),
            "Data/Fog.png" to WolfMapTextureControllerTest.png(32, 24),
            "Data/New.png" to WolfMapTextureControllerTest.png(17, 13))
        for (version in listOf(225, 369)) {
            val runtime = fixture(commands, compatibilityVersion = version, assets = assets)
            assertTrue(runtime.inspectCapabilities().supported)
            runtime.tick()
            assertTrue(runtime.snapshotState().mapTextures!!.backdrop.stringReloadPending)
            val first = runtime.frame().map!!
            assertEquals(0, first.backdrop!!.phaseX); assertEquals(0, first.fog!!.phaseY)
            repeat(6) { runtime.tick() }
            val saved = runtime.snapshot()
            val fresh = fixture(commands, compatibilityVersion = version, assets = assets)
            fresh.restore(saved)
            assertEquals(runtime.snapshotState().mapTextures, fresh.snapshotState().mapTextures)
            repeat(3) { runtime.tick(); fresh.tick() }
            assertEquals(runtime.frame().map!!.backdrop, fresh.frame().map!!.backdrop)
            assertEquals(runtime.frame().map!!.fog, fresh.frame().map!!.fog)
            val fogBefore = fresh.snapshotState().mapTextures!!.fog
            fresh.restoreSaveData(WolfSaveData(fresh.snapshot(), strings = mapOf(9_900_030 to "New.png\n0\n0")))
            val restored = fresh.frame().map!!
            assertEquals("Data/New.png", restored.backdrop!!.path); assertEquals(0, restored.backdrop!!.phaseX)
            assertEquals(fogBefore, fresh.snapshotState().mapTextures!!.fog)
            fresh.restoreSaveData(WolfSaveData(fresh.snapshot(), numbers = mapOf(9_000_061 to -1)))
            assertNull(fresh.frame().map!!.fog)
            assertEquals("Data/New.png", fresh.frame().map!!.backdrop!!.path)
            val before = fresh.snapshotState()
            assertThrows(IllegalArgumentException::class.java) { fresh.restoreState(before.copy(mapTextures = before.mapTextures!!.copy(
                backdrop = before.mapTextures!!.backdrop.copy(behaviorVersion = if (version < 300) 3 else 2)))) }
            assertEquals(before, fresh.snapshotState())
        }
    }

    private fun rejectsRuntime(action: () -> Unit): WolfRuntimeException {
        try { action(); fail("Expected runtime diagnostic") } catch (failure: WolfRuntimeException) { return failure }
        throw AssertionError("unreachable")
    }
    private fun fixture(commands: List<WolfCommand>, commons: List<WolfCommon> = emptyList(), extraMaps: Map<Int, WolfMap> = emptyMap(), initialMap: WolfMap? = null, tileset: WolfTileset? = null, databases: Map<WolfDatabaseKind, WolfDatabase> = emptyMap(), compatibilityVersion: Int? = null, cyclic: Boolean = false, assets: Map<String, ByteArray> = emptyMap(), start: WolfStartPosition = WolfStartPosition(0, 2, 2), encoding: WolfEncoding = WolfEncoding.UTF8, movementDirections: Int = 4): WolfRuntime {
        val source = object : WolfAssetSource { override fun read(path: String) = assets[path] ?: byteArrayOf(); override fun list(path: String) = emptyList<String>(); override fun exists(path: String) = path in assets || !path.endsWith(".wolfx", true) }
        val config = WolfGameConfig("Native fixture", "", encoding, 16, 96, 96, 60, "Hero.png", 4, movementDirections, 3, "sans", emptyList(), ByteArray(35).also { it[8] = 1; it[9] = 1; if (cyclic) it[34] = 1 }, IntArray(19).also { words -> repeat(7) { speed -> words[1 + speed * 2] = 1 shl speed; words[2 + speed * 2] = 1 shl speed }; words[18] = compatibilityVersion ?: 225 }, emptyList(), 0xc4)
        val map = initialMap ?: map("title", listOf(WolfEvent(0, "title", 2, 2, listOf(page(commands)))))
        val data = WolfGameData(config, commons, databases, listOf(tileset ?: WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))), listOf(WolfMapReference(0, "title", "title.mps")) + extraMaps.keys.map { WolfMapReference(it, "map$it", "map$it.mps") }, start)
        return WolfRuntime.fromData(source, data) { id -> if (id == 0) map else extraMaps[id] ?: error("Missing map $id") }
    }
    private fun map(name: String, events: List<WolfEvent>, width: Int = 6, height: Int = 6) = WolfMap(name, name, 103, WolfEncoding.UTF8, 0, width, height, 3, IntArray(width * height * 3) { 100000 }, events, 0x69, 0)
    private fun page(commands: List<WolfCommand>, trigger: Int = 1, index: Int = 0, conditions: ByteArray = ByteArray(37).also { it[0] = trigger.toByte() }) = WolfPage(index, -1, "", 2, 1, 255, 0, conditions, byteArrayOf(3, 3, 3, 0), 3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
    private fun command(opcode: Int, args: IntArray = intArrayOf(), strings: List<String> = emptyList(), indent: Int = 0, offset: Int = 0) = WolfCommand(opcode, args, indent, strings, offset = offset)
    private fun textPicture(id: Int, text: String, duration: Int = 0, delay: Int = 0) = command(150,
        intArrayOf(0x20, id, duration, 1, 1, 1, 255, 0, 0, 100, 0, 0, 0x646464, delay), listOf(text))
    private fun common(id: Int, commands: List<WolfCommand>, returnValue: Int? = null) = WolfCommon(id, id, "common$id", 0, ByteArray(7), commands, "", "", emptyList(), byteArrayOf(), emptyList(), emptyList(), intArrayOf(), 0, emptyList(), "", null, returnValue, 0)
    private fun saveDatabase(): Map<WolfDatabaseKind, WolfDatabase> {
        val fields = listOf(WolfDatabaseField("number", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 42),
            WolfDatabaseField("text", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0))
        return mapOf(WolfDatabaseKind.MUTABLE to WolfDatabase("fixture", WolfEncoding.UTF8, 1, listOf(WolfDatabaseType(0, "stats", "", fields,
            listOf(WolfDatabaseRow(0, "original", intArrayOf(7), listOf("initial"))), 0, null))))
    }
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }
}
