# Astra 1.12.0-beta.25

**Préversion · code Android 53**

Cette version reprend la beta.24, y compris MKXP-Z et les protections de
sauvegardes/mods, puis améliore les métadonnées, les jaquettes et les gestes.

## Métadonnées et jaquettes

- Import guidé : recherche automatique, lien ou navigateur, puis comparaison des
  informations actuelles et proposées avant de choisir les champs, tags et image.
  Fermer l’aperçu ne modifie pas la fiche.
- Éditeur plein écran, présentation adaptée au paysage, liens vérifiés et brouillons
  conservés pendant les imports. Les erreurs de sauvegarde restent visibles.
- F95Zone : reconnaissance des pièces jointes réservées aux membres et des sessions
  refusées ou expirées ; reconnexion et relecture proposées. Les cookies du navigateur,
  les aperçus et les téléchargements utilisent la même session.
- Prise en charge des images différées, des originaux et des images de repli.
  Les téléchargements invalides sont refusés et nettoyés.
- L’enrichissement automatique lit aussi les fiches liées et préserve les valeurs
  déjà renseignées ainsi qu’une jaquette choisie manuellement entre-temps.

## Gestes et confort d’utilisation

- Tirer vers le bas affiche un indicateur de recherche ; l’ouverture attend le
  relâchement. Revenir en arrière ou annuler le geste conserve la page.
- Le sélecteur de jaquettes défile entièrement, même en paysage, avec un long titre
  ou du texte agrandi. Les actions concurrentes sont désactivées pendant un téléchargement.
- Le recadrage accepte le pincement à deux doigts.
- Le déplacement des commandes Wolf conserve le doigt d’origine et restaure la
  position lors d’une annulation ou d’une perte de focus.
- Les boutons MKXP-Z montrent leur état pressé et le réinitialisent à la perte de focus.

## Installation et validation

Installer **Astra-1.12.0-beta.25.apk** par-dessus l’installation existante.
Le certificat de développement historique est conservé. La mise à jour depuis
l’APK public beta.24 a été vérifiée sans désinstallation sur Android Emulator,
avec conservation du catalogue, des préférences et du backup témoin.

**122 tests instrumentés distincts passent**, avec des reprises en paysage et
texte agrandi ; un test Godot est ignoré faute de plugin installé. Lint : aucune
erreur, 131 avertissements. Voir le
[bilan détaillé](https://github.com/azksama/AstraGames/blob/v1.12.0-beta.25/docs/VALIDATION_1.12.0-beta.25.md).

La récupération VNDB et la lecture de la fiche F95Zone publique ont été testées
en ligne. La session personnelle F95Zone de l’utilisateur et son téléchargement
authentifié restent à confirmer ; les cas membre sont couverts par des fixtures.
Les essais utilisent Android Emulator x86_64, sans qualification sur téléphone ARM64.

Pour recevoir cette préversion depuis Astra : **Paramètres → Mises à jour d’Astra
→ Recevoir les versions bêta**. Sources, notices et `SHA256SUMS.txt` accompagnent l’APK.
