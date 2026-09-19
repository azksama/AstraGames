# Astra 1.7.0 — Refonte UI/UX

L’interface reprend la direction de `design_astra.pen` en l’adaptant aux fonctions réelles d’Astra : bibliothèque personnelle, lancement JoiPlay, collections et outils locaux.

## Interface

- Fond `#09090f`, surfaces `#191e29`, bordures `#2b3242`, actions `#302147`, accents `#c9b7ff` et titres blancs. Geist pour les titres et Inter pour le texte, embarquées sous licence OFL.
- Accueil avec identité Astra/GAMES, recherche directe, dernière partie sur affiche panoramique et bouton de reprise, puis les deux jeux précédents. Historique et mises à jour accessibles dans Activité.
- Navigation flottante avec icônes et libellés ; rail latéral sur grand écran. La zone défilante réserve la place des commandes, y compris avec une police agrandie.
- Bibliothèque complète : titre, compteur, recherche, filtres et lignes compactes avec affiches. Grilles, favoris, sélection multiple et actions rapides conservés.
- Fiche du jeu : grande affiche, lancement, favori, statistiques réelles, outils, compatibilité et sections allégées. Largeur limitée et centrée sur tablette.
- Collections en trois colonnes, réglages en lignes compactes, sections ouvertes par défaut, résumé local de la bibliothèque. Les valeurs de progression, comptes et fonctions cloud d’exemple de la maquette ne sont pas simulés.
- Composants partagés harmonisés dans la configuration initiale, les filtres, dialogues, outils, sauvegardes et traduction. Sélecteurs réutilisables et boutons primaires, secondaires et textuels distincts.
- Traduction : panneau de progression avec estimation restante, sélecteurs compacts et actions pouvant revenir à la ligne. Le moteur ML Kit, son cache et la restauration des originaux sont conservés.
- La confirmation de restauration affiche désormais « Restaurer ».

## Validation

- 111 tests JVM ; 38 tests Android, dont ML Kit réel sur des textes synthétiques.
- Parcours d’interface testés sur émulateur Android 16 : téléphone 360 × 640 dp, tablette environ 1067 × 800 dp et téléphone avec texte à 150 %.
- Vérifications des accès à la fiche, aux outils, à la recherche, aux collections, à l’historique, aux mises à jour et à la restauration annulable ; conservation de l’ordre des jeux récents et des interactions de navigation.
- Compilation debug et release optimisée R8, lint sans erreur (41 avertissements préexistants). Les avertissements historiques de dépendances, icône et migrations restent hors de cette refonte.
- Images issues de la maquette utilisées uniquement comme données de test ; aucun jeu fictif ni image de démonstration n’est livré dans l’APK principal.

Contrôle effectué sur émulateur, pas sur téléphone physique. Les jeux réels de l’utilisateur et leur exécution avec JoiPlay ne sont pas couverts par ces tests. Les limitations MV/MZ du module de traduction restent applicables.

## Téléchargements

- `astra-1.7.0-debug.apk` : APK installable, signé avec la clé de développement locale.
- `astra-1.7.0-release-unsigned.apk` : APK optimisé, à signer avant installation.
- `astra-1.7.0-sources.zip` : sources du commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers.
