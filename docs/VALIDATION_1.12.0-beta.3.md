# Validation Astra 1.12.0-beta.3 — diagnostic Wolf RPG

Code Android 31, minSdk 26, targetSdk 36. Moteurs et composants épinglés inchangés.

## Fonctionnement

Paramètres → Diagnostic Wolf RPG → Mode debug Wolf RPG. Réglage persistant, désactivé
par défaut. Chaque lancement écrit un rapport minimal avant même de télécharger le
runtime. Le mode détaillé ajoute Box64 LOG=2, DYNAREC_LOG=1, détails Wine SEH/chargement
des DLL et Logcat limité au PID Astra. Les détails de signal et la bannière Box64 restent
actifs en mode normal. Sur ARM64, un test Box64 `-v` avec délai de 15 secondes précède
Wine en mode debug. L’initialisation wineboot reste limitée à 150 secondes.

Le rapport inclut version Astra/runtime, modèle Android, ABI, SoC si disponible, taille
des pages mémoire, espace libre, étapes, commandes et environnement construit par Astra,
exceptions, stdout/stderr fusionnés de Wine/Box64 et de PulseAudio, codes de sortie.
Il ne collecte pas l’environnement complet du téléphone, ni ses identifiants uniques,
ni les fichiers du jeu. Les chemins privés Astra sont abrégés dans l’export ; d’autres
chemins et le nom du jeu peuvent apparaître dans les messages du moteur.

Cinq sessions conservées sous noBackupFilesDir, quatre flux avec deux fichiers de
512 Kio chacun au maximum : environ 20 Mio au total, hors petites métadonnées. Export
texte à la demande via FileProvider dans un dossier cache dédié ; cinq exports maximum.
Aucun envoi automatique, aucune permission READ_LOGS. L’aperçu affiche au plus 48000
caractères ; le partage contient tous les logs conservés, après rotation éventuelle.

Les métadonnées sont atomiques et les logs sont écrits au fil de l’eau. Une exception
Java non interceptée est notée avant délégation au gestionnaire Android. Pour une session
interrompue par la mort du processus, Android 11+ est interrogé sur son historique d’arrêts,
avec PID, nom du processus et date correspondant à la session. L’absence d’information
n’est pas présentée comme une cause certaine. Aucun rapport ne promet une pile native
complète : Android peut ne pas la fournir et une fin brutale peut perdre les derniers
octets encore dans le pipe du processus enfant.

La fermeture et la finalisation des rapports restent dans un bloc NonCancellable. Une
erreur de synchronisation des sauvegardes s’ajoute à l’erreur initiale sans la remplacer.

## Preuves du 8 octobre 2026

- 216 tests JVM : zéro échec, dont append/rotation/taille des journaux et interprétation
  prudente des codes de sortie.
- Android Emulator API 36.1 x86_64 : 11 tests exécutés avec succès pour capture stdout/stderr
  d’un processus en échec, persistance du mode, export réel par URI FileProvider, rétention,
  récupération d’une session incomplète, interrupteur Compose, écran d’échec avant
  téléchargement, régressions stockage Wolf et sécurité des mises à jour.
- Deux tests supplémentaires, exécutés séparément avant et après `adb shell am force-stop`,
  vérifient que la dernière étape et le journal survivent à la mort réelle du processus.
  Cette fermeture volontaire ne reproduit pas le crash ARM64 signalé par l’utilisateur.
- Lancement du jeu officiel Wolf 3.729 dans le vrai runtime Wine x86_64 téléchargé : mode
  détaillé activé, commandes Valider/Quitter, contenu du rapport contrôlé. La finalisation
  normale du rapport est vérifiée par une assertion dédiée.
- Capture de l’interface de diagnostic inspectée ; fixture d’erreur explicitement simulée.
- Debug, instrumentation et release R8 compilés ; lint : zéro erreur, 61 avertissements.

Commandes de reproduction (APK debug et androidTest installés) :

```text
adb -s emulator-5584 shell am instrument -w -e class fr.astragames.app.windows.WolfDiagnosticsTest,fr.astragames.app.ui.WolfDiagnosticsUiTest,fr.astragames.app.windows.WolfRuntimeFailureUiTest,fr.astragames.app.windows.WolfGameStorageTest,fr.astragames.app.updates.AppUpdateSecurityTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e wolfRecovery prepare -e class fr.astragames.app.windows.WolfDiagnosticsTest#prepareProcessDeathRecovery fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am force-stop fr.astragames.app
adb -s emulator-5584 shell am instrument -w -e wolfRecovery verify -e class fr.astragames.app.windows.WolfDiagnosticsTest#verifyProcessDeathRecovery fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e wolfIntegration true -e wolfSample game3729 -e wolfDebug true -e class fr.astragames.app.windows.WolfIntegratedRuntimeTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Les tests de récupération sont ignorés sans l’argument correspondant. Le test de jeu
nécessite l’échantillon officiel préalablement préparé ; ce contenu n’est pas distribué.
Les preuves Android concernent l’APK debug, la release R8 est compilée et signée séparément.
Pas de téléphone ARM64 disponible : le crash Box64 de l’utilisateur n’est ni reproduit
ni déclaré corrigé. L’objectif livré est de rendre son prochain lancement diagnostiquable.

Références : [options officielles Box64](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md),
[historique Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo).
