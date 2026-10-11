package fr.astragames.app.wolfnative

import android.content.Context

/** Desktop window requests survive a process restart without modifying Game.ini or a save. */
internal class WolfNativeLaunchPreferences(context: Context, id: String) {
    private val preferences = context.applicationContext.getSharedPreferences("wolf_native_launch", Context.MODE_PRIVATE)
    private val key = WolfNativeSaveStore.digest(id.toByteArray(Charsets.UTF_8))

    val nextWindowScalePermille: Int
        get() = preferences.getInt(key, 0).coerceAtLeast(0)

    fun setNextWindowScale(permille: Int) {
        preferences.edit().putInt(key, permille.coerceAtLeast(0)).apply()
    }

    fun clear() { preferences.edit().remove(key).apply() }
}
