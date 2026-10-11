package fr.astragames.wolf

/** One admission contract shared by the interpreter and the preflight inspector. */
object WolfSystemSupport {
    val numericReads = (0..32).toSet() + (43..48).toSet() + (52..57).toSet() + (61..68).toSet() +
        (77..82).toSet() + (87..91).toSet() + (93..102).toSet() + (155..160).toSet() +
        setOf(35, 36, 37, 38, 39, 59, 60, 71, 72, 73, 84, 85, 104, 105, 108, 110, 111, 112,
            114, 115, 116, 117, 118, 119, 120, 125, 129, 130, 131, 132, 140, 143, 148, 150)
    val numericWrites = (0..11).toSet() + (14..19).toSet() + (52..57).toSet() + (61..68).toSet() +
        (87..91).toSet() + (93..102).toSet() + setOf(21, 22, 23, 25, 26, 27, 28, 29, 36, 37, 38,
            59, 60, 71, 72, 73, 104, 105, 110, 120, 125, 129, 130, 131, 132, 140, 148, 150)
    val fontStrings = (62..65).toSet() + 67
    val stringReads = (1..6).toSet() + (10..15).toSet() + (40..49).toSet() + fontStrings +
        setOf(19, 25, 26, 27, 30, 31, 33, 34, 52, 54, 55, 60, 68, 70, 71, 73, 75, 76)
    val stringWrites = (10..15).toSet() + fontStrings + setOf(26, 27, 30, 31, 60, 68)
    // A save contains persisted game configuration and world state, not host focus,
    // wall-clock samples, an active text editor, or a worker's live download counters.
    val numericSaveWrites = numericWrites - setOf(71, 72, 148)
    val numericSaveReads = numericSaveWrites + setOf(24, 30, 35, 48, 110, 112, 114, 115, 116, 117, 118, 119, 143) + (155..160)
    val stringSaveWrites = stringWrites - 68
    val stringSaveReads = stringSaveWrites + (1..6) + (40..49) + setOf(19, 25, 33, 34, 52, 73, 75)

    fun savedReferenceProblem(code: Int, write: Boolean = false): String? {
        val allowed = when (code) {
            in 9_000_000..9_099_999 -> code % 100_000 in if (write) numericSaveWrites else numericSaveReads
            in 9_900_000..9_999_999 -> code % 100_000 in if (write) stringSaveWrites else stringSaveReads
            else -> true
        }
        return if (allowed) null else "État de session absent des opérations isolées de sauvegarde : $code"
    }

    // Window-management, streaming, clipboard and alternate metadata roots need host contracts.
    // Keeping these explicit prevents an accepted write with no effect or a made-up zero read.
    val unsupportedNumbers = mapOf(33 to "saut de rendu persistant", 34 to "correction verticale des sprites",
        40 to "position de saisie X", 41 to "position de saisie Y", 42 to "rendu de saisie",
        74 to "visibilité du curseur", 122 to "filtrage du zoom de carte", 123 to "filtrage de la prochaine image",
        127 to "exclusion des tuiles de la sauvegarde", 134 to "liaison du pad POV", 135 to "liaison du stick",
        136 to "échelle de fenêtre précise", 137 to "échelle de fenêtre", 138 to "mode de fenêtre",
        139 to "extension Pro de carte", 141 to "langue du moteur Pro", 146 to "profil DirectInput",
        163 to "conversion tactile Windows")
    val unsupportedStrings = mapOf(20 to "proxy réseau", 21 to "port du proxy", 22 to "réception texte en cours",
        57 to "presse-papiers avec consentement", 74 to "changement de données au prochain lancement",
        82 to "moniteur de performance Wolf")

    internal fun initial(config: WolfGameConfig): Map<Int, Int> {
        val font = when { config.width < 640 -> 12; config.width < 800 -> 22; else -> 28 }
        return mapOf(0 to 0, 1 to -1, 2 to -1, 3 to -1, 4 to -1,
            5 to config.tileSize * 10, 6 to (config.tileSize * 4.3).toInt(), 7 to 0, 8 to font,
            9 to 120, 10 to 1, 11 to 1_000_000, 14 to 1, 15 to 16, 16 to 5,
            17 to -1, 18 to -1, 19 to -1, 21 to 0, 22 to 0, 23 to 0, 25 to -1,
            26 to if ((config.engineCompatibilityVersion ?: 0) >= 230) 14 else -999,
            27 to if ((config.engineCompatibilityVersion ?: 0) >= 230) 14 else -999,
            36 to 0, 37 to 2, 38 to 0, 59 to 0, 60 to 0, 73 to 0,
            87 to -1, 88 to -1, 89 to -1, 90 to -1, 91 to -1,
            93 to 0, 94 to 0, 100 to 100, 101 to 100, 102 to 100,
            120 to 0, 125 to 5, 129 to 100, 130 to 100, 131 to 0, 132 to 0, 150 to 0)
    }
}

/** W3 0093a810: Park–Miller, with rejection above two billion, not xorshift. */
internal fun wolfRandomNext(seed: Int): Int {
    var value = (seed.toLong() and 0xffffffffL) * 16807 % 2147483647L
    while (value > 2_000_000_000L) value = value * 16807 % 2147483647L
    return value.toInt()
}

/** Command260 progress is supplied by the actual reception, in Wolf's byte/second units. */
data class WolfDownloadProgress(val received: Int = 0, val expected: Int = 0, val bytesPerSecond: Int = 0,
    val seconds: Int = 0, val remainingSeconds: Int = 0) {
    init { require(received >= 0 && expected >= 0 && bytesPerSecond >= 0 && seconds >= 0 && remainingSeconds >= 0) }
    internal fun number(id: Int) = when (id) { 43 -> received; 44 -> expected; 45 -> bytesPerSecond; 46 -> seconds; 47 -> remainingSeconds; else -> error("Compteur de téléchargement inconnu") }
}

/** Measured host state is not fabricated from the configured target refresh rate. */
internal class WolfSystemTelemetry(private val nanoTime: () -> Long = System::nanoTime) {
    private var sampleAt = nanoTime()
    private var frameAt = sampleAt
    private var samples = 0
    var internalFps = 0; private set
    var renderFps = 0; private set
    var active = true; private set
    var controllerName = ""; private set
    var controllerKind = ""; private set
    var availableMemoryMB: Int? = null; private set
    var usedMemoryMB: Int? = null; private set
    var eventNanos = 0L; private set
    var renderNanos = 0L; private set
    private var pendingEventNanos = 0L
    fun tick() {
        val now = nanoTime(); frameAt = now; samples++
        val elapsed = now - sampleAt
        if (elapsed >= 1_000_000_000L) {
            internalFps = (samples.toLong() * 1_000_000_000L / elapsed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            samples = 0; sampleAt = now
        }
    }
    fun millis(base: Int, modern: Boolean) = (base + if (modern) ((nanoTime() - frameAt).coerceAtLeast(0) / 1_000_000L).coerceAtMost(1999).toInt() else 0).coerceAtMost(1999)
    fun rendered(fps: Int) { require(fps >= 0); renderFps = fps }
    fun renderedDuration(nanos: Long) { require(nanos >= 0); renderNanos = nanos }
    fun availableMemory(bytes: Long) { require(bytes >= 0); availableMemoryMB = (bytes / 1_048_576).coerceAtMost(4096).toInt() }
    fun usedMemory(bytes: Long) { require(bytes >= 0); usedMemoryMB = (bytes / 1_048_576).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun execution(nanos: Long, completed: Boolean) {
        pendingEventNanos += nanos.coerceAtLeast(0)
        if (completed) { eventNanos = pendingEventNanos; pendingEventNanos = 0 }
    }
    fun resetExecution() { pendingEventNanos = 0; eventNanos = 0 }
    fun focus(value: Boolean) { active = value; sampleAt = nanoTime(); frameAt = sampleAt; samples = 0; internalFps = 0; renderFps = 0 }
    fun controller(name: String, kind: String) { controllerName = name; controllerKind = if (name.isEmpty()) "" else kind.ifEmpty { "UNKNOWN" } }
}
