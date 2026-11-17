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
        SUPER_CUTSCENE,
        GAME_OVER,
        SPLASH_HOP
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

        // Cutscene hop & grow constants
        const val SUPER_HOP_FRAME_DURATION = 0.08f // 0.08s * 3 frames = 0.24s per hop
        const val SUPER_HOP_HEIGHT = 65f           // Small hop height in virtual pixels
        const val SUPER_GROW_DURATION = 0.20f       // Duration of 2x scale-up phase
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
    var cutsceneTimer: Float = 0f
        private set
    var isCutsceneFinished: Boolean = false
        private set
    var isSuperMode: Boolean = false
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
        isSuperMode = false
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
        cutsceneTimer = 0f
        isCutsceneFinished = false
        isInvincible = false
        isVisible = true
        isMirrored = false
        currentFrame = walkFrames[0]
        updateHitbox(baseScale, isSuperMode)
    }

    /**
     * Trigger the automated Super Mode cutscene set piece:
     * Plays 2nd..4th walk frames unmirrored, then mirrored (with sfx_arfarf),
     * then sets frame 0 facing right and scales up to 2.0x size.
     */
    fun triggerSuperCutscene() {
        animState = AnimState.SUPER_CUTSCENE
        isSuperMode = true
        cutsceneTimer = 0f
        isCutsceneFinished = false
        isInvincible = true
        isVisible = true
        isMirrored = false
        scale = EntityRegistry.POOCH.baseScale
        targetScale = EntityRegistry.POOCH.baseScale * GameConstants.SUPER_MODE_SCALE
        currentFrame = jumpFrames[1] // 2nd frame of jump animation
        SoundManager.playArfArf()
    }

    fun endSuperCutscene() {
        animState = AnimState.WALK
        isSuperMode = true
        walkTimer = 0f
        isCutsceneFinished = true
        isInvincible = false
        isMirrored = false
        scale = EntityRegistry.POOCH.baseScale * GameConstants.SUPER_MODE_SCALE
        targetScale = scale
        currentFrame = walkFrames[0]
    }

    /**
     * Resets Pooch back to normal size and ground baseline upon exiting Super Mode.
     * Restores jump state flags.
     */
    fun exitSuperMode() {
        isSuperMode = false
        val baseScale = EntityRegistry.POOCH.baseScale
        scale = baseScale
        targetScale = baseScale
        virtualY = getGroundHeadY(baseScale)
        velocityY = 0f
        isOnGround = true
        animState = AnimState.WALK
        walkTimer = 0f
        jumpTimer = 0f
        landingTimer = 0f
        hurtTimer = 0f
        isInvincible = false
        isVisible = true
        isMirrored = false
        currentFrame = walkFrames[0]
        updateHitbox(baseScale, false)
    }

    /**
     * Trigger continuous short hop animation for the splash screen.
     */
    fun triggerSplashHop() {
        animState = AnimState.SPLASH_HOP
        cutsceneTimer = 0f
        isInvincible = true
        isVisible = true
        isMirrored = false
        scale = EntityRegistry.POOCH.baseScale
        targetScale = scale
        currentFrame = jumpFrames[1]
    }

    /**
     * Trigger a jump if the player is grounded (or in landing recovery).
     *
     * @return true if jump was triggered, false otherwise.
     */
    fun jump(): Boolean {
        if (isSuperMode ||
            (!isOnGround && animState != AnimState.JUMP_LANDING) ||
            animState == AnimState.HURT ||
            animState == AnimState.SUPER_CUTSCENE ||
            animState == AnimState.SPLASH_HOP ||
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
    fun update(dt: Float, isSuperMode: Boolean, superRemainingTime: Float = -1f) {
        this.isSuperMode = isSuperMode || animState == AnimState.SUPER_CUTSCENE
        val baseScale = EntityRegistry.POOCH.baseScale
        val superScale = baseScale * GameConstants.SUPER_MODE_SCALE

        if (animState != AnimState.GAME_OVER && animState != AnimState.SUPER_CUTSCENE) {
            if (isSuperMode) {
                if (superRemainingTime in 0f..GameConstants.SUPER_SCALE_DOWN_DURATION) {
                    val progress = (superRemainingTime / GameConstants.SUPER_SCALE_DOWN_DURATION).coerceIn(0f, 1f)
                    scale = baseScale + (superScale - baseScale) * progress
                } else {
                    scale = superScale
                }
            } else {
                scale = baseScale
            }
            targetScale = scale
        }
        val groundHeadY = getGroundHeadY(scale)

        when (animState) {
            AnimState.WALK -> {
                if (isOnGround) {
                    virtualY = groundHeadY
                }
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
                if (isOnGround) {
                    virtualY = groundHeadY
                }
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

            AnimState.SUPER_CUTSCENE -> {
                cutsceneTimer += dt
                val hopDur = SUPER_HOP_FRAME_DURATION * 3f // 0.24s per hop
                val growDur = SUPER_GROW_DURATION           // 0.20s grow phase

                if (cutsceneTimer < hopDur) {
                    // Hop 1: facing right, jump_1..jump_3
                    val progress = (cutsceneTimer / hopDur).coerceIn(0f, 1f)
                    val frameIdx = 1 + (progress * 2.999f).toInt().coerceIn(0, 2)
                    currentFrame = jumpFrames[frameIdx]
                    isMirrored = false
                    scale = baseScale
                    val hopY = -kotlin.math.sin(progress * Math.PI).toFloat() * SUPER_HOP_HEIGHT
                    virtualY = getGroundHeadY(scale) + hopY
                } else if (cutsceneTimer < hopDur * 2f) {
                    // Hop 2: facing left (mirrored), jump_1..jump_3
                    val relTime = cutsceneTimer - hopDur
                    val progress = (relTime / hopDur).coerceIn(0f, 1f)
                    val frameIdx = 1 + (progress * 2.999f).toInt().coerceIn(0, 2)
                    currentFrame = jumpFrames[frameIdx]
                    isMirrored = true
                    scale = baseScale
                    val hopY = -kotlin.math.sin(progress * Math.PI).toFloat() * SUPER_HOP_HEIGHT
                    virtualY = getGroundHeadY(scale) + hopY
                } else if (cutsceneTimer < hopDur * 2f + growDur) {
                    // Grow phase: facing right, walk_0 frame, smoothly scale up to superScale
                    val relTime = cutsceneTimer - (hopDur * 2f)
                    val growProgress = (relTime / growDur).coerceIn(0f, 1f)
                    currentFrame = walkFrames[0]
                    isMirrored = false
                    scale = baseScale + (superScale - baseScale) * growProgress
                    virtualY = getGroundHeadY(scale)
                } else {
                    // Finish cutscene -> transition to WALK state at super scale
                    endSuperCutscene()
                    virtualY = getGroundHeadY(scale)
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

            AnimState.SPLASH_HOP -> {
                cutsceneTimer += dt
                val hopDur = SUPER_HOP_FRAME_DURATION * 3f // 0.24s per hop
                val cycleTime = cutsceneTimer % (hopDur * 2f)

                if (cycleTime < hopDur) {
                    // Hop 1: facing right, jump_1..jump_3
                    val progress = (cycleTime / hopDur).coerceIn(0f, 1f)
                    val frameIdx = 1 + (progress * 2.999f).toInt().coerceIn(0, 2)
                    currentFrame = jumpFrames[frameIdx]
                    isMirrored = false
                    scale = baseScale
                    val hopY = -kotlin.math.sin(progress * Math.PI).toFloat() * SUPER_HOP_HEIGHT
                    virtualY = getGroundHeadY(scale) + hopY
                } else {
                    // Hop 2: facing left (mirrored), jump_1..jump_3
                    val relTime = cycleTime - hopDur
                    val progress = (relTime / hopDur).coerceIn(0f, 1f)
                    val frameIdx = 1 + (progress * 2.999f).toInt().coerceIn(0, 2)
                    currentFrame = jumpFrames[frameIdx]
                    isMirrored = true
                    scale = baseScale
                    val hopY = -kotlin.math.sin(progress * Math.PI).toFloat() * SUPER_HOP_HEIGHT
                    virtualY = getGroundHeadY(scale) + hopY
                }
            }
        }

        updateHitbox(scale, isSuperMode)
    }

    private fun updateHitbox(scale: Float, isSuperMode: Boolean) {
        val f = currentFrame
        val w = f.width * scale
        val h = f.height * scale
        val left = virtualX - (f.originX * scale)
        val top = virtualY - (f.originY * scale)
        val right = left + w
        val bottom = top + h

        val insetFactor = if (isSuperMode) {
            EntityRegistry.POOCH.hitboxInsetFactor
        } else {
            0f
        }
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
        val mirrored = if (animState == AnimState.SUPER_CUTSCENE || animState == AnimState.SPLASH_HOP) isMirrored else (animState == AnimState.GAME_OVER && isMirrored)
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
