# Astra 1.6.3

Cette version réunit la revue globale du 19 septembre et les ajustements de navigation et de densité.

## Interface

- Menu inférieur flottant en pilule, sélection et interactions circulaires.
- Recherche par glissement vers le bas en haut de liste ou sur une page vide ; action d’accessibilité disponible. Le retour retrouve l’écran précédent.
- Historique avec jaquettes et ouverture de la fiche au toucher d’une ligne.
- Mises à jour compactes avec actions sur la même ligne et vérification depuis l’en-tête.
- Collections intégrées en trois colonnes, contenus centrés et espacements réduits.
- Filtres des collections personnelles centrés verticalement, retrait du sélecteur ET/OU. Les nouvelles collections exigent toutes les règles ; les collections existantes conservent leur logique.

## Fiabilité et performances

- Index de bibliothèque réutilisés, recherche temporisée et annulation des résultats périmés.
- Correction des conjonctions FTS4, des groupes de doublons et de la synchronisation des versions recherchables.
- Scanner transactionnel, annulation et scans concurrents mieux gérés.
- Contrôle des mods avant suppression groupée, erreurs présentées à l’utilisateur.
- Métadonnées communautaires, VNDB et état des mises à jour fiabilisés.
- Améliorations de navigation, localisation, accessibilité, thèmes et outils décrites dans `docs/AUDIT_2026-09-19.md`.

## Validation

- 92 tests JVM et 28 tests Android réussis, aucun échec ni test ignoré.
- Trois tests du geste : ouverture en haut de liste, ouverture sur page vide, défilement conservé à l’intérieur de la liste.
- Compilation debug et release R8 réussie ; lint debug : aucune erreur, 41 avertissements existants.
- Contrôle visuel de la pilule et de la grille à trois colonnes sur émulateur 360 × 640 dp ; recherche ouverte par geste dans l’application.

## Fichiers

- `astra-1.6.3-debug.apk` : installable, signé avec la clé de développement locale.
- `astra-1.6.3-release-unsigned.apk` : optimisé par R8, non signé ; nécessite une signature pour installation.
- `astra-1.6.3-sources.zip` : sources correspondant au commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers distribués.

Les essais sur émulateur ne remplacent pas les vérifications sur téléphone physique, avec des jeux réels sous JoiPlay ou des comptes communautaires authentifiés.
