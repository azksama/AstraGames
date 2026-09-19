# Astra 1.6.4

Traduction locale RPG Maker MV/MZ depuis **Fiche → Outils → Traduire le jeu**, avec Google Translate (ML Kit), sans compte ni clé API.

## Traduction

- Choix de la langue source et cible, téléchargement des modèles en Wi-Fi par défaut, progression et arrêt du traitement.
- Fonctionnement hors connexion après téléchargement des langues ; cache par paire de langues pour reprendre un traitement et éviter les doublons.
- Dialogues, choix, noms affichés, descriptions et menus standards MV/MZ. Conservation des commandes RPG Maker, des variables et des retours à la ligne.
- Sauvegardes vérifiées dans `data/.astra-translation`, application journalisée et restauration des fichiers d’origine. Blocage du lancement si les fichiers sont dans un état incomplet.
- Protection contre les conflits avec les mods installés par Astra. Restaurer les originaux avant de modifier les mods ou de mettre le jeu à jour.

## Validation

- 106 tests JVM et 32 tests Android réussis, incluant le modèle ML Kit réel sur textes synthétiques.
- Traduction et restauration vérifiées sur fichiers locaux et URI SAF imbriquées ; reprise du cache après interruption et vérification des commandes conservées.
- Test supplémentaire en mode avion, Wi-Fi et données mobiles désactivés, sans réseau actif : 3 tests réussis. Sur cette fixture de trois textes uniques, 473 ms avec modèles déjà téléchargés, puis 6 ms avec le cache ; ce résultat ne mesure pas un jeu complet ou un téléphone physique.
- Parcours Compose analyse → confirmation → traduction → restauration testé, avec contrôle visuel de l’écran sur petit téléphone émulé.
- Compilations debug et release R8 ; lint debug sans erreur, avec 41 avertissements préexistants.

## Limites

MV/MZ standards uniquement : les images, textes des plugins, scripts et sauvegardes du joueur sont exclus. Pas de prise en charge XP/VX/VX Ace, de remise en page automatique ou de validation sur jeux réels sous JoiPlay/téléphone physique. La qualité et la durée dépendent du jeu, des langues et de l’appareil. Garder Astra ouvert pendant le traitement et conserver le dossier de sauvegarde auprès du jeu. Voir le README pour les limites de taille et la récupération manuelle si le journal ou le stockage est endommagé.

## Fichiers

- `astra-1.6.4-debug.apk` : installable, signé avec la clé de développement locale.
- `astra-1.6.4-release-unsigned.apk` : optimisé par R8, non signé ; nécessite une signature pour installation.
- `astra-1.6.4-sources.zip` : sources du commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers distribués.
