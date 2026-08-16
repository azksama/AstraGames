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
import fr.astragames.app.launcher.JoiPlayLauncher
import fr.astragames.app.launcher.JoiPlayCatalogProvider
import fr.astragames.app.settings.SettingsRepository
import fr.astragames.app.worker.GameUpdatesWorker
import fr.astragames.app.worker.JoiPlayUpdateWorker

class AstraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AstraDatabase.create(this)
        val resolver = FileAccessResolver(this)
        val scanner = RecursiveSourceScanner(this, database.dao(), resolver)
        container = AppContainer(
            repository = GameRepository(this, database.dao(), scanner, BackupManager(this, database)),
            settings = SettingsRepository(this),
            launcher = JoiPlayLauncher(),
            covers = SearchCoverProvider(this),
            f95Zone = F95ZoneProvider(),
            vndb = VndbProvider(),
            joiPlayCatalog = JoiPlayCatalogProvider()
        )
        JoiPlayUpdateWorker.schedule(this)
        GameUpdatesWorker.schedule(this)
    }
}

data class AppContainer(
    val repository: GameRepository,
    val settings: SettingsRepository,
    val launcher: JoiPlayLauncher,
    val covers: SearchCoverProvider,
    val f95Zone: F95ZoneProvider,
    val vndb: VndbProvider,
    val joiPlayCatalog: JoiPlayCatalogProvider
)
