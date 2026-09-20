# Astra - direction produit et interface

## Lecture du produit

Astra est une médiathèque Android pour des catalogues très volumineux. L'interface doit donner la priorité aux jaquettes, rendre les états de scan explicites et rester utilisable avec une seule source comme avec plusieurs milliers de jeux.

## Système visuel

- Material 3 natif, sans mélange de bibliothèques visuelles.
- Palette sombre issue de `design_astra.pen` : fond `#09090f`, surfaces `#191e29`, actions `#302147`, accent et textes secondaires `#c9b7ff`, titres blancs. Teinte personnalisable et persistante dans Apparence.
- Jaquettes arrondies à 14 dp, surfaces à 14–18 dp ; les textes sous les affiches ne sont pas découpés par ces arrondis.
- Geist pour les titres et Inter pour le texte, embarquées dans l’application.

## Adaptation

- La navigation à quatre destinations (Accueil, Jeux, Collections, Paramètres) utilise des cibles de 56 dp avec un fond de sélection visible et une sémantique d’onglet ; sur écran large, un rail de 104 dp conserve ces destinations.
- La bibliothèque laisse l'utilisateur choisir précisément 2, 3 ou 4 colonnes.
- À partir de 600 dp, la grille devient adaptative ; à partir de 840 dp, ou en paysage entre 600 et 839 dp avec une fenêtre courte, la navigation passe sur un rail latéral compact.
- Les vues en liste restent disponibles pour les écrans étroits et la recherche.
- Les détails conservent une présentation plein écran sur téléphone.
- Les en-têtes ont une hauteur minimale de 64 dp hors barre système et grandissent avec le texte ; les actions secondaires passent dans un menu pour préserver le titre.
- Les actions de source sont regroupées sous le libellé afin de ne pas compresser le nom sur téléphone.
- Le menu inférieur flotte dans une pilule ; le contenu défile derrière elle, avec un espacement final pour garder les dernières lignes accessibles. Le clavier la recouvre sans la retirer de la composition. La recherche s’ouvre en tirant vers le bas en haut d’une liste ou sur une page vide, et reste accessible via une action d’accessibilité.
- Le glissement horizontal sur les destinations du menu passe à l’écran principal précédent ou suivant ; les listes verticales restent prioritaires tant que le mouvement n'est pas clairement horizontal.
- La transition entre pages dure 150 ms afin de garder le geste direct.
- Les survols et effets pressés du menu reprennent la forme arrondie de l’état actif.
- Les en-têtes natifs défilent ; la recherche reste ancrée en haut de Jeux après le retrait de l’en-tête.
- Les actions de l'assistant post-scan restent ancrées sous le contenu défilable et au-dessus du clavier et des barres système.

## États obligatoires

- Vide : invitation à ajouter une source et explication du scan récursif.
- Chargement : écran dédié indiquant la source, le chemin courant, la profondeur, les dossiers visités et les jeux détectés.
- Résultat vide : message distinct quand les filtres ne trouvent rien.
- Erreur : message contextuel via snackbar et état d'erreur persistant sur la source.
- Jeu manquant : avertissement conservant les métadonnées et action de rescan disponible.

## Principes d'interaction

- La suppression physique reste une action séparée, optionnelle, explicitement confirmée et limitée au dossier SAF exact du jeu.
- Les dossiers Astra restent virtuels.
- Les métadonnées utilisateur, favoris, tags et dossiers survivent à un déplacement reconnu par fingerprint.
- Les permissions sont demandées par le Storage Access Framework et conservées de façon persistante.

## Parcours

- Recherche : champ nommé et ciblé à l’ouverture, filtres horizontaux, sélecteur de tags partagé et état de chargement distinct des résultats vides. Une fiche ouverte depuis les résultats conserve la recherche au retour.
- Bibliothèque : menu d’affichage, sélection multiple compatible avec le bouton Retour, état manquant en grille et bouton de remise à zéro des résultats vides.
- Actions : cibles de 48 dp, favoris nommés selon leur état, paramètres basculables sur toute la ligne, titres de jeux conservés tels quels.
- Collections : listes intégrées avec compteurs, cartes personnelles avec résumé des critères. L’éditeur reprend la maquette actualisée : règles en largeur complète, toutes les règles ou au moins une et aperçu calculé sur les jeux réels.
- Détails et outils : en-tête dimensionné par son contenu, listes virtualisées, éditeur plein écran compatible avec le clavier, chargement/erreur distincts des listes vides.
- Couleurs : la teinte conserve les saturations et luminosités de la palette, les titres blancs et les couleurs d’erreur. La réinitialisation restaure exactement la palette d’origine.
