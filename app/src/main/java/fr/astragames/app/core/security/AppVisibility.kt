package fr.astragames.app.core.security

/** Counts every activity in our process, including the cover cropper. */
class AppVisibility {
    private var started = 0
    private var backgrounded = false
    fun started() { started++ }
    fun stopped(changingConfiguration: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !changingConfiguration) backgrounded = true
    }
    fun consumeBackground(): Boolean = backgrounded.also { backgrounded = false }
}
