package fr.astragames.app.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationTimingTest {
    @Test fun estimateUsesCharactersAndExcludesWarmupBeforeTheStart() {
        val timing = TranslationTiming(60_000, 1000)
        assertNull(timing.completed(100, 61_000))
        assertNull(timing.completed(100, 62_000))
        assertEquals(7L, timing.completed(100, 63_000))
        assertEquals(4L, timing.completed(300, 66_000))
        assertEquals(0L, timing.completed(400, 70_000))
    }

    @Test fun estimateForARetryOnlyAccountsForPendingCharacters() {
        val timing = TranslationTiming(0, 40)
        timing.completed(10, 1000)
        timing.completed(10, 2000)
        assertEquals(1L, timing.completed(10, 3000))
        assertEquals(0L, timing.completed(10, 4000))
    }

    @Test fun modelLineBreaksAreNormalizedWithoutAcceptingInjectedCommands() {
        assertEquals("Hello traveler", TranslationOutput.validated("  Hello\r\ntraveler  "))
        assertEquals("Hello traveler", TranslationOutput.validated("Hello\u2028traveler"))
        listOf("", "  \n", "Hello \\V[1]", "<b>Hello</b>", "Hello %1").forEach {
            assertNull(TranslationOutput.validated(it))
        }
    }
}
