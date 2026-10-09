package fr.astragames.app.windows

import android.content.Context
import com.winlator.xserver.XKeycode
import org.json.JSONObject

internal enum class WolfPerformance(val label: String, val strongMemory: String, val bigBlock: String, val nativeFlags: String) {
    BALANCED("Équilibré", "1", "2", "0"),
    FAST("Rapide", "0", "3", "1"),
    STABLE("Stable (bêta 6)", "2", "0", "0");
    fun environment() = mapOf("BOX64_DYNAREC_SAFEFLAGS" to "2", "BOX64_DYNAREC_STRONGMEM" to strongMemory,
        "BOX64_DYNAREC_BIGBLOCK" to bigBlock, "BOX64_DYNAREC_NATIVEFLAGS" to nativeFlags,
        "BOX64_DYNAREC_X87DOUBLE" to "1")
}

internal enum class WolfImageMode(val label: String) {
    FIT("Image entière · proportions conservées"),
    FILL("Remplir · recadrer sans déformer"),
    STRETCH("Étirer · utiliser tout l’écran")
}

internal enum class WolfStorageMode(val label: String) {
    AUTO("Dossier d’origine si accessible"),
    COPY("Copie privée dans Astra")
}

internal data class WolfOptions(
    val performance: WolfPerformance = WolfPerformance.BALANCED,
    val resolution: String = "auto",
    val smooth: Boolean = true,
    val maxFps: Int = 60,
    val showFps: Boolean = true,
    val directionalTouch: Boolean = false,
    val opacity: Int = 75,
    val size: Int = 100,
    val imageMode: WolfImageMode = WolfImageMode.FIT,
    val storageMode: WolfStorageMode = WolfStorageMode.AUTO
) {
    fun windowsResolution(landscape: Boolean) = if (resolution == "auto") {
        if (landscape) "1280x720" else "1280x960"
    } else resolution
    companion object { val resolutions = listOf("auto", "800x600", "1280x720", "1280x960", "1920x1080") }
}

/** One preference record per game; no mutation of source game files. */
internal class WolfGameOptions(context: Context, id: String) {
    private val prefs = context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE)
    private val key = id.toByteArray().let { java.security.MessageDigest.getInstance("SHA-256").digest(it).hexString() }
    private fun json() = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())
    fun read(): WolfOptions = json().let {
        WolfOptions(
            performance = runCatching { WolfPerformance.valueOf(it.optString("performance", "BALANCED")) }.getOrDefault(WolfPerformance.BALANCED),
            resolution = it.optString("resolution", "auto").takeIf(WolfOptions.resolutions::contains) ?: "auto",
            smooth = it.optBoolean("smooth", true), maxFps = if (it.optInt("maxFps", 60) == 30) 30 else 60,
            showFps = it.optBoolean("showFps", true), directionalTouch = it.optBoolean("directionalTouch"),
            opacity = it.optInt("opacity", 75).coerceIn(15, 100), size = it.optInt("size", 100).coerceIn(70, 150),
            imageMode = runCatching { WolfImageMode.valueOf(it.optString("imageMode", "FIT")) }.getOrDefault(WolfImageMode.FIT),
            storageMode = runCatching { WolfStorageMode.valueOf(it.optString("storageMode", "AUTO")) }.getOrDefault(WolfStorageMode.AUTO))
    }
    fun save(options: WolfOptions) {
        val data = json().put("performance", options.performance.name).put("resolution", options.resolution)
            .put("smooth", options.smooth).put("maxFps", options.maxFps).put("showFps", options.showFps)
            .put("directionalTouch", options.directionalTouch).put("opacity", options.opacity).put("size", options.size)
            .put("imageMode", options.imageMode.name).put("storageMode", options.storageMode.name)
        prefs.edit().putString(key, data.toString()).apply()
    }
    fun binding(id: String, fallback: XKeycode): XKeycode = runCatching {
        XKeycode.valueOf(json().optString("key_$id", fallback.name))
    }.getOrDefault(fallback)
    fun bind(id: String, code: XKeycode) { prefs.edit().putString(key, json().put("key_$id", code.name).toString()).apply() }
    fun position(id: String, landscape: Boolean): Pair<Float, Float>? {
        val data = json().optJSONArray("position_${landscape}_$id") ?: return null
        val x = data.optDouble(0).toFloat(); val y = data.optDouble(1).toFloat()
        return if (x.isFinite() && y.isFinite()) x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f) else null
    }
    fun position(id: String, landscape: Boolean, x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val data = json().put("position_${landscape}_$id", org.json.JSONArray().put(x.coerceIn(0f, 1f)).put(y.coerceIn(0f, 1f)))
        prefs.edit().putString(key, data.toString()).apply()
    }
    fun resetControls() {
        val data = json()
        data.keys().asSequence().filter { it.startsWith("position_") || it.startsWith("key_") }.toList().forEach(data::remove)
        data.remove("size"); data.remove("opacity")
        prefs.edit().putString(key, data.toString()).apply()
    }
}
