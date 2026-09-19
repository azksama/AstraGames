package fr.astragames.app.ui

import fr.astragames.app.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
    @Test fun semanticVersionsAreNotParsedAsIntegers() {
        assertEquals("New version available: 1.2-beta", AppLocalizer.text("Nouvelle version disponible : 1.2-beta", AppLanguage.ENGLISH))
        assertEquals("Did you install version 1.2?", AppLocalizer.text("Avez-vous effectué la mise à jour vers la version 1.2 ?", AppLanguage.ENGLISH))
    }

    @Test fun nestedLabelsUseTheRequestedLanguage() {
        val previous = AppLocalizer.language
        try {
            AppLocalizer.language = AppLanguage.FRENCH
            assertEquals("Favorites (12)", AppLocalizer.text("Favoris (12)", AppLanguage.ENGLISH))
        } finally {
            AppLocalizer.language = previous
        }
    }

    @Test fun dynamicCountFamiliesAreReachable() {
        val examples = mapOf(
            "Filtres (2)" to "Filters (2)",
            "Tout (2)" to "All (2)",
            "2 jeu(x) restant(s)" to "2 game(s) remaining",
            "2 nouveau(x) jeu(x)" to "2 new game(s)",
            "2 tag(s) sélectionné(s)" to "2 tag(s) selected",
            "2 exemplaires" to "2 copies",
            "2 lancements" to "2 launches"
        )
        examples.forEach { (source, expected) -> assertEquals(expected, AppLocalizer.text(source, AppLanguage.ENGLISH)) }
    }

    @Test fun unicodeAndUnrecognizedContentArePreserved() {
        val title = "星の物語 — Épisode 2"
        AppLanguage.entries.forEach { assertEquals(title, AppLocalizer.text(title, it)) }
    }

    @Test fun dynamicPatternsRemainReusableAcrossLanguages() {
        repeat(10) {
            assertEquals("24 games", AppLocalizer.text("24 jeux", AppLanguage.ENGLISH))
            assertEquals("24 juegos", AppLocalizer.text("24 jeux", AppLanguage.SPANISH))
            assertEquals("24 jeux", AppLocalizer.text("24 jeux", AppLanguage.FRENCH))
        }
    }
}
