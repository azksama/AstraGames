package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test
import com.winlator.renderer.RenderStatistics

class WolfFontConfigurationTest {
    @Test fun stableConfigurationEscapesPathsAndKeepsCompatibleFallbackPolicy() {
        val source = WolfFontConfiguration.xml("/game/A&B<fonts>", "/cache/shared", WolfFontPolicy.SYSTEM)
        assertTrue(source.contains("/game/A&amp;B&lt;fonts&gt;"))
        assertTrue(source.contains("/system/fonts"))
        assertTrue(source.contains("MS PGothic"))
        assertEquals(source, WolfFontConfiguration.xml("/game/A&B<fonts>", "/cache/shared", WolfFontPolicy.SYSTEM))
        assertFalse(WolfFontConfiguration.xml("/fonts", "/cache", WolfFontPolicy.BUNDLED).contains("/system/fonts"))
    }
    @Test fun rendererPercentilesIncludeDriverWaitsAndResetBoundedSamples() {
        val statistics = RenderStatistics()
        val now = System.nanoTime()
        statistics.frame(true, now, 1_000_000, 1024, 1, 0, 100_000)
        statistics.frame(true, now + 16_000_000, 2_000_000, 2048, 0, 1, 200_000)
        statistics.frame(true, now + 66_000_000, 20_000_000, 4096, 0, 1, 10_000_000)
        val result = statistics.snapshotAndReset()
        assertTrue(result.contains("soumission p95=20.00ms/p99=20.00ms"))
        assertTrue(result.contains("écart images p95=50.00ms/p99=50.00ms"))
        assertTrue(result.contains("complets=1, partiels=2"))
        val empty = statistics.snapshotAndReset()
        assertTrue(empty.contains("dessins=0"))
        assertTrue(empty.contains("p99=0.00ms"))
    }
}
