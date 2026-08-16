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
    val language: AppLanguage = AppLanguage.ENGLISH,
    val scanOnLaunch: Boolean = true,
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val coverSize: CoverSize = CoverSize.MEDIUM,
    val gridColumns: Int = 2,
    val searchEngine: SearchEngine = SearchEngine.YANDEX,
    val coverBlurMode: CoverBlurMode = CoverBlurMode.OFF,
    val openSearchInExternalBrowser: Boolean = false,
    val backupFolderUri: String? = null,
    val joiPlayCatalogJson: String = "",
    val joiPlayCatalogFetchedAt: Long = 0L,
    val joiPlayNotifiedVersions: String = "",
    val f95SessionUser: String? = null,
    val f95SessionXfUser: String? = null,
    val f95SessionXfSession: String? = null
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AstraSettings> = context.dataStore.data.map { values ->
        AstraSettings(
            onboardingCompleted = values[ONBOARDING] ?: false,
            language = AppLanguage.fromCode(values[LANGUAGE]),
            scanOnLaunch = values[SCAN_ON_LAUNCH] ?: true,
            dynamicColor = values[DYNAMIC_COLOR] ?: true,
            themeMode = enumValueOrDefault(values[THEME], ThemeMode.SYSTEM),
            viewMode = enumValueOrDefault(values[VIEW_MODE], LibraryViewMode.GRID),
            coverSize = enumValueOrDefault(values[COVER_SIZE], CoverSize.MEDIUM),
            gridColumns = (values[GRID_COLUMNS] ?: 2).coerceIn(2, 4),
            searchEngine = SearchEngine.fromCode(values[SEARCH_ENGINE]),
            coverBlurMode = CoverBlurMode.fromCode(values[COVER_BLUR_MODE]),
            openSearchInExternalBrowser = values[OPEN_SEARCH_IN_EXTERNAL_BROWSER] ?: false,
            backupFolderUri = values[BACKUP_FOLDER_URI],
            joiPlayCatalogJson = values[JOIPLAY_CATALOG_JSON].orEmpty(),
            joiPlayCatalogFetchedAt = values[JOIPLAY_CATALOG_FETCHED_AT] ?: 0L,
            joiPlayNotifiedVersions = values[JOIPLAY_NOTIFIED_VERSIONS].orEmpty(),
            f95SessionUser = values[F95_SESSION_USER],
            f95SessionXfUser = values[F95_SESSION_XF_USER],
            f95SessionXfSession = values[F95_SESSION_XF_SESSION]
        )
    }

    suspend fun completeOnboarding() = context.dataStore.edit { it[ONBOARDING] = true }
    suspend fun setLanguage(value: AppLanguage) = context.dataStore.edit { it[LANGUAGE] = value.code }
    suspend fun setScanOnLaunch(value: Boolean) = context.dataStore.edit { it[SCAN_ON_LAUNCH] = value }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[DYNAMIC_COLOR] = value }
    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[THEME] = value.name }
    suspend fun setViewMode(value: LibraryViewMode) = context.dataStore.edit { it[VIEW_MODE] = value.name }
    suspend fun setCoverSize(value: CoverSize) = context.dataStore.edit { it[COVER_SIZE] = value.name }
    suspend fun setGridColumns(value: Int) = context.dataStore.edit { it[GRID_COLUMNS] = value.coerceIn(2, 4) }
    suspend fun setSearchEngine(value: SearchEngine) = context.dataStore.edit { it[SEARCH_ENGINE] = value.code }
    suspend fun setCoverBlurMode(value: CoverBlurMode) = context.dataStore.edit { it[COVER_BLUR_MODE] = value.code }
    suspend fun setOpenSearchInExternalBrowser(value: Boolean) = context.dataStore.edit { it[OPEN_SEARCH_IN_EXTERNAL_BROWSER] = value }
    suspend fun setBackupFolder(uri: String) = context.dataStore.edit { it[BACKUP_FOLDER_URI] = uri }
    suspend fun cacheJoiPlayCatalog(json: String, fetchedAt: Long = System.currentTimeMillis()) = context.dataStore.edit {
        it[JOIPLAY_CATALOG_JSON] = json
        it[JOIPLAY_CATALOG_FETCHED_AT] = fetchedAt
    }
    suspend fun setJoiPlayNotifiedVersions(value: String) = context.dataStore.edit { it[JOIPLAY_NOTIFIED_VERSIONS] = value }

    suspend fun setF95Session(user: String?, xfUser: String, xfSession: String) = context.dataStore.edit {
        if (user != null) it[F95_SESSION_USER] = user else it.remove(F95_SESSION_USER)
        it[F95_SESSION_XF_USER] = xfUser
        it[F95_SESSION_XF_SESSION] = xfSession
    }

    suspend fun clearF95Session() = context.dataStore.edit {
        it.remove(F95_SESSION_USER)
        it.remove(F95_SESSION_XF_USER)
        it.remove(F95_SESSION_XF_SESSION)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        private val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        private val LANGUAGE = stringPreferencesKey("language")
        private val SCAN_ON_LAUNCH = booleanPreferencesKey("scan_on_launch")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val THEME = stringPreferencesKey("theme")
        private val VIEW_MODE = stringPreferencesKey("view_mode")
        private val COVER_SIZE = stringPreferencesKey("cover_size")
        private val GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val COVER_BLUR_MODE = stringPreferencesKey("cover_blur_mode")
        private val OPEN_SEARCH_IN_EXTERNAL_BROWSER = booleanPreferencesKey("open_search_in_external_browser")
        private val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        private val JOIPLAY_CATALOG_JSON = stringPreferencesKey("joiplay_catalog_json")
        private val JOIPLAY_CATALOG_FETCHED_AT = androidx.datastore.preferences.core.longPreferencesKey("joiplay_catalog_fetched_at")
        private val JOIPLAY_NOTIFIED_VERSIONS = stringPreferencesKey("joiplay_notified_versions")
        private val F95_SESSION_USER = stringPreferencesKey("f95_session_user")
        private val F95_SESSION_XF_USER = stringPreferencesKey("f95_session_xf_user")
        private val F95_SESSION_XF_SESSION = stringPreferencesKey("f95_session_xf_session")
    }
}
