package alzwded.poochgame.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * High-performance, zero-allocation utility for rendering sprite frames with
 * optional horizontal mirroring around their anchor point.
 */
object SpriteRenderer {

    /**
     * Draws a sprite frame anchored at virtual coordinates (virtualX, virtualY) with
     * optional horizontal mirroring around the anchor.
     *
     * @param canvas Target hardware-accelerated canvas.
     * @param bitmap Atlas bitmap containing the frame region.
     * @param frame SpriteFrame descriptor specifying crop region and origin pivot.
     * @param virtualX Horizontal virtual anchor point.
     * @param virtualY Vertical virtual anchor point.
     * @param scale Virtual scale multiplier.
     * @param scaleX Horizontal screen scaling factor (physical / virtual).
     * @param scaleY Vertical screen scaling factor (physical / virtual).
     * @param mirrored Whether to flip horizontally around the virtual anchor.
     * @param paint Pre-allocated Paint instance.
     * @param srcRect Pre-allocated Rect for source bounds (mutated in-place).
     * @param dstRect Pre-allocated RectF for destination bounds (mutated in-place).
     */
    fun drawFrame(
        canvas: Canvas,
        bitmap: Bitmap,
        frame: SpriteFrame,
        virtualX: Float,
        virtualY: Float,
        scale: Float,
        scaleX: Float,
        scaleY: Float,
        mirrored: Boolean,
        paint: Paint,
        srcRect: Rect,
        dstRect: RectF
    ) {
        val virtualLeft = virtualX - (frame.originX * scale)
        val virtualTop = virtualY - (frame.originY * scale)
        val virtualRight = virtualLeft + (frame.width * scale)
        val virtualBottom = virtualTop + (frame.height * scale)

        srcRect.set(
            frame.x,
            frame.y,
            (frame.x + frame.width).coerceAtLeast(frame.x),
            (frame.y + frame.height).coerceAtLeast(frame.y)
        )
        dstRect.set(
            virtualLeft * scaleX,
            virtualTop * scaleY,
            virtualRight * scaleX,
            virtualBottom * scaleY
        )

        if (mirrored) {
            canvas.save()
            // Flip horizontally across the vertical axis passing through virtual anchor
            canvas.scale(-1f, 1f, virtualX * scaleX, 0f)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
    }
}
