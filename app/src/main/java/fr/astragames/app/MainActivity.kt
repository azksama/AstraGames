@file:Suppress("DEPRECATION")

package fr.astragames.app

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import fr.astragames.app.ui.AstraApp
import fr.astragames.app.ui.AstraViewModel
import fr.astragames.app.ui.AppUpdateScreen
import fr.astragames.app.ui.LocalAppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fr.astragames.app.windows.WolfDiagnostics
import fr.astragames.app.ui.WolfDiagnosticsDialog
import java.io.File

class MainActivity : FragmentActivity() {
    private val viewModel by viewModels<AstraViewModel>()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var coverGameId: String? = null
    private var recoveredWolfReport by mutableStateOf<File?>(null)
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val gameId = coverGameId
        if (uri != null && gameId != null) launchCrop(gameId, uri) else coverGameId = null
    }
    private val sourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addSource)
    }
    private val tagPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importTags)
    }
    private val backupFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::configureBackupFolder)
    }
    private val backupRestorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreBackup)
    }
    private val saveFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel.tools::addSaveLocation)
    }
    private val modsRootPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel.tools::setModsRoot)
    }
    private val modZipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.tools.importModZip(it, null, false) }
    }
    private val coverCropper = registerForActivityResult(CropImageContract()) { result ->
        val gameId = coverGameId
        if (result.isSuccessful && gameId != null) result.uriContent?.let { viewModel.setCover(gameId, it) }
        coverGameId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coverGameId = savedInstanceState?.getString("coverGameId")
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cropRequests.collect { request -> launchCrop(request.gameId, request.source) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openFolderRequests.collect(::openDocumentFolder)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notificationPermissionRequests.collect {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tools.pickSaveFolderRequests.collect { saveFolderPicker.launch(null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tools.pickModsRootRequests.collect { modsRootPicker.launch(null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tools.pickModZipRequests.collect {
                    modZipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.biometricUnlockRequests.collect { promptBiometric() }
            }
        }
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val locked by viewModel.isLocked.collectAsStateWithLifecycle()
            var showAppUpdates by rememberSaveable { mutableStateOf(intent.getBooleanExtra("openAppUpdates", false)) }
            AstraTheme(hue = state.settings.accentHue) {
                AstraApp(
                    state = state,
                    viewModel = viewModel,
                    onPickSource = { sourcePicker.launch(null) },
                    onPickTags = { tagPicker.launch(arrayOf("text/plain", "text/csv", "application/json")) },
                    onPickCover = { gameId ->
                        coverGameId = gameId
                        viewModel.prepareExternalPicker()
                        coverPicker.launch(arrayOf("image/*"))
                    },
                    onPickBackupFolder = { backupFolderPicker.launch(null) },
                    onRestoreBackup = { backupRestorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onOpenBackupFolder = ::openBackupFolder
                )
                if (showAppUpdates && !locked && recoveredWolfReport == null) CompositionLocalProvider(LocalAppLanguage provides state.settings.language) {
                    AppUpdateScreen(viewModel.appUpdates, viewModel::prepareExternalPicker) { showAppUpdates = false }
                }
                if (state.settingsLoaded && !locked) recoveredWolfReport?.let { report ->
                    CompositionLocalProvider(LocalAppLanguage provides state.settings.language) {
                        WolfDiagnosticsDialog(initialReport = report, recovered = true, beforeExternal = viewModel::prepareExternalPicker) {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { runCatching { WolfDiagnostics(this@MainActivity).dismissRecovery(report) } }
                                recoveredWolfReport = null
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimes()
        viewModel.onAppResumed()
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { WolfDiagnostics(this@MainActivity).pendingRecovery() }.getOrNull() }
            if (!isFinishing) recoveredWolfReport = report
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("coverGameId", coverGameId)
        super.onSaveInstanceState(outState)
    }

    private fun promptBiometric() {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlockApp()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Astra")
                .setSubtitle("Deverrouiller Astra")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun launchCrop(gameId: String, source: Uri) {
        coverGameId = gameId
        coverCropper.launch(
            CropImageContractOptions(
                uri = source,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    fixAspectRatio = true,
                    aspectRatioX = 18,
                    aspectRatioY = 25,
                    imageSourceIncludeCamera = false,
                    imageSourceIncludeGallery = false,
                    outputCompressQuality = 100
                )
            )
        )
    }

    private fun openBackupFolder() {
        val uri = viewModel.uiState.value.settings.backupFolderUri?.let(Uri::parse)
        if (uri == null) {
            backupFolderPicker.launch(null)
            return
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }.onFailure {
            backupFolderPicker.launch(uri)
        }
    }

    private fun openDocumentFolder(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            runCatching { startActivity(picker) }
        }
    }
}
