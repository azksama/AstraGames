package fr.astragames.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.astragames.app.core.model.CoverSize
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("astra_settings")

data class AstraSettings(
    val onboardingCompleted: Boolean = false,
    val scanOnLaunch: Boolean = true,
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val coverSize: CoverSize = CoverSize.MEDIUM,
    val gridColumns: Int = 2,
    val backupFolderUri: String? = null
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AstraSettings> = context.dataStore.data.map { values ->
        AstraSettings(
            onboardingCompleted = values[ONBOARDING] ?: false,
            scanOnLaunch = values[SCAN_ON_LAUNCH] ?: true,
            dynamicColor = values[DYNAMIC_COLOR] ?: true,
            themeMode = enumValueOrDefault(values[THEME], ThemeMode.SYSTEM),
            viewMode = enumValueOrDefault(values[VIEW_MODE], LibraryViewMode.GRID),
            coverSize = enumValueOrDefault(values[COVER_SIZE], CoverSize.MEDIUM),
            gridColumns = (values[GRID_COLUMNS] ?: 2).coerceIn(2, 4),
            backupFolderUri = values[BACKUP_FOLDER_URI]
        )
    }

    suspend fun completeOnboarding() = context.dataStore.edit { it[ONBOARDING] = true }
    suspend fun setScanOnLaunch(value: Boolean) = context.dataStore.edit { it[SCAN_ON_LAUNCH] = value }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[DYNAMIC_COLOR] = value }
    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[THEME] = value.name }
    suspend fun setViewMode(value: LibraryViewMode) = context.dataStore.edit { it[VIEW_MODE] = value.name }
    suspend fun setCoverSize(value: CoverSize) = context.dataStore.edit { it[COVER_SIZE] = value.name }
    suspend fun setGridColumns(value: Int) = context.dataStore.edit { it[GRID_COLUMNS] = value.coerceIn(2, 4) }
    suspend fun setBackupFolder(uri: String) = context.dataStore.edit { it[BACKUP_FOLDER_URI] = uri }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        private val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        private val SCAN_ON_LAUNCH = booleanPreferencesKey("scan_on_launch")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val THEME = stringPreferencesKey("theme")
        private val VIEW_MODE = stringPreferencesKey("view_mode")
        private val COVER_SIZE = stringPreferencesKey("cover_size")
        private val GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
    }
}
