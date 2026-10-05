# Revue UI selon Emil — 5 octobre 2026

Skill explicitement demandé et lu : `C:/Users/AZK/.codex/skills/emil-design-eng/SKILL.md`. Passe ciblée sur le feedback des composants, la navigation, les durées, les états accessibles et les captures Android actuelles. L'auteur de cette revue a examiné les sources et captures en lecture seule ; les correctifs de production et leur exécution sont centralisés par l'agent principal.

Les principes retenus sont la réponse immédiate à une action, un feedback qui distingue les états, des animations brèves et utiles, ainsi que des contrôles compréhensibles sans leur seule apparence. Les recettes CSS du skill ne sont pas transposées littéralement : les boutons, menus et dialogues Material Compose fournissent déjà leur feedback et leurs transitions. Aucune animation de scale, cascade ou flou supplémentaire n'est recommandée pour ces parcours courants.

## Constats confirmés dans les sources

La colonne **After** décrit les correctifs **implémentés, relus dans les sources et confirmés par les tests natifs ciblés**. La suite complète de 61 tests en portrait et les deux passes de 32 tests UI en paysage puis portrait à 150 % ont réussi après reconstruction. Le [rapport Android](ANDROID_VALIDATION_2026-10-05.md) détaille la portée des assertions et chaque configuration.

| Before | After | Why |
|---|---|---|
| **P2 — Focus et appui non différenciés sur l'onglet sélectionné.** `NavIcon` désactivait l'indication (`indication = null`) et attribuait le même fond à `selected`, `hovered`, `focused` et `pressed`. Une fois sélectionné, l'onglet ne changeait donc pas visuellement lorsqu'il recevait le focus ou un appui. | **Implémenté :** fond réservé à la sélection, indication d'appui Material `ripple` et bordure de 2 dp lorsque le composant reçoit le focus. | Le feedback confirme l'interaction même sur un contrôle déjà actif. Le focus est une information différente de la sélection. Le feedback Material convient ici sans ajouter de réduction arbitraire du contrôle. |
| **P2 — État déplié/replié absent des sémantiques.** `SettingsSectionHeader` était cliquable sans rôle bouton ni description de son état ; le chevron avait une description nulle. | **Implémenté :** rôle `Button`, titre sémantique `heading`, état « Déplié » / « Replié » et action « Replier » / « Déplier », traduits dans les sept langues du catalogue. Toute la ligne conserve une seule action. | Les technologies d'assistance peuvent connaître l'état et le résultat attendu de l'action sans interpréter le chevron. Les sémantiques font l'objet d'un test instrumenté ajouté ; les annonces vocales TalkBack restent une preuve distincte. |
| **P2 — Libellés coupés au milieu d'un mot dans le rail paysage avec grande police.** La capture `large-text/redesign-collections.png` montre « Collecti / ons » et « Paramèt / res » dans le rail de 80 dp. | **Implémenté et rendu confirmé :** largeur de 104 dp lorsque `fontScale > 1.2`, avec conservation des 80 dp en paysage à taille de texte standard. Les libellés sont entiers dans `confirmed-landscape-large-text/redesign-settings.png`. | La navigation conserve des libellés lisibles lorsque l'utilisateur agrandit le texte, sans réduire la taille de police choisie. |
| **P2 — Contraste du titre d'accueil dépendant d'une couleur héritée.** Le titre de l'onboarding n'imposait pas `onBackground`, tandis que le dégradé utilisait une couleur de conteneur partiellement transparente. | **Implémenté précédemment dans cette session :** titre en `onBackground` et dégradé composé sur le fond, avec une teinte à 22 %. La capture portrait actuelle montre le titre clair et lisible sur le fond sombre. | La couleur du titre correspond désormais explicitement à celle du fond. La composition du dégradé réduit sa dépendance au contenu situé derrière ; aucune certification de contraste pour toutes les teintes n'est déduite de cette seule capture. |
| **P2 — Navigation restée en français dans l'interface anglaise.** L'appel de `Text` avec `textAlign` sélectionnait la surcharge Material sans passer par la localisation. | **Implémenté :** `NavIcon` calcule une fois le libellé traduit et le partage entre le texte visible et la description accessible. Les assertions Android vérifient Home/Games/Settings et l'absence de leurs équivalents français ; la capture R8 finale confirme le rendu. | Les destinations suivent la langue choisie et leur nom visible correspond au nom accessible. |

Références après correction, relatives à `app/src/main/java/fr/astragames/app/ui/` : `AstraApp.kt:263` (rail) et `AstraApp.kt:277` (`NavIcon`), `UiComponents.kt:32` (`SettingsSectionHeader`), `Localization.kt:72` (quatre libellés), `OnboardingScreens.kt:45` (fond) et `OnboardingScreens.kt:62` (titre).

## Points vérifiés sans défaut supplémentaire retenu

La navigation utilise un fondu de 150 ms. Le changement de langue, action occasionnelle, enchaîne 150 ms de sortie et 140 ms d'entrée. Aucune animation d'apparition depuis une échelle nulle, cascade répétitive de listes ou animation de layout ajoutée à chaque saisie n'a été trouvée dans les primitives inspectées. Le PIN possède une action clavier directe, un état de vérification empêchant les soumissions répétées et le feedback standard des composants Material. Les commandes d'enregistrement et de traduction exposent leur état occupé et empêchent les doubles mutations.

Les sources relues sont `AstraApp.kt`, `UiComponents.kt`, `DesignComponents.kt`, `LibraryScreens.kt`, `LockContent.kt`, ainsi que les interactions d'édition dans `SaveScreens.kt` et de traduction dans `GameTranslationScreen.kt`. Les temporisations du ViewModel et du debounce de recherche n'ont pas été assimilées à des animations.

## Preuves visuelles

Captures actuelles inspectées via `view_image`, sous `build/emulator-validation/portrait/` :

- `redesign-settings.png` : sections lisibles, hiérarchie distincte, navigation sélectionnée visible.
- `redesign-restore-dialog.png` : titre, conséquence, Annuler et Restaurer visibles, sans débordement observé.
- `redesign-detail.png` : titre et informations lisibles sur la couverture, Jouer/Outils accessibles visuellement, sections distinctes.
- `redesign-collections.png` : catégories, compteurs et création de collection identifiables.
- `lock.png` : biométrie et saisie du code visibles, validation inactive cohérente avec le champ vide.
- `onboarding.png` : titre clair, texte secondaire et bouton Continuer lisibles sur le fond sombre corrigé.

La capture `build/emulator-validation/large-text/redesign-collections.png` a également été inspectée : elle constitue la preuve du défaut de rail avant élargissement. Elle ne doit pas être présentée comme une capture du correctif final.

Après reconstruction, les captures suivantes ont été inspectées directement sous `build/emulator-validation/` : `confirmed-portrait/navigation-focused.png` montre une bordure distincte autour de l'onglet sélectionné ; `confirmed-portrait/onboarding.png` confirme le titre clair sur fond sombre ; `confirmed-landscape-large-text/redesign-settings.png` montre les libellés « Collections » et « Paramètres » entiers à 150 %. Les dimensions paysage de 2 400 × 1 080 et `fontScale = 1.5` sont consignés dans `confirmed-landscape-large-text-configuration.txt`.

Aucun défaut supplémentaire de contraste visuel, de clipping ou de hiérarchie n'a été confirmé dans les six états portrait ci-dessus. Cette inspection ne mesure pas un ratio de contraste, ne montre pas le mouvement image par image et ne prouve pas les annonces TalkBack. Elle ne suffit pas non plus à conclure sur les gestes, les performances sur téléphone physique ou chaque combinaison de langue et de taille de texte.

## État de validation

La confirmation Android exécutée après les derniers correctifs, y compris les libellés anglais de navigation, a réussi **61 tests sur 61** en portrait à 100 % sur l'émulateur SDK API 36.1, en **198,499 s**. Elle inclut les parcours UI ainsi que les preuves natives ML Kit, Keystore et SAF. Journal relu : `build/emulator-validation/instrumentation-confirmed-portrait.log`, résultat JUnit `OK (61 tests)`. Cette confirmation remplace les passes précédentes comme preuve de l'état final des sources.

Les deux tests ajoutés dans `app/src/androidTest/java/fr/astragames/app/ui/LibraryUiTest.kt` ont réussi : `settingsSectionAnnouncesItsStateAndActionAfterEachToggle` vérifie le rôle et les changements d'état sémantique ; `selectedNavigationRemainsFocusableAndKeyboardOperable` vérifie le focus d'un onglet sélectionné et son activation au clavier, avec capture du focus. Ils ne remplacent pas une écoute TalkBack.

La suite UI réexécutée en **paysage à 150 % a réussi 32 tests sur 32**, en **132,522 s**. Journal relu : `build/emulator-validation/instrumentation-confirmed-landscape-large-text.log`, résultat JUnit `OK (32 tests)`. Ce sont les mêmes scénarios UI sous une autre configuration, pas 32 tests uniques supplémentaires.

La reconstruction globale a également réussi : **200 tests JVM dans 39 suites**, builds debug/release R8, APK de tests et variantes Baseline Profile, lint **0 erreur / 45 avertissements**. `build/audit-verification-delivery-final-1.10.0.log` confirme `BUILD SUCCESSFUL in 58s`, avec 200 tâches dont 33 exécutées et 167 à jour.

La suite UI **portrait à 150 % a également réussi 32 tests sur 32**, en **124,801 s**. Journal relu : `build/emulator-validation/instrumentation-confirmed-portrait-large-text.log`, résultat JUnit `OK (32 tests)` ; captures sous `confirmed-portrait-large-text/`. Il s'agit de la seconde réexécution des mêmes scénarios UI, pas de nouveaux tests uniques.

Les deux matrices à 150 % précèdent la dernière correction des libellés anglais ; la disposition française testée reste inchangée. La suite complète de 61 tests et le lancement R8 ont été réexécutés après cette correction. La capture `build/emulator-validation/release-smoke-home.png` montre le défaut de langue avant correction ; `release-smoke-home-final.png` montre ensuite Home/Games/Collections/Settings. Le lancement final R8 a retourné `Status: ok` en 2 456 ms, sans erreur fatale dans le Logcat collecté ; cette durée isolée ne constitue pas un benchmark. Les détails de signature sont consignés dans le [rapport Android](ANDROID_VALIDATION_2026-10-05.md). Les validations utilisent uniquement Android Emulator, SDK, ADB et les tests instrumentés. Ni les tests sémantiques ni les captures ne remplacent un audit vocal TalkBack ou un essai sur téléphone physique.
