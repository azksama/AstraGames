# Astra 1.8.0 — Navigation, collections et teinte

## Changements

- Les en-têtes remontent avec le défilement. Dans Jeux, la recherche reste ensuite fixée sous la barre système. L’identité Astra/GAMES est alignée sur les quatre pages principales.
- La bibliothèque défile derrière le menu flottant, sans bande opaque. Le menu reste à sa place lorsque le clavier Android apparaît et le recouvre.
- Correction des noms de moteurs rognés dans les cartes : les arrondis ne coupent plus les textes sous les affiches.
- Icône Lecture dans un cercle pour les jeux récemment joués.
- Collections adaptées aux nouveaux écrans de `design_astra.pen` : listes avec compteurs, cartes intelligentes et résumé de leurs critères.
- Nouvel éditeur de règles avec aperçu réel des résultats, choix de toutes les règles ou au moins une, validation des champs et critère de disponibilité. Les critères existants et les collections enregistrées restent compatibles.
- Curseur de teinte dans Apparence, préférence conservée et réinitialisation au violet d’origine. Textes blancs et couleurs d’erreur préservés.
- En-têtes défilants également appliqués aux outils natifs, au diagnostic, au profil de lancement, aux runtimes et à la traduction.

## Validation

- 115 tests JVM : évaluation des règles, liens de tags, sous-dossiers, modes de correspondance et contraste de la palette sur les 360 teintes.
- 42 tests Android sur émulateur Android 16, dont ML Kit réel sur textes synthétiques. Vérification du clavier ouvert, des en-têtes alignés, de la recherche ancrée, du retour en haut de liste, de la teinte persistante et de l’éditeur de règles.
- Parcours contrôlés sur téléphone 360 × 640 dp, texte agrandi à 150 % et tablette environ 1067 × 800 dp.
- Lint : aucune erreur, 41 avertissements préexistants.
- APK debug et release optimisée R8 compilés.

Les captures utilisent des jeux de test. La validation sur émulateur ne remplace pas un contrôle sur téléphone physique ni un lancement des jeux réels avec JoiPlay.

## Fichiers

- `astra-1.8.0-debug.apk` : APK installable signé avec la clé de développement locale.
- `astra-1.8.0-release-unsigned.apk` : APK optimisé, à signer avant installation.
- `astra-1.8.0-sources.zip` : sources du commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers.
