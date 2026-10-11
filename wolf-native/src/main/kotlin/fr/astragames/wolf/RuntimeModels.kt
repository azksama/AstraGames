package fr.astragames.wolf

/** Logical inputs, independent of the Android, X11 and Windows key tables. */
enum class NativeKey { UP, DOWN, LEFT, RIGHT, ACCEPT, BACK, SHIFT, CONTROL, SPACE, Z, X, C, A, S, D, W, F1, F5, F12 }

data class NativeRect(val x: Float, val y: Float, val width: Float, val height: Float)
/** Native map texture phase in eighths of a logical pixel, already including scroll and shake. */
data class NativeMapTexture(val path: String, val phaseX: Int, val phaseY: Int,
    val blend: Int = 0, val opacity: Int = 255)
data class NativeTone(val red: Int = 0, val green: Int = 0, val blue: Int = 0)
data class NativeFlash(val red: Int, val green: Int, val blue: Int, val opacity: Int)
/** Physical game pixels. A published frame owns an immutable copy-on-write mask. */
data class NativeVisibilityMask(val width: Int, val height: Int, val pixels: ByteArray)
/** Ordered conversions of a file bitmap; parameters retain Wolf's integer units. */
data class NativeImageFilter(val operation: Int, val first: Int = 0, val second: Int = 0,
    val third: Int = 0, val fourth: Int = 0)
/** Per-SHOW identity, explicit seek revision, and the decoder's current measured timeline. */
data class NativeVideoState(val token: Long, val seekMs: Long = 0, val seekRevision: Long = 0,
    val speedPercent: Int = 100, val volumePercent: Int = 100, val loop: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = true,
    val width: Int = 0, val height: Int = 0)
data class NativeCharacter(
    val id: Int,
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val direction: Int = 2,
    val pattern: Int = 1,
    val opacity: Int = 255,
    val onTop: Boolean = false,
    val tileId: Int = -1,
    val moving: Boolean = false,
    val through: Boolean = false,
    val shadowPath: String? = null,
    val blend: Int = 0,
    /** Sprite elevation in logical pixels; the ground position and shadow stay at y. */
    val heightOffset: Float = 0f,
    /** Original half-grid bush predicate, independent from interpolated screen position. */
    val bush: Boolean = false,
    /** Character-effect brightness offsets; the shadow retains its own color. */
    val tone: NativeTone = NativeTone(),
    val maskEnabled: Boolean = false,
)

data class NativeMapScene(
    val map: WolfMap,
    val tileset: WolfTileset,
    val characters: List<NativeCharacter>,
    val cameraX: Float,
    val cameraY: Float,
    val tileSize: Int,
    val characterDirections: Int = 4,
    val animationPatterns: Int = 3,
    val zoom: Float = 1f,
    val zoomCenterX: Float = 0f,
    val zoomCenterY: Float = 0f,
    val maskEnabled: Boolean = false,
    /** Background and fog retain the bitmap's own size; they never repeat to fill the viewport. */
    val backdrop: NativeMapTexture? = null,
    val fog: NativeMapTexture? = null,
)

/** The encoding and compiler mode are needed to validate a saved text clock independently. */
data class NativeTextPictureTiming(
    val encoding: WolfEncoding,
    val compatibilityVersion: Int,
    val convertSpecial: Boolean,
    val state: WolfTextPictureTiming.State,
) {
    fun program(text: String): WolfTextPictureTiming.Program = WolfTextPictureTiming.compile(text,
        if (encoding == WolfEncoding.UTF8) Charsets.UTF_8 else java.nio.charset.Charset.forName("windows-31j"),
        compatibilityVersion, convertSpecial)
}

data class NativePicture(
    val id: Int,
    val path: String? = null,
    val text: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    /** Zero means the texture's intrinsic dimension; it never changes logical game resolution. */
    val width: Float = 0f,
    val height: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Int = 255,
    val rotation: Float = 0f,
    val blend: Int = 0,
    /** Z only orders pictures inside the immutable composition group selected by id. */
    val z: Int = 0,
    val sourceRect: NativeRect? = null,
    val color: Int = -1,
    val anchor: Int = 0,
    val fontSize: Float = 16f,
    val fontColor: Int = -1,
    val screenRelative: Boolean = true,
    /** Wolf picture types: file, file-variable, text, window-file, window-variable. */
    val kind: Int = 0,
    val divisionsX: Int = 1,
    val divisionsY: Int = 1,
    val pattern: Int = 1,
    val colorR: Float = 1f,
    val colorG: Float = 1f,
    val colorB: Float = 1f,
    val rubySize: Float = fontSize / 2,
    val shadowOffset: Int = 0,
    val visibleCharacters: Int = Int.MAX_VALUE,
    /** Signed picture-effect brightness offsets; independent of the picture150 base color. */
    val tone: NativeTone = NativeTone(),
    val maskEnabled: Boolean = false,
    val imageFilters: List<NativeImageFilter> = emptyList(),
    val video: NativeVideoState? = null,
    /** The rasterized text keeps the fonts used at SHOW, including the original empty setting. */
    val fontOverrides: Map<Int, String> = emptyMap(),
    /** Null in older saves means their already rendered text must stay fully visible. */
    val textTiming: NativeTextPictureTiming? = null,
)

data class NativeText(val text: String, val x: Float, val y: Float, val size: Float = 16f, val color: Int = -1)
data class NativeDialog(
    val text: String,
    val choices: List<String> = emptyList(),
    val selectedChoice: Int = 0,
    val waiting: Boolean = true,
    val fontSize: Float = 16f,
    val skinPath: String? = null,
    val choiceSkinPath: String? = null,
    val pausePath: String? = null,
    val bounds: NativeRect? = null,
    val visibleCharacters: Int = Int.MAX_VALUE,
    /** The Wolf message command emits text; common events normally draw its window. */
    val drawWindow: Boolean = false,
    val pauseFrames: Int = 1,
    val pauseFrame: Int = 0,
    val pauseX: Float? = null,
    val pauseY: Float? = null,
    val choiceCursorPath: String? = null,
    val choiceCursorFrames: Int = 1,
    val choiceCursorFrame: Int = 0,
    val choiceX: Float? = null,
    val choiceY: Float? = null,
    val marginX: Float = 0f,
    val marginY: Float = 0f,
    val cursorBlend: Int = 0,
    val rubySize: Float = fontSize / 2,
    val shadowOffset: Int = 0,
    val drawChoiceWindow: Boolean = true,
    val fontOverrides: Map<Int, String> = emptyMap(),
    /** Sys150 is captured for the text already on screen, independently of later writes. */
    val convertSpecial: Boolean = true,
)

data class NativeTransition(val previous: NativeFrame, val progress: Float, val maskPath: String? = null,
    /** Exact integer border used by Wolf; the fraction remains useful for a plain fade. */
    val border: Int? = null)

data class NativeFrame(
    val logicalWidth: Int,
    val logicalHeight: Int,
    val map: NativeMapScene?,
    val pictures: List<NativePicture>,
    val texts: List<NativeText>,
    val dialog: NativeDialog?,
    val tick: Long,
    val diagnostic: String? = null,
    val screenOpacity: Int = 255,
    val screenColor: Int = 0,
    val transition: NativeTransition? = null,
    val fontWeight: Int = 5,
    val tone: NativeTone = NativeTone(),
    val flash: NativeFlash? = null,
    val visibilityMask: NativeVisibilityMask? = null,
    val fontOverrides: Map<Int, String> = emptyMap(),
    /** Null keeps the initial viewport option; Sys140 explicitly selects nearest or smooth. */
    val windowSmooth: Boolean? = null,
    /** A foreground event can still be waiting after its final message has closed. */
    val eventRunning: Boolean = false,
) { val height: Int get() = logicalHeight }

sealed interface NativeEffect {
    data class AudioGain(val channel: Int, val percentage: Int) : NativeEffect
    /** Windows operations 1/2/3 preload, release one unused asset, release all unused assets. */
    data class AudioCache(val operation: Int, val path: String? = null) : NativeEffect
    data class Audio(
        val channel: Int,
        val path: String? = null,
        val volume: Int = 100,
        val pitch: Int = 100,
        val loop: Boolean = false,
        val fadeTicks: Int = 0,
        val stop: Boolean = false,
        /** Milliseconds for sampled audio, MIDI ticks for a Standard MIDI File. */
        val position: Int = 0,
        /** Sampled-audio milliseconds, or the original MIDI key transposition. */
        val loopStart: Int = 0,
        val voice: Int = 0,
        val pan: Int = 0,
        val delayTicks: Int = 0,
    ) : NativeEffect
    data class Save(val slot: Int, val key: String? = null) : NativeEffect
    data class Load(val slot: Int, val key: String? = null) : NativeEffect
    data class ReadSaveValue(val requestId: Long, val key: String, val variableCode: Int, val stringValue: Boolean) : NativeEffect
    data class WriteSaveValue(val requestId: Long, val key: String, val variableCode: Int, val number: Int? = null, val text: String? = null) : NativeEffect
    /** The host supplies its actual font-layout dimensions; no guessed text bitmap size. */
    data class MeasurePicture(val requestId: Long, val picture: NativePicture, val fontWeight: Int,
        val fontOverrides: Map<Int, String> = picture.fontOverrides) : NativeEffect
    /** SysS62..65 select game fonts; index5 (SysS67) selects the text-entry font. */
    data class FontOverride(val requestId: Long, val index: Int, val name: String) : NativeEffect
    /** Sys148 changes only the next process launch; Android retains its adaptive viewport. */
    data class NextLaunchScale(val permille: Int) : NativeEffect
    /** Decode the original mask's blue channel; alpha and image luminance never replace it. */
    data class MaskingImage(val requestId: Long, val path: String, val x: Int, val y: Int, val anchor: Int, val mode: Int) : NativeEffect
    data class TextInput(val requestId: Long, val initialText: String, val maxLength: Int, val cancellable: Boolean,
        val fontName: String? = null) : NativeEffect
    /** The host confirms its currently edited text; the VM never substitutes an old initial value. */
    data class ConfirmTextInput(val requestId: Long) : NativeEffect
    /** Wolf's SAPI speed (-10..10), and volume after its optional Sys102 correction. */
    data class Speech(val requestId: Long, val text: String, val speed: Int = 0, val volume: Int = 100,
        val voice: String? = null) : NativeEffect
    data class SpeechVoices(val requestId: Long) : NativeEffect
    data object StopSpeech : NativeEffect
    /** String command 122: game-relative text read/write/list and file/audio probes. */
    data class FileOperation(val requestId: Long, val operation: Int, val path: String,
        val text: String = "", val encodingMode: Int = 0, val modernText: Boolean = true,
        val windowsTextMode: Boolean = true) : NativeEffect
    /** Command260 is asynchronous; only the host's actual completion sets Sys48. */
    data class Download(val requestId: Long, val request: WolfDownloadRequest) : NativeEffect
    /** Returning to the title or restoring a world abandons its in-flight reception. */
    data class CancelDownload(val requestId: Long) : NativeEffect
    data class Diagnostic(val message: String) : NativeEffect
    data object Quit : NativeEffect
}

data class WolfCapabilityIssue(
    val file: String,
    val eventId: Int,
    val opcode: Int,
    val offset: Int,
    val reason: String,
)

data class WolfCapabilities(val issues: List<WolfCapabilityIssue>, val scannedMapCount: Int = 0, val inspectedAllMaps: Boolean = false) {
    val supported: Boolean get() = issues.isEmpty()
    fun description(): String = issues.take(12).joinToString("\n") {
        "${it.file} / événement ${it.eventId} / commande ${it.opcode} @0x${it.offset.toString(16)} : ${it.reason}"
    }
}

class WolfRuntimeException(
    val mapId: Int,
    val eventId: Int,
    val commonId: Int,
    val opcode: Int,
    val offset: Int,
    reason: String,
) : IllegalStateException("Wolf natif : carte $mapId, événement $eventId, commun $commonId, commande $opcode @0x${offset.toString(16)} : $reason")
