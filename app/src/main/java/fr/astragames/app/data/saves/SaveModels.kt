package fr.astragames.app.data.saves

data class SaveEdit(
    val path: String,
    val type: SaveEntryType,
    val newValue: String
)

data class SimpleSaveFields(
    val money: SaveEntry? = null,
    val level: SaveEntry? = null,
    val experience: SaveEntry? = null,
    val hp: SaveEntry? = null,
    val mp: SaveEntry? = null,
    val inventory: List<SaveEntry> = emptyList(),
    val relations: List<SaveEntry> = emptyList(),
    val progress: List<SaveEntry> = emptyList(),
    val variables: List<SaveEntry> = emptyList(),
    val switches: List<SaveEntry> = emptyList()
)
