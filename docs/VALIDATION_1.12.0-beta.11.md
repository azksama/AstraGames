# Validation Astra 1.12.0-beta.11

Version Android 39, runtime partagé `wine9-astra-2`. Cette livraison traite la reprise après
un arrêt brutal, l’affichage du chargement, le Retour à deux doigts et le zoom optionnel.

## Comportement livré

- Les sessions sont sérialisées jusqu’à la fin de leur fermeture et de la synchronisation.
  Les anciens processus Wine et audio sont identifiés dans `/proc` par leur UID et leurs
  chemins propres à Astra, avec une nouvelle vérification de leur identité avant le signal.
  La récupération ne supprime ni le préfixe Windows ni les sauvegardes.
- La lecture initiale des réglages est limitée à huit secondes et fournit une erreur visible
  si elle ne répond pas. Les composants déjà installés affichent leur vérification ; les
  téléchargements et extractions affichent leur avancement réel. L’extraction est interruptible.
- Huit étapes sont affichées avec leur détail et le temps écoulé. Les commandes et le compteur
  restent masqués jusqu’au rendu d’une image non noire du jeu ; les consoles et la préparation
  Windows sont exclues. Le décès du jeu est détecté pendant cette attente ; après trois minutes
  sans première image, le lancement échoue avec un accès au diagnostic.
- Un toucher bref de l’image à deux doigts envoie la touche Retour configurée pour ce jeu.
  Les gestes déplacés, annulés, trop longs ou à trois doigts ne déclenchent pas Retour.
- Le zoom à deux doigts est optionnel, désactivé par défaut et mémorisé par jeu. Il permet
  un grossissement de 1× à 4×, avec déplacement de l’image agrandie. Le point touché et le rendu
  utilisent la même transformation. La désactivation du réglage restaure le cadrage.

## Preuves locales

- **233 tests JVM** passent sans erreur, échec ni test ignoré. Builds debug, instrumentation
  et release R8 réussis. Lint : **0 erreur**, 87 avertissements dans l’application et 6 dans
  le module Windows ; les avertissements restent visibles.
- **25 tests Android** passent sur Android Emulator officiel API 36.1 x86_64 avec ADB et
  instrumentation SDK : gestes et persistance du zoom, nettoyage d’un processus appartenant
  au runtime, rendu GLES réel, entrées et options, stockage direct, fermeture et interface d’erreur.
- Le test du renderer maintient le chargement devant une console Windows colorée et devant
  une fenêtre de jeu noire, puis reconnaît une image de jeu statique visible. Il vérifie aussi
  le zoom, sa remise à zéro et la correspondance des coordonnées centrales.
- L’APK **release optimisé et non debuggable**, signé, lance le jeu officiel Wolf **3.729**
  depuis son dossier partagé d’origine avec le runtime Wine réel. La sonde attend une scène
  visible, envoie Valider, puis tue volontairement le processus Android avant sa fermeture.
  La première instrumentation rapporte donc `Process crashed`, résultat attendu de cette injection.
- Le lancement suivant réussit : image visible, entrée acceptée, fermeture Wine supervisée,
  session terminée et configuration source restaurée. Un fichier sentinelle `.sav`, écrit avant
  l’arrêt forcé, est conservé. Ce contrôle vérifie la conservation d’un fichier, pas une sauvegarde
  de partie interprétée par Wolf. Le mode `DIRECT` et l’absence de copie privée sont aussi exigés.
- Le relancement atteint la première image et active les commandes en **14 808 ms** ; la sonde
  termine sa vérification de scène à **29 697 ms**, dont environ 14,5 secondes d’attentes fixes.
  Le préfixe et les composants étaient déjà installés. Ce résultat n’est pas une mesure du
  premier téléchargement ni de tous les jeux.
- Les captures du chargement sans commandes et de la partie avec commandes ont été examinées.
  Alignement ZIP 16 Ko et signature APK v2/v3 vérifiés ; cela ne valide pas l’exécution sur un
  appareil utilisant des pages mémoire de 16 Ko.

Journaux dans `build/wolf-research/` : `beta11-build-final.log`, `beta11-runtime-lint.log`,
`beta11-android-tests.log`, `beta11-forced-interruption.log`, `beta11-release-recovery.log`,
`beta11-signature.log`. Captures : `beta11-loading.png`, `beta11-game.png`.

## Limites

Aucun téléphone ARM64, Samsung SM-S948B ni Dragon Blood n’a été testé dans cette validation.
Les tests de gestes utilisent les événements Android instrumentés ; ils ne remplacent pas un
essai tactile sur téléphone. Le délai réel dépend de Windows, du support et du jeu. La première
image est reconnue dans les fenêtres du jeu par un échantillonnage borné des pixels ; un jeu qui
reste entièrement noir ou attend une entrée avant sa première image peut nécessiter une adaptation.
La fermeture forcée ne crée pas de sauvegarde de la progression encore en mémoire.
