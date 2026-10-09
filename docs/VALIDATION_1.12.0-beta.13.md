# Validation Astra 1.12.0-beta.13

Version Android 41, runtime `wine9-astra-2`. Cette livraison corrige la récupération des
sauvegardes lors du premier passage d’une ancienne copie privée au dossier d’origine.

## Constat du rapport reçu

Le rapport de la session `1791564608315-217046fc-939a-4764-86bd-e301e2aaa63b`, sous bêta 12,
montre un moteur déjà installé, puis l’étape « Récupération des sauvegardes de l’ancienne copie… ».
Le fil d’affichage répond toujours. Après vingt secondes, le worker de préparation se trouve
dans une requête au fournisseur de documents Android.

Le mapping R8 de l’APK bêta 12 restitue `d5.k.v:234` comme la ligne 319 de la synchronisation
Wolf, `U1.a.g:10` comme `DocumentFile.findFile`, `U1.c.i:11` comme `TreeDocumentFile.getName`,
et `Z3.p5.e:15` comme `DocumentsContractApi19.queryForString`. La migration connaissait déjà
le dossier physique accessible, mais recherchait encore chaque segment de chemin via SAF.
`findFile` relisait les noms des enfants par des requêtes individuelles. L’index contenant
toutes les empreintes était aussi réécrit après chaque fichier récupéré.

Le rapport localise l’attente dans ce parcours ; il ne contient pas un arrêt final du jeu.
Un instantané de threads ne mesure pas à lui seul le nombre de requêtes ou leur durée totale.

## Correctif

- `WolfGameStorage` transmet directement le dossier physique à la récupération lorsqu’il
  est accessible. Les synchronisations de copies privées utilisent également cet accès
  lorsqu’il existe. Le choix de stockage par jeu reste respecté.
- `WolfFileSaveDestination` compare le contenu source à l’empreinte de l’import. Une source
  identique au fichier local valide une écriture antérieure ; une source divergente reste
  intacte et conserve le jeu dans sa copie privée. Un remplacement écrit et vérifie un fichier
  temporaire, copie et synchronise une sauvegarde de la version source, puis renomme le fichier
  complet dans le même dossier. Le fichier source reste présent pendant la préparation.
- Le parcours SAF lit les noms et types en lots par dossier. Les fichiers réellement modifiés
  sont revérifiés avant remplacement, car un cache de noms ne suffit pas pour autoriser
  l’écrasement d’une modification extérieure. Les requêtes de liste utilisent CancellationSignal.
  Un renommage final refusé tente de rétablir le fichier source et conserve la copie privée.
- Les ressources inchangées utilisent leurs métadonnées déjà indexées ; les sauvegardes
  reconnues sont toujours comparées par empreinte. Les fichiers créés ou modifiés hors Save
  restent récupérés. Les liens de la copie et les fichiers internes `.astra-` sont ignorés.
- Le nombre de fichiers vérifiés, récupérés et en conflit est affiché dès le début, après
  la première récupération et au plus une fois par seconde ensuite. L’index est enregistré
  en fin de parcours, y compris sur annulation ou erreur, au lieu d’être réécrit après chaque
  fichier. Une mort du processus avant cet enregistrement est récupérée par la comparaison
  des contenus au lancement suivant.

## Validation locale

- **233 tests JVM** : aucun échec, erreur ou test ignoré. Builds debug, instrumentation et
  release R8 réussis. Lint : zéro erreur, 88 avertissements dans l’application et 6 dans le
  module Windows, identiques à la bêta 12.
- **49 tests Android distincts passent**, dont sept nouveaux tests de migration. Deux sondes
  manuelles de mort du processus sont ignorées faute d’arguments. Le premier lot valide
  37 tests ; quatre noms de classes erronés dans la sélection ont été corrigés, puis les
  12 tests correspondants passent dans le second lot. Le récapitulatif nominatif est dans
  `build/wolf-research/beta13-test-summary.json`.
- Une vraie URI `com.android.externalstorage.documents` résout un dossier externe appartenant
  à l’application. La migration dispose de **70 000 fichiers de ressources en cache**, de
  32 sauvegardes modifiées et d’un nouveau fichier de progression hors Save. Le ContentResolver
  est volontairement indisponible dans le contexte utilisé pour migrer : les **33 fichiers**
  sont récupérés, leurs versions sources sauvegardées, le mode devient DIRECT et aucun accès
  au fournisseur n’est nécessaire. Dernière mesure : **7 673 ms**, 17 messages de progression.
  Un fichier de ressource privé rendu illisible confirme l’absence de relecture de son contenu.
  Les ressources de cette fixture sont vides ; le temps mesure la migration d’un cache terminé,
  pas l’import initial ou la lecture de 70 000 images sur un téléphone.
- Le fournisseur de test SAF traite 1 500 ressources et trois fichiers modifiés : **3 requêtes
  de métadonnées de document et 7 requêtes de dossiers**. Les fichiers imbriqués et hors Save
  sont récupérés, et la version source précédente reste sauvegardée.
- Une annulation après la première récupération conserve tous les fichiers privés, puis une
  nouvelle préparation termine la migration. La perte simulée de l’index après une écriture
  terminée ne crée ni conflit ni sauvegarde de sécurité en double. Un renommage SAF final
  refusé rétablit l’original, conserve la progression privée et permet un nouvel essai.
- Les tests de conflits vérifient la conservation des deux versions et l’export de la version
  privée. Les tests existants revalident le cache, les permissions, Game.ini, les sauvegardes
  Windows, les diagnostics, la reprise des processus, les commandes, les gestes et le contrôle
  de la première image. StrictMode ne détecte pas d’accès disque de préparation sur Main.

## APK release réel

L’APK signé, **non debuggable**, est installé par-dessus Astra sur Android Emulator officiel
API 36.1 x86_64. Le moteur Wine et le préfixe du jeu Wolf officiel **3.729** sont déjà préparés.
La sonde SDK crée une ancienne copie complète et une progression privée en attente, avec
les index d’import correspondants. La première exécution migre cette progression vers le
dossier partagé d’origine, garde la copie privée et la version source précédente, puis affiche
une scène du jeu et accepte Valider. La sonde vérifie ces fichiers avant de tuer volontairement
le processus Android ; `Process crashed` est le résultat attendu de cette interruption.

Le relancement du même APK réussit : commandes visibles à **13 813 ms**, scène contrôlée à
**28 776 ms**, dont environ 14,5 secondes d’attentes fixes de la sonde. Les sentinelles de
migration et d’interruption restent présentes. La fermeture normale termine Wine avec son
serveur supervisé et restaure les octets d’origine de Game.ini. Les sentinelles vérifient la
conservation de fichiers ; elles ne prouvent pas le chargement d’une sauvegarde de partie par Wolf.

Signature v2/v3, certificat historique d’Astra et alignement ZIP 16 Ko vérifiés. Les captures
du chargement et de la scène sont examinées : commandes absentes pendant le chargement,
présentes sur la scène. Les tests utilisent exclusivement Android Emulator, ADB, instrumentation,
Logcat et outils officiels du SDK Android.

Journaux : `build/wolf-research/beta13-build.log`, `beta13-final-test-build.log`,
`beta13-android-tests.log`, `beta13-android-additional-tests.log`,
`beta13-release-migration-interruption.log`, `beta13-release-recovery.log`,
`beta13-signature.log` et `beta13-alignment.log`. Captures : `beta13-loading.png`, `beta13-game.png`.

## Limites

Le jeu signalé et le Samsung ARM64 de l’utilisateur ne sont pas exécutés ici. Le rapport reçu
établit le parcours qui attend ; les tests reproduisent la migration, les conflits et les
interruptions sur émulateur. Le temps réel du jeu dépend du stockage, du fournisseur et des
fichiers modifiés ; aucune durée universelle de lancement ni cadence de jeu n’est annoncée.
L’installation conserve les données existantes. Aucune suppression de sauvegardes ou de
préfixe, ni réimportation systématique du jeu, n’est requise.
