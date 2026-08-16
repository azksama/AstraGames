package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.DetectionResult
import fr.astragames.app.core.model.GameEngine
import java.util.Locale

object EngineSignatureDetector {
    fun detect(relativeNames: Set<String>): DetectionResult? {
        val names = relativeNames.map { it.replace('\\', '/').lowercase(Locale.ROOT) }.toSet()
        fun has(path: String) = path.lowercase(Locale.ROOT) in names
        fun firstExe() = names.firstOrNull { it.endsWith(".exe") }?.substringAfterLast('/')

        return when {
            has("www/js/rmmz_core.js") || has("js/rmmz_core.js") -> DetectionResult(
                GameEngine.RPG_MAKER_MZ, .99f, listOf("rmmz_core.js"), firstExe() ?: "Game.exe"
            )
            has("www/js/rpg_core.js") || has("js/rpg_core.js") -> DetectionResult(
                GameEngine.RPG_MAKER_MV, .99f, listOf("rpg_core.js"), firstExe() ?: "Game.exe"
            )
            names.any { it.endsWith(".rvdata2") } -> DetectionResult(
                GameEngine.RPG_MAKER_VX_ACE, .98f, listOf("Données RPG Maker VX Ace"), firstExe() ?: "Game.exe"
            )
            names.any { it.endsWith(".rvdata") } -> DetectionResult(
                GameEngine.RPG_MAKER_VX, .97f, listOf("Données RPG Maker VX"), firstExe() ?: "Game.exe"
            )
            names.any { it.endsWith(".rxdata") } -> DetectionResult(
                GameEngine.RPG_MAKER_XP, .97f, listOf("Données RPG Maker XP"), firstExe() ?: "Game.exe"
            )
            names.any { it.startsWith("game/") && (it.endsWith(".rpy") || it.endsWith(".rpyc")) } || has("renpy") -> DetectionResult(
                GameEngine.RENPY, .95f, listOf("Ren'Py game scripts"), firstExe()
            )
            has("tyrano/plugins/kag/kag.js") || has("data/system/config.tjs") -> DetectionResult(
                GameEngine.TYRANO, .93f, listOf("Tyrano signature"), firstExe()
            )
            has("package.json") && names.any { it.contains("electron") } -> DetectionResult(
                GameEngine.ELECTRON, .85f, listOf("Electron package"), firstExe()
            )
            has("index.html") && names.any { it.endsWith("construct.json") || it.contains("c2runtime") || it.contains("c3runtime") } -> DetectionResult(
                GameEngine.CONSTRUCT, .9f, listOf("Construct runtime"), "index.html"
            )
            has("index.html") && names.any { it.endsWith("story.js") || it.endsWith("twine.js") } -> DetectionResult(
                GameEngine.TWINE, .9f, listOf("Twine story"), "index.html"
            )
            has("index.html") -> DetectionResult(GameEngine.HTML5, .72f, listOf("index.html"), "index.html")
            else -> null
        }
    }
}
