# Preuves des variables système Wolf, beta.18

Analyse statique REA 6.3.0 / Ghidra 12.1.4 du **Game.exe officiel Wolf 3.729**, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`. Cible REA `target-4ee2a905a5a7.exe`. Les adresses ci-dessous appartiennent exclusivement à cet artefact. Aucun EXE original n'a été exécuté ou modifié ; les pseudo-types et noms `FUN_*` viennent de Ghidra.

| Adresse / sujet | Evidence ID | Interprétation portée |
|---|---|---|
| `00959290`, constructeur | `ev_112a068eba2de83728586df974727ba665ead2d1112d229648c2d4aad8fd20c5` | Valeurs initiales qualifiées : positions, texte, répétitions, ombres, audio. |
| `00959ec0`, initialisation/reset | `ev_3e17fecbf9eaa84acb724660f7835cb072b79b0c1fc2961a25a6ed3e9f4420d6` | Marges/version, configuration réinitialisée ; ne pas conserver arbitrairement un ancien défaut absent d'un save. |
| `0093a810`, générateur aléatoire | `ev_8edef3c23fd5ed96d7da890d33d988f084b64ffffa04bf80c1bede02870bf572` | Park–Miller : facteur 16807, modulo 2147483647, rejet au-dessus de 2 milliards. |
| `009e26c0`, écriture système | `ev_71f08594cf20cc37e30ef56117658f6e9710f4db8601518aa15c7bdabd96f747` | Panoramique ±255 ; centre caméra ±99 999 ; origine du reset 0..2, branches de version. |
| `015055fc`, global Sys21 | `ev_04a4fcdc709708725562407d4bc89f60c42496ce0e4e68216ad89d5a04f9f02e` | Zéro statique dans le binaire ; preuve limitée à l'état statique. |
| `01505634`, global Sys22 | `ev_231d8d025f5b0089b771011f7a0971ba8840a1ffb66bf4354b28000001e26527` | Même limite. |
| `0150576c`, global Sys23 | `ev_d46616364204b4bdb69ac3d62756fb80eb7d66c68ec17eed45feac4483a79d52` | Même limite. |

Les extraits complets locaux sont conservés dans `build/wolf-research/beta18-system-00959290.txt`, `beta18-system-00959ec0.txt`, `beta18-system-0093a810.txt` et `beta18-system-009e26c0.txt`, hors sources distribuées. Le getter numérique étudié précédemment est `009e3f30`, conservé dans `build/wolf-rea/wolf3-native-followup/13-system-getter.c.txt`. `beta18-system-00a06cf0.txt` est un lecteur d'entrée, **pas** ce getter.

Les valeurs froides W3 retenues sont contrôlées par les tests de lecture de tout le catalogue admis. Les vecteurs RNG vérifient des résultats connus, y compris le rejet `127773 → 221848493`, ainsi que la restauration du seed. Les tests caméra/audio vérifient les effets et les écritures isolées d'une sauvegarde, pas seulement les valeurs stockées. Sys112 vaut 0 dans un lecteur normal ; le rapport Seris fourni par l'utilisateur échouait précisément sur sa lecture.

Limites : cette preuve statique n'est ni le code source original, ni une comparaison de tous les défauts Wolf 2.x, ni un essai complet de Seris. Le focus, l'horloge, les cadences et le pad sont des états réels de l'hôte Android, non une imitation d'API Windows. Le cas seed=0 utilise l'horloge de l'hôte plutôt que `GetTickCount`. La pile SysS55 est structurée par Astra et ne promet pas le texte japonais exact de Windows. Sys120 conserve l'origine des resets effectivement effectués ; cette livraison n'ajoute pas un parcours F12 Windows. Les fonctions non portées sont refusées dans le [catalogue complet](SYSTEM_VARIABLE_COVERAGE.md), avec Winlator disponible en secours.
