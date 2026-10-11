package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfHiddenContractsTest {
    @Test fun officialUnderscorePathSyntaxAndNestedOperandsUseTheExistingSearchContract() {
        val native = "<<FIND_PATH_DIR4_SX=1/SY=2/EX=5/EY=6/LEN=1>>"
        assertNull(WolfStringMacros.problem(native))
        assertEquals(WolfStringMacros.pathRequest(native.replace("DIR4_SX", "DIR4/SX")), WolfStringMacros.pathRequest(native))
        assertNull(WolfStringMacros.problem("<<FIND_PATH_DIR4_SX=\\cself[0]/SY=\\cself[1]/EX=\\cself[2]/EY=\\cself[3])>>"))
        assertNotNull(WolfStringMacros.problem("<<FIND_PATH_DIR4_SX=0/SY=0/EX=1/EY=1/NO_DIAG>>"))
    }
    @Test fun measurementsAndLineSelectionExecuteWithTheirLogicalWolfUnits() {
        val game = game(); val map = map()
        assertEquals("5", WolfStringMacros.evaluate("<<GET_STRING_BYTE_COUNT>>あAB", game, map))
        assertEquals("6", WolfStringMacros.evaluate("<<GET_STRING_WIDTH_COUNT>>Aéｱあ", game, map))
        assertEquals("second", WolfStringMacros.evaluate("<<CUT_LINE_STRING_1_1>>first\nsecond\nthird", game, map))
        assertEquals("second", WolfStringMacros.evaluate("<<CUT_LINE_STRING_1_-7>>first\nsecond\nthird", game, map))
        assertNotNull(WolfStringMacros.problem("<<GET_STRING_BYTE_COUNT>>\\c[2]formatted"))
    }
    @Test fun liveGraphicQueriesDoNotReadAnInactiveFirstPage() {
        val game = game(); val map = map()
        assertEquals("CharaChip/live.png", WolfStringMacros.evaluate("<<GET_MAPEV_FILENAME>>17", game, map,
            actorGraphic = { assertEquals(17, it); "CharaChip/live.png" }))
        assertEquals("current text", WolfStringMacros.evaluate("<<GET_FILENAME_FROM_PICTURE>>100", game, map,
            pictureFile = { assertEquals(100, it); "current text" }))
    }
    @Test fun allThreeDatabaseSchemasReturnFieldTypesAndDefinedOverflow() {
        val game = game(); val map = map()
        for (family in listOf("C", "U", "S")) {
            assertNull(WolfStringMacros.problem("<<GET_ISNUMBER_${family}DB_ITEM/TYPE=\\cself[0]/ITEM=0>>"))
            assertEquals("1", WolfStringMacros.evaluate("<<GET_ISNUMBER_${family}DB_ITEM/TYPE=0/ITEM=0>>", game, map))
            assertEquals("0", WolfStringMacros.evaluate("<<GET_ISNUMBER_${family}DB_ITEM/TYPE=0/ITEM=1>>", game, map))
            assertEquals("-1 <<Error>> Type Overflow: max=1", WolfStringMacros.evaluate("<<GET_ISNUMBER_${family}DB_ITEM/TYPE=1/ITEM=0>>", game, map))
            assertEquals("-1 <<Error>> Item Overflow: max=2", WolfStringMacros.evaluate("<<GET_ISNUMBER_${family}DB_ITEM/TYPE=0/ITEM=-1>>", game, map))
        }
    }
    @Test fun aggregateOperandsStayBoundedAndDoNotIncludeGameDialogue() {
        val command = WolfCommand(122, IntArray(256) { it }, 0, listOf("private game dialogue"), extension = byteArrayOf(0, 127, -1))
        val details = WolfDiagnosticOperands.command(command)
        assertTrue(details.contains("31(0x1f)")); assertTrue(details.contains("256 mots"))
        assertTrue(details.contains("007fff")); assertFalse(details.contains("private game dialogue"))
        val report = WolfCapabilities(listOf(WolfCapabilityIssue("map", 1, 122, 12, "reason", details)))
        assertFalse(report.description().contains("args=")); assertTrue(report.description(includeOperands = true).contains("args="))
    }
    private fun map() = WolfMap("map", "", 103, WolfEncoding.UTF8, 0, 10, 10, 0, intArrayOf(), emptyList(), 0x69, 0)
    private fun game(): WolfGameData {
        val config = WolfGameConfig("title", "", WolfEncoding.UTF8, 16, 96, 96, 60, "", 4, 4, 3, "sans", emptyList(), ByteArray(40), IntArray(20), emptyList(), 0xc4)
        val fields = listOf(WolfDatabaseField("number", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 0),
            WolfDatabaseField("string", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0))
        val database = WolfDatabase("db", WolfEncoding.UTF8, 1, listOf(WolfDatabaseType(0, "type", "", fields, emptyList(), 0, null)))
        return WolfGameData(config, emptyList(), WolfDatabaseKind.entries.associateWith { database }, emptyList(), emptyList(), WolfStartPosition(0, 0, 0))
    }
}
