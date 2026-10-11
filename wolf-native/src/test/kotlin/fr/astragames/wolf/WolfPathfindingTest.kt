package fr.astragames.wolf

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WolfPathfindingTest {
    @Test fun actorFootprintsAndDestinationAllowanceMatch206IndependentCppVectors() {
        val stream = javaClass.getResourceAsStream("/path-macros/w3-native-occupancy.tsv")
            ?: File("wolf-native/src/test/resources/path-macros/w3-native-occupancy.tsv").inputStream()
        var checked = 0
        stream.bufferedReader().useLines { lines -> lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val c = line.split('\t')
            val actors = c[6].split(';').map { raw -> raw.split(',').map(String::toInt).let {
                WolfPathfinding.Occupant(it[0], it[1], it[2], it[3] != 0, it[4] != 0, it[5], it[6]) } }
            val grid = WolfPathfinding.occupancy(c[1].toInt(), c[2].toInt(), actors, c[3].toInt(), WolfPathfinding.Point(c[4].toInt(), c[5].toInt()))
            assertArrayEquals(c[0], c[7].split(',').map(String::toInt).toIntArray(), grid.markers)
            checked++
        } }
        assertEquals(206, checked)
    }
    @Test fun eightWayGridMatchesThreeHundredIndependentCppVectors() {
        val stream = javaClass.getResourceAsStream("/path-macros/w3-native-grid.tsv")
            ?: File("wolf-native/src/test/resources/path-macros/w3-native-grid.tsv").inputStream()
        var checked = 0
        stream.bufferedReader().useLines { lines -> lines.filter { !it.startsWith('#') && it.isNotEmpty() }.forEach { line ->
            val c = line.split('\t')
            val grid = WolfPathfinding.buildGrid(c[1].toInt(), c[2].toInt(), c[3] == "1", c[4] == "1", c[5].split(',').map(String::toInt).toIntArray())
            assertArrayEquals(c[0], c[6].split(',').map(String::toInt).toIntArray(), grid.masks)
            checked++
        } }
        assertEquals(300, checked)
    }

    @Test fun versionTwoGoalOpensOnlyNonemptyCardinalNeighborsAndLeavesOriginalGridIntact() {
        val base = WolfPathfinding.buildGrid(3, 3, false, false, IntArray(9) { if (it == 4 || it == 1) 0 else 85 })
        val opened = base.openedGoal(WolfPathfinding.Point(1, 1))
        assertEquals(0, base.mask(1, 1)); assertEquals(255, opened.mask(1, 1))
        assertEquals(0, opened.mask(0, 1)) // A completely blocked neighbor stays blocked.
        assertTrue(opened.allows(WolfPathfinding.Point(2, 1), WolfPathfinding.Point(1, 1)))
        assertTrue(opened.allows(WolfPathfinding.Point(1, 2), WolfPathfinding.Point(1, 1)))
        assertFalse(opened.allows(WolfPathfinding.Point(2, 2), WolfPathfinding.Point(1, 1))) // No new diagonal entrance.
    }

    @Test fun nativeSerializerMatchesIndependentCppVectorsIncludingEmptyPathsAndWrapTies() {
        val stream = javaClass.getResourceAsStream("/path-macros/w3-native-format.tsv")
            ?: File("wolf-native/src/test/resources/path-macros/w3-native-format.tsv").inputStream()
        var checked = 0
        stream.bufferedReader().useLines { lines -> lines.filter { !it.startsWith('#') && it.isNotEmpty() }.forEach { line ->
            val columns = line.split('\t')
            val path = columns[7].split(';').map { point -> point.split(',').let { WolfPathfinding.Point(it[0].toInt(), it[1].toInt()) } }
            val expected = columns[9].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            assertEquals("${columns[0]} POS=${columns[1]} LEN=${columns[2]}", expected,
                WolfPathfinding.serialize(path, columns[1] == "1", columns[2].toInt(), columns[3].toInt(), columns[4].toInt(), columns[5] == "1", columns[6] == "1"))
            assertEquals(columns[8].toInt(), path.size - 1)
            checked++
        } }
        assertEquals(96, checked)
    }

    @Test fun fullSearchBudgetCountsExtractionsAndCanRejectGoalOnItsLastExtraction() {
        val request = WolfPathfinding.Request(4, WolfPathfinding.Point(1, 1), WolfPathfinding.Point(2, 1))
        val full = WolfPathfinding.search(4, 4, false, false, request, passage = { _, _ -> true })
        assertNotNull(full.path)
        val finalPop = WolfPathfinding.search(4, 4, false, false, request.copy(executions = full.pops), passage = { _, _ -> true })
        assertTrue(finalPop.tooFar); assertNull(finalPop.path)
        assertEquals("<<NoPath/TooFar>>", WolfPathfinding.format(finalPop, request, 4, 4, false, false))
        val complete = WolfPathfinding.search(4, 4, false, false, request.copy(executions = full.pops + 1), passage = { _, _ -> true })
        assertEquals(full.path, complete.path)
        val exhausted = WolfPathfinding.search(4, 4, false, false, request, passage = { _, _ -> false })
        assertEquals(1, exhausted.pops); assertFalse(exhausted.tooFar)
        assertEquals("<<NoPath>>", WolfPathfinding.format(exhausted, request, 4, 4, false, false))
    }

    @Test fun lengthTruncatesOnlySerializationAndMetadataKeepsTheFullPathLength() {
        val request = WolfPathfinding.Request(8, WolfPathfinding.Point(0, 0), WolfPathfinding.Point(3, 3), length = 1, steps = true, time = true)
        val result = WolfPathfinding.search(5, 5, false, false, request, passage = { _, _ -> true })
        assertEquals(3, result.fullSteps)
        assertEquals("3(=STEP)\n3\nTIME=7ms/EXE=${result.pops}/STEP=3", WolfPathfinding.format(result, request, 5, 5, false, false, 7))
        assertEquals("3(=STEP)\n1,1/\nTIME=7ms/EXE=${result.pops}/STEP=3", WolfPathfinding.format(result, request.copy(positions = true), 5, 5, false, false, 7))
    }

    @Test fun coordinatesNormalizeBeforeSearchAndWrappedShortestStepRetainsItsDirection() {
        val request = WolfPathfinding.Request(4, WolfPathfinding.Point(-1, 2), WolfPathfinding.Point(0, 2))
        val result = WolfPathfinding.search(8, 5, true, false, request, passage = { _, _ -> true })
        assertEquals(listOf(WolfPathfinding.Point(7, 2), WolfPathfinding.Point(0, 2)), result.path)
        assertEquals("6/END", WolfPathfinding.format(result, request, 8, 5, true, false))
        val stationary = request.copy(start = request.goal)
        val empty = WolfPathfinding.search(8, 5, false, false, stationary, passage = { _, _ -> true })
        assertEquals("/END", WolfPathfinding.format(empty, stationary, 8, 5, false, false))
        assertEquals("END", WolfPathfinding.format(empty, stationary.copy(positions = true, length = -1), 8, 5, false, false))
    }
}
