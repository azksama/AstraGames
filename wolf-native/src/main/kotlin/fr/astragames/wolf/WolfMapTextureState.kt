package fr.astragames.wolf

/** Persisted identity and phase of one native map texture, separate from its decoded pixels. */
data class WolfMapTextureState(
    val id: Int = -1,
    val path: String? = null,
    val scroll: WolfMapTextures.State = WolfMapTextures.cold(),
    val stringReloadPending: Boolean = false,
    /** W2 retains signed phases; W3 normalizes negative phases before drawing. */
    val behaviorVersion: Int = 3,
)

/** Background and fog have independent native clocks and texture identities. */
data class WolfMapTextureLayers(
    val backdrop: WolfMapTextureState = WolfMapTextureState(),
    val fog: WolfMapTextureState = WolfMapTextureState(),
)
