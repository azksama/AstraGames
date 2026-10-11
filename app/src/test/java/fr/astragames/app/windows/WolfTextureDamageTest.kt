package fr.astragames.app.windows

import com.winlator.renderer.TextureDamage
import org.junit.Assert.*
import org.junit.Test

class WolfTextureDamageTest {
    @Test fun distantSpritesDoNotIncludeTheWholeMapBetweenThem() {
        val damage = TextureDamage()
        damage.add(8, 8, 32, 32); damage.add(1200, 800, 32, 32)
        assertEquals(2, damage.size())
        assertEquals(2048L, area(damage))
    }
    @Test fun adjacentTilesCollapseToOneUpload() {
        val damage = TextureDamage()
        for (y in 0 until 960 step 64) for (x in 0 until 1280 step 64) damage.add(x, y, 64, 64)
        assertEquals(1, damage.size())
        assertEquals(1280L * 960, area(damage))
    }
    @Test fun boundedBatchNeverDropsDamageEvenWithHundredsOfRegions() {
        val damage = TextureDamage()
        val points = (0 until 180).map { ((it * 173) % 1279) to ((it * 139) % 959) }
        points.forEach { (x,y) -> damage.add(x,y,1,1) }
        assertTrue(damage.size() <= TextureDamage.MAX_REGIONS)
        points.forEach { (x,y) -> assertTrue("lost $x,$y", (0 until damage.size()).any {
            x >= damage.get(it,0) && x < damage.get(it,2) && y >= damage.get(it,1) && y < damage.get(it,3)
        }) }
        damage.clear(); assertEquals(0, damage.size())
    }
    private fun area(damage: TextureDamage): Long = (0 until damage.size()).sumOf {
        (damage.get(it,2) - damage.get(it,0)).toLong() * (damage.get(it,3) - damage.get(it,1))
    }
}
