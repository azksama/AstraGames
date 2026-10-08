package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test

class WolfReportPreviewTest {
    @Test fun androidNoiseCannotHideWineConnectionFailure() {
        val report = "ASTRA\nÉtat : code de sortie 1\n" +
            "===== events =====\n" + "copy\n".repeat(20_000) +
            "===== runtime =====\nWine starts\n" + "trace\n".repeat(20_000) +
            "a wine server seems to be running, but I cannot connect to it.\n" +
            "===== server =====\nserver context\n" +
            "===== startup =====\nboot context\n" +
            "===== audio =====\naudio context\n" +
            "===== shutdown =====\ncleanup context\n" +
            "===== android =====\n" + "View update\n".repeat(100_000)
        val preview = wolfReportPreview(report)
        assertTrue(preview.length <= 48_000)
        assertTrue(preview.contains("code de sortie 1"))
        assertTrue(preview.contains("Wine starts"))
        assertTrue(preview.contains("but I cannot connect"))
        for (name in listOf("events", "runtime", "server", "startup", "audio", "shutdown", "android"))
            assertTrue(preview.contains("===== $name ====="))
    }
    @Test fun shortReportsAreUnchanged() { assertEquals("small report", wolfReportPreview("small report")) }
    @Test fun onlyTheSpecificServerErrorIsPresentedAsACause() {
        assertNotNull(wolfRuntimeFailureHint("wine: a wine server seems to be running, but I cannot connect to it."))
        assertNull(wolfRuntimeFailureHint("Warning, cannot pre-load libastra_exec_bridge.so"))
        assertNull(wolfRuntimeFailureHint("code de sortie 1"))
    }
}
