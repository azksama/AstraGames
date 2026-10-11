package fr.astragames.wolf

/**
 * Innermost-first variable expansion shared by admission and execution. The shipped W3
 * source converter (00948960) repeats conversions up to 100 times and protects paired
 * backslashes. Admission substitutes a shape, never a guessed database value.
 */
internal object WolfTextExpansion {
    private const val MAX_PASSES = 100
    private const val MAX_LENGTH = 1_000_000

    fun hasReference(input: String): Boolean = WolfTextCodes.variableToken.findAll(input)
        .any { !WolfTextCodes.quotedEscape(input, it.range.first) }

    /** Collapse all nested variable layers before validating surrounding formatting. */
    fun staticShape(input: String): String = expand(input) { _, _ -> "16" }

    fun expand(input: String, resolve: (family: String, argument: String) -> String): String {
        require(input.length <= MAX_LENGTH) { "Texte supérieur à la capacité native" }
        var result = input
        repeat(MAX_PASSES) {
            val source = result
            var replacements = 0
            result = WolfTextCodes.variableToken.replace(source) { match ->
                if (WolfTextCodes.quotedEscape(source, match.range.first)) match.value
                else {
                    replacements++
                    resolve(match.groupValues[1], match.groupValues[2])
                }
            }
            require(result.length <= MAX_LENGTH) { "Développement de texte supérieur à la capacité native" }
            if (replacements == 0) return result
        }
        require(!hasReference(result)) { "Références de texte récursives au-delà de $MAX_PASSES développements" }
        return result
    }
}
