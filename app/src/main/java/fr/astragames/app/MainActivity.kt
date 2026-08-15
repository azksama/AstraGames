@file:Suppress("DEPRECATION")

package fr.astragames.app

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AstraViewModel>()
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

    private fun launchCrop(gameId: String, source: Uri?) {
        coverGameId = gameId
        coverCropper.launch(
            CropImageContractOptions(
                uri = source,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    fixAspectRatio = false,
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
}
