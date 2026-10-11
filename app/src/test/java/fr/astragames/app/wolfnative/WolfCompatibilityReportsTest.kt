package fr.astragames.app.wolfnative

import fr.astragames.wolf.*
import org.junit.Assert.*
import org.junit.Test

class WolfCompatibilityReportsTest {
    private fun record() = WolfAuditRecord("../../game", "日本語 • Seris", 1791722221349L, "test", "sha256-test", "Dossier d’origine",
        WolfCompatibilityAudit("Title", 372, 640, 480, 60, 2, 2, 2, 7, 8, 19,
            listOf(WolfAuditFeature("commande:252", "Commande 252", 4, 3)),
            listOf(WolfAuditFinding(WolfCapabilityIssue("Map.mps", 2, 252, 123, "Incompatible", "args=[13(0xd)]"), 1, 0, 3)),
            3, false, false, true, 51))
    @Test fun jsonRoundTripPreservesLocationsCountersAndNullableFields() {
        val original = record()
        assertEquals(original, WolfCompatibilityReports.decode(WolfCompatibilityReports.encode(original)))
        val missing = original.copy(audit = original.audit.copy(declaredEngineVersion = null,
            findings = listOf(WolfAuditFinding(WolfCapabilityIssue("Data", -1, -1, 0, "Error")))))
        assertEquals(missing, WolfCompatibilityReports.decode(WolfCompatibilityReports.encode(missing)))
    }
    @Test fun corruptedCountersCannotBecomeACompatibleReport() {
        val bad = WolfCompatibilityReports.encode(record()).replace("\"readableMaps\": 2", "\"readableMaps\": 3")
        try { WolfCompatibilityReports.decode(bad); fail("Impossible counts accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownSchemaIsRefusedAndTextIncludesSourceVersionAndScope() {
        try { WolfCompatibilityReports.decode(WolfCompatibilityReports.encode(record()).replace("\"schema\": 1", "\"schema\": 999")); fail("Unknown schema accepted") }
        catch (_: IllegalArgumentException) { }
        val text = record().textReport()
        assertTrue(text.contains("Astra test")); assertTrue(text.contains("Dossier d’origine"))
        assertTrue(text.contains("Aucune commande du jeu n’est exécutée")); assertTrue(text.contains("Winlator"))
    }
}
