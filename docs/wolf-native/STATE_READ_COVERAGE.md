# Commande124 : catalogue des lectures d’état

Astra 1.12.0-beta.19. Les tables du PE officiel Wolf3.729 contiennent 149 emplacements numérotés : **113 lectures admises et 36 emplacements refusés**, dont des slots réservés. Ce catalogue est distinct des 183 variables Sys/SysS ; il ne mesure pas la compatibilité globale d’un jeu.

Source exécutable : `WolfStateSupport.kt`, utilisée par le validateur de commandes partagé avec le préflight. Vérifier sans réseau : `python tools/wolf_state_audit.py --check`. Les tests JVM parcourent les 256 valeurs possibles de chaque catégorie, y compris les champs non catalogués. Les flags, profils de Game.dat, types de ressources et indices dynamiques restent contrôlés séparément.

Le [contrat REA](STATE_READ_REA.md) donne les adresses, tables, preuves et unités de chaque champ. L’admission requiert la VM **et l’hôte Android** : mémoire PSS/disponible mesurée, temps de rendu, positions/durées des décodeurs audio, événements sous le pointeur, boutons/molette et propriétés des images. Un temps audio absent renvoie 0 comme dans le lecteur original ; une mémoire hôte absente produit une erreur explicite. Les champs MIDI utilisent les ticks SMF, les autres pistes les millisecondes.

Les champs personnage20..22 (pattern picture et résidus pixel) et picture13..20 (quadrilatères) restent refusés. Les champs position réservés, certaines recherches/coordonnées de personnages et pixels précis non raccordés restent refusés. La recherche d’événement sous le pointeur nécessite un profil≥3.40 ; les conversions écran/carte et recherches de pictures nécessitent≥3.50. La table de langue doit être connue. Les mesures CPU/rendu représentent l’implémentation Android, pas une exécution DxLib Windows. Le pad Android utilise le profil de boutons compatible DirectInput d’Astra, sans inventer un périphérique Windows.

La sauvegarde native v23 conserve les durées totale/restante des images et reprend les tweens. Les anciennes sauvegardes à tween migrent leurs horloges avant une nouvelle écriture. Les temps transitoires, boutons physiques et décodeurs ne sont pas restaurés à partir de valeurs inventées. Les sauvegardes natives et Windows restent distinctes.

| Catégorie | Emplacements | Lectures | Refus |
|---|---:|---:|---:|
| Personnage | 31 | 28 | 3 |
| Position | 44 | 19 | 25 |
| Général | 36 | 36 | 0 |
| Image | 38 | 30 | 8 |

## Tous les emplacements

| Catégorie | Champ | Lecture native |
|---|---:|:---:|
| Personnage | 0 | oui |
| Personnage | 1 | oui |
| Personnage | 2 | oui |
| Personnage | 3 | oui |
| Personnage | 4 | oui |
| Personnage | 5 | oui |
| Personnage | 6 | oui |
| Personnage | 7 | oui |
| Personnage | 8 | oui |
| Personnage | 9 | oui |
| Personnage | 10 | oui |
| Personnage | 11 | oui |
| Personnage | 12 | oui |
| Personnage | 13 | oui |
| Personnage | 14 | oui |
| Personnage | 15 | oui |
| Personnage | 16 | oui |
| Personnage | 17 | oui |
| Personnage | 18 | oui |
| Personnage | 19 | oui |
| Personnage | 20 | refus |
| Personnage | 21 | refus |
| Personnage | 22 | refus |
| Personnage | 23 | oui |
| Personnage | 24 | oui |
| Personnage | 25 | oui |
| Personnage | 26 | oui |
| Personnage | 27 | oui |
| Personnage | 28 | oui |
| Personnage | 29 | oui |
| Personnage | 30 | oui |
| Position | 0 | oui |
| Position | 1 | oui |
| Position | 2 | oui |
| Position | 3 | oui |
| Position | 4 | oui |
| Position | 5 | oui |
| Position | 6 | oui |
| Position | 7 | oui |
| Position | 8 | oui |
| Position | 9 | oui |
| Position | 10 | oui |
| Position | 11 | refus |
| Position | 12 | refus |
| Position | 13 | refus |
| Position | 14 | refus |
| Position | 15 | refus |
| Position | 16 | refus |
| Position | 17 | refus |
| Position | 18 | refus |
| Position | 19 | refus |
| Position | 20 | refus |
| Position | 21 | refus |
| Position | 22 | oui |
| Position | 23 | oui |
| Position | 24 | refus |
| Position | 25 | refus |
| Position | 26 | refus |
| Position | 27 | refus |
| Position | 28 | refus |
| Position | 29 | refus |
| Position | 30 | refus |
| Position | 150 | refus |
| Position | 151 | refus |
| Position | 152 | oui |
| Position | 153 | oui |
| Position | 154 | oui |
| Position | 155 | oui |
| Position | 156 | refus |
| Position | 157 | refus |
| Position | 158 | refus |
| Position | 159 | refus |
| Position | 160 | refus |
| Position | 161 | oui |
| Position | 162 | oui |
| Général | 0 | oui |
| Général | 1 | oui |
| Général | 2 | oui |
| Général | 3 | oui |
| Général | 4 | oui |
| Général | 5 | oui |
| Général | 6 | oui |
| Général | 7 | oui |
| Général | 8 | oui |
| Général | 9 | oui |
| Général | 10 | oui |
| Général | 11 | oui |
| Général | 12 | oui |
| Général | 13 | oui |
| Général | 14 | oui |
| Général | 15 | oui |
| Général | 16 | oui |
| Général | 17 | oui |
| Général | 18 | oui |
| Général | 19 | oui |
| Général | 20 | oui |
| Général | 21 | oui |
| Général | 22 | oui |
| Général | 23 | oui |
| Général | 24 | oui |
| Général | 25 | oui |
| Général | 26 | oui |
| Général | 27 | oui |
| Général | 28 | oui |
| Général | 29 | oui |
| Général | 30 | oui |
| Général | 31 | oui |
| Général | 32 | oui |
| Général | 33 | oui |
| Général | 34 | oui |
| Général | 35 | oui |
| Image | 0 | oui |
| Image | 1 | oui |
| Image | 2 | oui |
| Image | 3 | oui |
| Image | 4 | oui |
| Image | 5 | oui |
| Image | 6 | oui |
| Image | 7 | oui |
| Image | 8 | oui |
| Image | 9 | oui |
| Image | 10 | oui |
| Image | 11 | oui |
| Image | 12 | oui |
| Image | 13 | refus |
| Image | 14 | refus |
| Image | 15 | refus |
| Image | 16 | refus |
| Image | 17 | refus |
| Image | 18 | refus |
| Image | 19 | refus |
| Image | 20 | refus |
| Image | 21 | oui |
| Image | 22 | oui |
| Image | 23 | oui |
| Image | 24 | oui |
| Image | 25 | oui |
| Image | 26 | oui |
| Image | 27 | oui |
| Image | 28 | oui |
| Image | 29 | oui |
| Image | 30 | oui |
| Image | 31 | oui |
| Image | 32 | oui |
| Image | 33 | oui |
| Image | 34 | oui |
| Image | 35 | oui |
| Image | 36 | oui |
| Image | 37 | oui |
