package fr.astragames.app.ui

import androidx.compose.runtime.compositionLocalOf

data class CoverBlurState(
    val blurred: Boolean = false,
    val enabled: Boolean = false,
    val toggle: () -> Unit = {}
)

val LocalCoverBlurState = compositionLocalOf { CoverBlurState() }
