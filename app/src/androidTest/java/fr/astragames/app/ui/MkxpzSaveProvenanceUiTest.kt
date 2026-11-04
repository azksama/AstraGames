package fr.astragames.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.readBytes
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.mkxpz.MkxpzRuntime
import fr.astragames.app.mkxpz.MkxpzStorage
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercise production sheets and their controller callbacks against separate SAF/private files. */
@RunWith(AndroidJUnit4::class)
class MkxpzSaveProvenanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val fixtureId = "mkxpz-provenance-ui-${UUID.randomUUID()}"
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
    private val previousLanguage = AppLocalizer.language
    private val selection = mutableStateOf<GameSave?>(null)
    private val showBackups = mutableStateOf(false)
    private lateinit var vm: AstraViewModel
    private lateinit var game: GameEntity
    private lateinit var profile: LaunchProfileEntity
    private lateinit var storage: MkxpzStorage
    private lateinit var sourceUri: String
    private lateinit var privateUri: String
    private val originalSource = saveBytes(11)
    private val originalPrivate = saveBytes(42)

    @Before fun importAuthoredSafFixture() = runBlocking {
        val result = requireNotNull(app.contentResolver.call(provider, "fixture", fixtureId, null))
        val root = requireNotNull(documentDir(app, Uri.parse(requireNotNull(result.getString("uri")))))
        val folder = requireNotNull(root.findFile("game"))
        val ini = requireNotNull(folder.createFile("application/octet-stream", "Game.ini"))
        requireNotNull(app.contentResolver.openOutputStream(ini.uri, "wt")).use {
            it.write("[Game]\nLibrary=RGSS104E.dll\nScripts=Data/Scripts.rxdata\nTitle=Authored UI fixture\n".toByteArray())
        }
        val source = requireNotNull(folder.createFile("application/octet-stream", "Save01.rxdata"))
        sourceUri = source.uri.toString()
        requireNotNull(app.contentResolver.openOutputStream(source.uri, "wt")).use { it.write(originalSource) }
        game = GameEntity(
            id = fixtureId, title = "MKXP-Z save provenance UI fixture", documentUri = folder.uri.toString(),
            physicalPath = null, executableName = "Game.exe", engine = "UNKNOWN", launcher = "JOIPLAY",
            sourceId = fixtureId, dateAdded = 1, lastModified = 1, fingerprint = fixtureId
        )
        profile = LaunchProfileEntity(gameId = fixtureId, launcherType = MkxpzRuntime.LAUNCHER,
            engineOverride = "RPG_MAKER_XP")
        app.container.dao.upsertGame(game)
        app.container.dao.upsertLaunchProfile(profile)
        storage = MkxpzStorage(app, fixtureId)
        storage.prepare(game, profile, 1)
        val privateFile = File(storage.gameDirectory, "Save01.rxdata")
        assertArrayEquals(originalSource, privateFile.readBytes())
        privateFile.writeBytes(originalPrivate)
        privateUri = Uri.fromFile(privateFile).toString()
        compose.runOnUiThread { vm = AstraViewModel(app); store.put(fixtureId, vm) }
        AppLocalizer.language = AppLanguage.FRENCH
    }

    @After fun removeOnlyAuthoredData() {
        compose.runOnUiThread { store.clear() }
        try {
            runBlocking { app.container.dao.deleteGameCompletely(fixtureId) }
            if (::storage.isInitialized) deleteOwnedDirectory(storage.directory)
            deleteOwnedDirectory(File(app.filesDir, "save-backups/" + sha256Hex(fixtureId.toByteArray())))
        } finally {
            app.contentResolver.call(provider, "removeFixture", fixtureId, null)
            AppLocalizer.language = previousLanguage
        }
    }

    private fun deleteOwnedDirectory(directory: File) {
        check(directory.canonicalFile.toPath().startsWith(app.filesDir.canonicalFile.toPath()))
        check(directory.canonicalFile != app.filesDir.canonicalFile)
        check(!directory.exists() || directory.deleteRecursively())
    }

    private fun saveBytes(value: Long) = Marshal.dump(MarshalValue.ObjectValue(
        MarshalValue.SymbolValue("Game_Party"),
        linkedMapOf(MarshalValue.SymbolValue("@gold") to MarshalValue.IntValue(value))
    ))

    private fun showProductionSheets() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme {
                    val selected = selection.value
                    when {
                        selected != null -> SaveEditorDialog(game, selected, vm) { selection.value = null }
                        showBackups.value -> SaveBackupsSheet(game, vm) { showBackups.value = false }
                        else -> SaveListSheet(game, vm, onEdit = { selection.value = it }, onDismiss = {})
                    }
                }
            }
        }
        awaitList()
    }

    private fun awaitList() {
        compose.waitUntil(10_000) { !vm.tools.savesLoading.value && vm.tools.gameSaves.value.size == 2 }
        compose.waitForIdle()
        assertNull(vm.tools.savesError.value)
        assertEquals(setOf(sourceUri, privateUri), vm.tools.gameSaves.value.map { it.uri }.toSet())
    }

    private fun assertRowOrigin(uri: String, label: String) {
        val tag = "save-$uri"
        scrollToRow(tag)
        assertRowText(tag, "Save01.rxdata")
        assertRowText(tag, label)
    }

    private fun expandSheet() {
        val expand = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand), useUnmergedTree = true)
        if (expand.fetchSemanticsNodes().isNotEmpty()) {
            expand.onFirst().performSemanticsAction(SemanticsActions.Expand) { assertTrue(it()) }
            compose.waitForIdle()
        }
    }

    private fun scrollToRow(tag: String) {
        expandSheet()
        // Material's sheet and the LazyColumn are separate scroll/expansion containers.
        // Scroll the list, not a precomposed row whose provisional bounds may look visible.
        compose.onNode(hasScrollToIndexAction(), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertRowText(tag: String, text: String) {
        val matcher = hasText(text) and hasAnyAncestor(hasTestTag(tag))
        compose.waitUntil(10_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertBackupOrigin(backup: SaveBackupEntity, label: String) {
        val tag = "backup-${backup.id}"
        scrollToRow(tag)
        assertRowText(tag, backup.sourceName)
        assertRowText(tag, label)
    }

    private fun openAndEdit(uri: String, label: String, original: String, replacement: String, captureName: String) {
        assertRowOrigin(uri, label)
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Éditer")) and hasAnyAncestor(hasTestTag("save-$uri")),
            useUnmergedTree = true
        ).performClick()
        compose.waitUntil(10_000) { !vm.tools.toolsBusy.value && vm.tools.saveRevision.value?.first == uri }
        compose.waitForIdle()
        assertNull(vm.tools.toolsError.value)
        compose.runOnIdle { assertEquals("The selected row must open its own URI", uri, selection.value?.uri) }
        compose.onNodeWithText(label).assertIsDisplayed()
        if (uri == sourceUri) compose.onNodeWithText(
            "Cette édition concerne le dossier source. La partie MKXP-Z conserve sa copie."
        ).assertIsDisplayed()
        val money = compose.onNode(hasSetTextAction() and hasText("Argent"))
        money.assertTextContains(original)
        capture(captureName)
        money.performTextReplacement(replacement)
        closeSoftKeyboard()
        compose.onNodeWithText("Enregistrer").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.tools.toolsBusy.value && vm.tools.saveRevision.value == null }
        compose.waitForIdle()
        assertNull(vm.tools.toolsError.value)
        compose.runOnIdle { assertNull(selection.value) }
        awaitList()
    }

    private fun assertBytes(source: ByteArray, privateCopy: ByteArray) {
        assertArrayEquals(source, readBytes(app, sourceUri))
        assertArrayEquals(privateCopy, readBytes(app, privateUri))
    }

    private fun restoreThroughSheet(backup: SaveBackupEntity, label: String, expected: ByteArray) {
        assertBackupOrigin(backup, label)
        compose.onNode(
            hasContentDescription("Restaurer") and hasAnyAncestor(hasTestTag("backup-${backup.id}")),
            useUnmergedTree = true
        ).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Restaurer cette sauvegarde ?").assertIsDisplayed()
        val confirmation = isDialog() and hasAnyDescendant(hasText("Restaurer cette sauvegarde ?"))
        compose.onNode(hasText(label) and hasAnyAncestor(confirmation), useUnmergedTree = true)
            .assertIsDisplayed()
        if (backup.sourceUri == sourceUri) capture("restore-source")
        compose.onNodeWithText("Restaurer").performClick()
        compose.waitUntil(10_000) {
            !vm.tools.toolsBusy.value && readBytes(app, backup.sourceUri).contentEquals(expected)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Android's window fade is outside Compose's test clock.
        android.os.SystemClock.sleep(350)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val output = File(requireNotNull(app.getExternalFilesDir(null)), "mkxpz-save-provenance-$name.png")
        output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    @Test fun sameNamedSourceAndPrivateRowsOpenEditAndRestoreTheirOwnFiles() {
        try {
            exerciseProvenance()
        } catch (failure: Throwable) {
            runCatching { captureFailure(failure) }
            throw failure
        }
    }

    private fun captureFailure(failure: Throwable) {
        val directory = requireNotNull(app.getExternalFilesDir(null))
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "mkxpz-save-provenance-failure.txt").writeText(buildString {
            appendLine(app.resources.configuration)
            appendLine(failure.stackTraceToString())
            appendLine("showBackups=${showBackups.value}; selected=${selection.value?.uri}")
            appendLine("loading=${vm.tools.savesLoading.value}; busy=${vm.tools.toolsBusy.value}")
            appendLine("savesError=${vm.tools.savesError.value}; toolsError=${vm.tools.toolsError.value}")
            appendLine("saveUris=${vm.tools.gameSaves.value.map { it.uri }}")
            roots.fetchSemanticsNodes().indices.forEach { appendLine(roots[it].printToString()) }
        })
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "mkxpz-save-provenance-failure.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun exerciseProvenance() {
        showProductionSheets()
        assertRowOrigin(privateUri, PRIVATE_ACTIVE)
        assertRowOrigin(sourceUri, SOURCE_UNUSED)
        // Both exact names were asserted visibly in their own rows, even on a short viewport.
        assertEquals(listOf("Save01.rxdata", "Save01.rxdata"), vm.tools.gameSaves.value.map { it.name })
        capture("list")

        openAndEdit(sourceUri, SOURCE_UNUSED, "11", "21", "editor-source")
        assertBytes(saveBytes(21), originalPrivate)
        openAndEdit(privateUri, PRIVATE_ACTIVE, "42", "84", "editor-private")
        assertBytes(saveBytes(21), saveBytes(84))
        val sourceBackup = runBlocking { app.container.dao.getSaveBackupsForSource(sourceUri).single() }
        val privateBackup = runBlocking { app.container.dao.getSaveBackupsForSource(privateUri).single() }
        assertEquals("Save01.rxdata", sourceBackup.sourceName)
        assertEquals(sourceBackup.sourceName, privateBackup.sourceName)
        assertArrayEquals(originalSource, readBytes(app, sourceBackup.backupUri))
        assertArrayEquals(originalPrivate, readBytes(app, privateBackup.backupUri))

        compose.runOnUiThread { showBackups.value = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Sauvegardes de sécurité").fetchSemanticsNodes().isNotEmpty()
        }
        expandSheet()
        compose.waitUntil(10_000) {
            // One materialized row establishes that the sheet has received Room's data.
            // Requiring both rows at once depends on viewport height, not correctness.
            compose.onAllNodes(hasTestTag("backup-${sourceBackup.id}") or hasTestTag("backup-${privateBackup.id}"),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertBackupOrigin(privateBackup, PRIVATE_ACTIVE)
        capture("backup-private")
        assertBackupOrigin(sourceBackup, SOURCE_UNUSED)
        capture("backup-source")
        capture("backups")
        restoreThroughSheet(sourceBackup, SOURCE_UNUSED, originalSource)
        assertBytes(originalSource, saveBytes(84))
        restoreThroughSheet(privateBackup, PRIVATE_ACTIVE, originalPrivate)
        assertBytes(originalSource, originalPrivate)

        // Keep the same sheet open: Room/profile changes must update origin descriptions live.
        runBlocking { app.container.dao.upsertLaunchProfile(profile.copy(launcherType = "JOIPLAY")) }
        assertBackupOrigin(privateBackup, "Copie Astra · inactive pour ce lanceur")
        capture("backup-private-joiplay")
        assertBackupOrigin(sourceBackup, "Dossier source · usage selon le lanceur externe")
        capture("backups-joiplay")
        assertEquals("UNKNOWN", runBlocking { app.container.dao.getGame(game.id)?.engine })
        assertEquals("JOIPLAY", runBlocking { app.container.dao.getGame(game.id)?.launcher })
        assertBytes(originalSource, originalPrivate)
    }

    companion object {
        private const val PRIVATE_ACTIVE = "Copie Astra active (MKXP-Z)"
        private const val SOURCE_UNUSED = "Dossier source · non utilisé par MKXP-Z"
    }
}
