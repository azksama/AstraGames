# Astra 1.11.0 — Analyse à la demande et mises à jour intégrées

- **Analyse des textes manuelle** : l’ouverture du traducteur ne lance plus d’extraction. Le bouton **Analyser les textes** démarre le travail à votre demande.
- **Cache persistant** : le dernier résultat complet réapparaît à la prochaine ouverture. Les extractions sont réutilisées si les empreintes des fichiers correspondent ; les fichiers modifiés sont relus avant toute application ou export. Une analyse interrompue ne remplace pas le résultat complet.
- **Rognage des jaquettes** : le passage entre les activités internes ne provoque plus de reverrouillage biométrique. Un vrai passage en arrière-plan conserve le verrouillage configuré.
- **Sources de métadonnées** : choix entre deux fournisseurs pendant l’import, liens conservés séparément, récupération des tags, images et champs disponibles. La migration du catalogue conserve les fiches existantes et les sauvegardes incluent le nouveau lien.
- **Mises à jour d’Astra depuis GitHub** : vérification périodique des releases stables, téléchargement automatique en Wi-Fi par défaut, notification et installation depuis les paramètres. Le dépôt est public ; aucun compte ou jeton n’est nécessaire.
- **Contrôles avant installation** : taille, SHA-256, package, version supérieure et certificat identique à l’application installée. Les APK non signés sont exclus et le fichier est revérifié juste avant l’installation.

Ouvrir **Paramètres → Mises à jour d’Astra** pour vérifier la disponibilité ou régler les automatismes. Android demande l’autorisation d’installer depuis Astra au premier usage, puis la confirmation de chaque installation. Une application Android ordinaire ne peut pas installer silencieusement une mise à jour.

Installez cet APK une première fois depuis GitHub pour bénéficier du module intégré dans les prochaines mises à jour.

## Validation

- 209 tests JVM réussis, sans échec ni test ignoré.
- 68 tests Android réussis dans la suite finale sur Android Emulator API 36.1, sans échec ni test ignoré. Le modèle ML Kit réel, la migration Room 8 vers 9, le stockage SAF, Android Keystore et la conservation des liens après scan sont inclus.
- Les trois parcours de traduction sont aussi vérifiés en paysage et portrait avec le texte à 150 %.
- Builds debug, release optimisée R8 et Baseline Profile réussis. Lint : 0 erreur, 47 avertissements ; les deux nouveaux conseils KTX concernent des commits de préférences dont le résultat d’écriture est contrôlé.
- Téléchargement depuis la release publique puis installation par l’installateur Android validés sur l’émulateur, depuis une copie de test du nouveau code déclarant une version inférieure. Le SHA-256 de l’APK effectivement installé correspond au fichier publié.
- Consultation du dépôt public depuis les APK debug et R8, signature et alignement de l’APK debug vérifiés. Voir les [preuves et limites](https://github.com/azksama/AstraGames/blob/main/docs/VALIDATION_1.11.0.md).

La preuve concerne l’émulateur et des données synthétiques. L’authentification est simulée dans le test de cycle de vie du rognage ; aucune biométrie physique ni exécution de jeu personnel n’est revendiquée.

## Fichiers

- **Astra-1.11.0-debug.apk** : installable, signé avec la clé de développement habituelle.
- Astra-1.11.0-release-unsigned.apk : version optimisée R8, à signer avant installation.
- Astra-1.11.0-sources.zip : sources du commit associé au tag.
- SHA256SUMS.txt : empreintes SHA-256 des trois fichiers.
