package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.GameEngine
import java.security.MessageDigest

object GameFingerprint {
    fun create(engine: GameEngine, normalizedTitle: String, productCode: String?, executable: String?): String {
        val raw = listOf(engine.name, normalizedTitle.lowercase(java.util.Locale.ROOT), productCode.orEmpty(), executable.orEmpty().lowercase(java.util.Locale.ROOT))
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
