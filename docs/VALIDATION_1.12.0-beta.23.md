# Astra 1.12.0-beta.23 — intégrations après l’étude MTool

Version Android `51`, 12 octobre 2026. Cette livraison met en œuvre six évolutions issues de l’[analyse REA](research/MTOOL-REA-2026-10-12.md). Astra conserve Winlator/Wine/Box64 ; aucun moteur Wolf natif ni binaire propriétaire MTool n’est ajouté.

## Fonctions livrées

| Fonction | Parcours et comportement |
|---|---|
| Variables du moteur | **Menu → Cheats et variables → Variables Wolf** : sonde du moteur, catégories/groupes/IDs, recherche par valeur, numériques et textes communs, relecture et annulation. Deux identités officielles exactes sont reconnues ; le scanner générique reste disponible. |
| Dossier SAF à la demande | **Réglages Wolf → Fichiers du jeu → Automatique** choisit accès physique, puis SAF compatible, puis copie annoncée. Descripteurs seekables/mappables et index borné ; les ressources ne sont pas importées. Les écritures utilisent un journal privé avec publication vérifiée. |
| Polices et mesure de rendu | Cache fontconfig partagé par runtime et politique, fichier stable, option polices embarquées. **Menu → Mesurer la fluidité** capture 60 secondes pour carte, texte ou images : percentiles des temps de soumission et des écarts d’images, transferts, mémoire et état thermique Android. |
| Traduction de données reconnues | **Outils → Traduire** couvre MV/MZ, RGSS XP/VX/VX Ace, cartes/événements communs Wolf décompressés et dialogues/choix `.rpy` Ren’Py. Mode local ML Kit et échange JSON manuel avec marqueurs, IDs, empreintes, backups et restauration. |
| Éditeur de sauvegardes Wolf | **Outils → Éditeur de sauvegardes** affiche les formats reconnus et modifie les cellules numériques prises en charge. Backup avant écriture, checksum, relecture, contrôle de révision et publication vers la source pour les copies privées. `Save_MTool` est découvert. |
| Contrôles portables | **Menu → Modifier les touches** : combinaisons, maintien, bascule ou séquence. Presets JSON importables/exportables, positions portrait/paysage, taille/opacité, migration des anciens presets et relâchement à la pause/perte de focus. |

Les API, bornes et preuves détaillées figurent dans [Variables Wolf](research/WOLF-DATA-ADAPTER.md), [Contrôles et sauvegardes](research/WOLF-CONTROLS-AND-SAVES.md), [SAF](wolf-saf-on-demand.md) et [Traduction](MANUAL_TRANSLATION.md).

## Vérifications

| Surface | Résultat |
|---|---|
| Gradle final | `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `assembleRelease`, `lintRelease` réussis. APK release avec R8 et ressources réduites. |
| JVM | **307 tests**, 0 échec, 0 erreur, 0 ignoré dans ce run. Le corpus officiel local et les sauvegardes réelles étaient présents ; leurs tests facultatifs ont réellement tourné. |
| Instrumentation Android finale | **43 tests réussis, 1 ignoré**, 0 échec, en 13,784 s : six tests SAF, sauvegardes privées/copiées/SAF, contrôles et régressions des parcours de traduction/backups. Le test optionnel téléchargeant un vrai modèle ML Kit n’a pas été activé. |
| Wine réel / Android SDK | Un exécutable Win32 écrit pour Astra est chargé depuis le fournisseur SAF, sans octets EXE importés ; sauvegarde publiée, sauvegarde précédente conservée, volume G: vérifié. Les vrais exemples officiels Wolf 2.2961 et 3.729 passent les lectures/écritures/annulations de données typées. Le run final SAF + Wolf 2 passe en 161,489 s, comprenant deux nouveaux préfixes de test ; Wolf 3 dispose d’un run distinct. **Trois cas réussis** supplémentaires, distincts de la suite précédente. |
| Sauvegarde réelle W2 | C++ WolfSave et Kotlin concordent sur sept sections et 17 580 cellules numériques. Le moteur original Windows recharge le témoin à `271828` et la copie modifiée à `314159`. Trois octets changent, dont le checksum. |
| Textes Wolf réels | Dix fichiers officiels, deux générations, encodages CP932/UTF-8 et compression LZ4 : extraction, absence de changement sans édition, patch ciblé et reparcours. Un round-trip d’une carte réelle à travers SAF est également instrumenté. |
| Textes Ren’Py/RGSS | Fixtures structurées et parcours SAF complets : export v2, rejet de marqueur perdu, patch des seuls textes exposés, code/ressources/sauvegardes conservés, restauration exacte. Aucun jeu commercial Ren’Py/RGSS n’est qualifié par ces fixtures. |
| Lint release | 0 erreur, **113 avertissements**, notamment des recommandations KTX et d’allocation de stockage. Ce résultat ne signifie pas une absence d’avertissements. |
| APK | ARM64 et x86_64, version `1.12.0-beta.23` / code `51`, non débogable, signatures v2/v3. Même certificat SHA-256 que beta.22 : `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793`. |
| Parcours APK release | Le moteur officiel Wolf 2 affiche la partie et accepte Valider ; le menu de cheats exécute une recherche mémoire sans écrire. Première préparation du préfixe : image utilisable en 81,247 s ; reprise du même jeu/préfixe : 9,778 s, puis 8,923 s sur l’APK final. Ces mesures portent sur l’émulateur et cette fixture. Captures du chargement, de la partie et du panneau relues visuellement. |
| Alignement | ZIP validé par `zipalign -P 16`; les segments LOAD des 14 bibliothèques ELF ont un alignement d’au moins 16 Kio. |
| Helper | Rebuild NDK CRT-free identique à l’asset livré : SHA-256 `f6c874a9ed273dd2f3c6383b399910a1ceb6e2f59eca1d673432c6c044bb76d7`. |

Les tests Android utilisent exclusivement **Android Studio Emulator**, Android 16/API 36 x86_64, ADB, instrumentation et Logcat. Les essais Windows sont des validations distinctes des formats/structures, pas des tests Android. Aucun outil ARTEMIS n’est utilisé.

### SAF : données et reprise

La préparation d’un dossier contenant 5 001 ressources inutilisées dont une illisible prend **27 ms** sur le fournisseur local de test, sans import de ressources, avec 8 requêtes de documents et 14 requêtes de dossiers. Cette mesure teste l’absence de parcours/copie complet ; elle ne prédit pas le temps du premier préfixe Wine, d’un fournisseur cloud ou de 70 000 fichiers sur téléphone.

Les six tests vérifient les FD/SCM_RIGHTS/mmap, la sauvegarde native, l’écriture interrompue, la publication refusée puis reprise, le conflit d’édition, le retrait d’accès et le repli explicite. Une écriture non scellée reste exportable mais ne remplace pas la source au prochain lancement. Une nouvelle écriture archive d’abord la version interrompue. Les quotas et erreurs de copie conservent les payloads précédents.

Le test de permission provoque une véritable `SecurityException` du fournisseur de debug ; celui-ci partage l’UID d’Astra, donc ce test ne simule pas une révocation Android entre deux applications distinctes. Le programme Wine utilise réellement le chargeur et les API Win32 ; il ne se limite pas à la sonde JNI.

### Répéter les validations

```powershell
$env:ASTRA_WOLF_CORPUS = (Resolve-Path build/wolf-research).Path
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
python scripts/wolf-runtime/build-memory-agent.py --check
```

Les jeux officiels sont obtenus séparément et conservés sous `build/wolf-research`; ils ne sont pas redistribués dans l’APK ou l’archive de sources. [create-official-save-fixture.py](../scripts/wolf-runtime/create-official-save-fixture.py) prépare une nouvelle copie de test, refuse une destination existante et conserve les originaux. Les fixtures de sauvegardes dans `src/test/resources` sont synthétiques et leur générateur est livré.

L’instrumentation ciblée emploie `WolfSafMountTest`, `WolfSaveManagerTest`, `AdditionalManualTranslationTest`, `WolfControlActionTest`, `WolfControlPresetUiTest`, `WolfTouchControlsTest`, `StorageWorkflowsTest`, `GameTranslationTest`, `GameManualTranslationTest` et `TranslationAnalysisCacheTest`. Les tests Wine sont opt-in : `wolfWineSaveProbe=true`, `wolfEngineDataProbe=true`, `wolfFixtureVersion=22961` ou `3729` ; la fixture correspondante doit être copiée par ADB dans l’émulateur. La carte officielle utilise `wolfTextCorpus=true`.

### Preuves conservées

- [Descripteurs SAF](evidence/wolf-beta23/saf-descriptors.txt) et [Wine/Win32 SAF](evidence/wolf-beta23/wine-saf.txt).
- [Données Wolf 2](evidence/wolf-beta23/engine-data-22961.txt) et [Wolf 3](evidence/wolf-beta23/engine-data-3729.txt).
- [Corpus de textes](evidence/wolf-beta23/text-corpus.json), [patch minimal](evidence/wolf-beta23/save-patch.json), [chargement témoin](evidence/wolf-beta23/offline-reload-control-proof.json) et [chargement de la copie modifiée](evidence/wolf-beta23/offline-reload-proof.json).
- Logs complets locaux : `build/mtool-final-release-build.log`, `build/mtool-final-android.log`, `build/mtool-final-wine.log`, `build/mtool-wine-data-w3.log`. Les anciens échecs de développement restent disponibles mais ne sont pas comptés comme succès.
- Résumé, signatures, SHA-256 et vérification publique : `build/release-1.12.0-beta.23/` et les assets de la release GitHub.

Le graphe de code local est actualisé : 10 192 nœuds et 32 358 liens. L’extracteur signale une extraction partielle pour 24 fichiers Kotlin ; cette limite de l’outil n’est pas remplacée par une affirmation de couverture totale.

## Limites restantes

- Les deux identités Wolf reconnues ne signifient pas tous les jeux. DB/système/self et exécutables personnalisés restent hors adaptateur typé ; le scanner générique reste disponible. La croissance des textes du moteur est refusée.
- L’éditeur hors jeu refuse la nouvelle sauvegarde réelle Wolf 3.729. Les banques inconnues et textes des sauvegardes restent non modifiables. La preuve W2 sous Windows ne qualifie pas tous les jeux Wolf.
- SAF exige des FD réguliers seekables et un fournisseur capable de publication vérifiée ; sinon Astra utilise la copie annoncée. Les modifications concurrentes pendant le dernier renommage ne disposent pas d’un compare-and-swap atomique universel. Les quotas privés sont distincts d’un éventuel quota cloud.
- La traduction ne couvre pas les archives protégées, les `.rpyc`, les messages dynamiques, tous les plugins ou la remise en page. Aucun hook propriétaire MTool n’est copié ; les notices MIT sont embarquées et accessibles dans Astra.
- La mesure concerne la soumission CPU/pilote et les images modifiées reçues ; une image statique ne mesure pas les FPS internes du moteur. Le cache des polices réduit les invalidations et scans répétés ; **30–60 FPS constants sur téléphone ne sont pas établis**.
- Le pont ARM64 compile et les imports du Box64 épinglé ont été vérifiés statiquement. Le nouveau montage SAF et les cheats typés nécessitent encore confirmation sur un téléphone ARM64 réel et sur le fournisseur de l’utilisateur.
- FEX/ARM64EC, hooks de textes dynamiques, glossaire, overlay de mods et outils avancés carte/événements restent des évolutions séparées. Le runtime actuel est conservé.

## Publication

La release fournit l’APK signé, les sources correspondant au commit publié, `SHA256SUMS.txt` et `VALIDATION_SUMMARY.json`. Activer **Paramètres → Mises à jour d’Astra → Recevoir les versions bêta** pour la détection de beta.23. Aucun effacement ni désinstallation de l’application n’est nécessaire.
