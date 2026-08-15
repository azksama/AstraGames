package fr.astragames.app.data.scanner

import fr.astragames.app.data.local.GameEntity

object DuplicateDetector {
    fun isDuplicate(first: GameEntity, second: GameEntity): Boolean {
        if (first.id == second.id) return false
        if (first.fingerprint == second.fingerprint) return true
        if (!first.productCode.isNullOrBlank() && first.productCode.equals(second.productCode, true)) return true
        if (first.documentUri == second.documentUri) return true
        return first.engine == second.engine && similarity(first.title, second.title) >= .92
    }

    private fun similarity(left: String, right: String): Double {
        val a = left.lowercase().filter(Char::isLetterOrDigit)
        val b = right.lowercase().filter(Char::isLetterOrDigit)
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.toSet().intersect(b.toSet()).size.toDouble()
        return common / a.toSet().union(b.toSet()).size
    }
}

object MovedGameDetector {
    fun isMoved(stored: GameEntity, detected: GameEntity): Boolean =
        stored.fingerprint == detected.fingerprint && stored.documentUri != detected.documentUri
}
