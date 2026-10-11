package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfScreenEffectsTest {
    @Test fun screenToneUsesSignedAdditiveValuesAndTheOriginalDurationPlusOneTimeline() {
        val effect = WolfScreenEffects()
        assertEquals(-255, WolfScreenEffects.percentage(0))
        assertEquals(0, WolfScreenEffects.percentage(100))
        assertEquals(127, WolfScreenEffects.percentage(150))
        effect.tone(NativeTone(-255, 127, 0), 2)
        assertEquals(NativeTone(), effect.state.tone)
        effect.tick(); assertEquals(NativeTone(), effect.state.tone)
        effect.tick(); assertEquals(NativeTone(-85, 43, 0), effect.state.tone)
        effect.tick(); assertEquals(NativeTone(-170, 85, 0), effect.state.tone)
        effect.tick(); assertEquals(NativeTone(-255, 127, 0), effect.state.tone)
        assertEquals(-1, effect.state.toneRemaining)
    }

    @Test fun flashIsANormalOverlayThatFadesBeforeDrawingAndNeverPersists() {
        val effect = WolfScreenEffects()
        effect.flash(NativeTone(-255, -128, 127), 2)
        assertEquals(NativeFlash(0, 127, 255, 255), effect.flashFrame())
        effect.tick(); assertEquals(NativeFlash(0, 127, 255, 170), effect.flashFrame())
        effect.tick(); assertEquals(NativeFlash(0, 127, 255, 85), effect.flashFrame())
        effect.tick(); assertNull(effect.flashFrame())
        assertEquals(NativeTone(), effect.state.tone)
    }

    @Test fun retargetingAndRestoringPreservesTheCurrentColorInsteadOfRestartingTheFade() {
        val effect = WolfScreenEffects()
        effect.tone(NativeTone(-120, 0, 0), 3); repeat(3) { effect.tick() }
        assertEquals(-60, effect.state.tone.red)
        effect.tone(NativeTone(60, 0, 0), 2)
        val restored = WolfScreenEffects().apply { restore(effect.state) }
        repeat(4) { effect.tick(); restored.tick(); assertEquals(effect.state, restored.state) }
        assertEquals(60, effect.state.tone.red)
    }

    @Test fun command151DecodesPackedBgrAndPersistsAnInProgressFlashAndTone() {
        fun command(code: Int, vararg args: Int) = WolfCommand(code, args, 0, emptyList())
        val commands = listOf(command(151, 0x00643200, 5), command(151, 0x01000064, 5), command(173))
        val source = object : WolfAssetSource {
            override fun read(path: String) = byteArrayOf()
            override fun list(path: String) = emptyList<String>()
            override fun exists(path: String) = true
        }
        val config = WolfGameConfig("Effects fixture", "", WolfEncoding.UTF8, 16, 96, 96, 60, "", 4, 4, 3,
            "sans", emptyList(), ByteArray(10), intArrayOf(0, 1, 1, 2, 2, 4, 4, 8, 8, 16, 16, 32, 32, 64, 64, 0, 96, 96, 0), emptyList(), 0xc4)
        val page = WolfPage(0, -1, "", 2, 1, 255, 0, ByteArray(37).also { it[0] = 1 }, byteArrayOf(3, 3, 3, 0),
            3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
        val map = WolfMap("effect", "effect", 103, WolfEncoding.UTF8, 0, 6, 6, 3,
            IntArray(108) { 100000 }, listOf(WolfEvent(0, "effect", 2, 2, listOf(page))), 0x69, 0)
        val data = WolfGameData(config, emptyList(), emptyMap(), listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
            listOf(WolfMapReference(0, "effect", "effect.mps")), WolfStartPosition(0, 2, 2))
        fun create() = WolfRuntime.fromData(source, data) { map }
        val vm = create(); vm.tick()
        assertEquals(NativeFlash(0, 0, 255, 255), vm.frame().flash)
        repeat(3) { vm.tick() }
        val copy = create().apply { restore(vm.snapshot()) }
        repeat(8) { vm.tick(); copy.tick(); assertEquals(vm.frame().tone, copy.frame().tone); assertEquals(vm.frame().flash, copy.frame().flash) }
        assertEquals(NativeTone(0, -127, -255), vm.frame().tone)
        assertNull(vm.frame().flash)
    }
}
