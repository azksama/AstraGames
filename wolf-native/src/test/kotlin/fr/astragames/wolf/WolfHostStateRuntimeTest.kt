package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfHostStateRuntimeTest {
    private fun command(op: Int, vararg args: Int, path: String? = null) = WolfCommand(op, args, 0, listOfNotNull(path))
    private fun general(field: Int, index: Int = field) = command(124, 2_000_000 + index, 0x3000, field)
    private fun actor(field: Int) = command(124, 2_000_100 + field, 0x1000, -2, field)
    private fun picture(field: Int, effects: Boolean = false, id: Int = 20, index: Int = field) =
        command(124, 2_000_200 + index, 0x4000 or if (effects) 64 else 0, id, field)
    private fun number(vm: WolfRuntime, index: Int) = vm.snapshotState().numbers[index] ?: error("Missing result $index: ${vm.frame().diagnostic}")

    @Test fun everyNumberedStateSlotAndReservedFieldUsesTheSharedAdmissionLedger() {
        val catalogs = listOf(WolfStateSupport.actorCatalog, WolfStateSupport.positionCatalog,
            WolfStateSupport.generalCatalog, WolfStateSupport.pictureCatalog)
        assertEquals(149, catalogs.sumOf { it.size })
        assertEquals(113, (1..4).sumOf { category -> catalogs[category - 1].count { WolfStateSupport.supports(category, it) } })
        for (category in 1..4) for (field in 0..255) {
            val c = when (category) {
                2 -> command(124, 2_000_000, 0x2000 or (field shl 16), 0, 0)
                3 -> command(124, 2_000_000, 0x3000, field)
                else -> command(124, 2_000_000, category shl 12, -2, field)
            }
            assertEquals("category=$category field=$field", WolfStateSupport.supports(category, field), WolfRuntime.capabilityProblem(c) == null)
        }
    }

    @Test fun generalReadsUseActualHostMemoryRenderTimePointerAndEngineState() {
        val vm = wolfHostRuntimeFixture((0..35).map(::general) + command(180, 100))
        vm.availableMemory(8L * 1024 * 1024 * 1024); vm.usedMemory(64L * 1024 * 1024)
        vm.renderedDuration(1_234_567); vm.mouse(40f, 44f, 7, 3)
        assertTrue(vm.inspectCapabilities().supported); vm.tick(); assertNull(vm.frame().diagnostic)
        for ((field, expected) in mapOf(0 to 0, 1 to -1, 2 to -1, 3 to 0, 4 to 0, 5 to 0, 6 to 0,
            7 to 2, 8 to 2, 9 to 2, 10 to 3, 11 to 0, 12 to 0, 13 to 0, 14 to 0, 15 to 6, 16 to 6,
            17 to -1, 20 to 40, 21 to 44, 22 to 64, 23 to 4096, 24 to 1, 25 to 0, 26 to 1,
            27 to 0, 28 to -1, 29 to 1, 30 to 0, 31 to 100, 32 to 0, 33 to 0, 34 to 0, 35 to 12)) {
            assertEquals("general$field", expected, number(vm, field))
        }
    }

    @Test fun actorFieldsUseLiveSpriteMetadataAndRestoringBeforeTheFirstTickKeepsMovement() {
        val vm = wolfHostRuntimeFixture(WolfStateSupport.actor.sorted().map(::actor) + command(180, 100),
            movement = byteArrayOf(6, 4, 5, 2))
        val early = vm.snapshot(); vm.restore(early); vm.tick(); assertNull(vm.frame().diagnostic)
        for ((field, expected) in mapOf(0 to 2, 1 to 2, 2 to 4, 3 to 4, 8 to -1, 10 to -2,
            12 to 0, 16 to 1, 18 to 3, 19 to 4, 26 to 255, 27 to 0))
            assertEquals("actor$field", expected, number(vm, 100 + field))
        val state = vm.snapshotState().actors.single()
        assertEquals(2, requireNotNull(state.motion).autonomousMode); assertEquals(5, requireNotNull(state.motion).frequency); assertEquals(4, state.speed)
        assertEquals(6, requireNotNull(state.animation).frequency)
    }

    @Test fun allMouseButtonsWheelEdgesAndDurationsAreSampledOncePerLogicalFrame() {
        val reads = listOf(command(123, 2_000_000, 0x370), command(123, 2_000_001, 0x20370),
            command(123, 2_000_002, 0x30370), command(123, 2_000_003, 0x40370)) +
            (7..12).map { general(it, it + 10) } + command(172)
        val vm = wolfHostRuntimeFixture(reads, fps = 30)
        vm.mouse(12f, 16f, 7, 2); vm.tick()
        assertEquals(20, number(vm, 0)); assertEquals(20, number(vm, 1)); assertEquals(0, number(vm, 2))
        assertEquals(1, number(vm, 3)); assertEquals(2, number(vm, 17)); assertEquals(2, number(vm, 20))
        vm.mouse(15f, 20f, 6); vm.tick()
        assertEquals(21, number(vm, 0)); assertEquals(0, number(vm, 1)); assertEquals(20, number(vm, 2))
        assertEquals(2, number(vm, 3)); assertEquals(0, number(vm, 17)); assertEquals(1, number(vm, 18)); assertEquals(1, number(vm, 19))
        assertEquals(0, number(vm, 20)); assertEquals(3, number(vm, 21)); assertEquals(4, number(vm, 22))
        vm.releaseInput(); vm.tick()
        assertEquals(0, number(vm, 0)); assertEquals(0, number(vm, 1)); assertEquals(0, number(vm, 2)); assertEquals(0, number(vm, 3))
    }

    @Test fun sampledAudioUsesTheSelectedBgsVoiceAndStopsNeverExposeStaleDecoderState() {
        val vm = wolfHostRuntimeFixture(listOf(
            command(140, 0x02000000, 0, 0, 0, 80, 100, path = "Music/field.ogg"),
            command(140, 0x02000010, 0, 0, 0, 60, 100, 0, 0, 3, path = "Music/rain.ogg"),
            command(121, 9_000_099, 3, 0, 0), command(180, 1),
            general(3), general(4), general(5), general(6),
            command(140, 0x00ffff00, 0, 0, 0), general(3, 7), general(5, 8), command(180, 100)))
        vm.tick()
        vm.audioPlayback(listOf(WolfAudioPlayback(0, 0, "Data/Music/field.ogg", 456, 2000),
            WolfAudioPlayback(1, 3, "Data/Music/rain.ogg", 900, 3000),
            WolfAudioPlayback(1, 0, "Data/Music/rain.ogg", 1, 1)))
        repeat(2) { vm.tick() }; assertNull(vm.frame().diagnostic)
        assertEquals(456, number(vm, 3)); assertEquals(900, number(vm, 4))
        assertEquals(2000, number(vm, 5)); assertEquals(3000, number(vm, 6))
        assertEquals(0, number(vm, 7)); assertEquals(0, number(vm, 8))
    }

    @Test fun pictureStatePreservesDurationAcrossSaveAndUsesNativeFrameUnitsAtThirtyFps() {
        for (fps in listOf(30, 60)) {
            val vm = wolfHostRuntimeFixture(listOf(command(180, 100)), fps = fps)
            val p = NativePicture(20, x = 0f, y = 5f, moveDuration = 4, moveRemaining = 4)
            val target = p.copy(x = 40f)
            vm.restoreState(vm.snapshotState().copy(pictures = listOf(p), tweens = listOf(WolfSnapshot.TweenState(p, target, 4, 0, false))))
            vm.tick()
            val first = vm.snapshotState().pictures.single()
            assertEquals(if (fps == 30) 20f else 10f, first.x, 0f)
            assertEquals(4, first.moveDuration); assertEquals(if (fps == 30) 2 else 3, first.moveRemaining)
            val saved = vm.snapshot(); vm.restore(saved)
            repeat(if (fps == 30) 1 else 3) { vm.tick() }
            assertEquals(40f, vm.snapshotState().pictures.single().x, 0f)
            assertEquals(0, vm.snapshotState().pictures.single().moveRemaining)
            vm.tick(); assertEquals(-1, vm.snapshotState().pictures.single().moveRemaining)
            assertEquals(4, vm.snapshotState().pictures.single().moveDuration)
            vm.restore(vm.snapshot()); assertEquals(4, vm.snapshotState().pictures.single().moveDuration)
        }
    }

    @Test fun pictureFieldsIncludeEffectsOnlyWhenRequestedAndNeighborQueriesWorkWithoutATarget() {
        val vm = wolfHostRuntimeFixture(listOf(picture(27), picture(27, effects = true, index = 100),
            picture(23), picture(24), picture(25), picture(26), picture(32), picture(33), picture(34), picture(35),
            picture(36, id = 25), picture(37, id = 25), command(180, 100)))
        val p = NativePicture(20, z = -10, anchor = 5, divisionsX = 3, divisionsY = 4, moveDuration = 7, moveRemaining = 7)
        val effects = WolfPictureEffects().apply { apply(20, 1, 20, 0, 0, 0) }.state()
        vm.restoreState(vm.snapshotState().copy(pictures = listOf(p, NativePicture(40)), pictureEffects = effects,
            delayedPictures = listOf(WolfSnapshot.DelayedPictureState(20, 1, 0, p, 8), WolfSnapshot.DelayedPictureState(20, 1, 0, p, 12))))
        vm.tick(); assertNull(vm.frame().diagnostic)
        assertEquals(100, number(vm, 227)); assertEquals(120, number(vm, 300))
        assertEquals(-10, number(vm, 223)); assertEquals(10, number(vm, 224)); assertEquals(3, number(vm, 225)); assertEquals(4, number(vm, 226))
        assertEquals(7, number(vm, 232)); assertEquals(6, number(vm, 233)); assertEquals(7, number(vm, 234)); assertEquals(11, number(vm, 235))
        assertEquals(20, number(vm, 236)); assertEquals(40, number(vm, 237))
    }
}
