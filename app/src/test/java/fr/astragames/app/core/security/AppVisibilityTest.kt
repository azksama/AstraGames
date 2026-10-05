package fr.astragames.app.core.security

import org.junit.Assert.*
import org.junit.Test

class AppVisibilityTest {
    @Test fun cropActivityIsNotAnAppBackgroundTransition() {
        val visibility = AppVisibility()
        visibility.started() // Main
        visibility.started() // Cropper
        visibility.stopped(false) // Main
        visibility.started() // Main returning
        visibility.stopped(false) // Cropper
        assertFalse(visibility.consumeBackground())
    }
    @Test fun homeFromTheCropperStillRequiresRelocking() {
        val visibility = AppVisibility()
        visibility.started(); visibility.started(); visibility.stopped(false)
        visibility.stopped(false) // Home while cropping
        visibility.started(); visibility.started(); visibility.stopped(false)
        assertTrue(visibility.consumeBackground())
        assertFalse(visibility.consumeBackground())
    }
    @Test fun configurationChangesDoNotRequireRelocking() {
        val visibility = AppVisibility()
        visibility.started(); visibility.stopped(true); visibility.started()
        assertFalse(visibility.consumeBackground())
    }
}
