package fr.astragames.app.wolfnative

import org.junit.Assert.*
import org.junit.Test

class WolfNativeFramePacerTest {
    @Test fun sixtyHzSimulationDoesNotLoseFramesWhenCallbacksArriveSlightlyEarly() {
        val pacer = WolfNativeFramePacer()
        val published = (0 until 180).count { tick ->
            val jitter = if (tick % 3 == 0) 200_000L else -200_000L
            pacer.shouldPublish(1_000_000_000L + tick * 1_000_000_000L / 60 + jitter, 60)
        }
        assertTrue("Dropped $published/180 frames under 0.2 ms jitter", published >= 177)
    }

    @Test fun thirtyFpsCapRemainsStableOnASixtyHzSimulation() {
        val pacer = WolfNativeFramePacer()
        val published = (0 until 180).count { tick ->
            pacer.shouldPublish(1_000_000_000L + tick * 1_000_000_000L / 60 + if (tick % 3 == 0) 200_000L else -200_000L, 30)
        }
        assertTrue("Cap emitted $published frames in three seconds", published in 89..91)
    }

    @Test fun resumedSessionDoesNotReplayOldDisplayDeadlines() {
        val pacer = WolfNativeFramePacer()
        assertTrue(pacer.shouldPublish(0, 60))
        assertTrue(pacer.shouldPublish(10_000_000_000, 60))
        assertFalse(pacer.shouldPublish(10_000_000_001, 60))
        assertTrue(pacer.shouldPublish(10_016_666_666, 60))
    }

    @Test fun ChangingTheCapAppliesItImmediately() {
        val pacer = WolfNativeFramePacer()
        assertTrue(pacer.shouldPublish(0, 30))
        assertFalse(pacer.shouldPublish(16_666_667, 30))
        assertTrue(pacer.shouldPublish(16_666_667, 60))
        assertTrue(pacer.shouldPublish(33_333_334, 60))
    }
}
