package fr.astragames.app.wolfnative

/** Keeps the display deadline on its original phase despite small scheduler jitter. */
internal class WolfNativeFramePacer {
    private var nextAt = 0L
    private var lastFps = 0

    fun shouldPublish(now: Long, maxFps: Int): Boolean {
        require(maxFps in 1..240)
        val period = 1_000_000_000L / maxFps
        if (maxFps != lastFps) { lastFps = maxFps; nextAt = now }
        if (now < nextAt) return false
        // A pause discards old deadlines. Normal jitter must not restart the interval,
        // which otherwise drops nearly every second frame at a 60 Hz simulation rate.
        nextAt = if (now - nextAt > period * 2) now + period else nextAt + period
        return true
    }
}
