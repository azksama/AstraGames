package fr.astragames.wolf

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WolfRuntimeTest {
    @Test fun authoredGameRunsTitleNewGameDialogMovementAndTeleport() {
        val title = listOf(
            command(150, intArrayOf(0, 1, 0, 0, 0, 1, 255, 0, 0, 100, 0), listOf("Picture/title.png")),
            command(102, intArrayOf(19), listOf("New game", "Quit")),
            command(401, intArrayOf(2)),
            command(150, intArrayOf(2, 1, 0), indent = 1),
            command(130, intArrayOf(-2, 1, 1, 1, 32), indent = 1),
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
        val common = common(0, listOf(command(121, intArrayOf(1_600_003, 1_600_000, 2, 0)), command(172)), returnValue = 1_600_003)
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
        val runtime = fixture(listOf(command(121, intArrayOf(2_000_000, 8_000_000, 0, 0))))
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
        val tiles = IntArray(6 * 6 * 3)
        tiles[2 * 6 + 3] = 32
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

    @Test fun actualOfficialSamplesReportFirstUnsupportedBootCommandInsteadOfFalseSuccess() {
        listOf("sample22961/WOLF_RPG_Editor2", "sample3729/WOLF_RPG_Editor3").forEach { folder ->
            val root = File(System.getProperty("wolf.fixtureRoot"), folder)
            assumeTrue(File(root, "Data/BasicData/Game.dat").isFile)
            val source = object : WolfAssetSource {
                override fun read(path: String) = File(root, path).readBytes()
                override fun list(path: String) = File(root, path).list()?.toList().orEmpty()
                override fun exists(path: String) = File(root, path).exists()
            }
            val runtime = WolfRuntime.load(source)
            assertFalse(runtime.inspectCapabilities(includeAllMaps = true).supported)
            val failure = rejectsRuntime { repeat(5) { runtime.tick() } }
            println("$folder : ${failure.message}")
            if (folder.startsWith("sample22961")) {
                assertEquals(111, failure.opcode); assertEquals(48, failure.commonId); assertEquals(0x14d8c, failure.offset)
                assertTrue(failure.message!!.contains("9000115"))
            } else {
                assertEquals(221, failure.opcode); assertEquals(48, failure.commonId); assertEquals(0x1c1d4, failure.offset)
            }
        }
    }

    @Test fun bitMaskConditionRequiresEveryRequestedBitIncludingEmptyMask() {
        assertFalse(WolfVariables.compare(5, 3, 6))
        assertTrue(WolfVariables.compare(7, 3, 6))
        assertTrue(WolfVariables.compare(0, 0, 6))
    }

    @Test fun unknownSystemReadsAndWritesFailAndAreRejectedBeforeStateMutation() {
        val read = fixture(listOf(command(121, intArrayOf(2_000_000, 9_000_084, 0, 0), offset = 32)))
        assertTrue(read.inspectCapabilities().issues.any { it.reason.contains("9000084") })
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
        assertTrue(timing.inspectCapabilities().issues.any { it.reason.contains("timing") })
        assertTrue(rejectsRuntime { timing.tick() }.message!!.contains("timing"))
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
            command(121, intArrayOf(2_000_000, 7, 0, 0)), command(130, intArrayOf(-1, 3, 2, 0, 32)), command(101, strings = listOf("Save"))))
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
        val conditions = ByteArray(37).also { it[0] = 1; it[1] = 0x21; putInt(it, 5, 9_000_084); putInt(it, 21, 1) }
        val event = WolfEvent(12, "guard", 2, 2, listOf(page(emptyList(), conditions = conditions).copy(offset = 0x27)))
        val runtime = fixture(emptyList(), initialMap = map("guard-map", listOf(event)))
        assertTrue(runtime.inspectCapabilities().issues.any { it.eventId == 12 && it.reason.contains("9000084") })
        val failure = rejectsRuntime { runtime.tick() }
        assertEquals(12, failure.eventId); assertEquals(0x27, failure.offset)
    }

    @Test fun downwardPassageContinuesToBlockingLowerLayerAndUnsupportedPhysicsIsGated() {
        val tiles = IntArray(6 * 6 * 3).also { it[2 * 6 + 3] = 32; it[6 * 6 + 2 * 6 + 3] = 33 }
        val tileset = WolfTileset(0, "", "", emptyList(), byteArrayOf(), intArrayOf(0, 0x200, 15))
        val runtime = fixture(emptyList(), initialMap = map("passage", emptyList()).copy(tiles = tiles), tileset = tileset)
        runtime.key(NativeKey.RIGHT, true); repeat(20) { runtime.tick() }
        assertEquals(2f, runtime.snapshotState().x, .001f)
        val p = page(emptyList()).copy(flags = 0x83, graphicBlend = 3, movement = byteArrayOf(3, 3, 3, 2))
        val game = fixture(emptyList(), initialMap = map("unsupported", listOf(WolfEvent(3, "moving", 2, 2, listOf(p)))))
        val report = game.inspectCapabilities().description()
        assertTrue(report.contains("Demi-pas")); assertTrue(report.contains("Mélange")); assertTrue(report.contains("autonome"))
        assertEquals(3, rejectsRuntime { game.tick() }.eventId)
    }

    private fun rejectsRuntime(action: () -> Unit): WolfRuntimeException {
        try { action(); fail("Expected runtime diagnostic") } catch (failure: WolfRuntimeException) { return failure }
        throw AssertionError("unreachable")
    }
    private fun fixture(commands: List<WolfCommand>, commons: List<WolfCommon> = emptyList(), extraMaps: Map<Int, WolfMap> = emptyMap(), initialMap: WolfMap? = null, tileset: WolfTileset? = null): WolfRuntime {
        val source = object : WolfAssetSource { override fun read(path: String) = byteArrayOf(); override fun list(path: String) = emptyList<String>(); override fun exists(path: String) = true }
        val config = WolfGameConfig("Native fixture", "", WolfEncoding.UTF8, 16, 96, 96, 60, "Hero.png", 4, 4, 3, "sans", emptyList(), byteArrayOf(), intArrayOf(), emptyList(), 0xc4)
        val map = initialMap ?: map("title", listOf(WolfEvent(0, "title", 2, 2, listOf(page(commands)))))
        val data = WolfGameData(config, commons, emptyMap(), listOf(tileset ?: WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))), listOf(WolfMapReference(0, "title", "title.mps")) + extraMaps.keys.map { WolfMapReference(it, "map$it", "map$it.mps") }, WolfStartPosition(0, 2, 2))
        return WolfRuntime.fromData(source, data) { id -> if (id == 0) map else extraMaps[id] ?: error("Missing map $id") }
    }
    private fun map(name: String, events: List<WolfEvent>, width: Int = 6, height: Int = 6) = WolfMap(name, name, 103, WolfEncoding.UTF8, 0, width, height, 3, IntArray(width * height * 3), events, 0x69, 0)
    private fun page(commands: List<WolfCommand>, trigger: Int = 1, index: Int = 0, conditions: ByteArray = ByteArray(37).also { it[0] = trigger.toByte() }) = WolfPage(index, -1, "", 2, 1, 255, 0, conditions, byteArrayOf(3, 3, 3, 0), 3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
    private fun command(opcode: Int, args: IntArray = intArrayOf(), strings: List<String> = emptyList(), indent: Int = 0, offset: Int = 0) = WolfCommand(opcode, args, indent, strings, offset = offset)
    private fun common(id: Int, commands: List<WolfCommand>, returnValue: Int? = null) = WolfCommon(id, id, "common$id", 0, ByteArray(7), commands, "", "", emptyList(), byteArrayOf(), emptyList(), emptyList(), intArrayOf(), 0, emptyList(), "", null, returnValue, 0)
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }
}
