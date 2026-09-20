# Interface 1.9 — portrait et paysage

La version paysage de `design_astra.pen` contient six cadres de 780 × 360 : accueil, fiche, paramètres, bibliothèque, collections et éditeur. Les libellés PLAYSTACK, la progression et les comptes de démonstration restent des références visuelles ; Astra affiche les données réelles de la bibliothèque.

## Dispositions

Le mode paysage s’active à partir de 600 dp de largeur lorsque la largeur dépasse la hauteur. Il utilise un rail de navigation de 80 dp, des en-têtes plus courts et des panneaux défilants. Le téléphone portrait conserve le menu flottant. Les textes, menus et commandes restent accessibles au clavier et avec une police agrandie.

- Accueil : reprise à gauche et grille récente à droite en paysage. En portrait, les sections restent empilées. « Tes jeux » permet de parcourir verticalement les dix derniers jeux lancés ; le dernier figure également dans la carte de reprise. La position de cette grille est partagée entre les deux dispositions.
- Fiche : affiche et identité à gauche, actions et informations à droite. En portrait, la fiche reste verticale. Les commandes Retour/Modifier ont 12 dp d’espace supplémentaire au-dessus, après la barre système.
- Jeux : titre et compteur compacts en paysage ; le mode liste utilise deux colonnes. Le choix liste/grille reste une préférence de l’utilisateur.
- Collections : sélections intégrées à gauche, collections intelligentes et dossiers à droite. Les compteurs intelligents sont à droite du nom et les critères immédiatement en dessous. L’icône des ajouts récents devient une bibliothèque avec un signe plus.
- Paramètres : les six volets sont fermés au premier affichage ; leurs états ouverts/fermés restent mémorisés pendant la navigation. Deux panneaux en paysage.
- Éditeur : nom, aperçu et enregistrement à gauche ; règles à droite. Les critères restent adaptés aux champs disponibles dans Astra.
- Verrouillage : véritable icône Astra, identité GAMES, carte biométrie/code et disposition latérale en paysage. Le code reste masqué, sa vérification utilise le même contrôleur, et une erreur locale est affichée après un refus. Le clavier numérique Android remplace le pavé redondant de l’ancien écran.

Les captures utilisent des jeux synthétiques et les illustrations de test de la maquette. Aucun jeu de démonstration n’est livré dans l’APK.

## Captures

- Paysage : [accueil](screenshots/1.9.0/landscape-home.png), [fiche](screenshots/1.9.0/landscape-detail.png), [jeux](screenshots/1.9.0/landscape-games.png), [collections](screenshots/1.9.0/landscape-smart-collections.png), [éditeur](screenshots/1.9.0/landscape-collection-editor.png), [paramètres](screenshots/1.9.0/landscape-settings.png), [verrouillage](screenshots/1.9.0/landscape-lock.png).
- Portrait : [collections](screenshots/1.9.0/portrait-smart-collections.png), [verrouillage](screenshots/1.9.0/portrait-lock.png).
- Texte à 150 % : [dixième jeu récent](screenshots/1.9.0/large-text-recent-ten.png), [verrouillage défilant](screenshots/1.9.0/large-text-lock.png).
