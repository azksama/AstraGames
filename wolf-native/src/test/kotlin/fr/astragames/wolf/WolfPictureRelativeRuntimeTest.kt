package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfPictureRelativeRuntimeTest {
    @Test fun relativeMovesUseTheCurrentPositionWhileSameSentinelsKeepTheFormerDestination() {
        val runtime = wolfHostRuntimeFixture(listOf(picture(0, 10, 20), picture(1, 30, 40, duration = 4),
            wait(1), picture(0x02000001, 5, -1_000_000), wait(100)))
        runtime.tick()
        assertEquals(10f, runtime.frame().pictures.single().x, 0f)
        runtime.tick()
        assertEquals(15f, runtime.frame().pictures.single().x, 0f)
        runtime.tick()
        val moved = runtime.frame().pictures.single()
        assertEquals(25f, moved.x, 0f) // Two interpolation ticks reach20; relative +5 is25.
        assertEquals(40f, moved.y, 0f) // SAME retains the former target; relative does not add it twice.
    }

    @Test fun relativeShowWithoutAnExistingPictureUsesTheOriginAndNativeTextCompletionIsDistinctFromMovies() {
        val runtime = wolfHostRuntimeFixture(listOf(picture(0x02001000, 160, 120),
            WolfCommand(150, intArrayOf(0x20, 21, 0, 1, 1, 1, 255, 0, 0, 100, 0), 0, listOf("text")),
            WolfCommand(124, intArrayOf(2_000_000, 0x4000, 21, 10), 0, emptyList()), wait(1),
            WolfCommand(124, intArrayOf(2_000_001, 0x4000, 21, 10), 0, emptyList()), wait(100)))
        runtime.tick()
        assertEquals(160f, runtime.frame().pictures.first { it.id == 20 }.x, 0f)
        assertEquals(120f, runtime.frame().pictures.first { it.id == 20 }.y, 0f)
        assertEquals(0, runtime.snapshotState().numbers[0]) // SHOW resets progress before the first text tick.
        repeat(2) { runtime.tick() }
        assertEquals(1, runtime.snapshotState().numbers[1])
        assertEquals(160f, runtime.frame().pictures.first { it.id == 20 }.x, 0f)
        assertEquals(120f, runtime.frame().pictures.first { it.id == 20 }.y, 0f)
    }
    private fun picture(flags: Int, x: Int, y: Int, duration: Int = 0) = WolfCommand(150,
        intArrayOf(flags, 20, duration, 1, 1, 1, 255, x, y, 100, 0), 0, listOf("Picture.png"))
    private fun wait(frames: Int) = WolfCommand(180, intArrayOf(frames), 0, emptyList())
}
