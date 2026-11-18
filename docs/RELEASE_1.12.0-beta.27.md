# Astra 1.12.0-beta.27

Préversion Android · code 55 · ARM64 et x86_64.

- **Configuration des nouveaux jeux.** Dans le rapport de scan, **Configurer** ouvre une page complète pour la jaquette, les métadonnées et les tags. Quand plusieurs jeux sont ajoutés, **Enregistrer et suivant** ouvre le suivant. **Plus tard** conserve les jeux dans la bibliothèque.
- **Organisation de la bibliothèque.** Le classement groupé par dossier, favori ou tag conserve la sélection d’origine en cas d’échec. Les filtres et les brouillons de tags se rouvrent après une rotation de l’écran.
- **Versions des jeux.** Astra reconnaît qu’une version 2.10 est plus récente qu’une 2.9. Confirmer la version installée conserve une annonce plus récente reçue entre-temps.
- **Historique.** L’effacement porte sur les sessions terminées. En cas d’échec d’écriture, elles restent visibles et l’opération peut être relancée.

## Recevoir la bêta 27

Ouvrez **Paramètres → Mises à jour d’Astra**, activez **Recevoir les versions bêta**, puis touchez **Vérifier les mises à jour**. Pour l’installer, choisissez **Télécharger et installer** et confirmez dans Android. L’APK signé utilise le même certificat que la bêta 26.

## Vérifications

Sur émulateur Android, 89 tests ont réussi en portrait et 6 en paysage avec le texte à 130 %. La mise à jour depuis la bêta 26 a conservé les 31 tables du catalogue de test, les préférences et une sauvegarde témoin. Après installation de l’APK signé, un scan a détecté un nouveau jeu. Sa configuration s’est ouverte en pleine page et son enregistrement l’a rendu visible dans la bibliothèque.

Le fichier `Astra-1.12.0-beta.27.apk` pèse 49 133 140 octets. Son SHA-256 est `bb3e709c408288b12613555b15024cbad75b6faa38b8365024e567e5e33f1e70`.

Les sources de l’application sont disponibles dans le tag de cette version. Les composants natifs et leurs notices sont identiques à ceux de la bêta 26. [Sources natives](https://github.com/azksama/AstraGames/releases/download/v1.12.0-beta.26/Astra-1.12.0-beta.26-native-sources.tar.gz) · [Notices](https://github.com/azksama/AstraGames/releases/download/v1.12.0-beta.26/Astra-1.12.0-beta.26-notices.zip)
