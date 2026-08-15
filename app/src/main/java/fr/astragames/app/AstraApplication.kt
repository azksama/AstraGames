package fr.astragames.app

import android.app.Application
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.core.metadata.GoogleCoverProvider
import fr.astragames.app.core.metadata.F95ZoneProvider
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import fr.astragames.app.launcher.JoiPlayLauncher
import fr.astragames.app.settings.SettingsRepository

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
            covers = GoogleCoverProvider(this),
            f95Zone = F95ZoneProvider()
        )
    }
}

data class AppContainer(
    val repository: GameRepository,
    val settings: SettingsRepository,
    val launcher: JoiPlayLauncher,
    val covers: GoogleCoverProvider,
    val f95Zone: F95ZoneProvider
)
