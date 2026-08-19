package fr.astragames.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.repository.SaveConflictStrategy
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun textualTagListReusesExistingTagsAndCreatesMissingOnes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = database.dao()
        dao.upsertGame(game())
        dao.upsertTag(TagEntity("adventure", "Adventure", "adventure", "Genres"))
        val repository = GameRepository(
            context,
            dao,
            RecursiveSourceScanner(context, dao, FileAccessResolver(context)),
            BackupManager(context, database)
        )

        assertEquals(3, repository.importTextTags("g1", "[Adventure] [Fantasy] [female protagonist]"))
        val assigned = dao.observeTagsForGame("g1").first()

        assertEquals(setOf("Adventure", "Fantasy", "female protagonist"), assigned.map { it.name }.toSet())
        assertEquals("adventure", assigned.single { it.name == "Adventure" }.id)
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

    @Test fun smartCollectionsPlayStatsAndIgnoredDuplicatesArePersisted() = runTest {
        val dao = database.dao()
        dao.replaceCollection(
            CollectionEntity("c1", "Mes RPG favoris", matchMode = "ALL"),
            listOf(
                CollectionRuleEntity("r1", "c1", "ENGINE", "IS", "RPG_MAKER_MV"),
                CollectionRuleEntity("r2", "c1", "FAVORITE", "IS", "true")
            )
        )
        dao.insertPlaySession(PlaySessionEntity("p1", "g1", 100, endedAt = 5_100, durationMs = 5_000))
        dao.ignoreDuplicateGroup(IgnoredDuplicateGroupEntity("fingerprint:fp", 200))

        assertEquals("ALL", dao.observeCollections().first().single().matchMode)
        assertEquals(2, dao.observeCollectionRules().first().size)
        assertEquals(5_000, dao.observePlayStats().first().single().totalDurationMs)
        assertEquals("fingerprint:fp", dao.observeIgnoredDuplicateGroups().first().single().groupKey)
    }

    @Test fun duplicateMergeKeepsTheChosenRecordAndCombinesItsHistory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = database.dao()
        dao.upsertGame(game().copy(description = null, playCount = 2))
        dao.upsertGame(
            game().copy(
                id = "g2", title = "Wind Waiting Island copy", documentUri = "content://g2",
                description = "Description importée", coverUri = "content://cover-g2", favorite = true,
                playCount = 3, fingerprint = "fp"
            )
        )
        dao.upsertTag(TagEntity("t1", "RPG", "rpg"))
        dao.upsertTag(TagEntity("t2", "Aventure", "aventure"))
        dao.replaceGameTags("g1", setOf("t1"))
        dao.replaceGameTags("g2", setOf("t2"))
        dao.insertPlaySession(PlaySessionEntity("p1", "g1", 100, 1_100, 1_000))
        dao.insertPlaySession(PlaySessionEntity("p2", "g2", 200, 2_200, 2_000))
        val repository = GameRepository(
            context, dao, RecursiveSourceScanner(context, dao, FileAccessResolver(context)), BackupManager(context, database)
        )

        repository.mergeDuplicate("g1", "g2", migrateSaves = false, SaveConflictStrategy.KEEP_PRIMARY, deleteSecondaryFiles = false)

        val merged = dao.getGame("g1")!!
        assertEquals("Description importée", merged.description)
        assertEquals("content://cover-g2", merged.coverUri)
        assertEquals(true, merged.favorite)
        assertEquals(5, merged.playCount)
        assertEquals(setOf("t1", "t2"), dao.getTagIdsForGame("g1").toSet())
        assertEquals(3_000, dao.observePlayStats().first().single().totalDurationMs)
        assertEquals("DUPLICATE_MERGED", dao.observeDeletedGames().first().single().reason)
        assertNull(dao.getGame("g2"))
    }

    @Test fun saveAndModTablesExistAfterCreation() = runTest {
        val dao = database.dao()
        dao.upsertSaveLocation(
            GameSaveLocationEntity("loc1", "g1", "content://saves", "RENPY", "saves", true, true, 1)
        )
        dao.insertSaveBackup(SaveBackupEntity("b1", "g1", "content://slot1", "slot1.save", "content://backup", 2, 10))
        dao.upsertMod(ModEntity("m1", "example.mod", "Example", "1.0", "A", "D", "RENPY", "content://mod", true, "OVERLAY", "game", "files", 3))
        dao.upsertInstallation(ModInstallationEntity("i1", "m1", "g1", 4, "1.0", "OVERLAY", "INSTALLED"))
        dao.insertInstalledFiles(listOf(ModInstalledFileEntity("f1", "i1", "content://file", "ADDED", null, "abc", null)))
        assertEquals("saves", dao.getSaveLocations("g1").single().displayName)
        assertEquals("slot1.save", dao.getSaveBackupsForSource("content://slot1").single().sourceName)
        assertEquals("Example", dao.getMods().single().name)
        assertEquals("ADDED", dao.getInstalledFiles("i1").single().action)
    }

    private fun game() = GameEntity(
        id = "g1", title = "Wind Waiting Island", documentUri = "content://g1", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "s1",
        dateAdded = 1, lastModified = 1, fingerprint = "fp"
    )
}
