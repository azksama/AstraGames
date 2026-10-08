# Sources et licence

Sous-ensemble de [Winlator](https://github.com/brunodev85/winlator-app), révision
`3981d86efa4f333b2a34a7da8b6521476cd8c8b9`, BrunoSX et contributeurs, LGPL-2.1.
Les en-têtes des dépendances tierces sont conservés dans leurs fichiers.

Modifications Astra du 8 octobre 2026 : sélection des classes X11/rendu/entrées,
retrait des écrans et dépendances Winlator, Activity Android générique, focus X11,
création non destructive des sockets, compatibilité NDK 29 et Gradle, extracteur
RuntimeArchive et intégration du pont Astra. Les fichiers sont les sources complètes
du module modifié, recompilables avec `./gradlew :windows-runtime:assembleRelease`.

Le code de l'application hôte utilise le module par dépendance Gradle. Pour tester
une version modifiée du module, reconstruire l'application avec `:app:assembleDebug`,
ou signer l'APK release avec sa propre clé. Ne pas désinstaller une installation
existante sans exporter ses données. Voir ../docs/wolf-windows-runtime.md.
