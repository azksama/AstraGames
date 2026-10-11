package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfVideoRuntimeTest {
    @Test fun moviePicturesRetainIdentityOnMoveAndApplyPauseSeekAndVolumeWithoutRestart() {
        val runtime = wolfHostRuntimeFixture(listOf(show("Movies/intro_loop.mp4"), wait(1), effect(18, 0),
            effect(19, 1000), effect(20, 45), move(), get(21, 0), get(22, 1), wait(100)))
        runtime.tick()
        val initial = runtime.frame().pictures.single().video!!
        assertTrue(initial.loop)
        runtime.videoState(20, initial.copy(width = 320, height = 240, positionMs = 700, durationMs = 2000))
        repeat(2) { runtime.tick() }
        val controlled = runtime.frame().pictures.single().video!!
        assertEquals(initial.token, controlled.token)
        assertEquals(0, controlled.speedPercent); assertFalse(controlled.playing)
        assertEquals(45, controlled.volumePercent); assertEquals(1000, controlled.seekMs)
        assertEquals(1, controlled.seekRevision)
        assertEquals(1000, runtime.snapshotState().numbers[0]); assertEquals(2000, runtime.snapshotState().numbers[1])
        runtime.videoState(20, initial.copy(positionMs = 900, durationMs = 2000))
        assertEquals(controlled, runtime.frame().pictures.single().video)
        runtime.videoState(20, controlled.copy(positionMs = 1001, speedPercent = 100, volumePercent = 100))
        val measured = runtime.frame().pictures.single().video!!
        assertEquals(0, measured.speedPercent); assertEquals(45, measured.volumePercent)
        assertEquals(1001, measured.positionMs)
    }

    @Test fun restoreSeeksToTheMeasuredPositionAndReplacementIgnoresTheFormerDecoder() {
        val runtime = wolfHostRuntimeFixture(listOf(show("Movies/intro.mp4"), wait(100)))
        val fresh = runtime.snapshot()
        runtime.tick()
        val initial = runtime.frame().pictures.single().video!!
        runtime.videoState(20, initial.copy(width = 320, height = 240, positionMs = 765, durationMs = 2000))
        val saved = runtime.snapshot()
        val decoded = WolfSnapshot.decode(saved, "host")
        assertEquals(765, decoded.pictures.single().video!!.positionMs)
        runtime.restore(saved)
        val restored = runtime.frame().pictures.single().video!!
        assertNotEquals(initial.token, restored.token)
        assertEquals(765, restored.seekMs)
        runtime.videoState(20, initial.copy(positionMs = 1000))
        assertEquals(765, runtime.frame().pictures.single().video!!.positionMs)
        runtime.restore(fresh); runtime.tick()
        val replacement = runtime.frame().pictures.single().video!!
        assertNotEquals(restored.token, replacement.token)
        runtime.videoState(20, restored.copy(positionMs = 1234))
        assertEquals(0, runtime.frame().pictures.single().video!!.positionMs)
    }

    @Test fun movieQueriesUseMillisecondsAndOrdinaryPicturesReturnZero() {
        val runtime = wolfHostRuntimeFixture(listOf(show("Movies/intro.mp4"), wait(1), get(21, 0), get(22, 1), wait(100)))
        runtime.tick()
        val initial = runtime.frame().pictures.single().video!!
        runtime.videoState(20, initial.copy(positionMs = 1990, durationMs = 2000, playing = false))
        repeat(2) { runtime.tick() }
        assertEquals(2000, runtime.snapshotState().numbers[0]); assertEquals(2000, runtime.snapshotState().numbers[1])
        val image = wolfHostRuntimeFixture(listOf(show("Picture.png"), get(21, 0), get(22, 1), get(10, 2), wait(100)))
        image.tick()
        assertEquals(0, image.snapshotState().numbers[0]); assertEquals(0, image.snapshotState().numbers[1])
        assertEquals(-1, image.snapshotState().numbers[2])
        assertNull(image.frame().pictures.single().video)
    }

    private fun show(path: String) = WolfCommand(150, intArrayOf(0, 20, 0, 1, 1, 1, 255, 0, 0, 100, 0, 0, 0x646464, 0), 0, listOf(path))
    private fun move() = WolfCommand(150, intArrayOf(1, 20, 0, 1, 1, 1, 255, 5, 6, 100, 0, 0, 0x646464, 0), 0, emptyList())
    private fun effect(operation: Int, value: Int) = WolfCommand(290, intArrayOf(operation * 16, 0, 20, 20, value, 0, 0), 0, emptyList())
    private fun get(field: Int, variable: Int) = WolfCommand(124, intArrayOf(2_000_000 + variable, 0x4000, 20, field), 0, emptyList())
    private fun wait(frames: Int) = WolfCommand(180, intArrayOf(frames), 0, emptyList())
}
