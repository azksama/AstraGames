# Astra - direction produit et interface

## Lecture du produit

Astra est une médiathèque Android pour des catalogues très volumineux. L'interface doit donner la priorité aux jaquettes, rendre les états de scan explicites et rester utilisable avec une seule source comme avec plusieurs milliers de jeux.

## Système visuel

- Material 3 natif, sans mélange de bibliothèques visuelles.
- Accent bleu-cyan, neutres froids et couleurs dynamiques Android 12+ en option.
- Rayon principal de 16 dp pour les jaquettes et les surfaces.
- Typographie sans serif système, titres denses et libellés fonctionnels.
- Thème système, clair et sombre.

## Adaptation

- La navigation sans libellés utilise une surface flottante compacte de 56 dp sur téléphone et un rail de 72 dp sur écran large.
- La bibliothèque laisse l'utilisateur choisir précisément 2, 3 ou 4 colonnes.
- À partir de 600 dp, la grille devient adaptative ; à partir de 840 dp, la navigation passe sur un rail latéral compact.
- Les vues en liste restent disponibles pour les écrans étroits et la recherche.
- Les détails conservent une présentation plein écran sur téléphone.
- Les en-têtes applicatifs restent limités à 52 dp hors barre système.
- Les actions de source sont regroupées sous le libellé afin de ne pas compresser le nom sur téléphone.
- Le bouton de recherche flotte au-dessus du menu, sans panneau opaque occupant toute la largeur basse.
- Le glissement horizontal depuis toute la page passe à l'écran principal précédent ou suivant ; les listes verticales restent prioritaires tant que le mouvement n'est pas clairement horizontal.
- La transition entre pages dure 150 ms afin de garder le geste direct.
- Les survols et effets pressés du menu reprennent exactement la forme arrondie de l'état actif.
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
