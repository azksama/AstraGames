# Variables système Wolf : couverture vérifiée

Catalogue contrôlé le 11 octobre 2026 pour Astra 1.12.0-beta.19. Les 182 identifiants de l’aide officielle normale/Pro et Sys119 (échelle logique portée précédemment) ont une admission explicite : 159 lectures natives et 24 fonctions refusées. Cette admission ne certifie pas la compatibilité complète d’un jeu ni toutes les variantes d’une commande.

Le contrat exécutable est `WolfSystemSupport.kt`, partagé par `Variables.kt`, `Runtime.kt` et `Capabilities.kt`. Reproduire le rapprochement sans réseau : `python tools/wolf_system_audit.py --check`. Les tests parcourent toutes les lectures admises au démarrage et toutes les lectures isolées admises d’une sauvegarde, ainsi que les effets modifiés.

Sources : [aide officielle normale](https://silversecond.com/WolfRPGEditor/Help/06systemvalue.html), [aide Pro](https://silversecond.com/WolfRPGEditor/Help/06systemvalue_pro.html). Les numéros 51/58 sont des séparateurs d’interface, exclus du catalogue. Les plages et les titres `SysS 1` avec espace sont développés ; les renvois Pro sont dédupliqués.

## Valeurs, effets et limites

- Le constructeur W3 `00959290` et l’initialiseur `00959ec0` du Game.exe officiel 3.729, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`, établissent notamment les positions −1, la police 12/22/28 selon résolution, les messages à 120 caractères/s avec 1 tick initial, les images texte à 1 000 000, la répétition 16/5, les ombres et les gains. Les globals statiques nuls de Sys21/22/23 ne prouvent pas tout état après initialisation. Le profil froid Astra utilise ces valeurs qualifiées ; ce n’est pas une comparaison exhaustive du constructeur W2.
- REA `009e26c0` établit les clamps de panoramique ±255, centre caméra ±99 999 et reset 0..2. Le centre affecte aussi le seuil du suivi de caméra. `0093a810` établit Park–Miller et le rejet au-dessus de 2 milliards. Les preuves sont de décompilation du binaire épinglé, sans prétendre disposer du code source original. Références locales privées : `build/wolf-research/beta18-system-*.txt` ; IDs de preuves consignés dans `SYSTEM_VARIABLE_REA.md`.
- Sys114 décrit Game.dat ; Sys115 annonce la cible de compatibilité du moteur Astra 3.72. Sys112=0 désigne le lecteur, indépendamment du mode debug ; Sys143=0 car l’ensemble Pro n’est pas complètement porté. Sys85=0 désigne le rendu Android accéléré, pas un pilote DirectX Windows.
- Sys31 mesure les ticks et Sys32 les images réellement redessinées : ils ne renvoient pas artificiellement la cadence cible. Sys77..82 lisent l’horloge locale. Sys111 suit le focus/lifecycle Android ; SysS70 vient du premier pad physique connecté, y compris avant une première touche et après déconnexion. La classification Windows SysS71 reste UNKNOWN pour un pad Android non classifié, vide sans pad. Les sticks/POV Windows Sys134/135/146 ne sont pas simulés.
- Sys43..47 proviennent du worker HTTP réel, avec callbacks périmés ignorés ; SysS22 (buffer texte progressif) et le proxy restent refusés. SysS68 suit les caractères Unicode de l’éditeur Android, accepte une écriture du jeu sans casser sa limite, puis renvoie <<NoData>> hors saisie. SysS54 expose le dernier avis du moteur, SysS55 une pile structurée Astra, sans garantir le format japonais exact de Windows.
- Les champs transitoires du host ne sont pas inventés lors d’une lecture isolée de sauvegarde. Les sous-ensembles de la table sont vérifiés au préflight et à l’exécution, avec les champs DB/ordinaires déjà portés. La restauration ajoute les defaults manquants des anciennes sauvegardes ; une sauvegarde capture configuration et monde, pas un worker ni un éditeur ouvert. RNG, caméra, audio et titre restaurent leurs effets. Les sauvegardes natives restent distinctes des saves Windows.
- Les restrictions existantes de profil restent effectives (polices/facteurs 3.x, Sys140 à partir de 3.55, Sys148 à partir de 3.30, Sys150 à partir de 3.50, autres variantes par commande). Une valeur ou variante dynamique n’est pas prouvée par la seule présence d’un identifiant. Les références indéterminées sont revérifiées à leur résolution ; les identifiants réservés/inconnus restent refusés.

## Préflight complet du jeu

Tous les communs, conditions et cartes déclarées sont inspectés avant lancement, en modes Automatique et Natif. Les références statiques numériques, SysS et échappements texte utilisent le lexer de la VM ; les doubles backslash littéraux sont ignorés. Le diagnostic conserve la liste complète bornée à 512 incompatibilités distinctes et indique si le scan est incomplet. L’aperçu de 12 lignes affiche le nombre restant. Automatique relance Winlator ; Natif arrête la préparation avec ce rapport, sans essayer une fonction déjà refusée. Les indices calculés et ressources générées ne peuvent pas être intégralement déterminés statiquement.

## Catalogue

L/E : lecture/écriture de la session. SL/SE : lecture/écriture isolée dans une sauvegarde native. Une case « — » est un refus, pas une valeur 0 implicite. Les unités et clamps sont contrôlés par la VM ; les propriétés de fenêtre Windows, filtres non portés, presse-papiers, autre BasicData et perfmonitor Wolf demandent encore un contrat Android.

| Identifiant | Fonction Astra | L | E | SL | SE |
|---|---|:---:|:---:|:---:|:---:|
| Sys0 | portrait du message | oui | oui | oui | oui |
| Sys1 | position du message | oui | oui | oui | oui |
| Sys2 | position du message | oui | oui | oui | oui |
| Sys3 | position des choix | oui | oui | oui | oui |
| Sys4 | position des choix | oui | oui | oui | oui |
| Sys5 | curseur de pause | oui | oui | oui | oui |
| Sys6 | curseur de pause | oui | oui | oui | oui |
| Sys7 | choix initial | oui | oui | oui | oui |
| Sys8 | taille du texte | oui | oui | oui | oui |
| Sys9 | progression du message | oui | oui | oui | oui |
| Sys10 | progression du message | oui | oui | oui | oui |
| Sys11 | progression des images texte | oui | oui | oui | oui |
| Sys12 | message et événement en cours | oui | — | — | — |
| Sys13 | message et événement en cours | oui | — | — | — |
| Sys14 | fenêtre des choix | oui | oui | oui | oui |
| Sys15 | répétition des directions | oui | oui | oui | oui |
| Sys16 | répétition des directions | oui | oui | oui | oui |
| Sys17 | sons des choix | oui | oui | oui | oui |
| Sys18 | sons des choix | oui | oui | oui | oui |
| Sys19 | sons des choix | oui | oui | oui | oui |
| Sys20 | choix courant | oui | — | — | — |
| Sys21 | ombre du texte | oui | oui | oui | oui |
| Sys22 | mélange du curseur | oui | oui | oui | oui |
| Sys23 | taille ruby | oui | oui | oui | oui |
| Sys24 | chargement et emplacement | oui | — | oui | — |
| Sys25 | chargement et emplacement | oui | oui | oui | oui |
| Sys26 | marges des choix | oui | oui | oui | oui |
| Sys27 | marges des choix | oui | oui | oui | oui |
| Sys28 | temps de jeu | oui | oui | oui | oui |
| Sys29 | temps de jeu | oui | oui | oui | oui |
| Sys30 | temps de jeu | oui | — | oui | — |
| Sys31 | cadences mesurées | oui | — | — | — |
| Sys32 | cadences mesurées | oui | — | — | — |
| Sys33 | saut de rendu | — | — | — | — |
| Sys34 | position verticale des sprites | — | — | — | — |
| Sys35 | héros en mouvement | oui | — | oui | — |
| Sys36 | ombre du héros | oui | oui | oui | oui |
| Sys37 | distance entre compagnons | oui | oui | oui | oui |
| Sys38 | permissions et résultat de saisie | oui | oui | oui | oui |
| Sys39 | permissions et résultat de saisie | oui | — | — | — |
| Sys40 | géométrie et rendu de saisie | — | — | — | — |
| Sys41 | géométrie et rendu de saisie | — | — | — | — |
| Sys42 | géométrie et rendu de saisie | — | — | — | — |
| Sys43 | réception réseau | oui | — | — | — |
| Sys44 | réception réseau | oui | — | — | — |
| Sys45 | réception réseau | oui | — | — | — |
| Sys46 | réception réseau | oui | — | — | — |
| Sys47 | réception réseau | oui | — | — | — |
| Sys48 | réception réseau | oui | — | oui | — |
| Sys52 | liaisons des commandes | oui | oui | oui | oui |
| Sys53 | liaisons des commandes | oui | oui | oui | oui |
| Sys54 | liaisons des commandes | oui | oui | oui | oui |
| Sys55 | liaisons des commandes | oui | oui | oui | oui |
| Sys56 | liaisons des commandes | oui | oui | oui | oui |
| Sys57 | liaisons des commandes | oui | oui | oui | oui |
| Sys59 | répétition valider et retour | oui | oui | oui | oui |
| Sys60 | répétition valider et retour | oui | oui | oui | oui |
| Sys61 | brouillard | oui | oui | oui | oui |
| Sys62 | brouillard | oui | oui | oui | oui |
| Sys63 | brouillard | oui | oui | oui | oui |
| Sys64 | brouillard | oui | oui | oui | oui |
| Sys65 | brouillard | oui | oui | oui | oui |
| Sys66 | arrière-plan | oui | oui | oui | oui |
| Sys67 | arrière-plan | oui | oui | oui | oui |
| Sys68 | arrière-plan | oui | oui | oui | oui |
| Sys71 | position du pointeur | oui | oui | — | — |
| Sys72 | position du pointeur | oui | oui | — | — |
| Sys73 | tactile des dialogues | oui | oui | oui | oui |
| Sys74 | curseur visible | — | — | — | — |
| Sys77 | horloge locale | oui | — | — | — |
| Sys78 | horloge locale | oui | — | — | — |
| Sys79 | horloge locale | oui | — | — | — |
| Sys80 | horloge locale | oui | — | — | — |
| Sys81 | horloge locale | oui | — | — | — |
| Sys82 | horloge locale | oui | — | — | — |
| Sys84 | format et backend | oui | — | — | — |
| Sys85 | format et backend | oui | — | — | — |
| Sys87 | ombres des compagnons | oui | oui | oui | oui |
| Sys88 | ombres des compagnons | oui | oui | oui | oui |
| Sys89 | ombres des compagnons | oui | oui | oui | oui |
| Sys90 | ombres des compagnons | oui | oui | oui | oui |
| Sys91 | ombres des compagnons | oui | oui | oui | oui |
| Sys93 | panoramique audio | oui | oui | oui | oui |
| Sys94 | panoramique audio | oui | oui | oui | oui |
| Sys95 | paramètres et canal audio | oui | oui | oui | oui |
| Sys96 | paramètres et canal audio | oui | oui | oui | oui |
| Sys97 | paramètres et canal audio | oui | oui | oui | oui |
| Sys98 | paramètres et canal audio | oui | oui | oui | oui |
| Sys99 | paramètres et canal audio | oui | oui | oui | oui |
| Sys100 | gains audio | oui | oui | oui | oui |
| Sys101 | gains audio | oui | oui | oui | oui |
| Sys102 | gains audio | oui | oui | oui | oui |
| Sys104 | caméra | oui | oui | oui | oui |
| Sys105 | caméra | oui | oui | oui | oui |
| Sys108 | instructions du tick | oui | — | — | — |
| Sys110 | état aléatoire | oui | oui | oui | oui |
| Sys111 | focus Android | oui | — | — | — |
| Sys112 | mode joueur ou test éditeur | oui | — | oui | — |
| Sys114 | versions de compatibilité | oui | — | oui | — |
| Sys115 | versions de compatibilité | oui | — | oui | — |
| Sys116 | dimensions logiques | oui | — | oui | — |
| Sys117 | dimensions logiques | oui | — | oui | — |
| Sys118 | dimensions logiques | oui | — | oui | — |
| Sys119 | échelle logique Astra déjà portée | oui | — | oui | — |
| Sys120 | origine du reset | oui | oui | oui | oui |
| Sys122 | filtres de carte et image | — | — | — | — |
| Sys123 | filtres de carte et image | — | — | — | — |
| Sys125 | épaisseur de texte | oui | oui | oui | oui |
| Sys127 | tuiles exclues du save | — | — | — | — |
| Sys129 | vitesse diagonale | oui | oui | oui | oui |
| Sys130 | vitesse diagonale | oui | oui | oui | oui |
| Sys131 | centre caméra | oui | oui | oui | oui |
| Sys132 | centre caméra | oui | oui | oui | oui |
| Sys134 | liaisons du pad | — | — | — | — |
| Sys135 | liaisons du pad | — | — | — | — |
| Sys136 | fenêtre Windows | — | — | — | — |
| Sys137 | fenêtre Windows | — | — | — | — |
| Sys138 | fenêtre Windows | — | — | — | — |
| Sys139 | extension de carte Pro | — | — | — | — |
| Sys140 | filtre de présentation | oui | oui | oui | oui |
| Sys141 | langue interne Pro | — | — | — | — |
| Sys143 | ensemble Pro complet | oui | — | oui | — |
| Sys146 | profil DirectInput | — | — | — | — |
| Sys148 | échelle au prochain lancement | oui | oui | — | — |
| Sys150 | conversion du texte | oui | oui | oui | oui |
| Sys155 | réglages des données du jeu | oui | — | oui | — |
| Sys156 | réglages des données du jeu | oui | — | oui | — |
| Sys157 | réglages des données du jeu | oui | — | oui | — |
| Sys158 | réglages des données du jeu | oui | — | oui | — |
| Sys159 | réglages des données du jeu | oui | — | oui | — |
| Sys160 | réglages des données du jeu | oui | — | oui | — |
| Sys163 | conversion tactile Windows | — | — | — | — |
| SysS1 | noms des commandes | oui | — | oui | — |
| SysS2 | noms des commandes | oui | — | oui | — |
| SysS3 | noms des commandes | oui | — | oui | — |
| SysS4 | noms des commandes | oui | — | oui | — |
| SysS5 | noms des commandes | oui | — | oui | — |
| SysS6 | noms des commandes | oui | — | oui | — |
| SysS10 | sprites de la partie | oui | oui | oui | oui |
| SysS11 | sprites de la partie | oui | oui | oui | oui |
| SysS12 | sprites de la partie | oui | oui | oui | oui |
| SysS13 | sprites de la partie | oui | oui | oui | oui |
| SysS14 | sprites de la partie | oui | oui | oui | oui |
| SysS15 | sprites de la partie | oui | oui | oui | oui |
| SysS19 | identification Astra | oui | — | oui | — |
| SysS20 | proxy | — | — | — | — |
| SysS21 | proxy | — | — | — | — |
| SysS22 | réception texte continue | — | — | — | — |
| SysS25 | message et encadrement | oui | — | oui | — |
| SysS26 | message et encadrement | oui | oui | oui | oui |
| SysS27 | message et encadrement | oui | oui | oui | oui |
| SysS30 | arrière-plan et brouillard | oui | oui | oui | oui |
| SysS31 | arrière-plan et brouillard | oui | oui | oui | oui |
| SysS33 | pistes audio | oui | — | oui | — |
| SysS34 | pistes audio | oui | — | oui | — |
| SysS40 | historique des choix affichés | oui | — | oui | — |
| SysS41 | historique des choix affichés | oui | — | oui | — |
| SysS42 | historique des choix affichés | oui | — | oui | — |
| SysS43 | historique des choix affichés | oui | — | oui | — |
| SysS44 | historique des choix affichés | oui | — | oui | — |
| SysS45 | historique des choix affichés | oui | — | oui | — |
| SysS46 | historique des choix affichés | oui | — | oui | — |
| SysS47 | historique des choix affichés | oui | — | oui | — |
| SysS48 | historique des choix affichés | oui | — | oui | — |
| SysS49 | historique des choix affichés | oui | — | oui | — |
| SysS52 | racine Data | oui | — | oui | — |
| SysS54 | dernier diagnostic du moteur | oui | — | — | — |
| SysS55 | pile des événements | oui | — | — | — |
| SysS57 | presse-papiers | — | — | — | — |
| SysS60 | titre de la tâche Android | oui | oui | oui | oui |
| SysS62 | polices du jeu | oui | oui | oui | oui |
| SysS63 | polices du jeu | oui | oui | oui | oui |
| SysS64 | polices du jeu | oui | oui | oui | oui |
| SysS65 | polices du jeu | oui | oui | oui | oui |
| SysS67 | polices du jeu | oui | oui | oui | oui |
| SysS68 | saisie en cours | oui | oui | — | — |
| SysS70 | pad connecté | oui | — | — | — |
| SysS71 | pad connecté | oui | — | — | — |
| SysS73 | racine BasicData | oui | — | oui | — |
| SysS74 | autre BasicData au prochain lancement | — | — | — | — |
| SysS75 | arguments Windows | oui | — | oui | — |
| SysS76 | langue Android | oui | — | — | — |
| SysS82 | moniteur de performance Wolf | — | — | — | — |
