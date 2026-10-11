package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfVariablesTest {
    private val address = EventAddress(0)

    @Test fun wolf2GlobalGainsHaveOnlyTheNativeLowerClampAndPreserveSignedSeGain() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val variables = WolfVariables(emptyMap(), { _, _ -> null }, systemWrite = { id, value -> writes += id to value },
            compatibilityVersion = 225)
        for (id in 100..102) variables.write(9_000_000 + id, -75, address)
        assertEquals(listOf(100 to 0, 101 to 0, 102 to -75), writes)
        assertEquals(-75, variables.read(9_000_102, address))
        for (id in 100..102) {
            variables.write(9_000_000 + id, Int.MAX_VALUE, address)
            assertEquals(Int.MAX_VALUE, variables.read(9_000_000 + id, address))
        }
        assertEquals(102 to Int.MAX_VALUE, writes.last())
    }

    @Test fun wolf3NormalizesAllGainChannelsBeforeHostCallbacksWithoutAnUpperClamp() {
        val observed = linkedMapOf<Int, Int>()
        lateinit var variables: WolfVariables
        variables = WolfVariables(emptyMap(), { _, _ -> null }, systemWrite = { id, value ->
            assertEquals(value, variables.system[id])
            observed[id] = value
        }, compatibilityVersion = 372)
        for (id in 100..102) {
            variables.write(9_000_000 + id, Int.MIN_VALUE, address)
            assertEquals(0, variables.read(9_000_000 + id, address))
            variables.write(9_000_000 + id, 175, address)
            assertEquals(175, variables.read(9_000_000 + id, address))
        }
        assertEquals(mapOf(100 to 175, 101 to 175, 102 to 175), observed)
    }
}
