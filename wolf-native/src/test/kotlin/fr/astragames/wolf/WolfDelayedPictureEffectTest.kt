package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfDelayedPictureEffectTest {
    @Test fun delayedEffectCapturesItsArgumentsAndExecutesAfterTheNativePredecrementBoundary() {
        val runtime = wolfHostRuntimeFixture(listOf(show(), number(42), effect(2_000_000, 1), number(99), wait()))
        runtime.tick()
        assertEquals(0, runtime.frame().pictures.single().z)
        runtime.tick()
        assertEquals(0, runtime.frame().pictures.single().z)
        assertEquals(0, runtime.snapshotState().delayedPictures.single().remaining)
        val saved = runtime.snapshot()
        runtime.restore(saved)
        runtime.tick()
        assertEquals(42, runtime.frame().pictures.single().z)
    }

    @Test fun pictureCancelRemovesEffectsAndZeroDelayAppliesImmediately() {
        val cancelled = wolfHostRuntimeFixture(listOf(show(), effect(42, 1),
            WolfCommand(150, intArrayOf(3, 20, 0), 0, emptyList()), wait()))
        repeat(4) { cancelled.tick() }
        assertEquals(0, cancelled.frame().pictures.single().z)
        assertTrue(cancelled.snapshotState().delayedPictures.isEmpty())
        val immediate = wolfHostRuntimeFixture(listOf(show(), effect(13, 0), wait()))
        immediate.tick(); assertEquals(13, immediate.frame().pictures.single().z)
    }

    private fun show() = WolfCommand(150, intArrayOf(0, 20, 0, 1, 1, 1, 255, 0, 0, 100, 0), 0, listOf("Picture.png"))
    private fun number(value: Int) = WolfCommand(121, intArrayOf(2_000_000, value, 0, 0), 0, emptyList())
    private fun effect(z: Int, delay: Int) = WolfCommand(290, intArrayOf(28 * 16, 0, 20, 20, z, 0, 0, delay), 0, emptyList())
    private fun wait() = WolfCommand(180, intArrayOf(100), 0, emptyList())
}
