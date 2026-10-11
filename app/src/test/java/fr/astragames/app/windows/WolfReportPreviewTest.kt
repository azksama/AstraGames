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
    @Test fun moduleRegistrationFailureDoesNotBlameAnUnobservedProfile() {
        val hint = requireNotNull(wolfRuntimeFailureHint("57626.196:0020:00c0:err:module:alloc_module rtl_rb_tree_put failed."))
        assertTrue(hint.contains("cause n’est pas déterminée")); assertFalse(hint.contains("peut être en cause"))
        assertNull(wolfRuntimeFailureHint("Error loading needed lib libgcrypt.so"))
    }
    @Test fun networkProcessFaultTakesPrecedenceOverModuleNoise() {
        val hint = requireNotNull(wolfRuntimeFailureHint("err:module:alloc_module rtl_rb_tree_put failed\nnsiproxy.so(update_if_table+1e4)\nSIGSEGV"))
        assertTrue(hint.contains("interfaces réseau")); assertTrue(hint.contains("ne prouve pas"))
    }
    @Test fun onlyTheSpecificServerErrorIsPresentedAsACause() {
        assertNotNull(wolfRuntimeFailureHint("wine: a wine server seems to be running, but I cannot connect to it."))
        assertNull(wolfRuntimeFailureHint("Warning, cannot pre-load libastra_exec_bridge.so"))
        assertNull(wolfRuntimeFailureHint("code de sortie 1"))
    }
}
