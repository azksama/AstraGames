# Astra 1.6.5

Nouvel accueil de reprise et correction du blocage « Le modèle a produit un texte incompatible » rencontré en traduction.

## Accueil et apparence

- Icône de l’app en haut à gauche, avec Astra et GAMES sur deux lignes.
- Dernier jeu lancé en grand format horizontal, recadré depuis sa jaquette, avec titre, date et heure de dernière session. Le bouton Reprendre la partie chevauche l’affiche et le fond de l’app.
- Section Tes jeux : bouton Tout voir vers la bibliothèque complète, puis les deuxième et troisième derniers jeux lancés.
- Menu flottant élargi, sans bande opaque derrière lui, avec Accueil, Jeux, Collections et Paramètres. Mises à jour et Historique sont retirés de ce menu en attendant leur nouvel emplacement.
- Sélection, survol, pression et focus circulaires. Les listes défilent derrière la pilule et disposent d’un espace final pour garder leurs dernières actions accessibles.
- Palette fixe : fond `#09090f`, cartes/menu `#716b82`, boutons et états actifs `#302147`, petits textes `#c9b7ff`, titres blancs. Les anciens réglages de thèmes et couleurs dynamiques sont retirés de l’interface.

## Traduction

- Les retours à la ligne générés par ML Kit sont normalisés à l’intérieur des fragments ; ceux du jeu et les commandes RPG Maker sont conservés.
- Une sortie vide ou incompatible conserve seulement le fragment concerné dans sa langue d’origine. Les traductions valides peuvent être appliquées avec sauvegarde des originaux, au lieu d’échouer à la toute fin.
- Bilan de traduction partielle, nombre de fragments uniques conservés persistant et réutilisation du cache valide. Les fragments refusés ne sont pas enregistrés comme des succès.
- Estimation du temps restant après les premiers fragments, fondée sur le volume traité pendant la tentative en cours, hors téléchargement et cache.

## Validation

- 111 tests JVM et 36 tests Android réussis, dont le modèle ML Kit réel sur des textes synthétiques.
- Régression reproduite avec un dernier fragment contenant une commande inattendue : application des autres textes, reprise depuis le cache et restauration à l’octet près.
- Tests du temps estimé affiché, du bilan partiel, de l’ordre des jeux, de l’accueil vide, du bouton de reprise et de la navigation.
- Contrôle visuel sur émulateur de petit téléphone ; compilation debug et release R8 ; lint sans erreur, 41 avertissements préexistants.

La capture fournie permet d’identifier le contrôle qui bloquait, mais le jeu concerné n’a pas été fourni : sa traduction complète et son exécution sur téléphone physique/JoiPlay restent à vérifier. Les limites MV/MZ de la version précédente restent applicables.

## Fichiers

- `astra-1.6.5-debug.apk` : APK installable, signé avec la clé de développement locale.
- `astra-1.6.5-release-unsigned.apk` : version optimisée R8, à signer avant installation.
- `astra-1.6.5-sources.zip` : sources du commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers distribués.
