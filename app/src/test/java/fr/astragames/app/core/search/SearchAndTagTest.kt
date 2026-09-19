package fr.astragames.app.core.search

import fr.astragames.app.core.model.TagMatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndTagTest {
    @Test fun parserBuildsSafePrefixAndQuery() {
        assertEquals("moby* fantasy* english*", SearchParser.toFtsQuery("Moby fantasy, English"))
    }

    @Test fun punctuationUsesTheSameWordBoundariesAsFts() {
        assertEquals("rpg* maker* mv*", SearchParser.toFtsQuery("RPG_MAKER-MV"))
        assertEquals(emptyList<String>(), SearchParser.terms("--- ___ \""))
    }

    @Test fun punctuationCannotIntroduceFtsOperators() {
        assertEquals("alpha* beta*", SearchParser.toFtsQuery("alpha -beta alpha"))
    }

    @Test fun tagModesRespectAllAnyAndExclude() {
        val game = setOf("fantasy", "rpg", "english")
        assertTrue(TagMatcher.matches(setOf("fantasy", "rpg"), game, TagMatchMode.ALL))
        assertTrue(TagMatcher.matches(setOf("horror", "rpg"), game, TagMatchMode.ANY))
        assertFalse(TagMatcher.matches(setOf("english"), game, TagMatchMode.EXCLUDE))
    }

    @Test fun textualTagsSupportCommasBracketsAndDeduplication() {
        assertEquals(
            listOf("adventure", "fantasy", "female protagonist"),
            parseTextTagList("[adventure] [fantasy] [female protagonist] adventure")
        )
        assertEquals(
            listOf("female protagonist", "adventure", "fantasy"),
            parseTextTagList("female protagonist, adventure; fantasy")
        )
    }
}
