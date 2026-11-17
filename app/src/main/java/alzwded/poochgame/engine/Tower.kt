package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/**
 * Entity representing a vertical stack/tower of Shy Guys or Fudge as a unified object.
 * Has its own independent rendering, collision hitbox, and physics logic,
 * allowing custom business rules distinct from single [ShyGuyEnemy] units.
 *
 * Strictly zero-allocation per frame loop.
 */
class Tower(
    private val shyguyAtlas: SpriteAtlas,
    private val fudgeAtlas: SpriteAtlas
) {
    enum class Type {
        SHY_GUY,
        FUDGE
    }

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

    val fudgeFramesPool: List<SpriteFrame> = fudgeAtlas.frames
    val currentFudgeFrames: Array<SpriteFrame> = Array(3) {
        fudgeFramesPool.firstOrNull()
            ?: SpriteFrame("fudge_0", 0, 0, 219, 212, 110, 162)
    }

    var type: Type = Type.SHY_GUY
        private set

    var state: State = State.INACTIVE
        private set

    val isActive: Boolean get() = state != State.INACTIVE

    var virtualX: Float = 0f
    var virtualY: Float = 0f
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

    // Independent velocities and offsets per stack layer (0 = bottommost, 1 = middle, 2 = topmost)
    private val vx = FloatArray(3)
    private val vy = FloatArray(3)
    private val offsetX = FloatArray(3)
    private val offsetY = FloatArray(3)

    fun spawn(spawnX: Float, towerHeight: Int = 3, forcedType: Type? = null) {
        state = State.WALKING
        type = forcedType ?: if (Random.nextBoolean()) Type.SHY_GUY else Type.FUDGE
        virtualX = spawnX
        height = towerHeight
        for (i in 0 until 3) {
            offsetX[i] = 0f
            offsetY[i] = 0f
            vx[i] = 0f
            vy[i] = 0f
        }

        if (type == Type.SHY_GUY) {
            scale = EntityRegistry.SHY_GUY.baseScale
            targetScale = scale
            initialHurtScale = scale
            virtualY = GameConstants.GROUND_Y - (GROUND_OFFSET * scale)
            currentWalkFrame = walkFrames[0]
        } else {
            scale = EntityRegistry.FUDGE.baseScale
            targetScale = scale
            initialHurtScale = scale
            if (fudgeFramesPool.isNotEmpty()) {
                for (i in 0 until 3) {
                    currentFudgeFrames[i] = fudgeFramesPool.random()
                }
            }
            val bottomFudge = currentFudgeFrames[0]
            val groundOffset = (bottomFudge.height - bottomFudge.originY).toFloat()
            virtualY = GameConstants.GROUND_Y - (groundOffset * scale)
        }

        walkTimer = 0f
        hurtTimer = 0f
        isCleared = false
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

        // Assign distinct explosive trajectories for each of the 3 sprites in the tower
        vx[0] = -300f
        vy[0] = -700f

        vx[1] = 0f
        vy[1] = -1050f

        vx[2] = 300f
        vy[2] = -850f

        for (i in 0 until 3) {
            offsetX[i] = 0f
            offsetY[i] = 0f
        }

        currentHurtFrame = hurtFrames[0]
        return true
    }

    fun update(dt: Float, scrollSpeed: Float, playerVirtualX: Float) {
        when (state) {
            State.INACTIVE -> return
            State.WALKING -> {
                val currentSpeed = if (type == Type.SHY_GUY) scrollSpeed + WALK_SPEED else scrollSpeed
                virtualX -= currentSpeed * dt
                if (type == Type.SHY_GUY) {
                    walkTimer += dt
                    val frameIdx = ((walkTimer / WALK_FRAME_DURATION).toInt()) % 6
                    currentWalkFrame = walkFrames[frameIdx]
                }
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

                // Update each sprite layer independently with its own velocity and gravity
                for (i in 0 until height.coerceAtMost(3)) {
                    vy[i] += GameConstants.GRAVITY * dt
                    offsetX[i] += vx[i] * dt
                    offsetY[i] += vy[i] * dt
                }

                if (type == Type.SHY_GUY) {
                    val frameIdx = ((hurtTimer / HURT_FRAME_DURATION).toInt()) % 4
                    currentHurtFrame = hurtFrames[frameIdx]
                }

                val progress = (hurtTimer / FLING_DURATION).coerceIn(0f, 1f)
                scale = initialHurtScale + (targetScale - initialHurtScale) * progress

                var allOffscreen = true
                val stackSpacing = 130f * scale
                for (i in 0 until height.coerceAtMost(3)) {
                    val partY = virtualY - (i * stackSpacing) + offsetY[i]
                    if (partY <= GameConstants.VIRTUAL_HEIGHT + 250f) {
                        allOffscreen = false
                        break
                    }
                }

                if (hurtTimer >= FLING_DURATION || allOffscreen || scale <= 0.03f) {
                    state = State.INACTIVE
                }
            }
        }
    }

    private fun updateHitbox() {
        val f = if (type == Type.SHY_GUY) currentWalkFrame else currentFudgeFrames[0]
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
        val stackSpacing = 130f * scale

        if (type == Type.SHY_GUY) {
            val bitmap = shyguyAtlas.bitmap ?: return
            val frame = if (state == State.WALKING) currentWalkFrame else currentHurtFrame
            for (h in 0 until height) {
                val idx = h.coerceIn(0, 2)
                val mirrored = (state == State.WALKING || vx[idx] < 0)
                val xPos = virtualX + offsetX[idx]
                val yPos = virtualY - (h * stackSpacing) + offsetY[idx]
                SpriteRenderer.drawFrame(
                    canvas = canvas,
                    bitmap = bitmap,
                    frame = frame,
                    virtualX = xPos,
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
        } else {
            val bitmap = fudgeAtlas.bitmap ?: return
            for (h in 0 until height) {
                val xPos = virtualX + offsetX[h.coerceIn(0, 2)]
                val yPos = virtualY - (h * stackSpacing) + offsetY[h.coerceIn(0, 2)]
                val frame = currentFudgeFrames[h.coerceIn(0, 2)]
                SpriteRenderer.drawFrame(
                    canvas = canvas,
                    bitmap = bitmap,
                    frame = frame,
                    virtualX = xPos,
                    virtualY = yPos,
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
    }

    fun reset() {
        state = State.INACTIVE
        for (i in 0 until 3) {
            offsetX[i] = 0f
            offsetY[i] = 0f
        }
        clearHitbox()
        isCleared = true
    }
}
