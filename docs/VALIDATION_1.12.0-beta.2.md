# Validation d’Astra 1.12.0-beta.2

Code de version Android 30, minSdk 26 et targetSdk 36. Le moteur Wolf est inchangé par
rapport à beta.1 ; cette version ajoute le choix du canal de mise à jour.

## Comportement

L’option « Recevoir les versions bêta » est désactivée par défaut. Elle est persistée dans
les préférences des mises à jour et relue lors des contrôles automatiques. Le canal stable
interroge `/releases/latest` ; le canal bêta examine les 100 dernières releases publiées
retournées par GitHub et choisit la version sémantique la plus récente dotée d’un APK
installable et d’une empreinte. Les brouillons et les assets invalides sont exclus.

La comparaison prend en charge les préversions et ignore les métadonnées de build :
`beta.2 < beta.10 < rc.1 < stable`. Le drapeau GitHub et un suffixe de préversion dans le
numéro suffisent chacun à interdire une offre sur le canal stable. Un changement de canal
invalide l’offre, le marqueur d’installation et le fichier téléchargé sous le verrou du
moteur de mise à jour, puis l’interface relance une vérification sans télécharger d’office.
Les contrôles existants de taille, SHA-256, identité du package, version et signature restent actifs.

## Vérifications du 8 octobre 2026

- 213 tests JVM réussis, aucun échec : ordre des versions, exclusion des brouillons,
  choix de canal, sélection d’assets, caches et restrictions des URL GitHub compris.
- 19 tests Android réussis sur Android Emulator API 36.1 x86_64 : sécurité des APK,
  persistance de l’option, invalidation d’une bêta téléchargée, usage du canal dans un
  contrôle automatique, sélecteur Compose et régressions des interrupteurs/bibliothèque.
- Le test réseau opt-in a lu sans authentification les deux flux publics et obtenu
  `1.12.0-beta.1` sur le canal bêta, avant publication de beta.2.
- Debug, instrumentation et release R8 construits. Lint : 0 erreur, 58 avertissements.
- APK release signé avec la clé de développement habituelle ; son code de version permet
  une mise à jour en place depuis beta.1. Empreintes accompagnant les assets publiés.

Tests reproductibles après installation des APK debug et androidTest :

```text
adb -s emulator-5584 shell am instrument -w -e class fr.astragames.app.updates.AppUpdateSecurityTest,fr.astragames.app.ui.AppUpdateChannelTest,fr.astragames.app.ui.LibraryUiTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e expectedPublicBeta 1.12.0-beta.2 -e class fr.astragames.app.updates.AppUpdateChannelNetworkTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Le test réseau attend la version indiquée dans son argument, à adapter lors d’une nouvelle
publication. Il est ignoré sans cet argument. La preuve d’interface concerne l’APK debug ;
la release R8 est compilée et son package est contrôlé séparément.

Les installations 1.11.0 et beta.1 ignorent les préversions : une première installation
manuelle de beta.2 est nécessaire pour obtenir l’option. Aucun retour automatique vers une
ancienne stable n’est effectué lorsque l’option est désactivée. Les limites ARM64/Box64 et
16 Ko décrites dans [la validation beta.1](VALIDATION_1.12.0-beta.1.md) restent valables.
