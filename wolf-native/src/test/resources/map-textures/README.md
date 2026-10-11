# Oracles des fonds et brouillards

Les sources transcrivent les PE officiels épinglés dans `docs/wolf-native/MAP_TEXTURES_REA.md`. Aucun Game.exe n'est exécuté et aucun asset propriétaire n'est inclus. Les opérations unsigned et copies de bits préservent Int32 sans overflow signé C++ indéfini. Les quotients Int64 tronquent vers zéro.

Dans un terminal **MSVC x86**, depuis la racine du dépôt :

```bat
cl /nologo /std:c++17 /EHsc /O2 /Fe:build\map-textures-w2.exe /Fo:build\map-textures-w2.obj wolf-native\src\test\resources\map-textures\w2-reference.cpp
build\map-textures-w2.exe > build\map-textures-w2.tsv
cl /nologo /std:c++17 /EHsc /O2 /Fe:build\map-textures-w3.exe /Fo:build\map-textures-w3.obj wolf-native\src\test\resources\map-textures\w3-reference.cpp
build\map-textures-w3.exe > build\map-textures-w3.tsv
```

Référence : VS18 BuildTools, `VsDevCmd.bat -arch=x86 -host_arch=amd64`, `/std:c++17 /EHsc /O2`. Le TSV sort avec CRLF Windows. Aucun Kotlin ne génère les attentes.

| Fichier | Cas | SHA-256 |
|---|---:|---|
| `../wolf-map-textures-w2-native.tsv` |394|`03badb31cd5372e9e4856ed0807158cfe146ea88775f26a5ee9ab9810a4b12b1`|
| `../wolf-map-textures-native.tsv` |346|`636e3cc6bc5f525df9901ce953837796d3326beefa05f9d6be48118d2c74812d`|
| `w2-reference.cpp` |source|`520cd7c7cb71d4f1e5957a6eb4ef38dafef345b8db94f210a276eaf49f25716f`|
| `w3-reference.cpp` |source|`a3666a89dd68e991c295ab0366b55893fa53f7c920013078444f8c79a1b85d7d`|

`WolfMapTexturesTest` compare les60 colonnes de chaque vecteur et valide les états après avance/restauration. Il vérifie aussi que layout ne crée aucun tick et que chaque profil refuse les phases de l'autre convention. Allocation GPU, décodage et pixels Android se valident séparément.
