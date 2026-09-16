package fr.astragames.app.data.scanner

import fr.astragames.app.data.local.GameEntity

object MovedGameDetector {
    fun isMoved(stored: GameEntity, detected: GameEntity): Boolean =
        stored.fingerprint == detected.fingerprint && stored.documentUri != detected.documentUri
}
