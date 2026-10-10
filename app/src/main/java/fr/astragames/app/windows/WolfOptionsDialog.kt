package fr.astragames.app.windows

import android.app.AlertDialog
import android.content.Context
import android.widget.*

/** Compact native controls shared by the game sheet and the in-session menu. */
internal fun showWolfOptions(host: Context, store: WolfGameOptions, changed: () -> Unit = {}): AlertDialog {
    val context = android.view.ContextThemeWrapper(host, android.R.style.Theme_Material_Dialog_Alert)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val accent = android.content.res.ColorStateList.valueOf(0xFFC9B7FF.toInt())
    val original = store.read()
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
    fun label(text: String) { layout.addView(TextView(context).apply { this.text = text; setPadding(0, 14, 0, 4) }) }
    fun choice(title: String, values: List<String>, selected: Int): Spinner {
        label(title)
        return Spinner(context).apply {
            contentDescription = title
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected); layout.addView(this)
        }
    }
    val runtime = choice("Moteur · au prochain lancement", WolfRuntimeMode.entries.map { it.label }, original.runtime.ordinal)
    label("Le moteur Android exécute directement les données Wolf. Automatique utilise Winlator lorsqu’une fonction du jeu n’est pas encore prise en charge. Natif expérimental permet de tester le moteur et fournit un diagnostic précis en cas d’incompatibilité.")
    label("Les sauvegardes natives restent séparées des sauvegardes Windows. Passer d’un moteur à l’autre ne transfère pas la progression.")
    val storage = choice("Fichiers Winlator · au prochain lancement", WolfStorageMode.entries.map { it.label }, original.storageMode.ordinal)
    label("Le dossier d’origine évite l’import et reçoit directement les sauvegardes. Une copie privée reste disponible si le dossier est inaccessible ou si des sauvegardes sont en conflit.")
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        label(if (android.os.Environment.isExternalStorageManager()) "Accès direct au stockage local autorisé."
            else "Pour Winlator, Android demande l’accès à tous les fichiers pour lancer un jeu local sans copie. Cette autorisation est facultative : sans elle, Winlator utilise une copie privée. Le moteur Android lit le dossier sélectionné avec son autorisation existante.")
        layout.addView(Button(context).apply {
            text = "Accès à tous les fichiers · réglages Android"
            setOnClickListener {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${host.packageName}"))
                runCatching { host.startActivity(intent) }.onFailure {
                    Toast.makeText(host, "Ouvrez les réglages Android → Astra → Accès à tous les fichiers.", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
    fun toggle(title: String, selected: Boolean) = Switch(context).apply {
        text = title; isChecked = selected; thumbTintList = accent; minHeight = dp(48); layout.addView(this)
    }
    fun slider(title: String, minimum: Int, maximum: Int, value: Int): SeekBar {
        val caption = TextView(context).apply { text = "$title : $value %"; setPadding(0, 14, 0, 4); layout.addView(this) }
        return SeekBar(context).apply {
            contentDescription = title; max = maximum - minimum; progress = value - minimum; progressTintList = accent; thumbTintList = accent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { caption.text = "$title : ${progress + minimum} %" }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }); layout.addView(this)
        }
    }
    val performance = choice("Fluidité · au prochain lancement", WolfPerformance.entries.map { it.label }, original.performance.ordinal)
    label("Rapide favorise la vitesse ; revenez à Stable si le jeu devient instable.")
    val resolution = choice("Affichage Windows · au prochain lancement", WolfOptions.resolutions.map {
        if (it == "auto") "Automatique (orientation)" else it
    }, WolfOptions.resolutions.indexOf(original.resolution))
    label("La définition interne dépend du jeu. Le lissage adoucit les pixels lors de l’agrandissement.")
    label("Automatique : bureau 4:3 en portrait, 16:9 en paysage, au lancement.")
    val imageMode = choice("Cadrage · immédiat", WolfImageMode.entries.map { it.label }, original.imageMode.ordinal)
    label("Image entière montre tout le jeu. Remplir coupe les bords ; Étirer déforme les proportions. Un jeu 4:3 ne gagne pas de décor en changeant le bureau Windows.")
    val smooth = toggle("Lissage renforcé", original.smooth)
    val fps = choice("Limite d’affichage", listOf("30 images/s", "60 images/s"), if (original.maxFps == 30) 0 else 1)
    val counter = toggle("Compteur d’images modifiées", original.showFps)
    label("Un compteur bas est normal sur une image immobile : les images identiques sont réutilisées.")
    val touch = choice("Tactile", listOf("Clic à la position touchée", "Direction au toucher (maintenir)"), if (original.directionalTouch) 1 else 0)
    label("Le clic déplace le personnage si le jeu le prévoit. Sinon, maintenez un côté de l’image pour avancer dans cette direction.")
    label("Touchez brièvement l’image avec deux doigts pour envoyer Retour, selon la touche configurée.")
    val zoom = toggle("Zoom à deux doigts · immédiat", original.pinchZoom)
    label("Écartez ou rapprochez deux doigts pour zoomer de 1× à 4×. Déplacez les deux doigts pour parcourir l’image agrandie. Désactiver le zoom rétablit le cadrage initial.")
    val opacity = slider("Opacité des touches", 15, 100, original.opacity)
    val size = slider("Taille des touches", 70, 150, original.size)
    label("Menu → Modifier les touches : faites glisser chaque touche ; touchez-la pour changer son action. Positions séparées en portrait et paysage.")
    val dialog = AlertDialog.Builder(context).setTitle("Réglages Wolf RPG")
        .setView(ScrollView(context).apply { addView(layout) })
        .setNegativeButton("Annuler", null)
        .setPositiveButton("Enregistrer") { _, _ ->
            store.save(WolfOptions(WolfPerformance.entries[performance.selectedItemPosition], WolfOptions.resolutions[resolution.selectedItemPosition],
                smooth.isChecked, if (fps.selectedItemPosition == 0) 30 else 60, counter.isChecked, touch.selectedItemPosition == 1,
                opacity.progress + 15, size.progress + 70, WolfImageMode.entries[imageMode.selectedItemPosition],
                WolfStorageMode.entries[storage.selectedItemPosition], zoom.isChecked, WolfRuntimeMode.entries[runtime.selectedItemPosition])); changed()
        }.create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
    }
    dialog.show()
    return dialog
}
