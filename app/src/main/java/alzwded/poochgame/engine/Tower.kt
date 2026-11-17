package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/**
 * Entity representing a vertical stack/tower of Shy Guys as a unified object.
 * Has its own independent rendering, collision hitbox, and physics logic,
 * allowing custom business rules distinct from single [ShyGuyEnemy] units.
 *
 * Strictly zero-allocation per frame loop.
 */
class Tower(private val shyguyAtlas: SpriteAtlas) {
    enum class State {
        INACTIVE,
        WALKING,
        HURT_FLING
    }

    companion object {
        const val WALK_FRAME_DURATION = 0.12f
        const val HURT_FRAME_DURATION = 0.08f
        const val GROUND_OFFSET = 19f
        const val HITBOX_INSET = 0.7f
        const val WALK_SPEED = 100f
        const val FLING_DURATION = 0.85f
    }

    val walkFrames: Array<SpriteFrame> = Array(6) { i ->
        shyguyAtlas.getFrame("walk_$i")
            ?: throw IllegalStateException("Missing walk_$i frame in shyguy atlas")
    }
    val hurtFrames: Array<SpriteFrame> = Array(4) { i ->
        shyguyAtlas.getFrame("ohoh_$i")
            ?: throw IllegalStateException("Missing ohoh_$i frame in shyguy atlas")
    }

    var state: State = State.INACTIVE
        private set
    val isActive: Boolean get() = state != State.INACTIVE

    var virtualX: Float = 0f
    var virtualY: Float = 0f
    var velocityX: Float = 0f
    var velocityY: Float = 0f
    var height: Int = 3
    var scale: Float = 1.0f
    var targetScale: Float = 1.0f
    private var initialHurtScale: Float = 1.0f

    var walkTimer: Float = 0f
    var hurtTimer: Float = 0f
    var currentWalkFrame: SpriteFrame = walkFrames[0]
        private set
    var currentHurtFrame: SpriteFrame = hurtFrames[0]
        private set

    val hitbox: RectF = RectF()
    var isCleared: Boolean = false
        private set

    // Pre-allocated draw primitives (zero heap allocations per frame)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun spawn(spawnX: Float, towerHeight: Int = 3) {
        state = State.WALKING
        virtualX = spawnX
        height = towerHeight
        scale = EntityRegistry.SHY_GUY.baseScale
        targetScale = scale
        initialHurtScale = scale
        virtualY = GameConstants.GROUND_Y - (GROUND_OFFSET * scale)
        velocityX = 0f
        velocityY = 0f
        walkTimer = 0f
        hurtTimer = 0f
        isCleared = false
        currentWalkFrame = walkFrames[0]
        updateHitbox()
    }

    fun onPlayerCollision(): Boolean {
        if (state != State.WALKING) return false
        state = State.HURT_FLING
        isCleared = true
        clearHitbox()
        hurtTimer = 0f
        initialHurtScale = scale
        targetScale = 2.0f
        velocityY = -900f
        velocityX = if (Random.nextBoolean()) 120f else -120f
        currentHurtFrame = hurtFrames[0]
        return true
    }

    fun update(dt: Float, scrollSpeed: Float, playerVirtualX: Float) {
        when (state) {
            State.INACTIVE -> return
            State.WALKING -> {
                virtualX -= (scrollSpeed + WALK_SPEED) * dt
                walkTimer += dt
                val frameIdx = ((walkTimer / WALK_FRAME_DURATION).toInt()) % 6
                currentWalkFrame = walkFrames[frameIdx]
                updateHitbox()

                if (!isCleared && virtualX < playerVirtualX - 40f) {
                    isCleared = true
                }
                if (virtualX < -250f) {
                    state = State.INACTIVE
                    isCleared = true
                }
            }
            State.HURT_FLING -> {
                hurtTimer += dt
                clearHitbox()
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt
                virtualX += velocityX * dt

                val frameIdx = ((hurtTimer / HURT_FRAME_DURATION).toInt()) % 4
                currentHurtFrame = hurtFrames[frameIdx]

                val progress = (hurtTimer / FLING_DURATION).coerceIn(0f, 1f)
                scale = initialHurtScale + (targetScale - initialHurtScale) * progress

                if (virtualY > GameConstants.VIRTUAL_HEIGHT + 250f || scale <= 0.03f) {
                    state = State.INACTIVE
                }
            }
        }
    }

    private fun updateHitbox() {
        val f = currentWalkFrame
        val stackSpacing = 130f * scale
        val singleW = f.width * scale
        val singleH = f.height * scale

        val left = virtualX - (f.originX * scale)
        val bottom = virtualY - (f.originY * scale) + singleH
        val top = virtualY - ((height - 1) * stackSpacing) - (f.originX * scale)
        val right = left + singleW

        val insetX = singleW * (1f - HITBOX_INSET) * 0.5f
        val insetY = singleH * (1f - HITBOX_INSET) * 0.5f

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
        val bitmap = shyguyAtlas.bitmap ?: return
        val mirrored = (state == State.WALKING || velocityX < 0)
        val frame = if (state == State.WALKING) currentWalkFrame else currentHurtFrame
        val stackSpacing = 130f * scale

        for (h in 0 until height) {
            val yPos = virtualY - (h * stackSpacing)
            SpriteRenderer.drawFrame(
                canvas = canvas,
                bitmap = bitmap,
                frame = frame,
                virtualX = virtualX,
                virtualY = yPos,
                scale = scale,
                scaleX = scaleX,
                scaleY = scaleY,
                mirrored = mirrored,
                paint = paint,
                srcRect = srcRect,
                dstRect = dstRect
            )
        }
    }

    fun reset() {
        state = State.INACTIVE
        clearHitbox()
        isCleared = true
    }
}
