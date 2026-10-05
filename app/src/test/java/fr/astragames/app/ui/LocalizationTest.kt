package fr.astragames.app.ui

import fr.astragames.app.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalizationTest {
    @Test fun manualTranslationAndSaveSafetyActionsCoverEveryLanguage() {
        val sources = listOf(
            "Déplié", "Replié", "Déplier", "Replier",
            "Sur cet appareil", "Fichier pour IA", "Importer la traduction", "Exporter les textes",
            "Appliquer le fichier traduit ?", "Vérifier et appliquer", "Sauvegardes de sécurité",
            "Restaurer cette sauvegarde ?", "Supprimer cette copie de sécurité ?",
            "Abandonner les modifications ?", "Les modifications non enregistrées seront perdues.",
            "Continuer à modifier", "Supprimer les tags sélectionnés ?",
            "Les tags seront retirés de tous les jeux associés.", "Extraction des textes",
            "Vérification du fichier traduit", "Fermez le jeu avant d’éditer une sauvegarde.",
            "Aucun résultat pour cette recherche.", "Aucun champ ne correspond aux filtres.",
            "Choisissez un nouveau fichier vide pour l’export. Aucun fichier existant ne sera remplacé.",
            "Impossible de vérifier le fichier d’export.",
            "Les noms utilisés par les conditions du jeu sont conservés pour rester compatibles avec vos sauvegardes.",
            "Impossible de déchiffrer cette sauvegarde. Elle est endommagée ou provient d’une autre installation d’Astra.",
            "Liste des textes incomplète ou issue d’une ancienne extraction. Exportez à nouveau et conservez toutes les entrées.",
            "Balise protégée absente.",
            "La restauration a échoué et le retour arrière est incomplet. Les copies originales sont conservées pour récupération. Ne désinstallez pas Astra. Dossier :"
        )
        sources.forEach { source ->
            assertEquals(source, AppLocalizer.text(source, AppLanguage.FRENCH))
            AppLanguage.entries.filter { it != AppLanguage.FRENCH }.forEach { language ->
                assertNotEquals("Missing translation: $language — $source", source, AppLocalizer.text(source, language))
            }
        }
        assertEquals("Export text", AppLocalizer.text("Exporter les textes", AppLanguage.ENGLISH))
        assertEquals("翻訳を読み込む", AppLocalizer.text("Importer la traduction", AppLanguage.JAPANESE))
    }

    @Test fun technicalTranslationKeysAndOriginalSaveValuesRemainIntact() {
        val guidance = "Modifiez uniquement les champs translation. Conservez les identifiants, les textes sources et les marqueurs ASTRA. Les instructions sont incluses dans le fichier."
        AppLanguage.entries.forEach { language ->
            val translated = AppLocalizer.text(guidance, language)
            org.junit.Assert.assertTrue(translated.contains("translation"))
            org.junit.Assert.assertTrue(translated.contains("ASTRA"))
        }
        assertEquals("Original: 星の物語 \\V[1]", AppLocalizer.text("Original : 星の物語 \\V[1]", AppLanguage.ENGLISH))
        assertEquals("原值：星の物語 \\V[1]", AppLocalizer.text("Original : 星の物語 \\V[1]", AppLanguage.CHINESE))
        assertEquals("Restore failed and rollback is incomplete. Original copies are retained for recovery. Do not uninstall Astra. Folder: /data/user/0/fr.astragames.app/no_backup/restore-123", AppLocalizer.text("La restauration a échoué et le retour arrière est incomplet. Les copies originales sont conservées pour récupération. Ne désinstallez pas Astra. Dossier : /data/user/0/fr.astragames.app/no_backup/restore-123", AppLanguage.ENGLISH))
    }
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
