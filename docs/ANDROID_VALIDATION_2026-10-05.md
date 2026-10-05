# Validation Android d’Astra 1.10.0 — 5 octobre 2026

Les essais utilisent exclusivement **Android Emulator, ADB, l’instrumentation Android et Logcat**, conformément à la demande de l’utilisateur. ARTEMIS est interdit dans les instructions globales Codex et n’est pas utilisé dans cette validation.

## Environnement et conservation des données

- Android Emulator 37.1.11.0, fourni par le SDK Android Studio ; image Google Play x86_64 API 36.1.
- AVD existant `Primio_Medium_API_36_1`, écran 1080 × 2400, densité 420 dpi.
- Session de confirmation : `emulator-5582`, 4 Gio de RAM, quatre cœurs, rendu GPU hôte, Vulkan désactivé.
- Lancement avec `-read-only -no-snapshot-load -no-snapshot-save`. Les données et réglages de l’AVD d’origine sont conservés ; les installations et fixtures n’existent que dans la session temporaire.
- Les modèles ML Kit sont réels. Les jeux, sauvegardes et documents testés sont des fixtures contrôlées, jamais présentés comme un jeu personnel fourni par l’utilisateur.

## Résultats

La première suite native a réussi **59 tests sur 59**, dont le modèle ML Kit réel, le Keystore Android et les échanges via ContentResolver. Journal : `build/emulator-validation/instrumentation-final-portrait.log`.

Deux tests supplémentaires couvrent les correctifs Emil : état des sections de paramètres et activation clavier d’un onglet déjà sélectionné. La confirmation finale a réussi avec les APK reconstruits après ces corrections. Les fixtures de défilement ont été adaptées sans changement de production supplémentaire.

| Configuration de confirmation | Tests | Résultat |
|---|---:|---|
| Portrait, texte 100 %, suite complète avec ML Kit réel | **61/61** | Réussi, 0 échec, 0 ignoré |
| Paysage, texte 150 %, suite UI | **32/32** | Réussi, 0 échec, 0 ignoré |
| Portrait, texte 150 %, suite UI | **32/32** | Réussi, 0 échec, 0 ignoré |

Le smoke test R8 a ensuite révélé des libellés de navigation restés en français en anglais : la surcharge Material `Text` avec `textAlign` contournait le wrapper localisé. Un libellé traduit explicitement est maintenant partagé par l’icône et le texte. Les assertions vérifient Home/Games/Settings visibles et Accueil/Jeux/Paramètres absents en anglais ; le dernier build et la suite complète incluent cette correction. Les deux matrices à 150 % ont été exécutées avant ce dernier changement de libellés, sans changement de disposition ou de contenu français.

Les deux suites de 32 sont des réexécutions des mêmes scénarios UI dans des configurations différentes ; elles ne représentent pas 64 tests uniques supplémentaires. Le nombre de réussites est validé dans le résultat JUnit (`OK (…)`), pas déduit du seul code de sortie ADB.

Journaux et captures : `build/emulator-validation/instrumentation-confirmed-*.log`, `confirmation-results.json`, `confirmed-portrait/`, `confirmed-landscape-large-text/`, `confirmed-portrait-large-text/`. Chaque configuration est consignée par `dumpsys window displays` ; la rotation, la taille de police et les dimensions PNG sont contrôlées.

## Parcours exécutés et portée de la preuve

| Parcours | Preuve native | Limite |
|---|---|---|
| Édition/restauration des sauvegardes | Six `StorageWorkflowsTest` : opérations via le provider SAF debug, conflits, backups et restauration ; quatre `SaveEditorUiTest` : brouillon, confirmation, champs readonly et révision conservée. | Fixtures représentatives ; aucun chargement dans un runtime JoiPlay réel. |
| Recréation d’un brouillon | `StateRestorationTester` réinstancie les états Compose et conserve la révision d’origine, même après modification externe du fichier. | Même processus et ViewModel ; ce n’est pas une simulation de mort du processus. |
| Traduction locale | `GameTranslationTest` avec `liveTranslation=true`, vrai modèle ML Kit, cache réutilisé. | Jeu synthétique, sans jugement de qualité linguistique. |
| Échange manuel pour IA | Quatre `GameManualTranslationTest` : extraction/import via ContentResolver, marqueurs RPGM imbriqués, sauts de page, identités protégées, fichiers périmés/refusés et restauration exacte. Trois tests UI de traduction dont accès aux commandes du mode manuel. | L’IA externe et le sélecteur de documents système ne sont pas pilotés de bout en bout. |
| Archives AST2 | Trois `BackupAndroidTest` : clé AndroidKeystore dédiée, blocs de plus de 1 Mio, flux vide, corruptions et sauvegarde/restauration avec Room et SAF. | Provider SAF de test local ; aucun fournisseur cloud, migration de clé ou profil mémoire maximal. |
| Navigation et formulaires | Accueil, bibliothèque, collections, paramètres, recherche/clavier, restauration, onboarding, verrouillage et éditeur. | Assertions et états capturés ; ni audit TalkBack manuel, ni biométrie physique. |

## Revue visuelle Emil et Impeccable

La direction existante est conservée : fond sombre, jaquettes mises en avant, navigation compacte et rail sur grande largeur. Les captures proviennent du rendu Android, pas d’une maquette web.

| Before | After | Why |
|---|---|---|
| Navigation en français alors que le reste de l’application était en anglais. | Un même libellé explicitement traduit pour le texte et sa description accessible. | Garder une langue cohérente dans les destinations principales. |
| Titre du premier écran sombre sur un fond insuffisamment contrasté. | Couleur `onBackground` explicite et fond composé opaque ; assertion native de contraste du grand titre. | Rendre le premier parcours lisible. |
| Un onglet sélectionné ne montrait plus l’appui ni le focus comme états distincts. | Indication Material, bordure de focus, activation par Entrée vérifiée. | Confirmer l’interaction et conserver un repère clavier. |
| Les sections des paramètres ne décrivaient pas leur état. | Rôle bouton, en-tête, état et action ouverts/fermés localisés dans les sept langues. | Exposer l’information aux technologies d’assistance. |
| En paysage avec une police à 150 %, le rail étroit coupait des mots sur deux lignes. | Rail de 104 dp quand le texte est agrandi, au lieu de 80 dp. | Garder les destinations lisibles sans réduire la police choisie. |

Le [rapport Emil](AUDIT_UI_EMIL_2026-10-05.md) décrit les sources et les critères applicables à Compose. Les animations Material sont conservées ; aucune cascade décorative ou réduction artificielle au clic n’a été ajoutée.

Les captures historiques `translation-light.png` montrent le thème sombre. Leur nom n’est pas une preuve de thème clair. Les captures d’un écran défilé peuvent montrer une carte partielle au bord de la liste ; cela ne suffit pas à conclure qu’une action est inaccessible.

## Incidents distingués des résultats réussis

- Des lancements antérieurs avec rendu logiciel ont arrêté le processus Windows de l’émulateur. Un passage avec GPU hôte a permis la suite complète initiale ; cela n’établit pas une cause interne certaine dans le pilote.
- Un ancien test d’accueil attendait le titre du mode portrait en paysage ; il vérifie maintenant le titre adaptatif et les défilements indépendants, en conservant les assertions de reprise, d’ordre et de navigation.
- Le contrôle d’une valeur readonly sous son libellé requiert un défilement avec la grande police. Le test conserve l’assertion de visibilité après `performScrollTo()`.
- Dans les tests de traduction, le titre et les onglets sont explicitement ramenés dans la zone visible avant assertion ou clic. Le footer reste accessible indépendamment du contenu défilant.
- Le test clavier doit quitter le mode de saisie tactile avant de demander le focus ; le fixture utilise explicitement le mode clavier.
- Une session a perdu le service système Android `activity` pendant la suite. Le journal `emulator-system-restart.log` conserve le redémarrage des services. Cette exécution interrompue n’est pas comptée comme réussie. La confirmation utilise une nouvelle session sur le port 5582 avec davantage de mémoire.

## Reproduction

Construire avec le JBR Android Studio :

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:debugUnitTestCoverageReport :app:assembleDebug :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest :baselineprofile:assembleBenchmarkRelease :baselineprofile:assembleNonMinifiedRelease --console=plain
```

Après démarrage de l’émulateur et installation des deux APK debug :

```powershell
adb -s emulator-5582 shell wm fixed-to-user-rotation enabled
adb -s emulator-5582 shell wm user-rotation lock 0
adb -s emulator-5582 shell settings put system font_scale 1.0
adb -s emulator-5582 shell am instrument -w -r -e liveTranslation true fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Pour la suite UI : remplacer `-e liveTranslation true` par `-e package fr.astragames.app.ui`. Utiliser `wm user-rotation lock 1` pour le paysage et `font_scale 1.5` pour le texte agrandi. Vérifier la configuration effective et restaurer les réglages si l’émulateur n’est pas une session temporaire.

Captures finales relues : `navigation-focused.png` et `onboarding.png` en portrait ; `redesign-settings.png` et `translation-manual.png` en paysage et portrait à 150 %. Les libellés du rail sont entiers, le focus est visible et les actions restent accessibles. Aucun autre correctif visuel n’a été retenu sur ces états.

## APK optimisé

Le dernier release R8 a été compilé, installé et lancé sur l’émulateur : `am start -W` retourne `Status: ok`, processus vivant et écran d’accueil rendu après le parcours initial. La hiérarchie UIAutomator confirme Home/Games/Collections/Settings affichés en anglais et l’absence de leurs anciens libellés français ; aucune erreur fatale de l’application n’apparaît dans le Logcat collecté. La suite complète finale passe **61/61 en 198,499 s** après ce dernier correctif. Le contrôle local de signature et d’alignement ZIP passe ; les 537 entrées de l’APK non signé sont identiques dans la copie signée utilisée pour cet essai. Journaux : `release-smoke-launch-final.log`, `release-smoke-logcat-final.log` ; capture `release-smoke-home-final.png` sous `build/emulator-validation/`. Le temps de lancement observé (2 456 ms) est un résultat de cette session, pas une mesure de performance représentative. L’artefact livré reste non signé ; une copie signée avec la clé de développement sert uniquement au smoke test local. Ce rapport consigne la validation locale préalable à la distribution GitHub décrite dans [les notes de version](RELEASE_1.10.0.md) ; aucune signature de production n’est fournie.

Les résultats JVM, lint, dépendances et les SHA-256 des APK livrés sont dans [le rapport global](AUDIT_2026-10-05.md). Les 45 avis lint restants sont explicites et n’ont pas été masqués.
