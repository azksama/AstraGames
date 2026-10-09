# Validation Astra 1.12.0-beta.12

Version Android 40, runtime `wine9-astra-2`. Cette livraison corrige des dépendances inutiles
dans la reprise d’un jeu en mode direct et améliore le diagnostic d’une attente de lancement.
Le signalement concernait l’étape 2, « Moteur Wolf déjà installé », avec un compteur qui avançait.
Le second signalement à l’étape 8 a été retiré par l’utilisateur après l’apparition de l’image.

## Changements

- La transition vers l’étape 3 est affichée avant la construction du stockage. Les opérations
  de préparation et les écritures du diagnostic sont exécutées sur le dispatcher IO. L’interface
  est mise à jour avant la persistance du libellé ; les accès aux vues restent sur Main.
- La récupération de Game.ini, la lecture du dernier mode, l’accès au dossier d’origine,
  l’index privé et la migration des sauvegardes ont des libellés distincts.
- Un jeu déjà en mode `DIRECT` avec la même source ne relit plus les index d’import et ne
  requiert plus une interrogation SAF avant d’utiliser son dossier physique accessible.
  L’inventaire de ses sauvegardes ignore aussi ces index. La migration depuis une copie
  et les contrôles des versions divergentes sont conservés.
- Le chargement propose **Diagnostic du lancement**. Une attente de plus de 20 secondes
  sans changement de libellé ajoute l’état des threads Astra dans un flux `loading` séparé,
  accessible même lorsque le verrou de métadonnées est occupé. Les workers sont reconnus
  par leur nom également dans l’APK obfusqué. Cette attente seule ne prouve pas un crash.
- Le rapport actif lit un instantané des métadonnées en mémoire. Il ne peut donc pas supprimer
  le fichier `.new` d’une écriture AtomicFile en cours. Les rapports terminés restent lus sur disque.
- Le contrôle de la première image et son délai de trois minutes restent identiques à la bêta 11.

## Validation

- **233 tests JVM**, sans échec, erreur ni test ignoré ; builds debug, instrumentation et
  release R8 réussis. Lint : 0 erreur, 88 avertissements dans l’application et 6 dans le module Windows.
- **42 tests Android distincts passent** : premier lot de 43 tests déclarés, dont 41 exécutés
  et deux sondes manuelles de mort du processus ignorées faute d’arguments ; second lot ciblé
  de 11 tests déclarés, dont neuf exécutés, ajoute un test et revalide les diagnostics finaux.
- Une reprise directe avec journal de configuration conservé et anciens index volontairement
  abîmés réussit. La sauvegarde est conservée et exportable ; les index restent inchangés,
  la configuration d’origine est restaurée et aucune copie privée du jeu n’est créée.
- StrictMode contrôle le parcours réel de l’activité : le moteur déjà installé passe à la
  vérification du dossier, puis fournit l’erreur attendue pour une source absente. Aucune lecture
  ou écriture de diagnostic/stockage n’est détectée sur le fil d’affichage.
- Un test maintient le verrou des métadonnées occupé et vérifie que le flux de chargement
  reste écrit et inclus dans le rapport. Un autre maintient une écriture AtomicFile ouverte :
  consulter le rapport actif conserve son fichier temporaire, puis sa finalisation réussit.
- L’APK release signé, **non debuggable**, lance Wolf officiel **3.729** avec les composants
  Wine réels depuis son dossier partagé d’origine. Après une scène visible, la sonde tue
  volontairement le processus Android ; le résultat `Process crashed` est attendu pour ce passage.
- Le relancement réussit : commandes activées à **14 854 ms**, vérification de scène à
  **29 782 ms**, dont environ 14,5 secondes d’attentes fixes de la sonde. Le jeu répond à Valider,
  Wine termine proprement, un fichier `.sav` sentinelle reste conservé et Game.ini est restauré.
  Le préfixe et le moteur étaient déjà préparés. La sentinelle vérifie un fichier, pas l’interprétation
  d’une sauvegarde de partie par Wolf.
- Signature v2/v3 et alignement ZIP 16 Ko vérifiés. Les captures du chargement et de la partie
  sont examinées ; les outils de test sont exclusivement Android Emulator officiel, ADB,
  instrumentation et outils du SDK.

Journaux locaux dans `build/wolf-research/` : `beta12-build.log`, `beta12-final-build.log`,
`beta12-android-tests.log`, `beta12-diagnostic-final-tests.log`, `beta12-forced-interruption.log`,
`beta12-release-recovery.log` et `beta12-signature.log`. Captures : `beta12-loading.png`, `beta12-game.png`.

## Limites

Le blocage exact de l’utilisateur à l’étape 2 n’a pas été reproduit sur son téléphone et aucun
nouveau rapport correspondant n’a été reçu pendant cette livraison. Les défauts des anciens
index et de l’écriture/lecture du diagnostic sont validés par des fixtures. Le parcours réel de
reprise est validé sur Android Emulator API 36.1 x86_64 ; aucun Samsung ARM64 ni Dragon Blood
n’est testé ici. Les nouveaux détails et le flux de chargement permettent de préciser une attente
persistante sur appareil. Aucune suppression du préfixe, des données d’Astra ou des sauvegardes
n’est nécessaire pour installer cette version.
