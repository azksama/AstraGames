package fr.astragames.wolf

/** Command124 fields qualified by the VM and its Android host; see STATE_READ_REA.md. */
object WolfStateSupport {
    val actor = (0..19).toSet() + (23..30).toSet()
    val position = (0..10).toSet() + setOf(22, 23, 152, 153, 154, 155, 161, 162)
    val general = (0..35).toSet()
    val picture = (0..12).toSet() + (21..37).toSet()

    /** All numbered slots in the pinned PE's public dispatch tables, including reserved slots. */
    val actorCatalog = (0..30).toSet()
    val positionCatalog = (0..30).toSet() + (150..162).toSet()
    val generalCatalog = (0..35).toSet()
    val pictureCatalog = (0..37).toSet()

    fun supports(category: Int, field: Int) = field in when (category) {
        1 -> actor; 2 -> position; 3 -> general; 4 -> picture; else -> emptySet()
    }
}
