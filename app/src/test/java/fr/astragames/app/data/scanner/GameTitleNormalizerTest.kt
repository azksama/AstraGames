package fr.astragames.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class GameTitleNormalizerTest {
    @Test
    fun extractsDeveloperVersionAndProductCode() {
        val result = GameTitleNormalizer.normalize(
            "[Kiiro Onichika] Apprentice Moby and the Wind-Waiting Island v1.3.2 (RJ01042745)"
        )
        assertEquals("Apprentice Moby and the Wind-Waiting Island", result.title)
        assertEquals("Kiiro Onichika", result.developer)
        assertEquals("1.3.2", result.version)
        assertEquals("RJ01042745", result.productCode)
    }
}
