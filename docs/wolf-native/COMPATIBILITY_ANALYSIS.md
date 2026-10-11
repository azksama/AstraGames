# Analyse de compatibilité Wolf depuis Astra

Disponible en beta.20 : ouvrir la fiche d’un jeu Wolf RPG, puis maintenir **Moteur / Wolf RPG** dans le résumé. L’entrée reste discrète ; un toucher simple ne lance aucune opération. L’analyse se déclenche avec **Analyser le jeu**, depuis Astra, sans entrer dans le jeu.

## Parcours et rapport

La progression affiche l’étape, le fichier courant, les cartes et les commandes inspectées. Fermer le panneau ou tourner l’appareil ne redémarre pas le scan. **Annuler l’analyse** conserve le dernier rapport terminé. La fermeture du ViewModel annule aussi le travail. L’analyse s’effectue hors du thread principal sur les fichiers autorisés du dossier d’origine, sans import général, téléchargement de runtime ou écriture de sauvegarde.

Le bilan indique version d’Astra, date, profil Wolf, dimensions et cadence déclarées, cartes visitées/lisibles, événements communs, pages et commandes. **Fonctions** inventorie commandes, références numériques/chaînes, routes et macros avec occurrences, blocages et vérifications à l’exécution. **Blocages** affiche fichier, événement, page, opcode, offset et operands disponibles. Les deux listes sont filtrables.

**Enregistrer** utilise le sélecteur de documents Android ; **Partager** ouvre explicitement le partage Android. Le format texte est lisible et le JSON conserve les compteurs et emplacements structurés. Un rapport reste privé tant que l’utilisateur ne l’exporte pas. Les dialogues du jeu ne sont pas recopiés dans les détails de commandes.

## Périmètre et verdicts

Le parseur charge les métadonnées du projet et les bases nécessaires. Le scan réutilise les règles d’admission du moteur natif sur **toutes les cartes déclarées dans SysDB, toutes leurs pages même inactives et tous les événements communs**. Il ne crée ni `WolfRuntime` ni VM. Une carte illisible n’interrompt pas l’examen des suivantes. Le compteur des occurrences continue même quand la liste des détails est pleine.

Les verdicts distinguent :

- **Blocages détectés** : au moins une fonction connue est refusée par le moteur natif.
- **Vérifications à l’exécution** : aucun blocage connu, mais valeurs, chemins ou services dynamiques demandent une validation pendant le jeu.
- **Aucun blocage connu** : aucune incompatibilité des règles actuelles n’a été trouvée dans le périmètre inspecté.
- **Analyse incomplète** : métadonnées/cartes illisibles, interruption structurelle ou limite d’inventaire empêchent un bilan complet.

Jusqu’à 20 000 détails et 20 000 fonctions distinctes sont conservés. Atteindre ces limites ne stoppe pas les compteurs ni les cartes suivantes ; le rapport devient incomplet. Les cartes non déclarées, branches réellement exécutées, données chiffrées non prises en charge, médias calculés, décodage Android, performance et comportement complet du jeu ne sont pas certifiés par cette analyse. Un rapport ancien décrit sa version d’Astra et du projet ; il faut relancer après modification.

## Choix du moteur

**Fiche → Compatibilité → Moteur de lancement** propose Automatique, Natif Android expérimental et Winlator. Le changement est enregistré uniquement pour ce jeu et conserve définition, commandes, positions et presets. Automatique respecte les sessions Windows existantes et le secours Winlator. Natif forcé garde les refus et le diagnostic ; il ne masque pas les incompatibilités du rapport. Les sauvegardes natives et Windows restent séparées.

## Stockage et validation

Le dernier rapport est écrit atomiquement dans `files/wolf-compatibility/<SHA-256 de l’identifiant du jeu>.json`. L’annulation est contrôlée avant validation de l’écriture. Les copies temporaires de partage sont dans `cache/wolf-reports/`, exposées avec une autorisation de lecture Android pour le fichier choisi. Le schéma JSON est versionné ; un rapport illisible ne devient pas un verdict compatible.

Les tests incluent toutes les pages et communs sans VM, carte initiale illisible, poursuite au-delà des 512 anciens diagnostics et du quota de 20 000, annulation, métadonnées invalides, persistance, texte/JSON, FileProvider, export `content://`, appui long et conservation des options du jeu. Les preuves de cette version figurent dans [VALIDATION_1.12.0-beta.20.md](../VALIDATION_1.12.0-beta.20.md).
