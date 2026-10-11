package fr.astragames.app.windows

import android.content.Context
import com.winlator.xserver.XKeycode
import org.json.JSONObject

internal enum class WolfPerformance(val label: String, val strongMemory: String, val bigBlock: String, val nativeFlags: String) {
    BALANCED("Équilibré", "1", "2", "0"),
    FAST("Rapide", "0", "3", "1"),
    STABLE("Stable", "2", "0", "0");
    fun environment() = mapOf("BOX64_DYNAREC_SAFEFLAGS" to if (this == FAST) "1" else "2", "BOX64_DYNAREC_STRONGMEM" to strongMemory,
        "BOX64_DYNAREC_BIGBLOCK" to bigBlock, "BOX64_DYNAREC_NATIVEFLAGS" to nativeFlags,
        "BOX64_DYNAREC_X87DOUBLE" to "1",
        // The faster profile reduces CALL/RET dispatch and Wine's server synchronization.
        // Balanced/Stable retain the conservative settings for games that need them.
        "BOX64_DYNAREC_CALLRET" to if (this == FAST) "1" else "0",
        "BOX64_DYNAREC_FORWARD" to when (this) { FAST -> "1024"; BALANCED -> "128"; STABLE -> "0" },
        "WINEESYNC" to if (this == FAST) "1" else "0",
        "WINEFSYNC" to "0")
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

internal enum class WolfRuntimeMode(val label: String) {
    AUTO("Automatique · natif compatible, sinon Winlator"),
    NATIVE("Natif Android · expérimental"),
    WINLATOR("Winlator · compatibilité Windows")
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
    val storageMode: WolfStorageMode = WolfStorageMode.AUTO,
    val pinchZoom: Boolean = false,
    val runtime: WolfRuntimeMode = WolfRuntimeMode.AUTO
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
            storageMode = runCatching { WolfStorageMode.valueOf(it.optString("storageMode", "AUTO")) }.getOrDefault(WolfStorageMode.AUTO),
            pinchZoom = it.optBoolean("pinchZoom", false),
            runtime = runCatching { WolfRuntimeMode.valueOf(it.optString("runtime", "AUTO")) }.getOrDefault(WolfRuntimeMode.AUTO))
    }
    fun save(options: WolfOptions) {
        val data = json().put("performance", options.performance.name).put("resolution", options.resolution)
            .put("smooth", options.smooth).put("maxFps", options.maxFps).put("showFps", options.showFps)
            .put("directionalTouch", options.directionalTouch).put("opacity", options.opacity).put("size", options.size)
            .put("imageMode", options.imageMode.name).put("storageMode", options.storageMode.name).put("pinchZoom", options.pinchZoom)
            .put("runtime", options.runtime.name)
        prefs.edit().putString(key, data.toString()).apply()
    }
    /** Changing only the engine preserves graphics settings, bindings and control presets. */
    fun runtime(mode: WolfRuntimeMode) { write(json().put("runtime", mode.name)) }
    fun binding(id: String, fallback: XKeycode): XKeycode = WolfKeyboard.parse(json().optString("key_$id", fallback.name)) ?: fallback
    fun bind(id: String, code: XKeycode) {
        require(WolfControlLayout.validId(id) && WolfKeyboard.valid(code))
        write(json().put("key_$id", code.name))
    }
    private fun write(data: JSONObject) { prefs.edit().putString(key, data.toString()).apply() }
    fun customControls() = WolfControlLayout.custom(json())
    fun addControl(label: String, code: XKeycode): WolfCustomControl {
        val controls = customControls()
        require(controls.size < WolfControlLayout.MAX_CUSTOM) { "24 boutons personnalisés maximum." }
        val control = WolfCustomControl("custom_" + java.util.UUID.randomUUID().toString().replace("-", ""), WolfControlLayout.label(label), code)
        require(control.label.isNotBlank() && WolfKeyboard.valid(code))
        write(json().put("customControls", WolfControlLayout.encodeCustom(controls + control)))
        return control
    }
    fun editControl(id: String, label: String, code: XKeycode) {
        val clean = WolfControlLayout.label(label)
        require(clean.isNotBlank() && WolfKeyboard.valid(code))
        val data = json()
        data.put("customControls", WolfControlLayout.encodeCustom(customControls().map { if (it.id == id) it.copy(label = clean, code = code) else it }))
        data.remove("key_$id"); write(data)
    }
    fun removeControl(id: String) {
        val data = json().put("customControls", WolfControlLayout.encodeCustom(customControls().filterNot { it.id == id }))
        data.remove("key_$id"); data.remove("position_false_$id"); data.remove("position_true_$id"); write(data)
    }
    fun controlLayout() = WolfControlLayout.normalized(json())
    fun loadControlLayout(layout: JSONObject) { val data = json(); WolfControlLayout.apply(data, layout); write(data) }
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
        WolfControlLayout.clear(data); write(data)
    }
}
