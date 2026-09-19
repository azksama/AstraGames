package fr.astragames.app.ui

import fr.astragames.app.core.runCatchingCancellable
import android.net.Uri
import fr.astragames.app.data.saves.GameSave
import fr.astragames.app.data.saves.SaveEntry
import fr.astragames.app.data.saves.SaveEdit
import fr.astragames.app.data.saves.SaveCodec
import fr.astragames.app.data.saves.readBytes
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.data.mods.ModCatalogItem
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.AstraApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GameToolsController(
    private val app: AstraApplication,
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<UiEvent>,
    private val beforeExternalPicker: () -> Unit
) {
    private val repository = app.container.repository
    private val settingsRepository = app.container.settings
    private val saveManager = app.container.saveManager
    private val modsManager = app.container.modsManager
    private val dao = app.container.dao
    val pickSaveFolderRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pickModsRootRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pickModZipRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val mutableSaves = MutableStateFlow<List<GameSave>>(emptyList())
    val gameSaves: StateFlow<List<GameSave>> = mutableSaves
    private val mutableSavesLoading = MutableStateFlow(false)
    val savesLoading = mutableSavesLoading.asStateFlow()
    private val mutableSavesError = MutableStateFlow<String?>(null)
    val savesError = mutableSavesError.asStateFlow()
    private val mutableSaveEntries = MutableStateFlow<List<SaveEntry>>(emptyList())
    val saveEntries: StateFlow<List<SaveEntry>> = mutableSaveEntries
    private val mutableMods = MutableStateFlow<List<ModCatalogItem>>(emptyList())
    val modsCatalog: StateFlow<List<ModCatalogItem>> = mutableMods
    private val mutableModsLoading = MutableStateFlow(false)
    val modsLoading = mutableModsLoading.asStateFlow()
    private val mutableModsError = MutableStateFlow<String?>(null)
    val modsError = mutableModsError.asStateFlow()
    private val mutableToolsBusy = MutableStateFlow(false)
    val toolsBusy: StateFlow<Boolean> = mutableToolsBusy
    private val mutableToolsError = MutableStateFlow<String?>(null)
    val toolsError: StateFlow<String?> = mutableToolsError
    private var loadedSaveHash: Pair<String, String>? = null
    private var activeModsGameId: String? = null
    private var saveLoadJob: Job? = null
    private var savesListJob: Job? = null
    private var modsListJob: Job? = null
    private var saveLoadGeneration = 0
    private var savesGeneration = 0
    private var modsGeneration = 0
    private val pendingSaveFolderGameId = MutableStateFlow<String?>(null)

    fun observeSaveLocations(gameId: String) = saveManager.observeLocations(gameId)

    fun observeSaveBackups(gameId: String) = saveManager.observeBackups(gameId)

    fun detectSaveLocations(gameId: String) = scope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        runCatchingCancellable { withContext(Dispatchers.IO) { saveManager.detectLocations(game) } }
            .onSuccess { loadSaves(gameId); events.emit(UiEvent.Message(it.size.toString() + " emplacement(s) de sauvegarde")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Detection impossible")) }
    }

    fun requestSaveFolder(gameId: String) {
        pendingSaveFolderGameId.value = gameId
        beforeExternalPicker()
        pickSaveFolderRequests.tryEmit(gameId)
    }

    fun addSaveLocation(uri: Uri) = scope.launch {
        val gameId = pendingSaveFolderGameId.value ?: return@launch
        val game = repository.getGame(gameId) ?: return@launch
        runCatchingCancellable { withContext(Dispatchers.IO) { saveManager.addLocation(game, uri, uri.lastPathSegment.orEmpty()) } }
            .onSuccess { pendingSaveFolderGameId.value = null; loadSaves(gameId); events.emit(UiEvent.Message("Dossier de sauvegarde ajoute")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Ajout impossible")) }
    }

    fun removeSaveLocation(id: String) = scope.launch { saveManager.removeLocation(id) }

    fun loadSaves(gameId: String) {
        val generation = ++savesGeneration
        savesListJob?.cancel()
        mutableSaves.value = emptyList()
        mutableSavesLoading.value = true
        mutableSavesError.value = null
        savesListJob = scope.launch {
            try {
                val game = repository.getGame(gameId) ?: return@launch
                runCatchingCancellable { withContext(Dispatchers.IO) { saveManager.listSaves(game) } }
                    .onSuccess { if (generation == savesGeneration) mutableSaves.value = it }
                    .onFailure {
                        if (generation == savesGeneration) mutableSavesError.value = it.message ?: "Lecture des sauvegardes impossible"
                    }
            } finally {
                if (generation == savesGeneration) mutableSavesLoading.value = false
            }
        }
    }

    fun loadSaveEntries(save: GameSave) {
        val generation = ++saveLoadGeneration
        saveLoadJob?.cancel()
        mutableSaveEntries.value = emptyList()
        loadedSaveHash = null
        saveLoadJob = scope.launch {
            mutableToolsBusy.value = true
            mutableToolsError.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = readBytes(app, save.uri)
                    SaveCodec.entries(SaveCodec.read(save.engine, bytes)) to
                        sha256Hex(bytes)
                }
                mutableSaveEntries.value = result.first
                loadedSaveHash = save.uri to result.second
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableToolsError.value = error.message ?: "Lecture impossible" }
            finally { if (generation == saveLoadGeneration) mutableToolsBusy.value = false }
        }
    }

    fun applySaveEdits(gameId: String, save: GameSave, edits: List<SaveEdit>, onSaved: () -> Unit = {}) = scope.launch {
        if (mutableToolsBusy.value) return@launch
        val game = repository.getGame(gameId) ?: return@launch
        val expected = loadedSaveHash?.takeIf { it.first == save.uri }?.second ?: return@launch
        mutableToolsBusy.value = true
        mutableToolsError.value = null
        try {
            withContext(Dispatchers.IO) { saveManager.writeSave(game, save, edits, expected) }
            events.emit(UiEvent.Message("Sauvegarde enregistree avec backup"))
            onSaved()
            loadSaves(gameId)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableToolsError.value = error.message ?: "Ecriture impossible" }
        finally { mutableToolsBusy.value = false }
    }

    fun restoreSaveBackup(backup: SaveBackupEntity) = scope.launch {
        runCatchingCancellable { withContext(Dispatchers.IO) { saveManager.restoreBackup(backup) } }
            .onSuccess { events.emit(UiEvent.Message("Backup restaure")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Restauration impossible")) }
    }

    fun deleteSaveBackup(backup: SaveBackupEntity) = scope.launch {
        runCatchingCancellable { withContext(Dispatchers.IO) { saveManager.deleteBackup(backup) } }
            .onSuccess { events.emit(UiEvent.Message("Backup supprime")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Suppression impossible")) }
    }

    fun setSaveEditorFavorites(value: String) = scope.launch { settingsRepository.setSaveEditorFavorites(value) }

    fun requestModsRoot() {
        beforeExternalPicker()
        pickModsRootRequests.tryEmit(Unit)
    }

    fun setModsRoot(uri: Uri) = scope.launch {
        runCatchingCancellable { modsManager.scanRepository(uri.toString()).also { settingsRepository.setModsRoot(uri.toString()) } }
            .onSuccess { activeModsGameId?.let(::loadMods); events.emit(UiEvent.Message(it.toString() + " mod(s) detecte(s)")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Scan des mods impossible")) }
    }

    fun scanMods() = scope.launch {
        val root = settingsRepository.settings.first().modsRootUri
        if (root == null) {
            events.emit(UiEvent.Message("Choisissez d abord le dossier Astra/Mods"))
            return@launch
        }
        runCatchingCancellable { modsManager.scanRepository(root) }
            .onSuccess { activeModsGameId?.let(::loadMods); events.emit(UiEvent.Message(it.toString() + " mod(s) detecte(s)")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Scan des mods impossible")) }
    }

    fun loadMods(gameId: String) {
        val generation = ++modsGeneration
        modsListJob?.cancel()
        activeModsGameId = gameId
        mutableMods.value = emptyList()
        mutableModsLoading.value = true
        mutableModsError.value = null
        modsListJob = scope.launch {
            try {
                val game = repository.getGame(gameId) ?: return@launch
                val root = settingsRepository.settings.first().modsRootUri
                if (root != null) runCatchingCancellable { modsManager.scanRepository(root) }
                    .onFailure {
                        if (generation == modsGeneration) mutableModsError.value = it.message ?: "Dépôt inaccessible"
                    }
                runCatchingCancellable { modsManager.catalogFor(game) }
                    .onSuccess { if (generation == modsGeneration) mutableMods.value = it }
                    .onFailure {
                        if (generation == modsGeneration) mutableModsError.value = it.message ?: "Catalogue de mods illisible"
                    }
            } finally {
                if (generation == modsGeneration) mutableModsLoading.value = false
            }
        }
    }

    fun requestModZipImport(gameId: String? = null) {
        if (gameId != null) activeModsGameId = gameId
        beforeExternalPicker()
        pickModZipRequests.tryEmit(Unit)
    }

    fun importModZip(uri: Uri, engine: String?, replaceExisting: Boolean) = scope.launch {
        val root = settingsRepository.settings.first().modsRootUri
        if (root == null) {
            events.emit(UiEvent.Message("Choisissez d abord le dossier Astra/Mods"))
            return@launch
        }
        if (mutableToolsBusy.value) return@launch
        mutableToolsBusy.value = true
        try {
            runCatchingCancellable { modsManager.importZip(uri, root, engine ?: activeModsGameId?.let { repository.getGame(it)?.engine }, replaceExisting) }
                .onSuccess { activeModsGameId?.let(::loadMods); events.emit(UiEvent.Message("Mod importe : " + it.name)) }
                .onFailure { events.emit(UiEvent.Message(it.message ?: "Import impossible")) }
        } finally { mutableToolsBusy.value = false }
    }

    fun installMod(gameId: String, modId: String) = scope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        val mod = dao.getMods().firstOrNull { it.id == modId } ?: return@launch
        if (mutableToolsBusy.value) return@launch
        mutableToolsBusy.value = true
        try {
            runCatchingCancellable { modsManager.install(game, mod) }
                .onSuccess {
                    events.emit(UiEvent.Message("Mod installe"))
                    loadMods(gameId)
                }
                .onFailure { events.emit(UiEvent.Message(it.message ?: "Installation impossible")) }
        } finally { mutableToolsBusy.value = false }
    }

    fun uninstallMod(gameId: String, installationId: String, force: Boolean = false) = scope.launch {
        val installation = dao.observeInstallationsForGame(gameId).first().firstOrNull { it.id == installationId } ?: return@launch
        if (mutableToolsBusy.value) return@launch
        mutableToolsBusy.value = true
        try {
            runCatchingCancellable { modsManager.uninstall(installation, force) }
                .onSuccess { warnings ->
                    if (warnings.isNotEmpty()) events.emit(UiEvent.Message("Des fichiers ont change depuis l installation"))
                    else {
                        events.emit(UiEvent.Message("Mod desinstalle"))
                        loadMods(gameId)
                    }
                }
                .onFailure { events.emit(UiEvent.Message(it.message ?: "Desinstallation impossible")) }
        } finally { mutableToolsBusy.value = false }
    }

}
