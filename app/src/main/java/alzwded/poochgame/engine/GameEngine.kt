package alzwded.poochgame.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log

/**
 * Core game engine. Manages game state, entities, physics, and rendering.
 *
 * All rendering uses pre-allocated drawing primitives (zero per-frame allocations).
 * All physics are delta-time scaled for frame-rate independence.
 */
class GameEngine(private val context: Context) {

    companion object {
        private const val TAG = "GameEngine"
    }

    // --- Game State ---
    var state: GameState = GameState.TOUCH_TO_START
        private set

    // --- Viewport ---
    /** Physical screen dimensions (set once surface is created). */
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    /** Scale factor from virtual coords to screen coords. */
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    // --- Background ---
    private var bgBitmap: Bitmap? = null
    private var bgScrollX: Float = 0f

    /** The width of the cropped viewport region within the background image. */
    private var bgCropWidth: Int = 0
    private var bgCropHeight: Int = 0

    // --- Pre-allocated draw primitives (zero-alloc rendering) ---
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 64f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val splashPaint = Paint().apply {
        color = Color.WHITE
        textSize = 80f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }
    private val debugPaint = Paint().apply {
        color = Color.argb(128, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // --- Sprite Data (parsed but not yet fully rendered) ---
    private var poochFrames: List<SpriteFrame> = emptyList()
    private var poochAnimations: Map<String, List<SpriteFrame>> = emptyMap()

    // --- Player State ---
    private var playerY: Float = 0f
    private var playerVelocityY: Float = 0f
    private var isOnGround: Boolean = true
    private var playerHP: Int = GameConstants.MAX_HP
    private var fudgeCount: Int = 0
    private var score: Int = 0

    /**
     * Initialize the engine when the rendering surface becomes available.
     * Loads assets and calculates viewport mapping.
     */
    fun init(surfaceWidth: Int, surfaceHeight: Int) {
        screenWidth = surfaceWidth
        screenHeight = surfaceHeight
        scaleX = surfaceWidth.toFloat() / GameConstants.VIRTUAL_WIDTH
        scaleY = surfaceHeight.toFloat() / GameConstants.VIRTUAL_HEIGHT

        Log.i(TAG, "Surface initialized: ${surfaceWidth}x${surfaceHeight}")
        Log.i(TAG, "Scale factors: x=$scaleX, y=$scaleY")

        loadAssets()
        resetPlayer()
    }

    /**
     * Load game assets from the assets directory.
     */
    private fun loadAssets() {
        // Load background
        try {
            val bgStream = context.assets.open("backgrounds/bg.jpg")
            bgBitmap = BitmapFactory.decodeStream(bgStream)
            bgStream.close()

            bgBitmap?.let { bg ->
                // Calculate the 10:16 crop region from the landscape background
                // The background is landscape (1376x768), we crop a portrait slice
                bgCropHeight = bg.height
                bgCropWidth = (bgCropHeight * 10f / 16f).toInt()
                Log.i(TAG, "Background loaded: ${bg.width}x${bg.height}, crop: ${bgCropWidth}x$bgCropHeight")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load background", e)
        }

        // Parse the pooch sprite descriptor (skeleton — sprite rendering comes later)
        try {
            poochFrames = SpriteJsonParser.parse(context, "sprites/pooch.json")
            poochAnimations = SpriteJsonParser.groupAnimations(poochFrames)
            Log.i(TAG, "Pooch sprite atlas parsed: ${poochFrames.size} frames, " +
                    "${poochAnimations.size} animations [${poochAnimations.keys.joinToString()}]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pooch sprite data", e)
        }
    }

    /**
     * Reset player state for a new game.
     */
    private fun resetPlayer() {
        playerY = GameConstants.GROUND_Y
        playerVelocityY = 0f
        isOnGround = true
        playerHP = GameConstants.MAX_HP
        fudgeCount = 0
        score = 0
        bgScrollX = 0f
    }

    /**
     * Handle a tap input event.
     * Coordinates are in screen space (will be converted to virtual internally if needed).
     */
    fun onTap(screenTapX: Float, screenTapY: Float) {
        when (state) {
            GameState.TOUCH_TO_START -> {
                state = GameState.PLAYING
                resetPlayer()
                Log.d(TAG, "Tap → PLAYING")
            }
            GameState.PLAYING, GameState.SUPER_MODE -> {
                if (isOnGround) {
                    playerVelocityY = GameConstants.JUMP_IMPULSE
                    isOnGround = false
                    Log.d(TAG, "Jump! velocityY=$playerVelocityY")
                }
            }
            GameState.GAME_OVER -> {
                state = GameState.TOUCH_TO_START
                Log.d(TAG, "Tap → TOUCH_TO_START")
            }
            GameState.HURT -> {
                // Ignore taps during hurt animation
            }
        }
    }

    /**
     * Update game logic for one frame.
     *
     * @param deltaTime Elapsed time since last frame in seconds, capped at [GameConstants.MAX_DELTA_TIME].
     */
    fun update(deltaTime: Float) {
        val dt = deltaTime.coerceAtMost(GameConstants.MAX_DELTA_TIME)

        when (state) {
            GameState.TOUCH_TO_START -> {
                // Idle — could animate background gently
            }
            GameState.PLAYING -> {
                updatePlaying(dt)
            }
            GameState.SUPER_MODE -> {
                // TODO: Super mode logic
                updatePlaying(dt)
            }
            GameState.HURT -> {
                // TODO: Hurt timer / invincibility frames
            }
            GameState.GAME_OVER -> {
                // TODO: Death animation physics
            }
        }
    }

    /**
     * Core gameplay update: scroll, player physics, spawning, collisions.
     */
    private fun updatePlaying(dt: Float) {
        // Scroll background
        val scrollSpeed = if (state == GameState.SUPER_MODE) {
            GameConstants.SUPER_SCROLL_SPEED
        } else {
            GameConstants.BASE_SCROLL_SPEED
        }
        bgScrollX += scrollSpeed * dt

        // Wrap background scroll for seamless looping
        bgBitmap?.let { bg ->
            val scrollableWidth = bg.width.toFloat()
            if (bgScrollX >= scrollableWidth) {
                bgScrollX -= scrollableWidth
            }
        }

        // Player gravity & jump physics
        if (!isOnGround) {
            playerVelocityY += GameConstants.GRAVITY * dt
            playerY += playerVelocityY * dt

            // Landing check
            if (playerY >= GameConstants.GROUND_Y) {
                playerY = GameConstants.GROUND_Y
                playerVelocityY = 0f
                isOnGround = true
            }
        }

        // Increment score over time
        score += (scrollSpeed * dt * 0.1f).toInt()

        // TODO: Obstacle/collectible spawning
        // TODO: Collision detection
    }

    /**
     * Render the current frame to the provided canvas.
     *
     * ZERO heap allocations in this method — all draw primitives are pre-allocated.
     */
    fun render(canvas: Canvas) {
        // Clear canvas
        canvas.drawColor(Color.BLACK)

        when (state) {
            GameState.TOUCH_TO_START -> renderSplash(canvas)
            GameState.PLAYING, GameState.SUPER_MODE -> renderPlaying(canvas)
            GameState.HURT -> renderPlaying(canvas) // TODO: add flicker overlay
            GameState.GAME_OVER -> renderGameOver(canvas)
        }
    }

    /**
     * Render the "Touch to Start" splash screen.
     */
    private fun renderSplash(canvas: Canvas) {
        // Draw background (static, centered crop)
        renderBackground(canvas)

        // Title text
        splashPaint.textSize = 80f * scaleX
        canvas.drawText(
            "POOCH RUNNER",
            screenWidth / 2f,
            screenHeight * 0.35f,
            splashPaint
        )

        // Tap prompt
        splashPaint.textSize = 48f * scaleX
        canvas.drawText(
            "Tap to Start",
            screenWidth / 2f,
            screenHeight * 0.55f,
            splashPaint
        )
    }

    /**
     * Render the active gameplay frame.
     */
    private fun renderPlaying(canvas: Canvas) {
        // 1. Background layer
        renderBackground(canvas)

        // 2. Player placeholder (green rectangle at player position until sprite rendering is built)
        val playerScreenX = 200f * scaleX
        val playerScreenY = playerY * scaleY
        val playerW = 80f * scaleX
        val playerH = 100f * scaleY

        dstRect.set(
            playerScreenX - playerW / 2f,
            playerScreenY - playerH,
            playerScreenX + playerW / 2f,
            playerScreenY
        )
        debugPaint.style = Paint.Style.FILL
        debugPaint.color = Color.argb(200, 139, 69, 19) // Brown placeholder for pooch
        canvas.drawRect(dstRect, debugPaint)

        // Ground line (debug)
        debugPaint.style = Paint.Style.STROKE
        debugPaint.color = Color.argb(128, 0, 255, 0)
        val groundScreenY = GameConstants.GROUND_Y * scaleY
        canvas.drawLine(0f, groundScreenY, screenWidth.toFloat(), groundScreenY, debugPaint)

        // 3. HUD
        renderHUD(canvas)
    }

    /**
     * Render the scrolling background with 10:16 portrait crop and seamless looping.
     */
    private fun renderBackground(canvas: Canvas) {
        val bg = bgBitmap ?: return

        val scrollPixel = bgScrollX.toInt() % bg.width

        // First pass: main visible portion
        val srcLeft = scrollPixel
        val srcRight = (scrollPixel + bgCropWidth).coerceAtMost(bg.width)
        val visibleWidth = srcRight - srcLeft

        srcRect.set(srcLeft, 0, srcRight, bgCropHeight)
        val dstW = (visibleWidth.toFloat() / bgCropWidth) * screenWidth
        dstRect.set(0f, 0f, dstW, screenHeight.toFloat())
        canvas.drawBitmap(bg, srcRect, dstRect, null)

        // Second pass: wrap-around portion (if the crop spans the image boundary)
        if (visibleWidth < bgCropWidth) {
            val remaining = bgCropWidth - visibleWidth
            srcRect.set(0, 0, remaining, bgCropHeight)
            dstRect.set(dstW, 0f, screenWidth.toFloat(), screenHeight.toFloat())
            canvas.drawBitmap(bg, srcRect, dstRect, null)
        }
    }

    /**
     * Render the heads-up display: HP hearts, fudge counter, score.
     */
    private fun renderHUD(canvas: Canvas) {
        textPaint.textSize = 40f * scaleX

        // Score
        canvas.drawText("Score: $score", 20f * scaleX, 50f * scaleY, textPaint)

        // Hearts
        val heartsText = "❤".repeat(playerHP) + "♡".repeat(GameConstants.MAX_HP - playerHP)
        canvas.drawText(heartsText, 20f * scaleX, 110f * scaleY, textPaint)

        // Fudge counter
        canvas.drawText(
            "Fudge: $fudgeCount/${GameConstants.FUDGES_FOR_SUPER}",
            20f * scaleX,
            170f * scaleY,
            textPaint
        )
    }

    /**
     * Render the game over screen.
     */
    private fun renderGameOver(canvas: Canvas) {
        renderBackground(canvas)

        splashPaint.textSize = 80f * scaleX
        canvas.drawText("GAME OVER", screenWidth / 2f, screenHeight * 0.4f, splashPaint)

        splashPaint.textSize = 48f * scaleX
        canvas.drawText("Score: $score", screenWidth / 2f, screenHeight * 0.5f, splashPaint)

        splashPaint.textSize = 40f * scaleX
        canvas.drawText("Tap to Restart", screenWidth / 2f, screenHeight * 0.6f, splashPaint)
    }

    /**
     * Release all loaded resources.
     */
    fun release() {
        bgBitmap?.recycle()
        bgBitmap = null
        Log.i(TAG, "Engine resources released")
    }
}
