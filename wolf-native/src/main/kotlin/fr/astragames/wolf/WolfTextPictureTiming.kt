package fr.astragames.wolf

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A picture escape whose native timing contract has not been established. */
class WolfTextPictureUnsupported(message: String) : IllegalArgumentException(message)

/**
 * Text-picture clock and the byte-correction part of the native text layout pass.
 * This is separate from the dialogue protocol and does not synthesize input for \\!.
 * REA: W3 986570/969db0/96a240/96c320/98f1a0/98c140;
 * W2 49d5b0/4cc6e0/492000. Rendering and font metrics are owned by the host.
 */
object WolfTextPictureTiming {
    const val MAX_TEXT_BYTES = 1_000_000
    private const val MAX_PROGRESS = 50_000_000.0

    internal enum class Kind { GLYPH, STYLE, BACKSLASH, SPEED, PAUSE, INSTANT, NORMAL, AUTO_CLOSE, ICON }
    internal data class Token(
        val start: Int, val end: Int, val kind: Kind, val argument: Int = 0,
        val glyphs: Int = 0, val byteCorrection: Int = 0,
    )

    class Program internal constructor(
        val source: String, val charset: Charset, val compatibilityVersion: Int,
        val compiledByteLength: Int, private val tokens: List<Token>,
    ) {
        val glyphCount: Int = tokens.sumOf { it.glyphs }
        internal val legacyParser: Boolean = compatibilityVersion < 300
        private val anchorCursors = if (legacyParser) emptySet() else tokens.filter { it.kind == Kind.SPEED }.map { it.start + 3 }.toSet()
        internal fun measuredCorrection(): Int = tokens.sumOf { it.byteCorrection }
        internal fun acceptsAnchor(cursor: Int): Boolean = cursor in anchorCursors
        internal fun layoutTokens() = tokens
    }

    /** Native fields, excluding rendering-only geometry, font measurements and dialogue input. */
    data class State(
        val speed: Double = 240.0,
        val progress: Double = 0.0,
        val previous: Double = 0.0,
        val correction: Int = 0,
        val waitTicks: Int = 0,
        val pauseCheckpoint: Int = 0,
        val autoClose: Boolean = false,
        val speedAnchors: Map<Int, Double> = emptyMap(),
    )

    data class Layout(val state: State, val visibleGlyphCount: Int)

    private val argumentEscape = Regex("""\\(imgS|img|isize|iS|space|font|sp|wE|cE|cS|cR|mx|my|bx|ax|ay|f\+|fR|[cfimr-])\[([^]]*)]""")
    private val styles = mapOf(
        "c" to 7, "f" to 6, "f+" to 6, "fR" to 6, "m" to 6, "-" to 4,
        "font" to 5, "space" to 5, "isize" to 6, "wE" to 6,
        "cE" to 6, "cS" to 6, "cR" to 6,
        "mx" to 6, "my" to 6, "bx" to 6, "ax" to 6, "ay" to 6,
    )

    /**
     * Accepts already expanded game text. The length is the native marker stream length,
     * never UTF-16 length. A charset replacement is rejected instead of changing timing.
     */
    fun compile(text: String, charset: Charset, compatibilityVersion: Int, convertSpecial: Boolean = true): Program {
        require(compatibilityVersion in 100..9999) { "Profil de texte Wolf invalide" }
        require(text.length <= MAX_TEXT_BYTES && '\u0000' !in text && '\u0001' !in text) { "Texte Wolf invalide ou trop long" }
        val tokens = ArrayList<Token>()
        var bytes = 0
        fun add(length: Int, kind: Kind, argument: Int = 0, glyphs: Int = 0, correction: Int = length) {
            require(length in 1..MAX_TEXT_BYTES && bytes <= MAX_TEXT_BYTES - length) { "Texte Wolf compilé trop long" }
            tokens += Token(bytes, bytes + length, kind, argument, glyphs, correction)
            bytes += length
        }
        var index = 0
        while (index < text.length) {
            if (convertSpecial) {
                if (text.startsWith("<L>", index) || text.startsWith("<C>", index) || text.startsWith("<R>", index)) {
                    add(3, Kind.STYLE); index += 3; continue
                }
                if (text[index] == '\\') {
                    val match = argumentEscape.matchAt(text, index)
                    if (match != null) {
                        val code = match.groupValues[1]
                        val argument = match.groupValues[2]
                        when (code) {
                            "sp" -> {
                                val speed = argument.toIntOrNull() ?: throw WolfTextPictureUnsupported("Vitesse de texte non numérique")
                                add(7, Kind.SPEED, if (speed <= 0) 65535 else speed.coerceAtMost(60000))
                            }
                            "i", "iS" -> {
                                val icon = argument.toIntOrNull() ?: throw WolfTextPictureUnsupported("Icône de texte non numérique")
                                require(icon in 0..9999) { "Icône de texte hors plage" }
                                add(6, Kind.ICON, glyphs = 1, correction = if (compatibilityVersion > 339) 5 else 3)
                            }
                            "img", "imgS" -> {
                                require(argument.isNotEmpty()) { "Image intégrée vide" }
                                val payload = byteLength(argument, charset) + 1
                                add(3 + payload, Kind.ICON, glyphs = 1,
                                    correction = if (compatibilityVersion > 339) 3 + payload - 1 else 3)
                            }
                            "r" -> throw WolfTextPictureUnsupported("Progression du ruby de picture non établie")
                            else -> {
                                if (code !in styles || argument.toIntOrNull() == null)
                                    throw WolfTextPictureUnsupported("Code de picture non établi : ${match.value}")
                                add(styles.getValue(code), Kind.STYLE)
                            }
                        }
                        index = match.range.last + 1; continue
                    }
                    val code = text.getOrNull(index + 1)
                    when (code) {
                        '\\' -> add(3, Kind.BACKSLASH, glyphs = 1)
                        'E', 'N' -> add(3, Kind.STYLE)
                        '>' -> add(3, Kind.INSTANT)
                        '<' -> add(3, Kind.NORMAL)
                        '.' -> add(3, Kind.PAUSE)
                        '^' -> add(3, Kind.AUTO_CLOSE)
                        '!' -> throw WolfTextPictureUnsupported("Picture \\! : libération par l’entrée native non établie")
                        'A' -> if (text.getOrNull(index + 2) in listOf('+', '-')) {
                            add(3, Kind.STYLE); index += 3; continue
                        } else throw WolfTextPictureUnsupported("Code de picture inconnu")
                        else -> throw WolfTextPictureUnsupported("Code de picture inconnu près de ${text.substring(index).take(40)}")
                    }
                    index += 2; continue
                }
            }
            val cp = text.codePointAt(index)
            val end = index + Character.charCount(cp)
            val length = byteLength(text.substring(index, end), charset)
            add(length, Kind.GLYPH, glyphs = if (cp == 10 || cp == 13) 0 else 1,
                correction = if (compatibilityVersion < 300) 0 else (length - 2).coerceAtLeast(0))
            index = end
        }
        return Program(text, charset, compatibilityVersion, bytes, tokens)
    }

    private fun byteLength(text: String, charset: Charset): Int {
        val encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return encoder.encode(CharBuffer.wrap(text)).remaining()
    }

    /** SHOW measures the full marker stream before overwriting speed with Sys11. */
    fun initial(program: Program, speed: Int): State = State(speed = speed.toDouble(), correction = program.measuredCorrection())

    /** Clock only. In native order, positive waits expire after deciding whether progress advances. */
    fun tick(state: State, step: Int): State {
        require(step in 1..60) { "Pas logique Wolf invalide" }
        val progress = if (state.waitTicks < 1) (state.progress + step * state.speed / 30.0).coerceAtMost(MAX_PROGRESS) else state.progress
        return state.copy(previous = state.progress, progress = progress, waitTicks = if (state.waitTicks > 0) state.waitTicks - step else state.waitTicks)
    }

    /** Rescans the drawn prefix, as 96a240 does; speed anchors and pause checkpoints prevent rearming. */
    fun layout(program: Program, state: State, fallbackSpeed: Int): Layout {
        var progress = state.progress
        var speed = state.speed
        var correction = 0
        var wait = state.waitTicks
        var pause = state.pauseCheckpoint
        var autoClose = state.autoClose
        var instant = false
        var visible = 0
        val anchors = state.speedAnchors.toMutableMap()
        for (token in program.layoutTokens()) {
            if (wait > 0 && token.start >= pause - 1) break
            // The outer parser always reads one encoding character before the reveal check.
            val firstLength = if (token.kind == Kind.GLYPH) token.end - token.start else 1
            if (!program.legacyParser) correction += (firstLength - 2).coerceAtLeast(0)
            val cursor = token.start + firstLength
            if (instant && progress < cursor - correction.toDouble()) progress = cursor - correction.toDouble()
            val firstInstant = !program.legacyParser && token.start == 0 && token.kind == Kind.INSTANT
            if (!instant && !firstInstant && progress.toInt() + correction < cursor) break
            if (token.kind == Kind.GLYPH) {
                visible += token.glyphs
                continue
            }
            correction += 3
            val payloadCursor = token.start + 3
            when (token.kind) {
                Kind.SPEED -> {
                    if (!program.legacyParser) {
                        val anchor = anchors.getOrPut(payloadCursor) { progress }
                        // Preserve the native Double operation order and truncation towards zero.
                        correction = (correction.toDouble() - (correction.toDouble() + anchor - payloadCursor)).toInt()
                    }
                    speed = (if (token.argument == 65535 || token.argument == 0) fallbackSpeed else token.argument).coerceAtLeast(1).toDouble()
                    correction += 4
                }
                Kind.PAUSE -> if (pause < payloadCursor) { wait = 16; pause = payloadCursor + 1 }
                Kind.INSTANT -> instant = true
                Kind.NORMAL -> instant = false
                Kind.AUTO_CLOSE -> autoClose = true
                else -> { correction += token.byteCorrection - 3; visible += token.glyphs }
            }
        }
        return Layout(state.copy(speed = speed, progress = progress, correction = correction, waitTicks = wait,
            pauseCheckpoint = pause, autoClose = autoClose, speedAnchors = anchors.toMap()), visible)
    }

    /** Exact text-picture GetInfo10; the host returns -1 separately for a non-text picture. */
    fun status(program: Program, state: State, step: Int): Int {
        require(step in 1..60) { "Pas logique Wolf invalide" }
        val threshold = (state.progress.toInt() + state.correction).toDouble() - step * state.speed / 30.0 + 1.0
        return if (program.compiledByteLength.toDouble() <= threshold) 1 else 0
    }

    fun advance(program: Program, state: State, fallbackSpeed: Int, step: Int): Layout = layout(program, tick(state, step), fallbackSpeed)

    /** A restore can validate first, without replacing the previously running state. */
    fun validate(program: Program, state: State): Boolean =
        state.speed.isFinite() && state.speed in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() &&
        state.progress.isFinite() && state.progress in -1.0e12..MAX_PROGRESS &&
        state.previous.isFinite() && state.previous in -1.0e12..MAX_PROGRESS &&
        state.waitTicks in -60..16 && state.pauseCheckpoint in 0..program.compiledByteLength + 1 &&
        state.speedAnchors.size <= 100_000 && state.speedAnchors.all { (cursor, value) ->
            program.acceptsAnchor(cursor) && value.isFinite() && value in -1.0e12..MAX_PROGRESS
        }
}
