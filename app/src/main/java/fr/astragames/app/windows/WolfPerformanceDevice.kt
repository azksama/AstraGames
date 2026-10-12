package fr.astragames.app.windows

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager

/** Sampled on the IO dispatcher, once per 10 seconds; never per frame. */
internal object WolfPerformanceDevice {
    fun snapshot(context: Context): String = runCatching {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "léger"
            PowerManager.THERMAL_STATUS_MODERATE -> "modéré"
            PowerManager.THERMAL_STATUS_SEVERE -> "sévère"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
            else -> "limitation importante"
        } else "non disponible"
        "Android : thermique=$thermal ; économie d’énergie=${power.isPowerSaveMode} ; mémoire disponible=${memory.availMem / 1_048_576}Mio ; " +
            "mémoire faible=${memory.lowMemory} ; PSS Astra=${Debug.getPss() / 1024}Mio (hors processus Wine enfants)."
    }.getOrElse { "État Android indisponible : ${it.javaClass.simpleName}" }
}
