# Métadonnées et jaquettes — 8 novembre 2026

## Périmètre

Refonte de l’import et de l’édition des métadonnées Android, vérification de l’enrichissement automatique et correction du chargement des jaquettes F95Zone. Travail local sur la branche `codex/wolf-runtime-release`, sans changement de version ni publication.

## Parcours livré

- Recherche automatique par titre, lecture d’un lien validé ou choix dans le navigateur.
- Aperçu avant toute écriture : valeurs actuelles et trouvées, choix des champs à remplacer, des tags à ajouter et d’une jaquette facultative. Les champs absents sont présélectionnés ; les champs déjà renseignés restent décochés.
- Lecture et fermeture d’un aperçu sans modification de la bibliothèque. Une erreur de téléchargement laisse l’aperçu et la sélection ouverts. Le recadrage intervient après l’enregistrement des informations sélectionnées.
- Éditeur plein écran, deux panneaux défilants à partir de 600 dp, bouton Enregistrer accessible au-dessus du clavier, validation des liens, retour d’erreur et confirmation d’abandon du brouillon.
- Conservation des saisies pendant un import ou un choix de jaquette ; mise à jour des champs restés intacts. Même synchronisation dans l’assistant après scan, sans réinitialiser la sélection de tags.
- Libellés ajoutés au catalogue des sept langues de l’application.

## Constats et corrections F95Zone

La fiche publique de test `https://f95zone.to/threads/yabunuma-the-complete-edition-v1-0-n-r-d-works.321695/`, consultée le 8 novembre 2026, renvoie HTTP 200 en mode visiteur mais remplace les pièces jointes par `.messageHide--attach`. Le document porte `data-logged-in="false"`. Cela se distingue d’une fiche qui n’a réellement aucune image. Le JavaScript public du site utilise toujours `data-src`, `data-srcset` et les éléments `picture` pour le chargement différé.

L’application reconnaît cette restriction. Lorsqu’une session enregistrée existe mais que F95Zone renvoie une page visiteur, elle demande une reconnexion au lieu de considérer le nom enregistré comme une preuve de connexion. Une session peut être refusée ou expirée ; le contrôle ne permet pas de déterminer la cause exacte de son invalidation.

La connexion vérifie l’état membre dans le document du navigateur. Une session active sans cookie persistant `xf_user` est acceptée ; `xf_session` reste nécessaire. Les cookies récents du navigateur prennent le relais des valeurs enregistrées. Les requêtes HTML, aperçus Coil et téléchargements partagent le même agent utilisateur et les cookies F95Zone, avec un contrôle de l’hôte à chaque requête réseau. Aucun cookie F95Zone n’est envoyé à un hôte externe ni en HTTP.

Le parseur se limite au premier message, exclut citations, signatures et émoticônes, distingue original et aperçu, conserve les proxys du site et prend en charge les sources différées et `picture`. Le téléchargement essaie les images de repli, reste borné à 20 Mio et vérifie le décodage Android. Les fichiers partiels sont supprimés en cas d’erreur.

## Autres défauts corrigés

- L’analyse d’un lien enregistrait auparavant des informations avant validation.
- L’ouverture d’un import ou du choix de jaquette fermait l’éditeur et perdait le brouillon.
- Un lien F95Zone invalide pouvait devenir silencieusement une valeur vide ; les erreurs de sauvegarde n’étaient pas traitées dans l’éditeur.
- Les résultats F95Zone étaient trouvés sans être lus pendant l’enrichissement automatique. Les fiches liées servent maintenant à compléter les informations et les jaquettes manquantes.
- Un résultat issu d’un moteur de recherche ne complète automatiquement une fiche que si son titre correspond. Une correspondance VNDB fondée uniquement sur une très courte sous-chaîne n’est plus acceptée.
- La fusion automatique s’effectue dans une transaction Room et conserve les informations déjà présentes. L’enregistrement automatique d’une jaquette est conditionnel : une sélection manuelle effectuée entre-temps reste prioritaire.
- Une version ou une langue absente laisse désormais le jeu éligible à l’actualisation. Le bilan compte les fiches réellement modifiées. Une erreur sur un jeu ne bloque pas le reste du lot.
- Les associations de tags vides ne déclenchent plus de lecture ni de réécriture inutiles.
- La sélection d’image dans le navigateur utilisait `document.images.forEach`, indisponible sur `HTMLCollection`. Elle utilise désormais `Array.from(document.images)` et a été exécutée dans le WebView Android réel.

## Validation

Environnement : émulateur Android Studio `small_phone`, API 36, x86_64. Tests effectués uniquement par les outils Android SDK, instrumentation et Logcat. Aucun outil ARTEMIS utilisé.

Commandes de construction :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Les classes `MetadataPipelineTest`, `MetadataUiTest`, `MetadataLiveTest`, `CoverLifecycleTest`, `MetadataMigrationTest` et `AstraDatabaseTest` sont exécutées avec `adb shell am instrument`. Les tests réseau sont activés explicitement avec `-e metadataLive true` ; ils sont ignorés par défaut pour ne pas rendre les tests ordinaires dépendants du réseau.

Les vérifications réseau ont obtenu la version de la fiche F95Zone actuelle avec l’indication de connexion requise. L’enrichissement complet du jeu témoin « Doki Doki Literature Club! » a enregistré développeur, description et une jaquette VNDB décodable de 600 × 900 pixels. Le jeu témoin et sa jaquette sont retirés après le test.

Les scénarios instrumentés couvrent notamment le HTML visiteur actuel, une session enregistrée refusée, une session sans cookie persistant, l’isolation des cookies, les images différées, les réponses invalides, les replis d’images, les champs sélectionnés, les brouillons, les liens invalides, l’index de recherche, les tags existants et la priorité de la jaquette manuelle.

| Vérification | Résultat |
|---|---|
| Compilation app et APK de tests | Réussie |
| Instrumentation principale, dont deux tests réseau réels | 40 / 40 réussis |
| Paysage, fenêtre 1280 × 720 px | 3 / 3 réussis |
| Portrait, texte système à 150 % | 3 / 3 réussis |
| Android Lint | Aucune erreur ; 117 avertissements restent dans le projet |
| Relecture visuelle | Portrait, paysage et texte agrandi contrôlés |

Journaux : [instrumentation](evidence/metadata-2026-11-08/instrumentation.txt), [paysage](evidence/metadata-2026-11-08/landscape.txt), [texte agrandi](evidence/metadata-2026-11-08/large-text.txt), [réseau réel](evidence/metadata-2026-11-08/network.txt), [résumé et empreinte APK](evidence/metadata-2026-11-08/summary.json).

Captures : [éditeur portrait](screenshots/metadata-2026-11-08/portrait-editor.png), [éditeur paysage](screenshots/metadata-2026-11-08/landscape-editor.png), [recherche](screenshots/metadata-2026-11-08/portrait-import-source.png), [connexion F95Zone requise](screenshots/metadata-2026-11-08/portrait-import-login.png), [texte agrandi](screenshots/metadata-2026-11-08/large-text-import-login.png).

APK debug : `app/build/outputs/apk/debug/app-debug.apk` (50,065,523 octets). SHA-256 : `05373e30e43d0a2086ce553d2c173617b0cd137f8d3612992bcd166e2a932690`.


## Limites

- La session personnelle F95Zone de l’utilisateur et un téléchargement authentifié avec son compte n’ont pas été testés. Le comportement membre est couvert par des fixtures et des tests de transmission de cookies ; la vérification réseau réelle utilise une page visiteur.
- L’acceptation par F95Zone d’une session expirée ou d’une vérification interactive ne peut pas être forcée. L’application propose la reconnexion et la relecture.
- Ryuugames et les différents moteurs de recherche restent dépendants de leurs réponses externes ; tous les services n’ont pas fait l’objet d’un test réseau réel dans cette intervention.
- Pas de validation sur téléphone physique ni de publication de release. L’APK fourni est une compilation debug destinée à l’essai.
- La recherche de défauts porte sur les parcours métadonnées, images, édition et stockage associés ; elle ne constitue pas un audit exhaustif des moteurs d’exécution de jeux.
