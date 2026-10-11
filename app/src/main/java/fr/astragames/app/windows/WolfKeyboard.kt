package fr.astragames.app.windows

import com.winlator.xserver.XKeycode

/** The physical PC keyboard supported by X11 and Wolf's DirectInput reader. */
internal object WolfKeyboard {
    val keys = XKeycode.entries.filter { it != XKeycode.KEY_NONE && it != XKeycode.KEY_MAX && !it.isCustomKey }
    fun valid(code: XKeycode) = code in keys
    fun parse(name: String): XKeycode? = runCatching { XKeycode.valueOf(name) }.getOrNull()?.takeIf(::valid)
    fun label(code: XKeycode): String = when (code) {
        XKeycode.KEY_ENTER -> "Entrée"; XKeycode.KEY_ESC -> "Échap"; XKeycode.KEY_SPACE -> "Espace"
        XKeycode.KEY_BKSP -> "Retour arrière"; XKeycode.KEY_TAB -> "Tabulation"
        XKeycode.KEY_SHIFT_L -> "Shift gauche"; XKeycode.KEY_SHIFT_R -> "Shift droite"
        XKeycode.KEY_CTRL_L -> "Ctrl gauche"; XKeycode.KEY_CTRL_R -> "Ctrl droite"
        XKeycode.KEY_ALT_L -> "Alt gauche"; XKeycode.KEY_ALT_R -> "Alt droite"
        XKeycode.KEY_UP -> "Haut"; XKeycode.KEY_DOWN -> "Bas"; XKeycode.KEY_LEFT -> "Gauche"; XKeycode.KEY_RIGHT -> "Droite"
        XKeycode.KEY_PRIOR -> "Page précédente"; XKeycode.KEY_NEXT -> "Page suivante"
        XKeycode.KEY_HOME -> "Début"; XKeycode.KEY_END -> "Fin"; XKeycode.KEY_DEL -> "Supprimer"
        XKeycode.KEY_CAPS_LOCK -> "Verr. maj."; XKeycode.KEY_NUM_LOCK -> "Verr. num."; XKeycode.KEY_SCROLL_LOCK -> "Arrêt défil."
        XKeycode.KEY_PRTSCN -> "Impr. écran"
        else -> code.name.removePrefix("KEY_").replace("KP_", "Pavé ").replace('_', ' ')
    }

    /** X11 key numbers are scan+8 except for the extended PC keys. */
    fun scan(code: XKeycode): Int? = when (code) {
        XKeycode.KEY_KP_ENTER -> 156; XKeycode.KEY_CTRL_R -> 157; XKeycode.KEY_KP_DIVIDE -> 181
        XKeycode.KEY_PRTSCN -> 183; XKeycode.KEY_ALT_R -> 184
        XKeycode.KEY_HOME -> 199; XKeycode.KEY_UP -> 200; XKeycode.KEY_PRIOR -> 201
        XKeycode.KEY_LEFT -> 203; XKeycode.KEY_RIGHT -> 205; XKeycode.KEY_END -> 207
        XKeycode.KEY_DOWN -> 208; XKeycode.KEY_NEXT -> 209; XKeycode.KEY_INSERT -> 210; XKeycode.KEY_DEL -> 211
        else -> (code.id.toInt() and 255).takeIf { valid(code) && (it in 9..91 || it in 95..96) }?.minus(8)
    }
}
