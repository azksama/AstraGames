package fr.astragames.app.core.search

import fr.astragames.app.core.model.TagMatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndTagTest {
    @Test fun parserBuildsSafePrefixAndQuery() {
        assertEquals("moby* AND fantasy* AND english*", SearchParser.toFtsQuery("Moby fantasy, English"))
    }

    @Test fun tagModesRespectAllAnyAndExclude() {
        val game = setOf("fantasy", "rpg", "english")
        assertTrue(TagMatcher.matches(setOf("fantasy", "rpg"), game, TagMatchMode.ALL))
        assertTrue(TagMatcher.matches(setOf("horror", "rpg"), game, TagMatchMode.ANY))
        assertFalse(TagMatcher.matches(setOf("english"), game, TagMatchMode.EXCLUDE))
    }
}
