# Validation — Astra 1.12.0-beta.21

Cette version concentre Wolf sur Winlator/Wine/Box64. Elle retire le moteur natif expérimental et son analyseur, en conservant les sauvegardes historiques et leur export. Elle ajoute le joystick, la taille/opacité individuelles des commandes et un éditeur de mémoire en session. Elle corrige le moteur affiché après sélection manuelle, la reconnaissance des plugins Ren’Py officiels versionnés, la navigation après déverrouillage et les jeux Windows qui relancent leur exécutable. Une option ouvre la demande Android d’exemption de l’optimisation de batterie.

## Rendu et lancement

Les régions modifiées de sprites éloignés sont transférées séparément au GPU. Le benchmark JNI/GLES SDK à 1280×960, 40 sprites, ordre ABBA, mesure **94,0 % d’octets en moins** et **17,2 % de temps en moins** sur copie/capture/transfert/fin GLES. Les pixels sont comparés et les scrolls en mémoire chevauchante sont testés. Aucune réduction de résolution ni suppression d’image n’est utilisée. Les [mesures détaillées](wolf-wine-performance-beta21.md) ne sont pas des FPS complets de jeu et ne garantissent pas 30/60 FPS sur téléphone.

Le rapport fourni montre un jeu **KiriKiri/TJS**, un défaut du service Wine `nsiproxy`, puis une relance de l’exécutable. Astra prenait la sortie du lanceur pour la fin du jeu. La surveillance des descendants est corrigée et testée avec un programme original exécuté dans le vrai Wine : parent sorti, enfant actif plus de six secondes, auxiliaire exclu, fin constatée après l’enfant. Cela ne prouve pas que les autres problèmes Wine de ce jeu privé sont résolus.

## Tests et APK

Android est vérifié exclusivement avec Android Studio Emulator, ADB, instrumentation et Logcat. AVD API 36.1/x86_64, 1080×2400, 420 dpi, rendu logiciel SwiftShader. Les animations système sont désactivées pour rendre les actions d’instrumentation reproductibles. Aucun téléphone ARM64 n’était connecté.

- **260 tests JVM réussis**, aucun échec ni ignoré ; builds debug et release R8 réussis ; lint release **0 erreur, 97 avertissements**.
- **94 cas JUnit Android distincts réussis avant publication** : 68 régressions stockage/sauvegardes/gestes/options/reprise/catalogue, 23 vérifications ciblées rendu/contrôles/verrouillage/batterie/Ren’Py/relance Wine, 1 scénario cheat et 2 scénarios de sauvegarde Wine. Les deux sondes de diagnostic après arrêt de processus, ignorées dans le lot général faute d’argument, ont ensuite été exécutées séparément avec préparation, arrêt forcé Android puis vérification : elles passent. Le même scénario cheat a été exécuté avec des fixtures Windows 32 et 64 bits, puis par l’interface ; ces réexécutions ne gonflent pas le compte distinct.
- Le premier lot ciblé avait un échec du driver de test : le libellé du menu du joystick était porté par un enfant non cliquable. Le test clique désormais son ancêtre cliquable via l’accessibilité SDK ; le cas rejoué passe. Les 22 autres cas du lot initial passaient. Aucun échec initial n’est effacé des preuves locales.
- Le cheat lit six types, recherche/affine, écrit, refuse une ancienne valeur, annule, fige/libère, refuse le mauvais processus et un processus terminé. L’interface effectue réellement recherche/modification/annulation. Captures des dialogues et du joystick inspectées. [Couverture et limites](wolf-cheats.md).
- Ren’Py : découverte effective du plugin officiel `cyou.joiplay.runtime.renpy.v8d4d1` installé dans l’AVD, contrôle du certificat officiel. Aucun jeu Ren’Py commercial n’a été lancé.
- Verrouillage : page et brouillon retrouvent leur état après authentification, faux PIN refusé, contenu privé absent de l’arbre d’accessibilité pendant le verrouillage.
- Sauvegardes : les API Win32 d’espace disque et les écritures réelles passent en accès direct et copie privée, avec progression antérieure conservée. Les exports d’anciennes sauvegardes natives gardent leurs originaux byte-identiques. Ces fichiers natifs ne sont pas convertis en sauvegardes Wine.

L’APK **non debuggable, R8 signé** a remplacé beta.20/code 48 par beta.21/code 49 avec `adb install -r`, sans désinstallation. Certificat historique SHA-256 `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793`, signature v2/v3, alignement ZIP 16 KiB ; les **14 bibliothèques ELF ARM64/x86_64** ont des segments LOAD alignés au moins à 16 KiB. Classes natives Wolf, codec dédié et notices devenues inutiles sont absents de l’APK ; le runtime Wine et ses notices restent présents. Le helper embarqué correspond au source recompilé avec `--check`. Aucun exécutable de jeu privé n’est embarqué.

Le probe SDK séparé a lancé l’exemple officiel **Wolf 3.729 dans l’APK R8** : commandes disponibles après **8 097 ms**, nouvelle partie rendue et entrée acceptée, menu cheat ouvert et vraie recherche Wine terminée sur 409 Mio sans écriture, puis arrêt propre du serveur Windows. Ce lancement utilise un runtime et un préfixe déjà préparés ; ce n’est pas une mesure de première installation. L’exécutable source conserve son SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e` après la mise à jour ; Game.ini est restauré byte-identique. Les réglages d’affichage existants restent inchangés.

## Traçabilité

Le graphe est actualisé par extraction AST ciblée et retrait vérifié des sources supprimées : **9 022 nœuds, 26 358 liens, 481 communautés**. Quatre fichiers Kotlin ont des limites partielles du parseur ; Gradle les compile. Les documents historiques et l’ancienne visualisation HTML ne sont pas réextraits. Le graphe ne certifie pas la compatibilité.

Les notes [Winlator beta21](wolf-winlator-beta21.md) décrivent les migrations et corrections. `VALIDATION_SUMMARY.json` et `SHA256SUMS.txt` joints à la release contiennent commit, résultats et empreintes. Le contrôle public teste le client Android contre le flux GitHub : bêta sélectionnée avec digest, préversions exclues du canal stable. Les quatre assets sont ensuite téléchargés anonymement et comparés aux empreintes locales et GitHub ; leur résultat final figure dans le résumé publié.
