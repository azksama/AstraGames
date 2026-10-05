# Astra 1.10.0 — Sauvegardes et traduction RPG Maker

## Changements

- Éditeur de sauvegardes fiabilisé pour Ren’Py, RPG Maker MV/MZ et RGSS : conservation des références et du conteneur, copie préalable, contrôle de révision, relecture après écriture et restauration en cas d’échec. Les brouillons sont conservés et leur abandon demande confirmation.
- Traduction RPG Maker MV/MZ corrigée : protection des commandes imbriquées, variables, sauts de page et noms utilisés dans les conditions du jeu ; traitement des textes longs et refus des sorties invalides.
- Nouveau mode **Fichier pour IA** : exporter les textes standards en JSON, faire traduire uniquement les champs `translation` par l’IA de son choix, puis importer le résultat dans Astra. Le contexte, les identifiants, les fichiers source et les marqueurs sont vérifiés avant application. Les originaux restent restaurables.
- Interface améliorée après revue Emil et Impeccable : contraste de l’accueil, retour visuel des appuis, focus clavier, état accessible des paramètres, navigation lisible avec texte agrandi et libellés cohérents avec la langue choisie.
- Sécurité renforcée : archives AST2 chiffrées et authentifiées par blocs, validation avant extraction, restauration avec reprise, confinement des chemins, restrictions des WebViews et lancement explicite des applications JoiPlay autorisées.
- Refactorisation des écrans sauvegardes/mods et du pipeline de traduction ; réduction de la mémoire utilisée par la recherche, traitements bornés et protection contre les résultats asynchrones périmés.

## Utiliser le mode manuel

Ouvrir **Fiche du jeu → Outils → Traduire le jeu → Fichier pour IA**. Exporter le fichier, suivre les consignes incluses et importer le JSON traduit. Le [guide et son prompt](https://github.com/azksama/AstraGames/blob/v1.10.0/docs/MANUAL_TRANSLATION.md) précisent le format.

Ce mode couvre les textes standards MV/MZ. Les scripts, contenus propres aux plugins, images et sauvegardes du joueur ne sont pas traduits. Fermer le jeu avant toute modification et conserver le dossier des originaux.

## Validation

- **200 tests JVM**, 39 suites, aucun échec ni test ignoré.
- **61 tests Android réussis** sur Android Emulator API 36.1, avec ML Kit réel, Android Keystore et stockage SAF de test.
- Les **32 scénarios UI** ont également réussi en paysage et portrait avec texte à 150 %. Ces deux passes précèdent le dernier correctif des libellés anglais ; la suite complète de 61 tests et le lancement R8 ont été relancés après celui-ci.
- Builds debug, release optimisée R8 et modules Baseline Profile réussis. Lint : **0 erreur, 45 avertissements** documentés.
- Signature et alignement de l’APK debug vérifiés ; lancement de l’APK R8 validé avec une copie signée pour le test local.

Les jeux et sauvegardes utilisés sont synthétiques. Aucun téléphone physique, jeu personnel, runtime JoiPlay réel ou parcours vocal TalkBack n’est revendiqué. Voir l’[audit global](https://github.com/azksama/AstraGames/blob/v1.10.0/docs/AUDIT_2026-10-05.md) et les [preuves Android](https://github.com/azksama/AstraGames/blob/v1.10.0/docs/ANDROID_VALIDATION_2026-10-05.md).

## Téléchargements et compatibilité

- **`Astra-1.10.0-debug.apk`** : APK directement installable, signé avec la clé de développement locale.
- `Astra-1.10.0-release-unsigned.apk` : APK optimisé R8, à signer avant installation ; aucune signature de production n’est fournie.
- `Astra-1.10.0-sources.zip` : sources exactes du commit associé au tag.
- `SHA256SUMS.txt` : empreintes SHA-256 des trois fichiers.

Les nouveaux exports de catalogue utilisent AST2 et ne sont pas lisibles par les anciennes versions d’Astra. Astra 1.10.0 conserve la lecture des archives AST1 et ZIP compatibles. La clé reste liée à l’installation Android : ces archives ne permettent pas une migration vers un autre appareil ou après désinstallation.
