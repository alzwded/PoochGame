package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Collectible Heart item entity.
 * Restores +1 HP up to max 3 HP when picked up.
 */
class HeartCollectible(private val atlas: SpriteAtlas) {
    enum class State {
        INACTIVE,
        ACTIVE
    }

    companion object {
        const val HITBOX_INSET = 0.5f
        const val FLYING_SPEED = 100f
        val MAX_JUMP_HEIGHT: Float
            get() = (GameConstants.JUMP_IMPULSE * GameConstants.JUMP_IMPULSE) / (2f * GameConstants.GRAVITY)
    }

    private val allFrames: List<SpriteFrame> = atlas.frames
    var state: State = State.INACTIVE
        private set
    val isActive: Boolean get() = state == State.ACTIVE

    var virtualX: Float = 0f
    var virtualY: Float = 0f
    var extraSpeed: Float = 0f
    var isFlying: Boolean = false
    var scale: Float = EntityRegistry.HEART.baseScale
    var currentFrame: SpriteFrame = allFrames.firstOrNull()
        ?: SpriteFrame("heart_0", 0, 0, 232, 214, 114, 102)
        private set

    val hitbox: RectF = RectF()

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun spawn(spawnX: Float, flying: Boolean, speedOffset: Float = 0f) {
        state = State.ACTIVE
        isFlying = flying
        extraSpeed = speedOffset
        scale = EntityRegistry.HEART.baseScale
        currentFrame = allFrames.random()

        val groundOffset = (currentFrame.height - currentFrame.originY) * scale
        val baseGroundY = GameConstants.GROUND_Y - groundOffset
        virtualY = if (isFlying) {
            baseGroundY - MAX_JUMP_HEIGHT
        } else {
            baseGroundY - 40f
        }
        virtualX = spawnX
        updateHitbox()
    }

    fun update(dt: Float, scrollSpeed: Float, playerVirtualX: Float) {
        if (state != State.ACTIVE) return
        virtualX -= (scrollSpeed + extraSpeed) * dt
        updateHitbox()
        if (virtualX < -250f) {
            reset()
        }
    }

    fun onCollect() {
        reset()
    }

    fun reset() {
        state = State.INACTIVE
        clearHitbox()
    }

    private fun updateHitbox() {
        val f = currentFrame
        val w = f.width * scale
        val h = f.height * scale
        val left = virtualX - (f.originX * scale)
        val top = virtualY - (f.originY * scale)
        val right = left + w
        val bottom = top + h
        val insetX = w * (1f - HITBOX_INSET) * 0.5f
        val insetY = h * (1f - HITBOX_INSET) * 0.5f
        hitbox.left = left + insetX
        hitbox.top = top + insetY
        hitbox.right = right - insetX
        hitbox.bottom = bottom - insetY
    }

    private fun clearHitbox() {
        hitbox.left = 0f
        hitbox.top = 0f
        hitbox.right = 0f
        hitbox.bottom = 0f
    }

    fun render(canvas: Canvas, scaleX: Float, scaleY: Float) {
        if (state != State.ACTIVE) return
        val bitmap = atlas.bitmap ?: return
        SpriteRenderer.drawFrame(
            canvas = canvas,
            bitmap = bitmap,
            frame = currentFrame,
            virtualX = virtualX,
            virtualY = virtualY,
            scale = scale,
            scaleX = scaleX,
            scaleY = scaleY,
            mirrored = false,
            paint = paint,
            srcRect = srcRect,
            dstRect = dstRect
        )
    }
}
