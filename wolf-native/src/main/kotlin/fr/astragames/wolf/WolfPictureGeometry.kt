package fr.astragames.wolf

import kotlin.math.cos
import kotlin.math.sin

/** W3 99da70: rectangle hit testing, including the3.64 right/bottom exclusive change. */
internal object WolfPictureGeometry {
    fun contains(picture: NativePicture, width: Int, height: Int, x: Int, y: Int,
        cameraX: Float, cameraY: Float, compatibilityVersion: Int): Boolean {
        val w = width * picture.scaleX.toDouble()
        val h = height * picture.scaleY.toDouble()
        val px = picture.x.toDouble() - if (picture.screenRelative) 0.0 else cameraX.toDouble()
        val py = picture.y.toDouble() - if (picture.screenRelative) 0.0 else cameraY.toDouble()
        val left = px - when (picture.anchor) { 1, 5, 6 -> w / 2; 3, 4 -> w; else -> 0.0 }
        val top = py - when (picture.anchor) { 1 -> h / 2; 2, 4, 6 -> h; else -> 0.0 }
        val cx = ((left + left + w) / 2).toInt().toDouble()
        val cy = ((top + top + h) / 2).toInt().toDouble()
        // The native easy-window path deliberately uses its unrotated bounding rectangle.
        val angle = if (picture.kind in setOf(3, 4) || compatibilityVersion < 230) 0.0
            else -picture.rotation * Math.PI / 180.0
        val dx = x - cx; val dy = y - cy
        val localX = cx + dx * cos(angle) - dy * sin(angle)
        val localY = cy + dx * sin(angle) + dy * cos(angle)
        val minX = minOf(left, left + w); val maxX = maxOf(left, left + w)
        val minY = minOf(top, top + h); val maxY = maxOf(top, top + h)
        return if (compatibilityVersion < 364 || angle != 0.0)
            localX >= minX && localX <= maxX && localY >= minY && localY <= maxY
        else localX >= minX && localX < maxX && localY >= minY && localY < maxY
    }
}
