# Validation de la préversion 1.12.0-beta.26

Application `fr.astragames.app`, version `1.12.0-beta.26`, code Android `54`.
Qualification du 17 novembre 2026, depuis le commit de beta.25 `ba76219`.

| Identité | Valeur |
| --- | --- |
| APK | `Astra-1.12.0-beta.26.apk` |
| SHA-256 APK | `450dc0d3cdd2f0d5a219dee524e0284109d48e5cd87133898835301c66fb9584` |
| SHA-256 certificat | `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793` |
| Variante | Release non débogable, R8 et réduction des ressources actifs |
| SDK et ABI | minSdk 26, targetSdk 36 ; ARM64 et x86_64 |

## Vérifications réalisées

Les essais utilisent exclusivement Android Studio Emulator, le SDK Android, ADB,
l’instrumentation et Logcat. Les parcours instrumentés tournent sur Android 16
(API 36) x86_64 ; le remplacement final de l’APK est vérifié sur Android 17
(API 37) x86_64. Aucun test JVM ni téléphone physique n’est présenté comme exécuté.

| Contrôle | Résultat |
| --- | --- |
| Assemblages debug, androidTest et release | Réussis |
| Android Lint release | 0 erreur, 137 avertissements |
| Stockage, bibliothèque, métadonnées, Room et réseau réel | 85 tests réussis, aucun ignoré |
| Reprise en paysage 1280 × 720, texte 130 % | 5 tests réussis |
| Reprise du défilement des doublons en portrait | 1 test réussi |
| Mise à jour beta.25 → beta.26 et navigation après installation | Réussies, données témoins identiques |
| Signature v2/v3 et alignement ZIP 16 Kio | Vérifiés avec les outils SDK |
| Logcat de l’APK release pendant le parcours final | Aucun crash enregistré |

La matrice finale contient **85 tests distincts**, avec 6 reprises dans les autres
configurations. Les premières passes de mise au point ne sont pas ajoutées à ces
totaux. Les avertissements lint restent visibles ; ils ne sont pas présentés comme
corrigés par cette version.

## Suppression, exclusion et doublons

Les 11 tests de `LibraryManagementTest` vérifient un véritable fournisseur de
documents SAF de fixture : suppression du dossier choisi, conservation de l’autre
copie, refus Android, fournisseur annonçant une suppression sans effacer, dossiers
partagés, sources racines et sélection multiple partiellement refusée.

L’exclusion survit à l’indisponibilité du stockage, couvre les sous-dossiers et
les autres autorisations donnant accès au même dossier. Elle n’exclut plus une
autre copie sur la seule base de son empreinte. Le scan concurrent attend le
retrait. La fusion récupère les sauvegardes de fixture avant de supprimer le
secondaire. Les titres, versions, liens de fiches et suites numérotées sont exercés.
Les doublons affichés restent des rapprochements possibles, sans hachage exhaustif
du contenu des jeux.

Les tests d’interface vérifient les deux choix de retrait, le chemin affiché,
le maintien du dialogue en cas de refus, les actions sur chaque copie et leur
accessibilité après défilement sur écran court.

## Navigateur et dates

Le navigateur interne s’ouvre même si le navigateur externe est préféré ailleurs.
Les essais couvrent le changement de moteur, le rejet des adresses locales et la
sélection explicite de liens F95Zone et Ryuugames dans un véritable WebView avec
pages HTTPS de fixture. Une ancienne navigation ne remplace plus l’adresse d’une
nouvelle recherche. La fiche n’est importée qu’après sélection et confirmation.

Les deux tests réseau réels ont récupéré les champs d’une fiche F95Zone publique
et un enrichissement VNDB avec jaquette décodable. Un captcha Yandex a été observé
pendant le parcours en ligne ; le moteur se change directement dans Astra.
Ces essais ne qualifient ni un compte F95Zone personnel ni tous les moteurs et
sites dans toutes les conditions réseau.

Les filtres récents, la période personnalisée, les deux journées incluses, les
bornes de mois et le changement d’heure sont vérifiés. Le calendrier français et
le bouton d’application sont contrôlés, y compris dans l’APK release minifié.
Le navigateur release et le changement Yandex → Google sont aussi parcourus
hors ligne, avec erreur de connexion visible et sélection de lien désactivée.

## Conservation des données

L’APK beta.25 de référence conserve le SHA-256
`ff5dcec4043d9efbd471b2b419bf138e8b2867e882be8d6d83f485130f9f9f74`.
L’APK signé final est installé avec `adb install -r`, sans désinstallation.
Une sonde indépendante fondée sur les API Android compare **31 tables SQLite**,
les préférences, un fichier de sauvegarde témoin et la date de première installation.

Les comparaisons sont identiques après remplacement puis après ouverture de la
bibliothèque, des doublons, des dates personnalisées, de la fiche, de l’éditeur,
de l’import et du navigateur intégré. Le catalogue témoin contient un jeu favori.
La qualification finale est hors ligne pour éviter les écritures automatiques
liées aux vérifications réseau pendant la comparaison des préférences.

Le schéma Room reste en version 9 : il ne s’agit pas d’une nouvelle migration.
La sonde n’ajoute aucun hook à l’APK utilisateur et n’est pas distribuée avec lui.

## Portée de la livraison

Les sources natives et notices sont reprises de beta.25, sans changement de
runtime dans les sources de cette version. L’alignement ZIP ne constitue pas
une qualification de jeux ou de runtimes sur un téléphone ARM64 à pages de 16 Kio.
Aucun jeu commercial, plugin externe ou appareil physique n’est nouvellement qualifié.

L’archive source correspond au commit de la version. `SOURCE_MANIFEST.json`
identifie les fichiers, `VALIDATION_SUMMARY.json` résume ces résultats et
`SHA256SUMS.txt` permet de vérifier les assets. Les journaux de la matrice finale
et les empreintes du catalogue synthétique sont conservés sous
`docs/evidence/library-2026-11-17/`.
