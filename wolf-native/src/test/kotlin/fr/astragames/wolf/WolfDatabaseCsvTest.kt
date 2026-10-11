package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfDatabaseCsvTest {
    @Test fun quotesNewlinesAndMissingFieldsFollowTheNativeStateMachine() {
        val rows = WolfDatabaseCsv.read("heading\r\n7,\"a,b\"\r\n,\"line1\nline2\"\r\n9\r\n", 2, 20, true)
        assertEquals(3, rows.size)
        assertEquals(mapOf(0 to "7", 1 to "a,b"), rows[0].fields)
        assertEquals(mapOf(0 to "", 1 to "line1\r\nline2"), rows[1].fields)
        assertEquals(mapOf(0 to "9"), rows[2].fields)
    }

    @Test fun closedQuotesCommitBeforeEofWhileBareUnterminatedFieldsDoNot() {
        assertEquals(mapOf(0 to "7"), WolfDatabaseCsv.read("h\n7,unfinished", 2, 5, false).single().fields)
        assertEquals(mapOf(0 to "7", 1 to "completed"), WolfDatabaseCsv.read("h\n7,\"completed\"", 2, 5, false).single().fields)
        assertEquals(mapOf(0 to "a\"b"), WolfDatabaseCsv.read("h\n\"a\"\"b\"ignored", 1, 5, true).single().fields)
        assertEquals(1, WolfDatabaseCsv.read("header without newline", 1, 5, true).size)
        assertTrue(WolfDatabaseCsv.read("h\n", 1, 5, true).isEmpty())
    }

    @Test fun modernTabSeparatorAndDataNamesKeepTheExtraColumnOutOfTheValues() {
        val rows = WolfDatabaseCsv.read("\ufeffh\n8\t\"台詞\"\t<<!--DATANAME--!>>row<<COMMA>>name,\n9,text,\n", 2, 1, true)
        assertEquals(1, rows.size); assertEquals("row,name", rows.single().name)
        assertEquals(mapOf(0 to "8", 1 to "台詞"), rows.single().fields)
        assertEquals("8\ttext", WolfDatabaseCsv.read("h\n8\ttext\n", 1, 1, false).single().fields[0])
    }

    @Test fun nativeExportUsesBinaryCrlfBomAndVersionedQuoteEscaping() {
        val type = database().types.single()
        val modern = WolfDatabaseCsv.write(type, 0, 1, true, 369, { _, _ -> 12 }, { _, _ -> "a\"b\nc" }, { "row,name" })
        assertEquals("\ufeff\"number\",\"text\",\r\n12,\"a\"\"b\nc\",<<!--DATANAME--!>>row<<COMMA>>name,\r\n", modern)
        val legacy = WolfDatabaseCsv.write(type, 0, 1, false, 225, { _, _ -> 12 }, { _, _ -> "a\"b" }, { "unused" })
        assertEquals("\"number\",\"text\",\r\n12,\"a\"b\",\r\n", legacy)
    }

    @Test fun importsApplyAtomicallyAndDoNotMutateTheParsedMetadata() {
        val data = database()
        val variables = WolfVariables(mapOf(WolfDatabaseKind.USER to data)) { _, _ -> null }
        variables.importCsv(WolfDatabaseKind.USER, 0, 1, listOf(WolfDatabaseCsv.Row(mapOf(0 to "42 suffix", 1 to "new"), "named")))
        assertEquals(2, variables.rowCount(WolfDatabaseKind.USER, 0)); assertEquals(42, variables.databaseNumber(WolfDatabaseKind.USER, 0, 1, 0))
        assertEquals("new", variables.readString(1_000_000_101, EventAddress(0))); assertEquals("named", variables.rowName(WolfDatabaseKind.USER, 0, 1))
        assertEquals(1, data.types.single().rows.size); assertEquals("initial", data.types.single().string(0, 1))
        val before = variables.imports.state()
        try { variables.importCsv(WolfDatabaseKind.USER, 0, 0, listOf(WolfDatabaseCsv.Row(mapOf(0 to "9")), WolfDatabaseCsv.Row(mapOf(3 to "bad")))); fail("Missing field") }
        catch (_: IllegalStateException) { }
        assertEquals(before, variables.imports.state())
        val restored = WolfVariables(mapOf(WolfDatabaseKind.USER to data)) { _, _ -> null }
        restored.imports.restore(before); assertEquals("new", restored.databaseString(WolfDatabaseKind.USER, 0, 1, 1))
    }

    @Test fun malformedOverlaysAreRefusedBeforeReplacingImportedCells() {
        val changes = WolfDatabaseChanges(mapOf(WolfDatabaseKind.USER to database()))
        val key = WolfDatabaseChanges.RowKey(WolfDatabaseKind.USER, 0)
        val cell = WolfDatabaseChanges.Cell(WolfDatabaseKind.USER, 0, 0, 0)
        val good = WolfDatabaseChanges.State(mapOf(key to 1), mapOf(cell to 7), emptyMap())
        changes.restore(good)
        try { changes.restore(good.copy(strings = mapOf(cell to "wrong type"))); fail("Invalid value type") }
        catch (_: IllegalArgumentException) { }
        assertEquals(good, changes.state())
    }

    private fun database(): WolfDatabase {
        val fields = listOf(WolfDatabaseField("number", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 19),
            WolfDatabaseField("text", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0))
        return WolfDatabase("csv", WolfEncoding.UTF8, 1, listOf(WolfDatabaseType(0, "stats", "", fields,
            listOf(WolfDatabaseRow(0, "original", intArrayOf(7), listOf("initial"))), 0, null)))
    }
}
