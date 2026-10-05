# Validation d’Astra 1.11.0

Vérifications du 5 octobre 2026, exclusivement avec Android Emulator du SDK Android Studio, ADB, instrumentation et Logcat. L’émulateur de travail sur le port 5582 utilise une instance en lecture seule de l’AVD API 36.1, sans enregistrer de snapshot. L’autre émulateur n’est pas modifié.

## Résultats du code livré

- 209 tests JVM : aucun échec, aucune erreur, aucun test ignoré.
- 68 tests Android dans la confirmation finale, avec modèle ML Kit réel : aucun échec ni test ignoré (`build/android-1.11.0-confirmed.log`).
- Builds debug et release R8, APK d’instrumentation et variantes Baseline Profile compilés.
- Lint debug : 0 erreur et 47 avertissements. Les deux nouveaux avertissements KTX suggèrent de remplacer les commits de préférences ; le code garde leur valeur de retour afin de détecter un échec d’écriture.
- APK debug : version 1.11.0, code 28, signature identique aux versions précédentes, alignement vérifié.
- APK R8 installé et lancé avec une copie signée pour le test local ; consultation de la release GitHub publique réussie depuis cet APK. Le fichier R8 distribué reste non signé.

La suite couvre le cache persistant, son isolation par jeu, l’invalidation après modification d’un JSON et la conservation du dernier résultat après échec ; l’ouverture du traducteur sans analyse ; les contrôles de téléchargement et le refus des APK modifiés, d’un autre package ou d’une version déjà installée ; le vrai cycle de vie de l’activité de rognage ; la migration Room 8 vers 9, les deux liens de métadonnées dans les sauvegardes et leur conservation après scan.

Le premier outil de test de migration a rencontré une incompatibilité de sérialisation interne. Le test final reconstruit le schéma 8 exporté avec le pilote SQLite Android, puis ouvre cette base avec Room et la migration de production. Room valide donc réellement le schéma obtenu ; le test vérifie aussi la conservation des valeurs existantes.

## Revue des parcours selon Emil

| Before | After | Why |
|---|---|---|
| Ouvrir le traducteur lançait l’extraction. | Bouton explicite et bilan conservé entre les ouvertures. | Respecter le moment choisi par l’utilisateur et éviter une attente répétée. |
| Revenir de l’activité de rognage redemandait le déverrouillage. | Visibilité suivie pour toutes les activités internes, avec verrouillage conservé après un vrai arrière-plan. | Conserver la continuité du parcours de jaquette. |
| Les mises à jour de l’application exigeaient une recherche manuelle sur GitHub. | Écran dédié, progression du téléchargement, choix Wi-Fi et ouverture de l’installateur après vérification. | Rendre l’action et ses étapes compréhensibles. |

Les trois parcours de traduction passent en paysage et en portrait avec texte à 150 %. La dernière passe portrait fixe explicitement la rotation dans WindowManager : capture 1080 × 2274 et écran 1080 × 2400 confirmés après les tests (`build/android-1.11.0-translation-portrait-final.log`). Les captures locales se trouvent dans `build/emulator-validation-1.11.0/`. Une capture d’un dialogue défilé peut omettre son en-tête ; les assertions contrôlent les actions après défilement.

## Limites

Les données de jeux sont synthétiques. L’authentification est simulée dans le test de rognage ; le cycle de vie et l’activité Android sont réels. Les fournisseurs sont couverts par des fixtures de leurs pages et la consultation de leur structure publique ; aucun compte personnel ni téléchargement de jeu n’est utilisé. Aucun téléphone physique, runtime JoiPlay réel ni couverture universelle des plugins n’est revendiqué. Android garde la confirmation de chaque installation.
