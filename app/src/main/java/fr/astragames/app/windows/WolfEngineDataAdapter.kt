package fr.astragames.app.windows

/** Per-game, live read/write adapter. Undo stores IDs and layout tokens, never reusable addresses. */
internal class WolfEngineDataAdapter(private val session: WolfCheatSession) : EngineDataAdapter {
    private var undo: Pair<EngineDataVariable, EngineDataVariable>? = null
    override val canUndo get() = undo != null
    override suspend fun probe(): EngineDataProbe = session.engineCommand(11).probe
    override suspend fun search(category: EngineDataCategory?, bank: Int?, query: String, offset: Int): EngineDataPage {
        val response = session.engineCommand(12, category = category, bank = bank, query = query, offset = offset).checked()
        return EngineDataPage(response.probe as EngineDataProbe.Supported, response.count, offset, response.variables)
    }
    override suspend fun read(id: Int): EngineDataVariable = session.engineCommand(13, variable = id).checked().variables.single()
    override suspend fun write(before: EngineDataVariable, after: EngineDataValue): EngineDataVariable {
        check(!before.readOnly) { "Cette variable est disponible en lecture uniquement." }
        require((before.value is EngineDataValue.Number) == (after is EngineDataValue.Number)) { "Le type de la variable ne peut pas être changé." }
        val confirmed = mutate(before, after)
        undo = before to confirmed
        return confirmed
    }
    private suspend fun mutate(before: EngineDataVariable, after: EngineDataValue): EngineDataVariable {
        val confirmed = session.engineCommand(14, variable = before.id, token = before.token,
            before = before.value.display, after = after.display).checked().variables.single()
        check(confirmed.id == before.id && confirmed.token == before.token && confirmed.value == after) { "La relecture ne confirme pas la modification." }
        return confirmed
    }
    override suspend fun undo(): EngineDataVariable {
        val (before, after) = checkNotNull(undo) { "Aucune modification de variable à annuler." }
        return mutate(after, before.value).also { undo = null }
    }
}
