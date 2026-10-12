package fr.astragames.app.windows

/** Engine IDs, unlike scanner addresses, have meaning only in a proven engine layout. */
internal enum class EngineDataCategory(val wire: Int, val label: String) {
    NUMBERS(1, "Variables numériques"), TEXT(2, "Variables texte"),
    COMMON_NUMBERS(3, "Variables numériques communes"), COMMON_TEXT(4, "Variables texte communes")
}

internal sealed interface EngineDataValue {
    val display: String
    data class Number(val value: Int) : EngineDataValue { override val display get() = value.toString() }
    data class Text(val value: String) : EngineDataValue { override val display get() = value }
}

internal data class EngineDataVariable(
    val id: Int, val category: EngineDataCategory, val bank: Int, val index: Int,
    val value: EngineDataValue, val token: Long, val capacityBytes: Int, val readOnly: Boolean
)

internal sealed interface EngineDataProbe {
    data class Supported(val build: String, val token: Long, val codepage: Int, val banks: List<Int>,
                         val textCount: Int, val commonCount: Int) : EngineDataProbe
    data class Unavailable(val reason: String) : EngineDataProbe
}

internal data class EngineDataPage(val probe: EngineDataProbe.Supported, val count: Long,
                                 val offset: Int, val variables: List<EngineDataVariable>)

internal interface EngineDataAdapter {
    val canUndo: Boolean
    suspend fun probe(): EngineDataProbe
    suspend fun search(category: EngineDataCategory? = null, bank: Int? = null, query: String = "", offset: Int = 0): EngineDataPage
    suspend fun read(id: Int): EngineDataVariable
    suspend fun write(before: EngineDataVariable, after: EngineDataValue): EngineDataVariable
    suspend fun undo(): EngineDataVariable
}
