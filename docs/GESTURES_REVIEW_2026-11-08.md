# Revue des interactions et des gestes — 8 novembre 2026

Revue menée avec le skill [gestures d’Emil Kowalski](https://github.com/emilkowalski/skills/tree/5206a950b715e0cabe7043ea0b2304332b91494e/skills/gestures).
Les 14 skills de ce dépôt déjà installés ont été mis à jour et les quatre nouveaux
(`gestures`, `choreograph`, `animation-performance`, `remove-animations`) ajoutés.
Les anciennes versions ont été sauvegardées hors du projet. Ces fichiers de travail
ne font pas partie de l’application Android.

Les principes sont appliqués à Jetpack Compose et aux vues Android : manipulation
directe, annulation, propriété du pointeur, défilements imbriqués et composants
Material existants. Aucune dépendance web n’est ajoutée.

## Corrections

| Avant | Après | Pourquoi |
| --- | --- | --- |
| La recherche s’ouvrait pendant que le doigt était encore posé. | Indicateur lié au déplacement ; validation au relâchement, annulation possible en revenant sous le seuil. | Corriger le geste avant de quitter la page. |
| Aucun retour visible pendant le geste de recherche. | Loupe qui suit le déplacement, résistance au-delà du seuil, retour amorti annulable. | Montrer ce que le geste va déclencher. |
| Le sélecteur de jaquettes gardait ses commandes dans une colonne fixe. | Une seule grille défilante contient titre, commandes et images ; panneau déployé à l’ouverture. | Garder les actions accessibles sur écran court et avec texte agrandi. |
| Un autre choix d’image pouvait être lancé pendant un téléchargement. | Les actions locales et de suppression attendent la fin du téléchargement. | Éviter des modifications concurrentes de la jaquette. |
| Le recadrage gardait le réglage par défaut désactivant le multi-touch. | Pincement activé et vérifié dans le vrai composant Android. | Permettre une manipulation attendue du cadre. |
| L’éditeur Wolf utilisait l’index implicite du premier pointeur. | Propriété par identifiant, sauvegarde unique au relâchement du doigt propriétaire. | Éviter les sauts et la reprise par un second doigt. |
| Une perte de focus pouvait laisser un déplacement visuel non sauvegardé. | Restauration de la position précédente lors de l’annulation ou perte de focus. | Garder affichage et position enregistrée cohérents. |
| Les boutons MKXP-Z consommaient le toucher sans état pressé. | État pressé pendant le contact, effacé au relâchement et à la perte de focus. | Retour immédiat sans doubler les entrées du jeu. |

La distance de recherche est lue pendant le dessin, sans recomposer toute la page
à chaque déplacement. Wolf déplace directement les vues, sans animation intermédiaire.
Les panneaux Material gardent leurs glissements, défilements imbriqués et actions accessibles.

## Tour des parcours

| Parcours | Vérifications |
| --- | --- |
| Accueil, navigation, bibliothèque | Onglets, listes/grilles, favoris, sélection, actions rapides, en-têtes défilants, recherche fixe et clavier. |
| Recherche et filtres | Tirage en haut de liste, absence de déclenchement au milieu, annulation, geste inversé, curseur horizontal qui conserve son geste. |
| Collections et tags | Création/aperçu des règles, dossiers, catégories longues et actions accessibles avec texte agrandi. |
| Fiche, métadonnées, jaquettes | Brouillons, erreurs, aperçu sans écriture, sélection des champs, accès aux images et pincement du recadrage. |
| Sources, scan, paramètres | Rapport, recherche après scan, profil de lancement, erreurs, options, mises à jour et restauration annulable. |
| Outils | Éditeur de sauvegardes, provenance source/copie privée, backups, erreur de dépôt de mods, traduction locale/manuelle. |
| Verrouillage | Code masqué, page/brouillon conservés, recadrage interne distinct du vrai passage en arrière-plan. |
| Commandes Wolf et MKXP-Z | Appuis simultanés, annulation, perte de focus, déplacement/presets, commandes tactiles et accessibles MKXP-Z sans double activation. |

Les captures ont été relues. Les nouveaux tests exécutent de vrais événements
tactiles sur Android Emulator. Le raccourci horizontal entre onglets reste un
changement de page à seuil ; il ne devient pas un carrousel avec prévisualisation.

Captures : [éditeur release](screenshots/gestures-2026-11-08/release-editor.png),
[import release](screenshots/gestures-2026-11-08/release-import.png),
[sélecteur en paysage](screenshots/gestures-2026-11-08/landscape-cover-picker-large-text.png),
[sélecteur avec texte agrandi](screenshots/gestures-2026-11-08/portrait-cover-picker-large-text.png),
[geste maintenu](screenshots/gestures-2026-11-08/portrait-search-pull-held.png).
Quatre tests de gestes passent également avec les animations système désactivées.

## Résultats et limites

Voir le [bilan beta.25](VALIDATION_1.12.0-beta.25.md) pour les chiffres et la preuve
de mise à jour. La revue couvre les familles d’écrans, pas chaque combinaison de
jeu, plugin et service distant. Les tests de traduction utilisent des fixtures,
sans téléchargement de nouveau modèle. Le plugin Godot absent est signalé comme test ignoré.

Essais uniquement sur Android Emulator avec le SDK, instrumentation, ADB et Logcat.
Aucun essai sur téléphone physique, écran 120 Hz ou appareil ARM64 n’est revendiqué.
La fluidité perçue sur ces appareils reste à vérifier.
