package fr.astragames.app.core.search

fun levenshtein(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    val rows = if (left.length >= right.length) left else right
    val columns = if (left.length >= right.length) right else left
    var previous = IntArray(columns.length + 1) { it }
    var current = IntArray(columns.length + 1)
    rows.forEachIndexed { leftIndex, leftChar ->
        current[0] = leftIndex + 1
        columns.forEachIndexed { rightIndex, rightChar ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftChar == rightChar) 0 else 1
            )
        }
        val reusable = previous
        previous = current
        current = reusable
    }
    return previous.last()
}
