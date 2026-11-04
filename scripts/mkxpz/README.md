# Build du prototype MKXP-Z Astra

Les bibliothèques de `mkxpz-runtime/src/main/jniLibs/` ont été **compilées depuis les sources**, sans réutilisation des bibliothèques ou RTP de MTool. Le port choisi est [BookerRues9/mkxp-z-android-reworked](https://github.com/BookerRues9/mkxp-z-android-reworked), commit `b668e08cb58edf661d56fd4d396036ac31cd8332`, dérivé de thehatkid et mkxp-z. Le moteur se présente comme MKXP-Z 2.4, avec Ruby 3.1.0. `sources-lock.json` verrouille toutes les révisions Git et les sous-modules SDL.

## Environnement et compilation

Docker Desktop/Linux sert uniquement à compiler. Les tests Android restent exécutés avec Android Studio Emulator et les outils SDK Windows.

Prérequis : Docker Linux, Git, environ 10 Go libres, réseau pour les sources. Depuis la racine des sources, en PowerShell :

```powershell
New-Item -ItemType Directory -Force build/mkxpz-16k | Out-Null
docker build -t astra-mkxpz-build:ndk29-16k scripts/mkxpz
docker run -d --name astra-mkxpz-rebuild -v "${PWD}/build/mkxpz-16k:/out" -v "${PWD}/scripts/mkxpz:/scripts:ro" astra-mkxpz-build:ndk29-16k sleep infinity
docker exec astra-mkxpz-rebuild python3 /scripts/prepare-sources.py /work/x86_64
docker exec -e MKXP_SOURCE_ROOT=/work/x86_64 astra-mkxpz-rebuild bash /scripts/build-native.sh x86_64
docker exec astra-mkxpz-rebuild python3 /scripts/prepare-sources.py /work/arm64
docker exec -e MKXP_SOURCE_ROOT=/work/arm64 astra-mkxpz-rebuild bash /scripts/build-native.sh arm64-v8a
docker exec astra-mkxpz-rebuild python3 /scripts/record-sources.py /work/x86_64
Copy-Item build/mkxpz-16k/libs/x86_64/*.so mkxpz-runtime/src/main/jniLibs/x86_64/
Copy-Item build/mkxpz-16k/libs/arm64-v8a/*.so mkxpz-runtime/src/main/jniLibs/arm64-v8a/
Copy-Item build/mkxpz-16k/ruby-stdlib.zip mkxpz-runtime/src/main/assets/mkxpz/
```

Chaque ABI utilise un arbre indépendant : les configurations Autoconf sont spécifiques à l'architecture. Le build émet les huit `.so`, leurs SHA-256, et une archive de bibliothèque standard Ruby contenant les `rbconfig.rb` des deux architectures. Les notices natives générées sont dans `build/mkxpz-16k/licenses`; celles intégrées au module incluent également la licence SIL OFL de la police Liberation Sans 2.00.1 et sa table de métadonnées.

L'image Ubuntu 22.04 est verrouillée par digest. Le NDK Linux **r29 / 29.0.14206865** est vérifié contre le checksum du catalogue officiel Android (`87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b`). API native minimale : 26. Aucun SDK Android ni émulateur tiers n'est utilisé par Docker. Les commandes ci-dessus placent les sorties dans `build/mkxpz-16k/`.

Pour recréer les sources Java SDL, copier `app/src/main/java/org/libsdl/app/` de cette révision du port puis appliquer `python3 scripts/mkxpz/patch-java.py <dossier-java-SDL>`. Le comportement `onStart` du port délègue le démarrage du thread SDL à l'activité cliente; `MkxpzActivity` assure ce démarrage. Le patch accepte la surface réellement fournie sous Android 16 lorsque le système ignore l'orientation demandée.

## Adaptations natives

`patch-upstream.py` retire la demande de permission globale de stockage : Astra fournit `GAME_PATH`, dossier privé préparé pour le jeu. Il active les archives statiques PIC et l'optimisation `-O2`. Les macros assembleur AArch64 de Pixman 0.42.2 ne sont pas acceptées par l'assembleur intégré Clang; la compilation utilise donc le fallback C de Pixman, sans son fast path AArch64 NEON. Les autres mécanismes RGSS/Ruby, SDL, OpenAL, PhysFS et le rendu GLES proviennent du moteur réel.

Les liens ELF utilisent `-z max-page-size=16384` et `-z common-page-size=16384`, y compris Ruby construit avec Autoconf et OpenAL construit avec CMake. `ndk-build` active les pages flexibles; `libc++_shared.so` provient du NDK r29. Une reconstruction propre de chaque ABI est obligatoire : aucun objet 4 KiB de la première livraison n'est réutilisé. `export-clean-cache.py` peut exporter les arbres Git verrouillés du cache existant via `git archive`, sans leurs objets/configurations générés; le chemin principal `prepare-sources.py` récupère les mêmes révisions depuis leurs dépôts.

Le patch adapte aussi les callbacks FreeType/HarfBuzz et le polling des capteurs SDL au NDK 29. Dans Ruby, la taille de page utilisée par le gestionnaire de débordement x86 est détectée avant l'installation des signaux. La préfaute Linux `reserve_stack` est exclue sur Android après un SIGSEGV reproduit en 16 Kio dans le thread SDL attaché à la JVM ; la mesure et les contrôles des limites de pile restent actifs.

Pour archiver les sources correspondantes après un build depuis Git, utiliser des dossiers de sortie neufs :

```powershell
docker exec astra-mkxpz-rebuild python3 /scripts/export-clean-cache.py /work/x86_64 /work/clean-export
docker exec astra-mkxpz-rebuild tar -czf /out/source-cache.tar.gz -C /work/clean-export .
docker exec astra-mkxpz-rebuild bash /scripts/archive-sources.sh /work/corresponding-sources
```

L'archive conserve les arbres amont corrigés et les scripts dans `astra-build/`, sans les objets générés. Le cache inclut les sources extraites des tarballs vérifiés de libtheora et libiconv récupérés par `prepare-sources.py`.

Les adaptations au NDK récent remplacent `ALooper_pollAll` par `ALooper_pollOnce` dans la mise à jour non bloquante des capteurs SDL et corrigent les signatures `void*` des callbacks FreeType/HarfBuzz. Ruby mémorise la taille système de page avant l'installation des gestionnaires de signaux, au lieu du `4096` constant de la récupération de débordement x86.

Un premier essai réel 16 KiB a révélé un autre défaut : `reserve_stack` préfaute presque toute la pile avec `alloca`, alors que le thread RGSS est un pthread Bionic déjà attaché à ART. Son écriture touchait une protection de pile et provoquait un SIGSEGV avant le premier script. La préfaute Linux est désormais exclue **uniquement sous Android**. `get_stack`, les limites de pile Ruby et les contrôles de débordement restent actifs.

## Licences et portée

- MKXP/MKXP-Z : GPL-2.0-or-later (en-têtes), texte GPLv2 fourni. Les sources du port, les patches et les dépendances doivent accompagner toute redistribution selon leurs licences; ce prototype n'est pas une autorisation de publier un binaire sans sources.
- SDL2, SDL_image, SDL_ttf, SDL_sound : zlib; codecs et FreeType/HarfBuzz gardent leurs notices propres.
- Ruby 3.1 : Ruby/BSD; OpenSSL 1.1.1t : licences OpenSSL/SSLeay.
- OpenAL Soft : LGPL; iconv/uchardet : LGPL; PhysFS : zlib; Pixman : MIT; Ogg/Vorbis/Theora : BSD. Les textes exacts sont dans les assets `licenses/mkxpz/`.
- Police incorporée : Liberation Sans **2.00.1**, SIL OFL 1.1. Aucune ressource RPG Maker RTP commerciale n'est incluse.

Les binaires sont x86_64 et ARM64 natifs, construits pour des pages de 4 ou 16 KiB. **L'alignement ELF ne constitue pas à lui seul une preuve d'exécution 16 KiB** : consulter le [bilan public de validation](../../docs/VALIDATION_1.12.0-beta.24.md). ARM64 compilé doit être distingué des essais x86_64. Ruby 3.1 n'est pas Ruby 1.8/1.9; la réussite des fixtures RGSS 1/2/3 n'établit donc pas la compatibilité avec tous les jeux ou extensions Windows. Les anciennes dépendances de ce port sont documentées; ce prototype n'est pas une version de production durcie.

Les [notes de version](../../docs/RELEASE_1.12.0-beta.24.md) décrivent la préversion distribuée. Le [bilan public](../../docs/VALIDATION_1.12.0-beta.24.md) précise les tests associés à l'APK final et les preuves natives antérieures réutilisées. Les pages applicatives de 16 Kio exposées par l'image SDK ne constituent pas une qualification sur un appareil ARM64 à noyau 16 Kio. Une archive source déterministe n'établit pas, à elle seule, une reconstruction de l'APK identique octet pour octet.
