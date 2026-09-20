# Interface 1.8

Référence : mise à jour de `design_astra.pen`, six écrans dont Collections et création de règles. Les propriétés de démonstration sont adaptées aux données réelles d’Astra : moteur, tags, dossiers, favoris, jaquette, disponibilité dans le catalogue, dates et durée de jeu.

- L’identité Astra/GAMES partage la même hauteur sur les quatre destinations. Les en-têtes des pages natives remontent avant le contenu ; ils reviennent lorsque la liste atteint son début. Dans Jeux, la recherche reste ancrée sous la barre système.
- La liste s’étend derrière la navigation flottante. Seul son espacement final permet de remonter les dernières lignes au-dessus du menu. Le clavier recouvre le menu, qui reste composé à sa position initiale.
- Les collections intégrées sont présentées en lignes avec compteur. Les collections personnalisées affichent leurs règles, le nombre réel de résultats et un menu de modification/suppression.
- L’éditeur propose toutes les règles ou au moins une, des champs adaptés à chaque critère et un aperçu calculé hors du thread principal avec le même évaluateur que la bibliothèque. Les liens des tags et les sous-dossiers sont pris en compte. Une règle incomplète ne peut pas être enregistrée.
- Apparence propose un curseur de teinte de 0 à 359 degrés, enregistré au relâchement, avec retour au violet d’origine. Les saturations et luminosités restent constantes ; les textes blancs et les couleurs d’erreur sont préservés.
- Les arrondis s’appliquent aux affiches, sans rogner les titres ni les libellés des moteurs.

Les pages web intégrées restent des vues de sites externes. Les contrôles de diagnostic, profil, traduction, sauvegardes, historique des modifications et runtimes utilisent également les en-têtes défilants.

Les captures utilisent uniquement les fixtures de test issues de la maquette. Elles ne sont pas embarquées dans l’application distribuée.

Captures natives : [grille](screenshots/1.8.0/grid.png), [recherche ancrée](screenshots/1.8.0/grid-scrolled.png), [collections](screenshots/1.8.0/collections.png), [éditeur](screenshots/1.8.0/collection-editor.png), [clavier](screenshots/1.8.0/keyboard.png), [texte agrandi](screenshots/1.8.0/large-collection-editor.png), [tablette](screenshots/1.8.0/tablet-grid.png).
