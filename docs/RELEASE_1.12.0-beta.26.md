# Astra 1.12.0-beta.26

Préversion Android · code 54.

- **Supprimer le jeu et les fichiers** efface réellement le dossier du jeu et retire sa fiche. Le chemin et la suppression des sauvegardes présentes dans ce dossier sont annoncés avant confirmation. En cas de refus Android, la fiche reste visible avec l’erreur.
- **Ignorer le dossier** conserve ses fichiers, retire le jeu d’Astra et exclut le dossier et ses sous-dossiers des scans. Les autres copies du même jeu restent détectables. Réautorisation dans **Paramètres → Dossiers ignorés**.
- **Rechercher des doublons**, accessible depuis la bibliothèque, compare les identités détectées, titres, liens de fiches et identifiants produit. Chaque copie affiche son emplacement et sa version, avec une action de suppression ou d’exclusion. La fusion avec récupération des sauvegardes reste disponible. Ces rapprochements sont des doublons possibles, pas une comparaison du contenu octet par octet.
- **Rechercher dans Astra** ouvre directement le navigateur intégré pour les fiches F95Zone et Ryuugames, même si le navigateur externe est préféré ailleurs. Recherche/adresse modifiable, choix du moteur, précédent/suivant, rechargement et bouton **Utiliser le lien**. L’aperçu des métadonnées conserve sa confirmation avant import.
- **Collections → Récemment ajoutés** propose 24 heures, 7 jours, 14 jours, 1 mois, 3 mois, plus de 3 mois, toutes les dates et une période personnalisée. Les bornes de la période personnalisée incluent les deux journées, dans le fuseau local. Les jeux sont présentés du plus récent au plus ancien.

La sélection multiple propose les deux actions de retrait. Les sources de bibliothèque et les dossiers partagés ou imbriqués avec un autre jeu sont protégés contre l’effacement. Un scan concurrent attend la fin du retrait pour éviter de recréer la fiche.

Installez **Astra-1.12.0-beta.26.apk** par-dessus votre version actuelle, sans désinstaller. Le certificat historique de distribution est conservé. Pour recevoir cette préversion dans Astra : **Paramètres → Mises à jour d’Astra → Recevoir les versions bêta**.

Les vérifications Android utilisent Android Studio Emulator, ADB, instrumentation et Logcat. La mise à jour depuis beta.25 est contrôlée sur un catalogue témoin. Aucun téléphone physique ni compte F95Zone personnel n’est qualifié par ces essais. Voir le [bilan de validation](https://github.com/azksama/AstraGames/blob/v1.12.0-beta.26/docs/VALIDATION_1.12.0-beta.26.md).
