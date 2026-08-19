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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
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
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val viewModel by viewModels<AstraViewModel>()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var coverGameId: String? = null
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
        uri?.let(viewModel::addSaveLocation)
    }
    private val modsRootPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setModsRoot)
    }
    private val modZipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importModZip(it, null, false) }
    }
    private val coverCropper = registerForActivityResult(CropImageContract()) { result ->
        val gameId = coverGameId
        if (result.isSuccessful && gameId != null) result.uriContent?.let { viewModel.setCover(gameId, it) }
        coverGameId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                viewModel.pickSaveFolderRequests.collect { saveFolderPicker.launch(null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pickModsRootRequests.collect { modsRootPicker.launch(null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pickModZipRequests.collect {
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
            AstraTheme(state.settings.themeMode, state.settings.dynamicColor) {
                AstraApp(
                    state = state,
                    viewModel = viewModel,
                    onPickSource = { sourcePicker.launch(null) },
                    onPickTags = { tagPicker.launch(arrayOf("text/plain", "text/csv", "application/json")) },
                    onPickCover = { gameId ->
                        launchCrop(gameId, null)
                    },
                    onPickBackupFolder = { backupFolderPicker.launch(null) },
                    onRestoreBackup = { backupRestorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onOpenBackupFolder = ::openBackupFolder
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimes()
        viewModel.onAppResumed()
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
        super.onStop()
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

    private fun launchCrop(gameId: String, source: Uri?) {
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
                    imageSourceIncludeGallery = source == null,
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
