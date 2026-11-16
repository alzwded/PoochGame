package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/**
 * Entity representing a Shy Guy obstacle/enemy.
 *
 * Implements:
 * - 6-frame walk cycle (walk_0..walk_5) mirrored horizontally to face left.
 * - Hurt pain animation using ohoh_0..ohoh_3 sprites.
 * - Dynamic hurt physics: flung upward, gravity drop, and linear scaling to either
 *   2.0x (foreground throw) or 0.0x (background throw).
 * - Instant hitbox removal upon collision (no double jeopardy).
 * - Strictly zero-allocation per frame loop.
 */
class ShyGuyEnemy(private val atlas: SpriteAtlas) {

    enum class State {
        INACTIVE,
        WALKING,
        HURT_FLING
    }

    companion object {
        const val WALK_FRAME_DURATION = 0.12f
        const val HURT_FRAME_DURATION = 0.08f
        const val GROUND_OFFSET = 19f // Frame height (166) minus originY (147)
        const val HITBOX_INSET = 0.7f // 80% inset for fair play collisions
        const val WALK_SPEED = 100f    // Walking speed relative to scrolling ground
        const val FLING_DURATION = 0.85f
    }

    val walkFrames: Array<SpriteFrame> = Array(6) { i ->
        atlas.getFrame("walk_$i")
            ?: throw IllegalStateException("Missing walk_$i frame in shyguy atlas")
    }

    val hurtFrames: Array<SpriteFrame> = Array(4) { i ->
        atlas.getFrame("ohoh_$i")
            ?: throw IllegalStateException("Missing ohoh_$i frame in shyguy atlas")
    }

    var state: State = State.INACTIVE
        private set

    val isActive: Boolean get() = state != State.INACTIVE

    var virtualX: Float = 0f
    var virtualY: Float = 0f
    var velocityX: Float = 0f
    var velocityY: Float = 0f

    var scale: Float = 1.0f
    var targetScale: Float = 1.0f
    private var initialHurtScale: Float = 1.0f

    var walkTimer: Float = 0f
    var hurtTimer: Float = 0f

    var currentFrame: SpriteFrame = walkFrames[0]
        private set

    val hitbox: RectF = RectF()

    /**
     * Whether the player has cleared this enemy (either jumped over it or collided with it).
     */
    var isCleared: Boolean = false

    // Pre-allocated draw rects & paint (zero allocations)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun spawn(spawnX: Float) {
        state = State.WALKING
        virtualX = spawnX
        scale = EntityRegistry.SHY_GUY.baseScale
        targetScale = scale
        initialHurtScale = scale
        virtualY = GameConstants.GROUND_Y - (GROUND_OFFSET * scale)
        velocityX = 0f
        velocityY = 0f
        walkTimer = 0f
        hurtTimer = 0f
        isCleared = false
        currentFrame = walkFrames[0]
        updateHitbox()
    }

    /**
     * Triggered when the player bonks/collides with this enemy.
     * Instantly clears hitbox (no double jeopardy) and launches into hurt fling.
     */
    fun onPlayerCollision(): Boolean {
        if (state != State.WALKING) return false

        state = State.HURT_FLING
        isCleared = true
        clearHitbox()

        hurtTimer = 0f
        initialHurtScale = scale

        // Randomly scale to 2.0x (flung to foreground) or 0.0x (flung to background)
        //targetScale = if (Random.nextBoolean()) 2.0f else 0.0f
        // Overrulled, the game is more fun if they are always flown towards the screen
        targetScale = 2.0f

        velocityY = -900f
        velocityX = if (Random.nextBoolean()) 120f else -120f
        currentFrame = hurtFrames[0]
        return true
    }

    fun update(dt: Float, scrollSpeed: Float, playerVirtualX: Float) {
        when (state) {
            State.INACTIVE -> return

            State.WALKING -> {
                // Move with scrolling ground plus own walk speed to the left
                virtualX -= (scrollSpeed + WALK_SPEED) * dt

                walkTimer += dt
                val frameIdx = ((walkTimer / WALK_FRAME_DURATION).toInt()) % 6
                currentFrame = walkFrames[frameIdx]

                updateHitbox()

                // Clearance check: player has moved past this enemy
                if (!isCleared && virtualX < playerVirtualX - 40f) {
                    isCleared = true
                }

                // Despawn when walked off screen left
                if (virtualX < -250f) {
                    state = State.INACTIVE
                    isCleared = true
                }
            }

            State.HURT_FLING -> {
                hurtTimer += dt
                clearHitbox()

                // Gravity & velocity
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt
                virtualX += velocityX * dt

                // Cycle ohoh_* hurt animation
                val frameIdx = ((hurtTimer / HURT_FRAME_DURATION).toInt()) % 4
                currentFrame = hurtFrames[frameIdx]

                // Linearly interpolate scale towards targetScale (2x or 0x)
                val progress = (hurtTimer / FLING_DURATION).coerceIn(0f, 1f)
                scale = initialHurtScale + (targetScale - initialHurtScale) * progress

                // Despawn off bottom of screen or when scaled near 0
                if (virtualY > GameConstants.VIRTUAL_HEIGHT + 250f || scale <= 0.03f) {
                    state = State.INACTIVE
                }
            }
        }
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

    fun clearHitbox() {
        hitbox.left = 0f
        hitbox.top = 0f
        hitbox.right = 0f
        hitbox.bottom = 0f
    }

    fun render(canvas: Canvas, scaleX: Float, scaleY: Float) {
        if (state == State.INACTIVE) return

        val bitmap = atlas.bitmap ?: return
        // Walk frames in sheet face right, so mirror them to face left!
        val mirrored = (state == State.WALKING || velocityX < 0)

        SpriteRenderer.drawFrame(
            canvas = canvas,
            bitmap = bitmap,
            frame = currentFrame,
            virtualX = virtualX,
            virtualY = virtualY,
            scale = scale,
            scaleX = scaleX,
            scaleY = scaleY,
            mirrored = mirrored,
            paint = paint,
            srcRect = srcRect,
            dstRect = dstRect
        )
    }

    fun reset() {
        state = State.INACTIVE
        clearHitbox()
        isCleared = true
    }
}
