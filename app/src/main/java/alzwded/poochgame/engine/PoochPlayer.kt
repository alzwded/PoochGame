package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Controller and renderer for Pooch (the player character).
 *
 * Implements:
 * - 6-frame looping walk cycle (cycling every 1/6th of a second).
 * - 5-phase jump progression:
 *   1. jump_0 used for windup (~3-4 frames @ 60 FPS).
 *   2. Cycle through jump_1, jump_2, jump_3 until reaching the apex (vy >= 0).
 *   3. jump_4 during downward fall until landing.
 *   4. jump_5 upon landing for a few frames (~5 frames @ 60 FPS).
 *   5. Seamless return to walk cycle.
 * - Origin-anchored positioning at the middle of Pooch's head (which sits ~69 unscaled
 *   pixels above the ground contact line).
 * - Strictly zero-allocation per frame rendering loop.
 */
class PoochPlayer(private val atlas: SpriteAtlas) {

    enum class AnimState {
        WALK,
        JUMP_WINDUP,
        JUMP_ASCENT,
        JUMP_FALL,
        JUMP_LANDING
    }

    companion object {
        /** Duration of each walk frame in seconds (1/6th of a second). */
        const val WALK_FRAME_DURATION = 1.0f / 6.0f

        /** Duration of initial crouch/windup frame (jump_0) in seconds. */
        const val JUMP_WINDUP_DURATION = 0.06f

        /** Expected ascent duration used to space jump_1, jump_2, jump_3 evenly. */
        const val JUMP_ASCENT_DURATION = 0.36f

        /** Duration of landing recovery frame (jump_5) in seconds. */
        const val JUMP_LANDING_DURATION = 0.08f

        /** Distance in unscaled sprite pixels from head origin to ground contact. */
        const val HEAD_TO_GROUND_OFFSET_UNSCALED = 69f
    }

    // Direct indexed arrays for zero-allocation O(1) frame lookup
    // 5 walk frames: walk_0 to walk_4 (walk_5 omitted per user request)
    val walkFrames: Array<SpriteFrame> = Array(5) { index ->
        atlas.getFrame("walk_$index")
            ?: throw IllegalStateException("Missing walk_$index frame in pooch atlas")
    }

    val jumpFrames: Array<SpriteFrame> = Array(6) { index ->
        atlas.getFrame("jump_$index")
            ?: throw IllegalStateException("Missing jump_$index frame in pooch atlas")
    }

    // --- Virtual Coordinates (1080x1728 workspace) ---
    var virtualX: Float = 260f
    var virtualY: Float = 0f
    var velocityY: Float = 0f
    var isOnGround: Boolean = true
        private set

    // --- Animation Timers & State ---
    var animState: AnimState = AnimState.WALK
        private set

    var walkTimer: Float = 0f
        private set
    var jumpTimer: Float = 0f
        private set
    var landingTimer: Float = 0f
        private set

    /** Currently active frame to render. */
    var currentFrame: SpriteFrame = walkFrames[0]
        private set

    // Pre-allocated draw rects & paint (zero per-frame allocations)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        reset()
    }

    /**
     * Compute Pooch's head origin Y position when standing on the ground.
     */
    fun getGroundHeadY(scale: Float = EntityRegistry.POOCH.baseScale): Float {
        return GameConstants.GROUND_Y - (HEAD_TO_GROUND_OFFSET_UNSCALED * scale)
    }

    /**
     * Reset player position and animation state for a new game or respawn.
     */
    fun reset() {
        val baseScale = EntityRegistry.POOCH.baseScale
        virtualY = getGroundHeadY(baseScale)
        velocityY = 0f
        isOnGround = true
        animState = AnimState.WALK
        walkTimer = 0f
        jumpTimer = 0f
        landingTimer = 0f
        currentFrame = walkFrames[0]
    }

    /**
     * Trigger a jump if the player is grounded (or in landing recovery).
     *
     * @return true if jump was triggered, false otherwise.
     */
    fun jump(): Boolean {
        if (!isOnGround && animState != AnimState.JUMP_LANDING) {
            return false
        }

        isOnGround = false
        animState = AnimState.JUMP_WINDUP
        jumpTimer = 0f
        velocityY = GameConstants.JUMP_IMPULSE // -1600 px/s
        currentFrame = jumpFrames[0]
        return true
    }

    /**
     * Update animation timing and kinematics for one frame.
     *
     * @param dt Elapsed delta time in seconds, capped at [GameConstants.MAX_DELTA_TIME].
     * @param isSuperMode Whether super mode is currently active (doubles run tempo).
     */
    fun update(dt: Float, isSuperMode: Boolean) {
        val baseScale = if (isSuperMode) {
            EntityRegistry.POOCH.baseScale * GameConstants.SUPER_MODE_SCALE
        } else {
            EntityRegistry.POOCH.baseScale
        }
        val groundHeadY = getGroundHeadY(baseScale)

        when (animState) {
            AnimState.WALK -> {
                val frameDuration = if (isSuperMode) {
                    WALK_FRAME_DURATION * 0.5f // Double tempo in super mode
                } else {
                    WALK_FRAME_DURATION
                }
                walkTimer += dt
                val index = ((walkTimer / frameDuration).toInt()) % 5
                currentFrame = walkFrames[index]
            }

            AnimState.JUMP_WINDUP -> {
                jumpTimer += dt
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt

                if (jumpTimer >= JUMP_WINDUP_DURATION) {
                    animState = AnimState.JUMP_ASCENT
                    val ascentElapsed = jumpTimer - JUMP_WINDUP_DURATION
                    val progress = (ascentElapsed / JUMP_ASCENT_DURATION).coerceIn(0f, 0.999f)
                    val frameIdx = 1 + (progress * 3).toInt()
                    currentFrame = jumpFrames[frameIdx.coerceIn(1, 3)]
                } else {
                    currentFrame = jumpFrames[0]
                }
            }

            AnimState.JUMP_ASCENT -> {
                jumpTimer += dt
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt

                if (velocityY >= 0f) {
                    // Apex reached! Transition to downward fall
                    animState = AnimState.JUMP_FALL
                    currentFrame = jumpFrames[4]
                } else {
                    // Cycle jump_1, jump_2, jump_3 during ascent
                    val ascentElapsed = jumpTimer - JUMP_WINDUP_DURATION
                    val progress = (ascentElapsed / JUMP_ASCENT_DURATION).coerceIn(0f, 0.999f)
                    val frameIdx = 1 + (progress * 3).toInt() // 1, 2, or 3
                    currentFrame = jumpFrames[frameIdx.coerceIn(1, 3)]
                }
            }

            AnimState.JUMP_FALL -> {
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt
                currentFrame = jumpFrames[4]

                // Check landing
                if (virtualY >= groundHeadY) {
                    virtualY = groundHeadY
                    velocityY = 0f
                    isOnGround = true
                    animState = AnimState.JUMP_LANDING
                    landingTimer = JUMP_LANDING_DURATION
                    currentFrame = jumpFrames[5]
                }
            }

            AnimState.JUMP_LANDING -> {
                landingTimer -= dt
                currentFrame = jumpFrames[5]

                if (landingTimer <= 0f) {
                    animState = AnimState.WALK
                    walkTimer = 0f
                    currentFrame = walkFrames[0]
                }
            }
        }
    }

    /**
     * Render the active Pooch sprite frame anchored to virtual coordinates.
     * ZERO memory allocations inside this method.
     */
    fun render(canvas: Canvas, scaleX: Float, scaleY: Float, isSuperMode: Boolean) {
        val scale = if (isSuperMode) {
            EntityRegistry.POOCH.baseScale * GameConstants.SUPER_MODE_SCALE
        } else {
            EntityRegistry.POOCH.baseScale
        }

        val frame = currentFrame

        // Anchor frame at (virtualX, virtualY) via (originX, originY)
        val virtualLeft = virtualX - (frame.originX * scale)
        val virtualTop = virtualY - (frame.originY * scale)
        val virtualRight = virtualLeft + (frame.width * scale)
        val virtualBottom = virtualTop + (frame.height * scale)

        srcRect.set(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
        dstRect.set(
            virtualLeft * scaleX,
            virtualTop * scaleY,
            virtualRight * scaleX,
            virtualBottom * scaleY
        )

        val bitmap = atlas.bitmap ?: return
        canvas.drawBitmap(bitmap, srcRect, dstRect, spritePaint)
    }
}
