# AstraGames — analyse du 18 novembre 2026

Après les analyses du catalogue, des métadonnées et de l’organisation de la bibliothèque, cette passe porte sur le suivi des versions de jeux, l’historique et le profil de performances.

| Parcours | Défaut observé | Vérification du correctif |
| --- | --- | --- |
| Détecter une mise à jour de jeu | Astra proposait la 2.9 à un jeu en 2.10 et la 1.4.0 à un jeu en v1.4. | La liste, le tri, la fiche et la tâche de fond partagent la comparaison. Les versions numériques et les préversions suivent leur ordre. Pour des éditions nommées comme « Chapter A » et « Chapter B », un changement de libellé reste visible. |
| Confirmer une version installée | Accepter la 2.0 pouvait effacer l’annonce de la 3.0 arrivée entre-temps. Rouvrir Astra remplaçait aussi une vérification déjà en attente. | La confirmation conserve la version affichée, même après recréation de l’écran. Une modification concurrente de la version installée est protégée. WorkManager conserve la tâche en attente. Une notification refusée par Android reste éligible au prochain contrôle. |
| Effacer l’historique | La demande de confirmation disparaissait après recréation de l’écran. La suppression individuelle acceptait aussi l’identifiant d’une session en cours. | L’historique supprime uniquement des sessions terminées. Un échec d’écriture conserve les lignes et permet de réessayer. Pour les anciennes sessions sans durée enregistrée, l’écran et les statistiques utilisent les mêmes bornes, de zéro à douze heures. |
| Générer le profil de performances | Un balayage horizontal suffisait pour que le générateur termine, même si la bibliothèque restait fermée. | Sur une installation vierge, le générateur a franchi la configuration initiale et vérifié l’écran Jeux à sept reprises. Les fichiers produits contiennent des règles de démarrage pour `MainActivity` et des règles pour `LibraryScreen`. |

## Vérifications Android

- Sur l’émulateur Android Studio, API 36 et x86_64, la sélection de régression passe avec **256 tests en portrait**. Parmi eux figurent 16 nouveaux tests du suivi des versions, de l’historique, de la planification et des notifications.
- Les 256 incluent aussi 31 contrôles ciblés sur la traduction et la restauration de fichiers, les profils de sauvegarde MKXP-Z, les commandes Wolf, la première ouverture, le verrouillage, le choix du profil Godot, la batterie et les mises à jour d’Astra.
- **23 tests passent en paysage**, sur un écran de 640 × 360 dp avec une échelle de police de 1,3. Le test de notification a également été exécuté avec l’autorisation Android refusée, puis accordée. Seul le second essai a publié une notification système.
- Sur un second émulateur initialement vierge, les deux tests de génération passent. Le profil de démarrage contient 22 586 règles et celui du parcours bibliothèque 24 308. Ces chiffres décrivent la couverture de la collecte. Le temps gagné sur un démarrage reste à mesurer.
- Quatre règles observées sur l’accueil et la bibliothèque ont été intégrées au profil livré. Les signatures traitées par R8 correspondent aux quatre signatures collectées sur l’émulateur. Une copie de l’APK release signée avec la clé de test a démarré et ouvert la bibliothèque. Android a installé le profil embarqué puis confirmé la compilation `speed-profile`.
- Les compilations debug et release réussissent. Android Lint relève zéro erreur dans chaque variante, avec 139 avertissements en debug et 136 en release.

Les essais de mise à jour emploient des réponses contrôlées pour F95Zone et Ryuugames. Ils couvrent le repli d’un fournisseur vers l’autre, les erreurs, l’annulation et la conservation des données après un changement de version. La connexion à un compte personnel, le téléchargement d’un modèle de traduction et une partie sur appareil ARM64 physique restent hors de cette validation.
