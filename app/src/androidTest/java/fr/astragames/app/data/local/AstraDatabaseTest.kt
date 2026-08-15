package fr.astragames.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AstraDatabaseTest {
    private lateinit var database: AstraDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AstraDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun gameAndFtsIndexStayInSync() = runTest {
        database.dao().upsertGame(game())
        assertEquals("g1", database.dao().searchGames("wind*").first().single().id)
    }

    @Test fun f95ImportReusesExistingTagsAndAssociatesNewOnes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = database.dao()
        dao.upsertGame(game())
        dao.upsertTag(TagEntity("existing", "Ren'Py", "ren'py", "Moteurs"))
        val repository = GameRepository(
            context,
            dao,
            RecursiveSourceScanner(context, dao, FileAccessResolver(context)),
            BackupManager(context, database)
        )

        assertEquals(2, repository.importF95Tags("g1", setOf("Ren'Py", "2d game")))
        val assigned = dao.observeTagsForGame("g1").first()

        assertEquals(setOf("Ren'Py", "2d game"), assigned.map { it.name }.toSet())
        assertEquals("existing", assigned.single { it.name == "Ren'Py" }.id)
        assertEquals("F95Zone", assigned.single { it.name == "2d game" }.groupName)
    }

    @Test fun deletedGamesCanBePersistedAndRestoredForScanning() = runTest {
        val dao = database.dao()
        val deleted = DeletedGameEntity("d1", "Astra Quest", "content://game", null, "fingerprint", "s1", 42)
        dao.upsertDeletedGame(deleted)

        assertEquals("d1", dao.findDeletedGameByDocumentUri("content://game")?.id)
        assertEquals("d1", dao.findDeletedGameByFingerprint("fingerprint")?.id)

        dao.restoreDeletedGame("d1")
        assertEquals(0, dao.observeDeletedGames().first().size)
    }

    @Test fun launchProfilesAndDetailedScanReportsArePersisted() = runTest {
        val dao = database.dao()
        dao.upsertSource(GameSourceEntity("s1", "Jeux", "content://source"))
        dao.upsertLaunchProfile(
            LaunchProfileEntity("g1", engineOverride = "RPG_MAKER_MZ", executableName = "Custom.exe")
        )
        dao.insertScanHistory(
            ScanHistoryEntity(
                id = "scan-1", sourceId = "s1", startedAt = 10, finishedAt = 20,
                gamesFound = 1, gamesAdded = 1, visitedFolders = 4
            )
        )
        dao.insertScanReportItems(
            listOf(ScanReportItemEntity("item-1", "scan-1", "s1", "g1", "RPGM/Game", "Game", "ADDED", "Nouveau jeu détecté"))
        )

        assertEquals("Custom.exe", dao.getLaunchProfile("g1")?.executableName)
        assertEquals(4, dao.getLatestScanHistory("s1")?.visitedFolders)
        assertEquals("ADDED", dao.getScanReportItems("scan-1").single().status)
    }

    private fun game() = GameEntity(
        id = "g1", title = "Wind Waiting Island", documentUri = "content://g1", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "s1",
        dateAdded = 1, lastModified = 1, fingerprint = "fp"
    )
}
