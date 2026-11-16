package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Controller and renderer for Pooch (the player character).
 *
 * Implements:
 * - 5-frame looping walk cycle (walk_0..walk_4) cycling every 1/6th of a second.
 * - 5-phase jump progression:
 *   1. jump_0 used for windup (~3-4 frames @ 60 FPS).
 *   2. Cycle through jump_1, jump_2, jump_3 until reaching the apex (vy >= 0).
 *   3. jump_4 during downward fall until landing.
 *   4. jump_5 upon landing for a few frames (~5 frames @ 60 FPS).
 *   5. Seamless return to walk cycle.
 * - 1.0s Hurt state: uses jump_5, invincible, non-strobing quarter-second flicker
 *   (show 1/4s, hide 1/4s, show 1/4s, hide 1/4s).
 * - Game Over state: upward fling + gravity fall off screen, cycling jump_1..jump_3
 *   and their horizontally mirrored states, linearly scaling towards 2.0f (foreground fling).
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
        JUMP_LANDING,
        HURT,
        GAME_OVER
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

        /** Total duration of hurt state in seconds. */
        const val HURT_DURATION = 1.0f

        /** Flicker quarter-interval for hurt state (0.25s). */
        const val HURT_FLICKER_INTERVAL = 0.25f

        /** Frame duration during Game Over death spin. */
        const val GAME_OVER_FRAME_DURATION = 0.08f

        /** Duration over which Pooch scales to targetScale during game over death fling. */
        const val GAME_OVER_FLING_DURATION = 0.85f

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

    // --- Virtual Coordinates & Scale (1080x1728 workspace) ---
    var virtualX: Float = 260f
    var virtualY: Float = 0f
    var velocityY: Float = 0f
    var isOnGround: Boolean = true
        private set

    var scale: Float = EntityRegistry.POOCH.baseScale
    var targetScale: Float = EntityRegistry.POOCH.baseScale
    private var initialGameOverScale: Float = EntityRegistry.POOCH.baseScale

    // --- Animation Timers & State ---
    var animState: AnimState = AnimState.WALK
        private set

    var walkTimer: Float = 0f
        private set
    var jumpTimer: Float = 0f
        private set
    var landingTimer: Float = 0f
        private set
    var hurtTimer: Float = 0f
        private set
    var gameOverTimer: Float = 0f
        private set

    var isInvincible: Boolean = false
        private set

    var isVisible: Boolean = true
        private set

    var isMirrored: Boolean = false
        private set

    /** Currently active frame to render. */
    var currentFrame: SpriteFrame = walkFrames[0]
        private set

    /** Inset collision bounds for fair play. */
    val hitbox: RectF = RectF()

    /** True if the game over death fall has moved completely off screen bottom. */
    val isGameOverAnimFinished: Boolean
        get() = (animState == AnimState.GAME_OVER && virtualY > GameConstants.VIRTUAL_HEIGHT + 250f)

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
     * True if the player is vulnerable to enemy collisions.
     */
    fun isVulnerable(): Boolean {
        return !isInvincible && animState != AnimState.HURT && animState != AnimState.GAME_OVER
    }

    /**
     * Reset player position and animation state for a new game or respawn.
     */
    fun reset() {
        val baseScale = EntityRegistry.POOCH.baseScale
        scale = baseScale
        targetScale = baseScale
        initialGameOverScale = baseScale
        virtualY = getGroundHeadY(baseScale)
        velocityY = 0f
        isOnGround = true
        animState = AnimState.WALK
        walkTimer = 0f
        jumpTimer = 0f
        landingTimer = 0f
        hurtTimer = 0f
        gameOverTimer = 0f
        isInvincible = false
        isVisible = true
        isMirrored = false
        currentFrame = walkFrames[0]
        updateHitbox(baseScale)
    }

    /**
     * Trigger a jump if the player is grounded (or in landing recovery).
     *
     * @return true if jump was triggered, false otherwise.
     */
    fun jump(): Boolean {
        if ((!isOnGround && animState != AnimState.JUMP_LANDING) ||
            animState == AnimState.HURT ||
            animState == AnimState.GAME_OVER
        ) {
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
     * Trigger the 1.0s hurt state: uses jump_5, enters invincibility, and flickers.
     */
    fun triggerHurt() {
        if (animState == AnimState.GAME_OVER) return

        animState = AnimState.HURT
        hurtTimer = 0f
        isInvincible = true
        isVisible = true
        isMirrored = false
        currentFrame = jumpFrames[5]
    }

    /**
     * Trigger the game over death animation: upward pop, gravity drop off screen,
     * cycling jump_1..jump_3 and their mirrored states, and scaling towards targetScale (2.0f).
     */
    fun triggerGameOver() {
        animState = AnimState.GAME_OVER
        gameOverTimer = 0f
        velocityY = -1200f
        isOnGround = false
        isInvincible = true
        isVisible = true
        isMirrored = false
        initialGameOverScale = scale
        targetScale = 2.0f
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

        if (animState != AnimState.GAME_OVER) {
            scale = baseScale
            targetScale = baseScale
        }

        when (animState) {
            AnimState.WALK -> {
                val frameDuration = if (isSuperMode) {
                    WALK_FRAME_DURATION * 0.5f
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
                    animState = AnimState.JUMP_FALL
                    currentFrame = jumpFrames[4]
                } else {
                    val ascentElapsed = jumpTimer - JUMP_WINDUP_DURATION
                    val progress = (ascentElapsed / JUMP_ASCENT_DURATION).coerceIn(0f, 0.999f)
                    val frameIdx = 1 + (progress * 3).toInt()
                    currentFrame = jumpFrames[frameIdx.coerceIn(1, 3)]
                }
            }

            AnimState.JUMP_FALL -> {
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt
                currentFrame = jumpFrames[4]

                if (virtualY >= groundHeadY) {
                    virtualY = groundHeadY
                    velocityY = 0f
                    isOnGround = true
                    animState = AnimState.JUMP_LANDING
                    landingTimer = JUMP_LANDING_DURATION
                    currentFrame = jumpFrames[5]
                    SoundManager.playThump()
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

            AnimState.HURT -> {
                hurtTimer += dt
                currentFrame = jumpFrames[5] // jump_5 during hurt

                // Non-strobing flicker: show 1/4s, hide 1/4s, show 1/4s, hide 1/4s
                val quarter = (hurtTimer / HURT_FLICKER_INTERVAL).toInt()
                isVisible = (quarter % 2 == 0)

                // Maintain physics if hurt mid-air
                if (!isOnGround) {
                    velocityY += GameConstants.GRAVITY * dt
                    virtualY += velocityY * dt
                    if (virtualY >= groundHeadY) {
                        virtualY = groundHeadY
                        velocityY = 0f
                        isOnGround = true
                        // Play landing thump sound if hitting ground while hurt
                        SoundManager.playThump()
                    }
                }

                if (hurtTimer >= HURT_DURATION) {
                    animState = AnimState.WALK
                    isInvincible = false
                    isVisible = true
                    walkTimer = 0f
                    currentFrame = walkFrames[0]
                }
            }

            AnimState.GAME_OVER -> {
                gameOverTimer += dt
                velocityY += GameConstants.GRAVITY * dt
                virtualY += velocityY * dt

                // Cycle jump_1, jump_2, jump_3, mirrored jump_1, mirrored jump_2, mirrored jump_3
                val cycleIdx = ((gameOverTimer / GAME_OVER_FRAME_DURATION).toInt()) % 6
                val frameIdx = (cycleIdx % 3) + 1 // maps to 1, 2, or 3
                currentFrame = jumpFrames[frameIdx]
                isMirrored = (cycleIdx >= 3)
                isVisible = true

                // Linearly interpolate scale towards targetScale (2.0f foreground fling)
                val progress = (gameOverTimer / GAME_OVER_FLING_DURATION).coerceIn(0f, 1f)
                scale = initialGameOverScale + (targetScale - initialGameOverScale) * progress
            }
        }

        updateHitbox(scale)
    }

    private fun updateHitbox(scale: Float) {
        val f = currentFrame
        val w = f.width * scale
        val h = f.height * scale
        val left = virtualX - (f.originX * scale)
        val top = virtualY - (f.originY * scale)
        val right = left + w
        val bottom = top + h

        val insetFactor = EntityRegistry.POOCH.hitboxInsetFactor
        val insetX = w * (1f - insetFactor) * 0.5f
        val insetY = h * (1f - insetFactor) * 0.5f
        hitbox.left = left + insetX
        hitbox.top = top + insetY
        hitbox.right = right - insetX
        hitbox.bottom = bottom - insetY
    }

    /**
     * Render the active Pooch sprite frame anchored to virtual coordinates.
     * ZERO memory allocations inside this method.
     */
    @Suppress("UNUSED_PARAMETER")
    fun render(canvas: Canvas, scaleX: Float, scaleY: Float, isSuperMode: Boolean) {
        if (!isVisible) return

        val bitmap = atlas.bitmap ?: return
        val mirrored = (animState == AnimState.GAME_OVER && isMirrored)

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
            paint = spritePaint,
            srcRect = srcRect,
            dstRect = dstRect
        )
    }
}
