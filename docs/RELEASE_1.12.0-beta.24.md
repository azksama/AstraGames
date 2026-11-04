# Astra 1.12.0-beta.24

**Préversion · code Android 52**

Cette préversion réunit MKXP-Z, les corrections de la revue UI/UX et code, puis
les correctifs finaux Wolf et archives de mods.

## MKXP-Z intégré

Dans la fiche d’un jeu XP, VX ou VX Ace, ouvrir **Compatibilité → Profil de
lancement**, choisir **Astra · MKXP-Z (prototype)**, enregistrer puis **Jouer**.
JoiPlay reste disponible.

- Commandes tactiles et accessibles, reprise d’une préparation interrompue et
  respect du verrou Astra en arrière-plan.
- Copie privée au premier lancement ; les outils indiquent **Copie Astra active
  (MKXP-Z)**. Prévoir l’espace nécessaire et sauvegarder dans le jeu avant de quitter.
- Aucune synchronisation ni mise à jour automatique depuis le dossier source.
  Aucun jeu ni RTP fourni.

## Corrections visibles

- Recherche lisible avec le clavier en paysage et le texte agrandi.
- Actions rapides défilables et catégories longues plus lisibles.
- Messages de scan au-dessus de la navigation ; barres système lisibles dans les
  dialogues Astra, y compris lorsque le système est en mode clair.
- Sélection de dossier accessible, lancement désactivé pour un jeu manquant et
  erreurs d’ouverture des liens prises en charge.

## Sauvegardes et mods

- Propriétaire contrôlé lors des restaurations/suppressions ; dossier source et
  copie privée distingués. Export/import des sauvegardes privées MKXP-Z/Wolf prises
  en charge, avec refus des fichiers manquants et chemins non autorisés.
- **Wolf :** une sauvegarde restaurée, même inchangée lors de l’export, reste
  protégée au prochain lancement ou à l’actualisation des ressources. Une source
  différente déclenche un conflit au lieu d’écraser la copie restaurée.
- **Mods :** originaux nécessaires à la désinstallation vérifiés à l’export/import.
  Original manquant, altéré ou attribué à une autre installation : refus avant
  remplacement des données. Anciens noms de backups conservés ; restes locaux
  non référencés exclus de l’export.

Les archives `.astra` restent chiffrées avec la clé de l’installation : pas de
migration après désinstallation ou vers un autre téléphone. Elles n’incluent pas
les copies complètes des jeux, les préfixes Windows ou les permissions de dossiers.
Conserver séparément les dossiers des jeux.

## Installation et portée de validation

L’APK public est le **build release minifié**, signé avec le certificat de
développement historique. **La mise à jour directe depuis beta.23 est validée sans
désinstallation** sur émulateur : catalogue, préférences, sauvegarde et backup de
fixture conservés, recherche et fiche accessibles. Cet essai ne lance pas un jeu.
Les résultats et limites sont détaillés dans le
[bilan de validation](VALIDATION_1.12.0-beta.24.md).

Installer `Astra-1.12.0-beta.24.apk` par-dessus beta.23. Pour recevoir les préversions
depuis Astra, activer **Paramètres → Mises à jour d’Astra → Recevoir les versions
bêta**. Les sources natives correspondantes, notices et `SHA256SUMS.txt`
accompagnent l’APK ; les APK de tests ne sont pas distribués comme application.

Les essais antérieurs utilisent des fixtures sur Android Emulator x86_64, avec
petite/grande fenêtre, paysage et texte agrandi.
**L’exécution ARM64 n’est pas démontrée.** L’image SDK 16 Kio expose des pages
applicatives de 16 Kio sur un noyau/MMU de 4 Kio ; elle ne qualifie pas un appareil
ARM64 à noyau 16 Kio. MKXP-Z n’est pas qualifié pour tous les jeux commerciaux,
extensions Windows, Win32API, RTP, MIDI, vidéos ou archives chiffrées.
