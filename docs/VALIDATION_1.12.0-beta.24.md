# Validation de la préversion publique 1.12.0-beta.24

Ce bilan concerne le nouvel APK **release minifié**, version `1.12.0-beta.24`,
code Android `52`, package `fr.astragames.app`.

| Identité | Valeur |
|---|---|
| APK utilisateur | `Astra-1.12.0-beta.24.apk` |
| SHA-256 APK | `f9bed24855f5931f707796bc4ca50c3e52bb09f8b4b2b8809eab20765c669ebc` |
| SHA-256 certificat | `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793` |
| Variante | Release, R8 et réduction des ressources actifs, non débogable |

Le certificat de développement historique est conservé. Il est identique à celui
de l'APK beta.23 distribué. Aucune clé nouvelle n'est créée ni incluse dans les assets.
Les binaires debug et les sondes séparées servent uniquement à la qualification et
ne sont pas les APK utilisateur de cette release.

## Résultats liés à cette reconstruction

| Contrôle | Résultat |
|---|---|
| Assemblages debug, tests et release | Réussis |
| Tests JVM avec corpus Wolf externe disponible | 307 réussis, aucun échec/erreur/ignoré |
| Lint release | 0 erreur, 130 avertissements |
| Tests instrumentés debug, SDK x86_64 4 Kio | 110 réussis, aucun ignoré |
| Tests instrumentés debug, SDK x86_64 16 Kio | 110 réussis, aucun ignoré |
| Code minifié réel, SDK 4 Kio | 31 cas mods et 2 scénarios Wolf réussis |
| Code minifié réel, SDK 16 Kio | 31 cas mods et 2 scénarios Wolf réussis |
| Mise à jour beta.23 vers beta.24, SDK 4 Kio | Réussie sans désinstallation ; données de fixture conservées |
| Signature APK | Schémas v2/v3 vérifiés, certificat historique |
| ZIP et ELF 16 Kio | zipalign réussi ; 30 bibliothèques dans chacun des APK application debug et release |

Les 110 tests debug regroupent 14 régressions d'intégrité des archives de mods,
11 régressions de restauration Wolf et 85 tests voisins : archives, propriété,
symlinks, rollback, migrations Room, stockage COPY/DIRECT/SAF, reprise et gros
ensembles de ressources. Ces essais utilisent des fixtures isolées.

Les 130 avertissements lint restent ceux du dernier état revu, notamment des
suggestions de dépendances et de style. Aucune mise à niveau majeure n'a été faite
pour changer ce décompte. Le corpus Wolf réel n'est pas distribué ; un clone sans
ces ressources ne peut pas reproduire les tests correspondants comme exécutés.
Voir les [instructions de construction](BUILD_1.12.0-beta.24.md).

## Mise à jour sans perte des données de fixture

L'essai commence avec l'APK beta.23 public exact, SHA-256
`31d9051bc277c0672cc1d0ba8fdfe74168edbdafbde102596d4e87470f538d74`, sur un nouvel
émulateur dédié. Le vrai onboarding crée le catalogue Room et les préférences.
Une fixture est ensuite ajoutée à cette base : jeu favori, source, profil,
index de recherche, emplacement de sauvegarde et backup.

L'APK release beta.24 est installé par remplacement, sans désinstallation ni
effacement des données. L'identité et la date de première installation restent
inchangées. Les lignes des six tables sont comparées par colonne, type et valeur ;
six fichiers, dont DataStore, sauvegarde et backup, sont comparés octet pour octet.
Ces comparaisons passent avant puis après ouverture de la nouvelle interface.
Le réglage de scan reste désactivé, la recherche retrouve le favori et la fiche
du jeu s'ouvre. Les captures sont relues.

Il s'agit d'une preuve de mise à jour en place sur Android x86_64 4 Kio, pas d'une
partie jouée ni d'un essai de migration de schéma : le schéma Room reste en version 9.
La fixture est insérée par SQLite dans la vraie base créée par l'application ;
cet essai ne revendique pas l'import/scan d'un jeu réel.

## Restauration et code minifié

Une sauvegarde Wolf propre A, archivée alors que sa source vaut A puis restaurée
après évolution de cette source en B, reste A dans la copie privée. La source B
reste intacte et la synchronisation signale le conflit. Les ressources du jeu
continuent d'être importées ; une écriture ultérieure peut être publiée lorsque
la source correspond à la baseline attendue. COPY, SAF, reprises et états pending
sont couverts par les régressions debug.

Une archive de mods qui conserve une référence `REPLACED` sans son original, ou
avec des octets différents du hash enregistré, est refusée avant modification du
catalogue/WAL et des backups vivants. L'export vérifie lui aussi les originaux.
Les cas valides comprennent un véritable aller-retour puis une désinstallation
qui restitue l'original. `ADDED` reste sans backup/hash original requis.

La sonde minifiée charge les vrais helpers `prepareExport`/`planImport` des mods
et les vrais corps Wolf `planImport`/`prepare`/`synchronize` depuis l'APK utilisateur.
Elle est liée à son APK, son mapping et son DEX ; aucun hook ou keep rule n'est
ajouté au produit. Ses 31 cas mods comprennent cinq acceptations et 26 refus attendus.
Elle ne réalise pas la désinstallation ni la transaction Room complète des mods ;
ces couches sont exercées par les tests debug. Les scénarios Wolf minifiés ne
qualifient ni Wine, ni le codec, ni SAF ou l'annulation du dispatcher extérieur.

## Héritage et limites

Les grands parcours UI, les fixtures RGSS XP/VX/VX Ace et les essais Wolf/Wine
antérieurs ne sont pas présentés comme de nouvelles exécutions de cet APK.
Ils restent des preuves historiques du code conservé. Les 35 payloads de runtimes
comparés sont identiques aux versions qualifiées ; aucune nouvelle compilation
native n'a été nécessaire pour ces corrections de données.

L'exécution ARM64 n'est pas démontrée. L'image SDK x86_64 16 Kio expose des pages
applicatives de 16 Kio avec la compatibilité 4 Kio désactivée, sur un noyau/MMU de
4 Kio. L'alignement des bibliothèques ne constitue pas une preuve de fonctionnement
sur tous les téléphones. La limite RELRO de zstd ARM64 reste distincte de cet
alignement. La cause d'un ancien gel d'émulateur reste inconnue ; aucun essai
interrompu n'est compté comme réussi.

Les jeux commerciaux, extensions Windows, RTP et le lanceur externe réel ne sont
pas universellement qualifiés. La preuve audio antérieure est héritée, sans nouvel
enregistrement de cette reconstruction. Les archives restent liées à la clé de
l'installation et n'incluent pas les assets des jeux. Les hashes d'un ZIP legacy
contrôlent sa cohérence, pas son authenticité si son payload et ses hashes sont
modifiés ensemble.

Les sources et notices accompagnent l'APK. `VALIDATION_SUMMARY.json` fournit le
résumé structuré et `SHA256SUMS.txt` les empreintes des assets ; les journaux bruts,
identifiants locaux et données de qualification ne sont pas publiés.
