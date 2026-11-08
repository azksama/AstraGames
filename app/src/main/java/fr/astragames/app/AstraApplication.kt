package fr.astragames.app

import android.app.Application
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.core.metadata.SearchCoverProvider
import fr.astragames.app.core.metadata.F95ZoneProvider
import fr.astragames.app.core.metadata.VndbProvider
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import fr.astragames.app.data.mods.ModsManager
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.data.saves.SaveManager
import fr.astragames.app.launcher.JoiPlayLauncher
import fr.astragames.app.launcher.JoiPlayCatalogProvider
import fr.astragames.app.settings.SettingsRepository
import fr.astragames.app.worker.GameUpdatesWorker
import fr.astragames.app.worker.JoiPlayUpdateWorker

class AstraApplication : Application(), coil3.SingletonImageLoader.Factory {
    internal val updates by lazy { fr.astragames.app.updates.AppUpdateManager(this) }
    val visibility = fr.astragames.app.core.security.AppVisibility()
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val process = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
            .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (process == "$packageName:mkxpz") return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) = visibility.started()
            override fun onActivityStopped(activity: android.app.Activity) = visibility.stopped(activity.isChangingConfigurations)
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        val database = AstraDatabase.create(this)
        val resolver = FileAccessResolver(this)
        val scanner = RecursiveSourceScanner(this, database.dao(), resolver)
        val finder = SaveFinder(this)
        val f95Zone = F95ZoneProvider { url -> android.webkit.CookieManager.getInstance().getCookie(url) }
        container = AppContainer(
            repository = GameRepository(this, database.dao(), scanner, BackupManager(this, database)),
            settings = SettingsRepository(this),
            launcher = JoiPlayLauncher(),
            covers = SearchCoverProvider(this, f95Zone),
            f95Zone = f95Zone,
            ryuugames = fr.astragames.app.core.metadata.RyuugamesProvider(),
            vndb = VndbProvider(),
            joiPlayCatalog = JoiPlayCatalogProvider(),
            saveManager = SaveManager(this, database.dao(), finder),
            modsManager = ModsManager(this, database.dao()),
            dao = database.dao()
        )
        JoiPlayUpdateWorker.schedule(this)
        fr.astragames.app.updates.AppUpdateWorker.schedule(this, updates.state.value.automatic, updates.state.value.wifiOnly)

    }
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context).components {
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { container.covers.imageClient }))
        }.build()
}

data class AppContainer(
    val repository: GameRepository,
    val settings: SettingsRepository,
    val launcher: JoiPlayLauncher,
    val covers: SearchCoverProvider,
    val f95Zone: F95ZoneProvider,
    val ryuugames: fr.astragames.app.core.metadata.RyuugamesProvider,
    val vndb: VndbProvider,
    val joiPlayCatalog: JoiPlayCatalogProvider,
    val saveManager: SaveManager,
    val modsManager: ModsManager,
    val dao: fr.astragames.app.data.local.AstraDao
)
