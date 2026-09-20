# Astra 1.9.0 — Mode paysage et accueil étendu

## Changements

- Adaptation paysage guidée par la nouvelle maquette : navigation latérale compacte, accueil et fiches en deux panneaux, bibliothèque en deux colonnes en mode liste, collections et paramètres répartis sur la largeur, éditeur de règles avec aperçu latéral.
- « Tes jeux » affiche jusqu’à dix jeux récemment lancés, parcourables verticalement, avec conservation de la position lors du changement de disposition.
- Icône « Récemment ajoutés » remplacée par une bibliothèque avec un signe plus.
- Espace supérieur augmenté pour Retour et Modifier dans les fiches.
- Compteur des collections intelligentes à droite du nom, critères rapprochés juste dessous.
- Volets des paramètres fermés par défaut.
- Écran de verrouillage redessiné avec le vrai logo Astra, actions biométriques et saisie du code masquée via le clavier Android. La vérification du code et le dialogue biométrique système restent inchangés.

## Validation

- 116 tests JVM et 45 tests Android réussis sur émulateur Android 16/API 36.1.
- Neuf parcours ciblés réussis en paysage 780 × 360 dp, en paysage compact 640 × 360 dp et en portrait avec texte à 150 %.
- Lint : aucune erreur, 41 avertissements préexistants.
- Builds debug et release optimisée R8 réussis ; signature de l’APK debug vérifiée.
- Captures et détails : [Interface 1.9](https://github.com/azksama/AstraGames/blob/v1.9.0/docs/UI_1.9.md).

Les interactions biométriques ont été vérifiées côté interface. Le capteur biométrique physique et le lancement des jeux dans JoiPlay n’ont pas été testés sur téléphone pour cette version.

## Téléchargements

- `astra-1.9.0-debug.apk` : APK installable signé avec la clé de développement locale.
- `astra-1.9.0-release-unsigned.apk` : APK optimisé, à signer avant installation.
- `astra-1.9.0-sources.zip` : sources du commit publié.
- `SHA256SUMS.txt` : empreintes des fichiers.
