package fr.astragames.wolf

import java.util.Locale

/** Movie pictures retain the normal file-picture transform and composition group. */
object WolfVideo {
    fun isMovie(path: String): Boolean = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mpg", "mpeg", "avi", "ogv", "mp4")
    fun loops(path: String): Boolean = path.lowercase(Locale.ROOT).substringBeforeLast('.').endsWith("_loop")
    fun validate(state: NativeVideoState) {
        require(state.token >= 0 && state.seekMs in 0..Int.MAX_VALUE.toLong() && state.seekRevision >= 0 &&
            state.speedPercent in 0..400 && state.volumePercent in 0..100 &&
            state.positionMs in 0..Int.MAX_VALUE.toLong() && state.durationMs in 0..Int.MAX_VALUE.toLong() &&
            state.width in 0..16384 && state.height in 0..16384 && state.width.toLong() * state.height <= 16 * 1024 * 1024) {
            "État vidéo Wolf hors capacité"
        }
    }
}
