package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfTextExpansionTest {
    @Test fun collapsesNestedDatabaseArgumentsBeforePresentationValidation() {
        val source = "\\cdb[\\cself[0]:\\cself[1]:\\cself[2]]G\\udb[1:\\v[0]:0]"
        assertEquals("16G16", WolfTextExpansion.staticShape(source))
        assertEquals("16", WolfTextExpansion.staticShape("\\sdb[\\udb[1:\\cdb[2:3:4]:0]:2:0]"))
        assertEquals("\\font[16]16", WolfTextExpansion.staticShape("\\font[\\v[0]]\\cdb[13:\\cself[20]:3]"))
    }

    @Test fun expandsActualValuesBeforeReadingAConcreteDatabaseCell() {
        val calls = mutableListOf<Pair<String, String>>()
        val result = WolfTextExpansion.expand("\\cdb[\\cself[0]:\\v[0]:\\cself[1]]") { family, argument ->
            calls += family to argument
            when (family) {
                "cself" -> if (argument == "0") "13" else "3"
                "v" -> "16"
                "cdb" -> { assertEquals("13:16:3", argument); "42" }
                else -> error("Unexpected family")
            }
        }
        assertEquals("42", result)
        assertEquals(listOf("cself" to "0", "v" to "0", "cself" to "1", "cdb" to "13:16:3"), calls)
    }

    @Test fun protectsQuotedEscapesAndPreservesUnknownFormattingForRejection() {
        assertFalse(WolfTextExpansion.hasReference("\\\\cdb[13:16:3]"))
        assertEquals("\\\\cdb[13:16:3]", WolfTextExpansion.staticShape("\\\\cdb[13:16:3]"))
        assertEquals("\\\\16", WolfTextExpansion.staticShape("\\\\\\v[0]"))
        assertEquals("\\unknown[16]", WolfTextExpansion.staticShape("\\unknown[\\v[0]]"))
        assertTrue(WolfTextExpansion.hasReference("\\cdb[\\v[0]:0:0]"))
    }

    @Test fun acceptsTheHundredthLayerButRejectsAdditionalLayersAndCycles() {
        fun nested(count: Int) = "\\v[".repeat(count) + "0" + "]".repeat(count)
        assertEquals("16", WolfTextExpansion.staticShape(nested(100)))
        assertThrows(IllegalArgumentException::class.java) { WolfTextExpansion.staticShape(nested(101)) }
        assertThrows(IllegalArgumentException::class.java) { WolfTextExpansion.expand("\\s[0]") { _, _ -> "\\s[0]" } }
    }

    @Test fun boundsInputAndExpandedValuesWithoutTruncation() {
        assertThrows(IllegalArgumentException::class.java) { WolfTextExpansion.staticShape("a".repeat(1_000_001)) }
        assertThrows(IllegalArgumentException::class.java) {
            WolfTextExpansion.expand("\\s[0]") { _, _ -> "a".repeat(1_000_001) }
        }
    }
}
