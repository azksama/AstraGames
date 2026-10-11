package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class WolfCompatibilityAuditTest {
    private val source = object : WolfAssetSource {
        override fun read(path: String): ByteArray = error("No game command or asset should be executed/read: $path")
        override fun exists(path: String) = false
        override fun list(path: String) = emptyList<String>()
    }
    private fun game(commons: List<WolfCommon> = emptyList(), count: Int = 2) = WolfGameData(
        WolfGameConfig("Audit fixture", "", WolfEncoding.UTF8, 16, 96, 96, 60, "", 4, 4, 3, "", emptyList(),
            ByteArray(20), IntArray(19).also { it[18] = 372 }, emptyList(), 0xc4), commons, emptyMap(),
        listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
        List(count) { WolfMapReference(it, "map$it", "Data/MapData/map$it.mps") }, WolfStartPosition(0, 0, 0))
    private fun page(commands: List<WolfCommand>, index: Int = 0) = WolfPage(index, -1, "", 2, 1, 255, 0,
        ByteArray(37), byteArrayOf(3, 3, 3, 0), 3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
    private fun map(id: Int, commands: List<WolfCommand> = emptyList(), pages: List<WolfPage>? = null) = WolfMap("map$id", "", 103,
        WolfEncoding.UTF8, 0, 2, 2, 3, IntArray(12), listOf(WolfEvent(0, "event", 0, 0, pages ?: listOf(page(commands)))), 0x69, 0)
    private fun common(id: Int, commands: List<WolfCommand>) = WolfCommon(id, id, "common$id", 0, ByteArray(7), commands,
        "", "", emptyList(), byteArrayOf(), emptyList(), emptyList(), intArrayOf(), 0, emptyList(), "", null, null, 0)

    @Test fun allCommonEventsAndInactivePagesAreInspectedWithoutRunningTheGame() {
        val bad = WolfCommand(252, intArrayOf(42), 0, listOf("PRIVATE_DIALOGUE"), offset = 41)
        val data = game(listOf(common(9, listOf(bad.copy(offset = 18)))))
        val loaded = mutableListOf<Int>()
        val audit = WolfCompatibilityAuditor.inspectData(source, data, { id ->
            loaded += id
            map(id, pages = listOf(page(listOf(WolfCommand(0, intArrayOf(), 0, emptyList()))), page(listOf(bad), 1)))
        })
        assertEquals(listOf(0, 1), loaded)
        assertEquals(5L, audit.commands); assertEquals(4L, audit.pages)
        assertEquals(3, audit.findings.size); assertEquals(WolfAuditVerdict.BLOCKED, audit.verdict)
        assertEquals(3L, audit.features.single { it.key == "commande:252" }.blocked)
        assertTrue(audit.findings.any { it.issue.eventId == 9 && it.mapId == null })
        assertTrue(audit.findings.any { it.pageIndex == 1 && it.mapId == 1 })
        assertFalse(audit.textReport().contains("PRIVATE_DIALOGUE"))
    }
    @Test fun noKnownBlockerDoesNotClaimAPlaythroughOrFrameRate() {
        val audit = WolfCompatibilityAuditor.inspectData(source, game(), { map(it) })
        assertEquals(WolfAuditVerdict.NO_KNOWN_BLOCKER, audit.verdict)
        assertTrue(audit.complete)
        assertTrue(audit.textReport().contains("performances nécessitent une vérification"))
    }
    @Test fun dynamicReferencesAndHostRequestsAreExplicitlyConditional() {
        val command = WolfCommand(150, intArrayOf(0, 1, 0, 1, 1, 1, 255, 0, 0, 100, 0), 0, listOf("Picture/\\cself[5].png"))
        val audit = WolfCompatibilityAuditor.inspectData(source, game(), { map(it, listOf(command)) })
        assertTrue(audit.findings.isEmpty())
        assertEquals(WolfAuditVerdict.RUNTIME_CHECKS_REQUIRED, audit.verdict)
        assertEquals(2L, audit.features.single { it.key == "commande:150" }.runtimeChecks)
        assertTrue(audit.features.any { it.key.startsWith("variable:") })
    }
    @Test fun unreadableStartingMapDoesNotPreventInspectingCommonsAndOtherMaps() {
        val data = game(listOf(common(7, listOf(WolfCommand(256, intArrayOf(), 0, emptyList())))), 3)
        val progress = mutableListOf<WolfAuditProgress>()
        val loaded = mutableListOf<Int>()
        val audit = WolfCompatibilityAuditor.inspectData(source, data, { id ->
            loaded += id; if (id == 0) error("fixture unreadable") else map(id)
        }, progress::add)
        assertEquals(listOf(0, 1, 2), loaded)
        assertEquals(3, audit.visitedMaps); assertEquals(2, audit.readableMaps)
        assertEquals(WolfAuditVerdict.INCOMPLETE, audit.verdict)
        assertTrue(audit.findings.any { it.issue.opcode == 256 })
        assertEquals(3, progress.last().completedMaps)
    }
    @Test fun scanContinuesBeyondTheLaunchDiagnosticBudget() {
        val audit = WolfCompatibilityAuditor.inspectData(source, game(count = 600), { id ->
            map(id, listOf(WolfCommand(257, intArrayOf(), 0, emptyList(), offset = id)))
        })
        assertTrue(audit.complete); assertEquals(600, audit.visitedMaps)
        assertEquals(600, audit.findings.size); assertEquals(600L, audit.commands)
    }
    @Test fun truncatedDetailsStillCountEveryInstructionAndNeverAdvertiseCompleteCoverage() {
        val commands = List(WolfCompatibilityAuditor.MAX_DETAILS + 1) { WolfCommand(255, intArrayOf(), 0, emptyList(), offset = it) }
        val audit = WolfCompatibilityAuditor.inspectData(source, game(count = 1), { map(it, commands) })
        assertEquals(commands.size.toLong(), audit.commands)
        assertEquals(WolfCompatibilityAuditor.MAX_DETAILS, audit.findings.size)
        assertTrue(audit.detailsTruncated); assertFalse(audit.complete)
        assertEquals(WolfAuditVerdict.INCOMPLETE, audit.verdict)
    }
    @Test fun cancellationPropagatesInsteadOfBecomingAnIncompatibility() {
        var checkpoints = 0
        try {
            WolfCompatibilityAuditor.inspectData(source, game(count = 600), { map(it) }, checkpoint = {
                if (++checkpoints == 20) throw CancellationException("fixture cancelled")
            })
            fail("Cancellation was swallowed")
        } catch (expected: CancellationException) { assertEquals("fixture cancelled", expected.message) }
    }
    @Test fun metadataFailureProducesAnExportableIncompleteReport() {
        val audit = WolfCompatibilityAuditor.inspect(source)
        assertEquals(WolfAuditVerdict.INCOMPLETE, audit.verdict)
        assertFalse(audit.finished); assertTrue(audit.findings.single().issue.reason.contains("Données non inspectables"))
        assertTrue(audit.textReport().contains("INCOMPATIBILITÉS"))
    }
}
