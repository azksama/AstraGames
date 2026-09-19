# Référence visuelle 1.7

Base fournie : `design_astra.pen` (format JSON 2.18, quatre écrans). Les contenus PLAYSTACK, jeux, compte et progression sont des exemples de maquette. Astra utilise ses données réelles et conserve son identité, ses lanceurs et ses opérations réversibles.

| Élément | Traduction dans Astra |
| --- | --- |
| Bibliothèque | Accueil : dernier jeu, deux précédents, recherche et activité |
| Tous les jeux | Bibliothèque filtrée, liste ou grille selon la préférence existante |
| Fiche | Affiche panoramique, lancement et données de session réelles |
| Paramètres | Résumé local, préférences compactes, sources, organisation, sécurité et sauvegardes |
| Découvrir / Profil | Jeux / Paramètres, sans création d’un faux catalogue ou compte |
| Suivi de chapitre / succès / note | Temps de jeu, lancements et moteur réellement disponibles |

Couleurs : `#09090f` / `#191e29` / `#2b3242` / `#302147` / `#c9b7ff` / blanc. Typographie : Geist / Inter ; marges principales de 20 dp, sections espacées de 20–24 dp, cartes arrondies de 14–18 dp. Les boutons conservent une cible tactile accessible.

La palette, la typographie, les en-têtes, boutons et listes partagés s’appliquent aussi aux écrans secondaires : configuration, tags, doublons, runtimes, outils, sauvegardes, mods et traduction. Leurs opérations métier restent intactes. Les nouveaux contrôles de navigation et de présentation sont couverts par `RedesignUiTest`, avec fixtures supprimées à la fin de chaque test.

Les fichiers `app/src/androidTest/assets/design/` viennent des images jointes à la maquette. Ils servent exclusivement aux captures de validation et ne font pas partie des ressources de l’application distribuée. Licences des polices : `docs/licenses/`.

Captures natives sur émulateur, avec jeux de test : [accueil](screenshots/1.7.0/home.png), [fiche](screenshots/1.7.0/game.png), [bibliothèque](screenshots/1.7.0/library.png), [paramètres](screenshots/1.7.0/settings.png).
