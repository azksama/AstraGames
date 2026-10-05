package fr.astragames.app.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class EditDistanceTest {
    @Test fun keepsEmptyAndKnownDistancesSymmetric() {
        listOf(Triple("", "abc", 3), Triple("kitten", "sitting", 3), Triple("ゲーム", "ゲーム", 0), Triple("ab", "ba", 2))
            .forEach { (left, right, expected) ->
                assertEquals(expected, levenshtein(left, right))
                assertEquals(expected, levenshtein(right, left))
            }
    }
}
