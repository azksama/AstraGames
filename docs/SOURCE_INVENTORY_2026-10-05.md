# Inventaire de sources — 5 octobre 2026

État local des sources, base Git `2fe2ae0`, branche `codex/save-translation-audit`. Les empreintes SHA-256 portent sur les octets exacts du workspace, y compris les fins de ligne. Cet inventaire n’est ni une mesure de couverture des tests ni la preuve d’une exécution sur appareil.

**122 fichiers sous `app/src/main/`, dont 111 fichiers Kotlin (17341 lignes) et 11 manifestes, ressources ou profils embarqués.** Tous les fichiers de ce répertoire, y compris les nouvelles sources non encore commitées, figurent ci-dessous.

Les lignes sont comptées avec `str.splitlines()` après décodage UTF-8 ; les fichiers binaires portent « — ». Les caches, dépendances téléchargées, artefacts de build, tests, source set debug et générateur Baseline Profile sont exclus du total de production.

| Volet Kotlin | Fichiers | Lignes |
|---|---:|---:|
| Core/catalogue/stockage/runtime | 47 | 5750 |
| Sauvegardes | 16 | 2217 |
| Traduction | 9 | 969 |
| UI/état/thème | 39 | 8405 |

## Modules Kotlin les plus volumineux

| Fichier | Lignes |
|---|---:|
| `ui/AstraViewModel.kt` | 1157 |
| `ui/Localization.kt` | 820 |
| `data/repository/GameRepository.kt` | 812 |
| `ui/MetadataScreens.kt` | 661 |
| `data/local/AstraDao.kt` | 550 |
| `ui/GameDetailScreens.kt` | 510 |
| `data/saves/Pickle.kt` | 451 |
| `ui/LibraryScreens.kt` | 436 |
| `data/saves/Marshal.kt` | 410 |
| `ui/GameSetupScreens.kt` | 386 |
| `ui/AstraApp.kt` | 378 |
| `data/scanner/RecursiveSourceScanner.kt` | 368 |

La localisation est une table multilingue ; sa taille ne mesure pas la complexité algorithmique. `AstraViewModel`, `GameRepository`, le DAO et les écrans métadonnées restent des candidats à une extraction progressive. Aucune réécriture intégrale de ces modules ni absence totale de code mort n’est revendiquée.

## Code Kotlin de production

| Fichier | Lignes | Octets | SHA-256 |
|---|---:|---:|---|
| `app/src/main/java/fr/astragames/app/AstraApplication.kt` | 59 | 2372 | `d2ce50d9a88f23ac64cc0c0ece0102a39e8ca186140c4200a90258dd9ab76de2` |
| `app/src/main/java/fr/astragames/app/core/collections/SmartCollectionEvaluator.kt` | 85 | 3913 | `534b9adc115a19202d4b97c967d63af6ae9c9384a542bf180c2443a6b992ab17` |
| `app/src/main/java/fr/astragames/app/core/filesystem/BoundedCopy.kt` | 26 | 898 | `5a8b94eacbfc9f146dd04c8b590d650b626fcadefa88df9f23dc04559a9ff537` |
| `app/src/main/java/fr/astragames/app/core/filesystem/FileAccessResolver.kt` | 39 | 1914 | `881f3ecf7e318e6715ab36e4977970f8d46b8596ff5af6ed0ed4253e4767eea4` |
| `app/src/main/java/fr/astragames/app/core/metadata/CoverProvider.kt` | 221 | 10148 | `c44c94b53e151526446b68818e8ca2fed4c583899144e0a465515202efd198c7` |
| Fournisseur communautaire (`core/metadata/`) | 285 | 12896 | `d2bd801d3ba29b185d5c612653e33dabb725bf7c318b7e9d682b3d28eacf2c59` |
| `app/src/main/java/fr/astragames/app/core/metadata/VndbProvider.kt` | 148 | 7134 | `6fe28983963cb32f60fe7c0994a22213bc2c344416699bacd7d8fca5eea4170f` |
| `app/src/main/java/fr/astragames/app/core/model/Models.kt` | 75 | 2094 | `3e9d2c4166664a093e56fd0289cf13fea0b26a283d84e522254af8f51e28356a` |
| `app/src/main/java/fr/astragames/app/core/RunCatchingCancellable.kt` | 11 | 307 | `5b87b2b3f6c9c9bbdfb5f78c5a825dbbbcd5ce3820d0c6159858422fa8fa9968` |
| `app/src/main/java/fr/astragames/app/core/search/DuplicateDetector.kt` | 50 | 2606 | `2a59ab920bc0332f93eb3d5ad1711e895152fdf5372cb6b9da43def409e44367` |
| `app/src/main/java/fr/astragames/app/core/search/EditDistance.kt` | 25 | 963 | `fc4b28424f870b00c55dbf1047401861fcbbfc24cdc631091292cd3771ba8552` |
| `app/src/main/java/fr/astragames/app/core/search/SearchParser.kt` | 17 | 528 | `b73f4d225b1fafc1634e43e4438765464baa3ee8cb0575a74116efb57380ee3f` |
| `app/src/main/java/fr/astragames/app/core/search/TagMatcher.kt` | 11 | 434 | `a2da7237b4929c5eeb758517edb8d54e8be7735744502b6b41dfe8a2a64741cb` |
| `app/src/main/java/fr/astragames/app/core/search/TextTagParser.kt` | 13 | 575 | `8084cb23fd4686c3d7a55ba1637e10c34c351725702f8b80a06e96be8535ef72` |
| `app/src/main/java/fr/astragames/app/core/security/KeystoreCrypto.kt` | 58 | 2521 | `683911d373125b78e766146cf5b025e22c1b0e430400c139f1e1f8702e87b835` |
| `app/src/main/java/fr/astragames/app/data/backup/BackupArchive.kt` | 67 | 3415 | `a4da37c1b922e9d0b3836ec9bb39710d94050a07abd83d429d719808c807d5be` |
| `app/src/main/java/fr/astragames/app/data/backup/BackupEnvelope.kt` | 176 | 7999 | `905c00a399b6c17d6a7f382f5f2c41e7b0dcd19a9f235f228776b8412360f330` |
| `app/src/main/java/fr/astragames/app/data/backup/BackupManager.kt` | 265 | 16971 | `590523cad92a328c044fe8c2bc9c33d97a1c9300e54247e38ec12f569351f939` |
| `app/src/main/java/fr/astragames/app/data/backup/BackupRollback.kt` | 47 | 2057 | `0a8ffa749f9997195ab2a5b207787fba776b22b5b679f620fa2bf912c63b4318` |
| `app/src/main/java/fr/astragames/app/data/local/AstraDao.kt` | 550 | 23996 | `80793fadeac7577b694335ec60fb16ee785b6dd9eb960a312a22f7b8af44f3ea` |
| `app/src/main/java/fr/astragames/app/data/local/AstraDatabase.kt` | 126 | 8485 | `ab0497e81ee27be9613419f3d73a8ca86dd1ea787c951a6b36fe48b052feb73d` |
| `app/src/main/java/fr/astragames/app/data/local/Entities.kt` | 319 | 9977 | `219112cf1138218c8d12c902cdb4a6b88710d0e6a6be04944d31783b75146946` |
| `app/src/main/java/fr/astragames/app/data/mods/ModArchive.kt` | 71 | 3273 | `99c829cd43d80f75fad662c2b819a530a9928dd5411ed80254b7383c2b19efc5` |
| `app/src/main/java/fr/astragames/app/data/mods/ModModels.kt` | 100 | 3805 | `b63585044b951f054421ece4b184e8e5428d29311c1fe6d6b9c81b8b0ac648b2` |
| `app/src/main/java/fr/astragames/app/data/mods/ModsManager.kt` | 322 | 19261 | `d7ce39ce5d2effdb4046f042d049dcfb076b3f0a7c9b97ef7163446f7c1d2571` |
| `app/src/main/java/fr/astragames/app/data/repository/GameRepository.kt` | 812 | 41347 | `006ec591b5214d61e0643a23418b5691948744f1449358f91dc9425d9d6b7642` |
| `app/src/main/java/fr/astragames/app/data/saves/BoundedStreams.kt` | 23 | 912 | `83b819a7f9dff1b40ac1e3ef0198d2cefea1039ff1d90267967c2db290a00685` |
| `app/src/main/java/fr/astragames/app/data/saves/DocumentDirs.kt` | 17 | 760 | `16ab56e2d78b6db8f6944ccfb7386d789d6a9768bcbc99b9a5b947d2c0015c86` |
| `app/src/main/java/fr/astragames/app/data/saves/GameSaves.kt` | 133 | 5942 | `6b91a3f47909acb6165515d9ba6962a76cdde9e5f14799e6d3560a5d4250ef47` |
| `app/src/main/java/fr/astragames/app/data/saves/JsonSaveEditor.kt` | 115 | 5900 | `5a68e225fd2fc001acf0cdcfa33110b606ec8e7aeb8931ac7b4c68841348227d` |
| `app/src/main/java/fr/astragames/app/data/saves/LzString.kt` | 233 | 6898 | `dc3e1e0c967eab6e3ce5d9706995f28c492f5d3e5879152e248820178b2fcc15` |
| `app/src/main/java/fr/astragames/app/data/saves/Marshal.kt` | 410 | 21167 | `48c0bee7dd7713d3fd0cd3c74541a401eafe9c6ba0199221d9de29148e9fd02a` |
| `app/src/main/java/fr/astragames/app/data/saves/MarshalFlattener.kt` | 189 | 11080 | `078059d66931a28250a2a02352b0539c960cb630047caa3db89a208d5f94bc86` |
| `app/src/main/java/fr/astragames/app/data/saves/Pickle.kt` | 451 | 25002 | `b7d2f9412a0d4ff298095219af860388afbc40c338d0376821771a4c8b28c753` |
| `app/src/main/java/fr/astragames/app/data/saves/PickleEntries.kt` | 156 | 8570 | `ff2b2e9008133073cd0f4832cee16a16c66ed904cc3ea8b1b663077bf4399c6a` |
| `app/src/main/java/fr/astragames/app/data/saves/PythonStringLiterals.kt` | 43 | 1749 | `3f430368a84b3e7e9a38629ba2b9931dd5c9bf6f16e75c4c06374d5e5dfcae0a` |
| `app/src/main/java/fr/astragames/app/data/saves/RenPyArchive.kt` | 46 | 2252 | `a3a6944d751b9d0d323c3aa16b18b3e677a7864ba117cb9e809fe05bc15dea8d` |
| `app/src/main/java/fr/astragames/app/data/saves/RpgMakerSaveCodec.kt` | 52 | 2347 | `51e78ece58513cec3c9a2ecb4024e71a3814f650744272b65ea68a6c00f9c5da` |
| `app/src/main/java/fr/astragames/app/data/saves/SaveCodec.kt` | 91 | 5108 | `dc499a14f2d5503d6b60c4416416577c26c41d336d59b26c9dbcec59657bfaaa` |
| `app/src/main/java/fr/astragames/app/data/saves/SaveCompression.kt` | 41 | 1365 | `4a0e272e5cb4f121dde0dbbae76fd7ff56cb169e60bc470bbfae241005d7d4fe` |
| `app/src/main/java/fr/astragames/app/data/saves/SaveManager.kt` | 197 | 9434 | `88f4151cc2aaea05439075b7b28d9aac3288fb42888f74ba0a6b4628ab010081` |
| `app/src/main/java/fr/astragames/app/data/saves/SaveModels.kt` | 20 | 605 | `60a18f0d9e92469c1da1206d9fe790de792f32b3d335984252fd8a22bbdea28b` |
| `app/src/main/java/fr/astragames/app/data/scanner/EngineSignatureDetector.kt` | 48 | 2769 | `d5c744dc6ae2c6fd1bc78bdcc1609fc956b6535e68157885d09eb12e012dc78d` |
| `app/src/main/java/fr/astragames/app/data/scanner/GameFingerprint.kt` | 14 | 623 | `26b26ced2be7f7bc0badad58b1f4eb7e2351ae627aac17b593406301d48973b3` |
| `app/src/main/java/fr/astragames/app/data/scanner/GameTitleNormalizer.kt` | 20 | 1113 | `e988ca367742761fe13ba4c69ea69b8b3f03acb79a946e99c0b47075ef723f6e` |
| `app/src/main/java/fr/astragames/app/data/scanner/LibraryReconciliation.kt` | 8 | 291 | `f1b414dabaae16236d5dc2e3807f23539e257eb4e5b64eb1b118a4e99570ff22` |
| `app/src/main/java/fr/astragames/app/data/scanner/RecursiveSourceScanner.kt` | 368 | 19993 | `2efa84730a43867e3de6bd3bea63122f9f07bef528bd105b8a99f2c96430b9ef` |
| `app/src/main/java/fr/astragames/app/launcher/CompatibilityDiagnostic.kt` | 77 | 4612 | `6c06705d11490894901d27b2d4c731b8f50dff11a2e140cfbda6bce5e47ba61b` |
| `app/src/main/java/fr/astragames/app/launcher/GameLauncher.kt` | 16 | 583 | `c4e29f25c77d3cebf058a361388f8166d047c35189936b13d392be210140bf34` |
| `app/src/main/java/fr/astragames/app/launcher/JoiPlayCatalogProvider.kt` | 61 | 2499 | `1d862e87f2a0bf79d7a2ad513f04b06b33ebf9c16f1c98f02e9a9d83af791d1d` |
| `app/src/main/java/fr/astragames/app/launcher/JoiPlayLauncher.kt` | 133 | 6852 | `7e18d63cfb29c4acb0dd1ead01282eca72bba775dcbd188a71d517f1ea4f3588` |
| `app/src/main/java/fr/astragames/app/launcher/JoiPlayRuntimeManager.kt` | 187 | 9085 | `a70c076b32f9676fb147e3c0b73d75e1412337d4e39898bfb74b47090fa2d885` |
| `app/src/main/java/fr/astragames/app/MainActivity.kt` | 207 | 9197 | `21ab580945930ad73ec665e8b62aa2203e8ec9d746694ada0c245e6e65d43442` |
| `app/src/main/java/fr/astragames/app/settings/AppLanguage.kt` | 20 | 674 | `c2d5f2eabe14800915919723705709443041f01d09429568fcfaa971615b0f00` |
| `app/src/main/java/fr/astragames/app/settings/CoverBlurMode.kt` | 14 | 307 | `e46c44b03ec9874e5b7adb2254534e5edc2ed961c85ec4933d2b8e14bd4b4c8b` |
| `app/src/main/java/fr/astragames/app/settings/SearchEngine.kt` | 49 | 2005 | `1f470813d91ac0e243ccb9e85ccbf35829ad1973964a7ba59a84318dea89ab71` |
| `app/src/main/java/fr/astragames/app/settings/SettingsRepository.kt` | 188 | 11161 | `0ce168bbbc6487637d877e803b77ca2d3cbd49b15e128d9b26839e4d96c61324` |
| `app/src/main/java/fr/astragames/app/tools/GameTools.kt` | 104 | 4005 | `84571ebc21ab8bb0d0eeee119389a26b7fe5e3aa5528d9aa8744fd4189ef3b52` |
| `app/src/main/java/fr/astragames/app/translation/GameTranslationManager.kt` | 275 | 17346 | `0a8e2a27617ab37f95c6353fb7030c547b4634a4f92cb585a793c8742bd2920f` |
| `app/src/main/java/fr/astragames/app/translation/LocalTranslator.kt` | 34 | 1600 | `4781c7e2a64cf62ee2765b9ed6e118c0983545740dfcf6ae73e14e43957c65d6` |
| `app/src/main/java/fr/astragames/app/translation/ManualTranslationBundle.kt` | 138 | 10718 | `5c3862662e59788c873a87ab750f56002c63eede286a98ce8bedd9d2ab266138` |
| `app/src/main/java/fr/astragames/app/translation/ProtectedText.kt` | 164 | 7753 | `e50024c6db4c768b4ac4115e10d2cca74dd2c160d486f2c2f1f709e5bd34e5fd` |
| `app/src/main/java/fr/astragames/app/translation/RpgTextDocument.kt` | 96 | 6475 | `97d36c9170ff1baa56d672a47b27047b9a1a9d5d7d3169be2143c7eee01be507` |
| `app/src/main/java/fr/astragames/app/translation/TranslationIo.kt` | 25 | 868 | `2011b2d032cc07db25469f77ee856e36d4d559f3b5054a93806b76de5b79bc76` |
| `app/src/main/java/fr/astragames/app/translation/TranslationJson.kt` | 82 | 3872 | `958015014a7667ebf492995640ba72325918406793bc001d48de55bb7fa94e04` |
| `app/src/main/java/fr/astragames/app/translation/TranslationPatch.kt` | 132 | 6749 | `c9e87207f4b94c476897b41ec2ae731855411494674e5b7170a016959999f694` |
| `app/src/main/java/fr/astragames/app/translation/TranslationTiming.kt` | 23 | 1009 | `249167c3f13f47831928f0880e3229e2c07da1d0cc873f61ef82711983c6cd51` |
| `app/src/main/java/fr/astragames/app/ui/AdaptivePages.kt` | 31 | 1370 | `214a8d36e858ddddfc266b72815039d48266b9197f6e1595b3abc650d3a31138` |
| `app/src/main/java/fr/astragames/app/ui/AstraApp.kt` | 378 | 21294 | `8df408c3e0018e27bb6ab42efc99b790fa1c776244641d3face963282e72f68d` |
| `app/src/main/java/fr/astragames/app/ui/AstraButtons.kt` | 35 | 2177 | `22f3484b1e7c4b5ada5fd62e89a2e40181115d934b440da351a28cf85f0d5d56` |
| `app/src/main/java/fr/astragames/app/ui/AstraUiState.kt` | 126 | 4575 | `c22e59e9aa11770f74633c74b74d4dc1be766a11acb573e94eb18afefe5eed82` |
| `app/src/main/java/fr/astragames/app/ui/AstraViewModel.kt` | 1157 | 59420 | `e117e292240580f0743a7370e7c81f22747c9ade1ca50272d8988cd65c48c7ec` |
| `app/src/main/java/fr/astragames/app/ui/CollectionScreens.kt` | 219 | 14640 | `bd9b20332273eaa83c0791da66c3ebea32cb1b9f9e3b6f44483bf70420ad311f` |
| `app/src/main/java/fr/astragames/app/ui/CoverBlurState.kt` | 11 | 289 | `c29249ebb9cdfa6c1249bdde4a74adcdbda4950afd2477ce993cc05ed536195b` |
| `app/src/main/java/fr/astragames/app/ui/DesignComponents.kt` | 79 | 4579 | `ef0df0ed4d09e84f8b994f267392398226d721ff2aab38347af4fe7fecb8803a` |
| `app/src/main/java/fr/astragames/app/ui/DuplicateScreens.kt` | 234 | 15316 | `4f7f11e5acdeffb0360b3f4bb37464b0c5f7617f18d438604f3437f156348bcc` |
| `app/src/main/java/fr/astragames/app/ui/GameDetailScreens.kt` | 510 | 32819 | `51ac936ade1dd8d4dae13202bdd73ec60ec1bb390b794d7375cae700f7c8f0f8` |
| `app/src/main/java/fr/astragames/app/ui/GameSetupScreens.kt` | 386 | 23999 | `d5536264c6f65152ad3e24cca5feb5a3e576b2711b6252728ba22215d31ec04f` |
| `app/src/main/java/fr/astragames/app/ui/GameToolsController.kt` | 286 | 14599 | `b241439902f45f52b7d94df0e7724aa582168d56602883622af3a0e84dbbfc3d` |
| `app/src/main/java/fr/astragames/app/ui/GameToolsScreens.kt` | 81 | 4420 | `25709de484a646ce5fb2bc396f337491de75f8198d73c6256d5e09f7b761c6b7` |
| `app/src/main/java/fr/astragames/app/ui/GameTranslationController.kt` | 111 | 5257 | `b57e93026d573a39facbe7c279027428dfdb3a18f4073a296004d1c911247caa` |
| `app/src/main/java/fr/astragames/app/ui/GameTranslationScreen.kt` | 177 | 13633 | `8022083b2fc5192c1bf2434dff801fee6f4dc78e324137d967d1c788421e8a8e` |
| `app/src/main/java/fr/astragames/app/ui/GameUpdates.kt` | 18 | 663 | `40d5d963b9bb36baee81a934d552829ed67756d02236ad0bf1d07962aac8cd96` |
| `app/src/main/java/fr/astragames/app/ui/HomeScreen.kt` | 162 | 9746 | `ef887ce073e04eabe7f18ae366c68beeea379a3544f8cf89693c4b1c55afad83` |
| `app/src/main/java/fr/astragames/app/ui/HuePreference.kt` | 35 | 1871 | `1f2cde3238f57b68750794a6fcadfbb0184998ed5e2212763a8895c8b002f7b3` |
| `app/src/main/java/fr/astragames/app/ui/LibraryDialogs.kt` | 277 | 15228 | `a4740d8dcc413f3180c04ee375e7b82f4e575543608a7551b992ca1b0996baa5` |
| `app/src/main/java/fr/astragames/app/ui/LibraryIndex.kt` | 91 | 4735 | `91896b82be0b9a208cfd64ce628c286af68326f43b7880d2b81c28065a92b7a9` |
| `app/src/main/java/fr/astragames/app/ui/LibraryScreens.kt` | 436 | 27241 | `3d63d98bcace8b65352be8baac2cad62200b6cbb39622586c5678f03737e7d16` |
| `app/src/main/java/fr/astragames/app/ui/LibrarySearch.kt` | 35 | 1233 | `366097e16cbd202d2236afa765f712dfe722811e73e81572f1d67f1d0ebe4d2b` |
| `app/src/main/java/fr/astragames/app/ui/Localization.kt` | 820 | 152010 | `198daa73a58cb8d0d3a28c29bb342a10d768777911ec5022903fa2d4867b4655` |
| `app/src/main/java/fr/astragames/app/ui/LockContent.kt` | 79 | 5372 | `fac4a5c29a9080a5c89ae40f2ed91f25caab76565ef7a347064b97ded9e5f90f` |
| `app/src/main/java/fr/astragames/app/ui/MetadataScreens.kt` | 661 | 37394 | `80ba5cf6d2bca9e464c832ffb89f6a92564d607a1537bedd34edab81daded3a3` |
| `app/src/main/java/fr/astragames/app/ui/ModsScreen.kt` | 94 | 5488 | `9ae6cb225532052ae3c0ad4740a2bd79e2c4fb8b043ecc3c0ac3c48673f8a2a3` |
| `app/src/main/java/fr/astragames/app/ui/OnboardingScreens.kt` | 197 | 12210 | `4176434f6b08f3b522af36eb03fb077d6b9fec818e2749e0709a216eb9d5a21d` |
| `app/src/main/java/fr/astragames/app/ui/SaveScreens.kt` | 253 | 18167 | `ab82a38b31a8b79557f8a9edfe48f4e4363c09fe0406f4f1d8ae4dee0ccee5c0` |
| `app/src/main/java/fr/astragames/app/ui/ScrollingScaffold.kt` | 67 | 2884 | `2970cbea757dfa9c27a938f298d83d20cc25d2cb91c6fa79466cd2ef4b4357fd` |
| `app/src/main/java/fr/astragames/app/ui/SearchScreen.kt` | 130 | 7725 | `ec95e0ba7b45c30ca72c365f7c807ec469e53cc02d794cc65c6d4cc402034a8d` |
| `app/src/main/java/fr/astragames/app/ui/SettingsScreen.kt` | 325 | 19708 | `a0b935815a794f6a6306ea48d2424d94810477c58ee055cebae0f8264bd8ed4f` |
| `app/src/main/java/fr/astragames/app/ui/SmartCollectionEditor.kt` | 160 | 11767 | `f2adb84ac9abb16da9ee7a1c512952102275eba466f64b66e9d519b1a97f155c` |
| `app/src/main/java/fr/astragames/app/ui/StatusScreens.kt` | 139 | 8258 | `39f7eaf71024c0e5905d81deb196afc1a4b682e659dc5e7a6c0d9a69bc176adb` |
| `app/src/main/java/fr/astragames/app/ui/TagScreens.kt` | 196 | 12751 | `df0db8473ba2746e27def0a49b0e48450fdc2bbced497d34ffbe58f5a1768cad` |
| `app/src/main/java/fr/astragames/app/ui/theme/AstraTheme.kt` | 66 | 3545 | `4cd62e04dedfe74876f4ff33a15261aa93df4c86deccd4cbf2c56dcc7a336a76` |
| `app/src/main/java/fr/astragames/app/ui/theme/Type.kt` | 27 | 1875 | `c1bf0d35ba877a3cb2eb6ba4023b69cf00dd77e827f2645e8e2e3592bf6e2165` |
| `app/src/main/java/fr/astragames/app/ui/ToolsScreens.kt` | 128 | 8152 | `8778bac33c1446672f3674fcc30064540a3a2839931a320db0a8e296f023ca72` |
| `app/src/main/java/fr/astragames/app/ui/UiComponents.kt` | 141 | 6064 | `ec82b12f67b58c24a7093a82f70325b34e23369baf20782e45968cc5a769c233` |
| `app/src/main/java/fr/astragames/app/ui/WebViewSecurity.kt` | 47 | 2083 | `7eb0a2addf779272412efef93018092745390722eda09e7bf7ea7ff3b328be5f` |
| `app/src/main/java/fr/astragames/app/worker/GameUpdatesWorker.kt` | 100 | 4958 | `5d1df7c01e9c29862cce5f1289c888127a7822aa4eb3995ab17d63c4f693ff3c` |
| `app/src/main/java/fr/astragames/app/worker/JoiPlayUpdateWorker.kt` | 72 | 3444 | `169b5f7bdc582eae850a14e5f77ddbac6a5fdaab2514c772b1b14397b6d1b215` |
| `app/src/main/java/fr/astragames/app/worker/LibraryScanWorker.kt` | 32 | 1342 | `618b4a19eb8ebef9f1ce10bab2f6bce4204ea12c7b5b9f3d15c97bf29d91c381` |
| `app/src/main/java/fr/astragames/app/worker/NetworkConstraints.kt` | 8 | 244 | `a74e1da4812388b67fed0a2ecac6eaf7c4b4931d278af1a802f425d32943dd7c` |
| `app/src/main/java/fr/astragames/app/worker/UpdateNotifications.kt` | 46 | 2047 | `2fa2213ba3e506debbdd985f1e0c6958858e4ced95170661de8e0e675ed7ae4c` |

## Manifestes, ressources et profil embarqués

| Fichier | Lignes | Octets | SHA-256 |
|---|---:|---:|---|
| `app/src/main/AndroidManifest.xml` | 87 | 3631 | `5ac0e4a31281035a9914237a7c500824f661e9361629a183c57fdbaa8d282114` |
| `app/src/main/baseline-prof.txt` | 8 | 451 | `dbceada2f4f8d945f24034add71d244e37d6179b5dc529bb71f8325eb520ebad` |
| `app/src/main/res/font/geist.ttf` | — | 169056 | `73894e0448cae90a92b6c2f8732b7bb9acb7b94c418bff559dad4a18e1de9659` |
| `app/src/main/res/font/inter.ttf` | — | 876576 | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` |
| `app/src/main/res/values/strings.xml` | 3 | 71 | `27a1b077ea633d5c9a763533cfdb517ab96eaf364597268779558cac425557c4` |
| `app/src/main/res/values/themes.xml` | 17 | 847 | `f3500e17fe8e2b8774e2da6365be9f86131f445dad76d4e18dcf2bd9f2d9d8e8` |
| `app/src/main/res/values-fr/strings.xml` | 3 | 71 | `27a1b077ea633d5c9a763533cfdb517ab96eaf364597268779558cac425557c4` |
| `app/src/main/res/values-night/themes.xml` | 9 | 464 | `ecb5f0857052cc15f5960185941c80099e47e416b1b080fe584714f3e984571e` |
| `app/src/main/res/xml/backup_rules.xml` | 8 | 462 | `fe0d1182d85adb30dc212a812624b34e942f2200ede1ddf56a3eaa3c77f2d27a` |
| `app/src/main/res/xml/data_extraction_rules.xml` | 11 | 462 | `f6f00a3f285c461079fe4d5d089e164af142b182707509602f950cc45a8ad368` |
| `app/src/main/res/xml/file_paths.xml` | 4 | 177 | `56f72cd05d3f13845678b16c04b8f49b904cc9985f05e3ad51dd119b6fade6b4` |

## Configuration de construction et wrapper

Ces fichiers influencent la compilation et sont inventoriés séparément du code livré dans l’application. `local.properties` et les secrets locaux sont exclus.

| Fichier | Lignes | Octets | SHA-256 |
|---|---:|---:|---|
| `settings.gradle.kts` | 19 | 377 | `b0a7c20938d3179949f7f9be6d3e7166b0ef2b72eb6cf934b96bc0dbce2f890f` |
| `build.gradle.kts` | 8 | 410 | `ed706154f0f744b5d350efe635a9b67a058dc999322a9221dec40ed6a3427c04` |
| `gradle.properties` | 5 | 174 | `e24b235d699cd8812071579986941082dea752459b07cf6fb19cd74d9c6e0365` |
| `app/build.gradle.kts` | 134 | 5478 | `246a0c3c85d42cf14dd537a66a1ac8855022d4ffca990a097a91b37774a48cf7` |
| `app/proguard-rules.pro` | 2 | 149 | `b1653bdfcc58933c432560ca13c56d6ba5eee17cc591e29b6686f2440e64247b` |
| `baselineprofile/build.gradle.kts` | 33 | 896 | `fdf940cd9535a88209415cea828a8e2c67e0e5e2685cf9b3ac33eb61e9bbaec5` |
| `gradle/wrapper/gradle-wrapper.properties` | 7 | 260 | `5f705adaa45c1b4941763e0cb0fde5f2435c7ccbe2feafba9f3659a23a4f2ca5` |
| `gradle/wrapper/gradle-wrapper.jar` | — | 43583 | `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |
| `gradlew` | 252 | 8762 | `a3648413b47ef77af21d5ebc36c687c7d103aaef3e17f33de7d4f080a6f300a3` |
| `gradlew.bat` | 94 | 2966 | `57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f` |

## Autres sources et exclusions

| Périmètre | Fichiers | Statut dans cet inventaire |
|---|---:|---|
| `app/src/test/` | 53 | Tests JVM et fixtures ; hors total production |
| `app/src/androidTest/` | 15 | Tests instrumentés ; résultats dans le [rapport de validation](AUDIT_2026-10-05.md) |
| `app/src/debug/` | 2 | Manifest/provider de test ; non livrés en release |
| `baselineprofile/src/` | 1 | Générateur instrumenté ; hors application |
| `scripts/` | 5 | Outillage de vérification et génération |
| `app/schemas/` | 8 | Schémas Room historiques |

Les polices embarquées ont une empreinte mais ne sont pas auditées comme du code. Les maquettes `.pen`, captures historiques, contenus de jeux utilisateur, archives générées et JAR de vérification sous `build/` ne font pas partie des sources de production. Les dépendances sont inventoriées dans `DEPENDENCIES_2026-10-05.json`. Le [rapport de validation](AUDIT_2026-10-05.md) décrit les résultats de build, les tests effectivement exécutés et leurs limites, y compris sur Android.

## Reproduction

Depuis la racine du dépôt, avec Python 3 :

```powershell
python scripts/source-inventory.py
python scripts/source-inventory.py --check
```

La première commande régénère uniquement ce document. La seconde vérifie que chaque chemin, compte et empreinte correspond toujours au workspace ; elle ne modifie rien. Relancer après tout dernier changement de source.
