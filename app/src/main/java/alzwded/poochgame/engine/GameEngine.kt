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

    // --- Entities & Spawner ---
    var pooch: PoochPlayer? = null
        private set
    var spawnManager: SpawnManager? = null
        private set

    // --- Game Stats ---
    var playerHP: Int = GameConstants.MAX_HP
        private set
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
        SoundManager.playMainBgm()
    }

    /**
     * Load game assets from the assets directory.
     */
    private fun loadAssets() {
        // 1. Load background
        try {
            val bgStream = context.assets.open("backgrounds/bg.jpg")
            bgBitmap = BitmapFactory.decodeStream(bgStream)
            bgStream.close()

            bgBitmap?.let { bg ->
                bgCropHeight = bg.height
                bgCropWidth = (bgCropHeight * 10f / 16f).toInt()
                Log.i(TAG, "Background loaded: ${bg.width}x${bg.height}, crop: ${bgCropWidth}x$bgCropHeight")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load background", e)
        }

        // 2. Load Pooch sprite atlas and initialize PoochPlayer
        try {
            val poochAtlas = SpriteAtlasManager.loadAtlas(
                context,
                EntityRegistry.POOCH.atlasKey,
                "sprites/pooch.jpg",
                "sprites/pooch.json"
            )
            pooch = PoochPlayer(poochAtlas)
            Log.i(TAG, "PoochPlayer initialized with atlas '${poochAtlas.key}'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pooch atlas", e)
        }

        // 3. Load Shy Guy & Fudge sprite atlases and initialize SpawnManager
        try {
            val shyguyAtlas = SpriteAtlasManager.loadAtlas(
                context,
                EntityRegistry.SHY_GUY.atlasKey,
                "sprites/shagay.jpg",
                "sprites/shagay.json"
            )
            val fudgeAtlas = SpriteAtlasManager.loadAtlas(
                context,
                EntityRegistry.FUDGE.atlasKey,
                "sprites/fudge.jpg",
                "sprites/fudge.json"
            )
            spawnManager = SpawnManager(shyguyAtlas, fudgeAtlas)
            Log.i(TAG, "SpawnManager initialized with atlases '${shyguyAtlas.key}' & '${fudgeAtlas.key}'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load spawner atlases", e)
        }
    }

    /**
     * Reset player state and clear entities for a new game.
     */
    fun resetPlayer() {
        pooch?.reset()
        spawnManager?.reset()
        playerHP = GameConstants.MAX_HP
        fudgeCount = 0
        score = 0
        bgScrollX = 0f
    }

    /**
     * Handle a tap input event.
     */
    fun onTap(screenTapX: Float, screenTapY: Float) {
        when (state) {
            GameState.TOUCH_TO_START -> {
                state = GameState.PLAYING
                resetPlayer()
                SoundManager.playMainBgm()
                Log.d(TAG, "Tap → PLAYING")
            }
            GameState.PLAYING, GameState.SUPER_MODE, GameState.HURT -> {
                pooch?.jump()
            }
            GameState.GAME_OVER -> {
                // Ignore taps during death animation; engine resets automatically
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
                pooch?.update(dt * 0.75f, false)
            }
            GameState.PLAYING, GameState.SUPER_MODE -> {
                updatePlaying(dt)
            }
            GameState.HURT -> {
                updatePlaying(dt)
                if (pooch?.animState != PoochPlayer.AnimState.HURT) {
                    state = GameState.PLAYING
                }
            }
            GameState.GAME_OVER -> {
                updateGameOver(dt)
            }
        }
    }

    /**
     * Core gameplay update: scroll, player physics, spawning, collisions.
     */
    private fun updatePlaying(dt: Float) {
        val isSuper = (state == GameState.SUPER_MODE)
        val scrollSpeed = if (isSuper) {
            GameConstants.SUPER_SCROLL_SPEED
        } else {
            GameConstants.BASE_SCROLL_SPEED
        }

        // 1. Scroll background (convert virtual px/s to bitmap px/s)
        val bitmapScrollSpeed = if (bgCropWidth > 0) {
            scrollSpeed * (bgCropWidth.toFloat() / GameConstants.VIRTUAL_WIDTH)
        } else {
            scrollSpeed
        }
        bgScrollX += bitmapScrollSpeed * dt
        bgBitmap?.let { bg ->
            val scrollableWidth = bg.width.toFloat()
            if (bgScrollX >= scrollableWidth) {
                bgScrollX -= scrollableWidth
            }
        }

        // 2. Update Pooch kinematics & animation
        val p = pooch
        p?.update(dt, isSuper)

        // 3. Update spawner & enemies
        val sm = spawnManager
        val playerX = p?.virtualX ?: 0f
        sm?.update(dt, scrollSpeed, playerX)

        // 4. Collectible collision check (Fudge)
        if (p != null && sm != null && p.animState != PoochPlayer.AnimState.GAME_OVER) {
            val collectedFudge = sm.checkFudgeCollision(p.hitbox)
            if (collectedFudge != null) {
                collectedFudge.onCollect()
                fudgeCount++
                SoundManager.playCrunch()
                Log.d(TAG, "Fudge collected! Count: $fudgeCount")
                if (fudgeCount >= GameConstants.FUDGES_FOR_SUPER) {
                    fudgeCount = 0
                    Log.d(TAG, "Fudge count reached 10 -> Resetting to 0 for Super Pooch")
                }
            }
        }

        // 5. Enemy collision check (only if player is vulnerable)
        if (p != null && sm != null && p.isVulnerable()) {
            val collidedEnemy = sm.checkPlayerCollision(p.hitbox)
            if (collidedEnemy != null) {
                collidedEnemy.onPlayerCollision()
                playerHP--
                if (playerHP <= 0) {
                    playerHP = 0
                    state = GameState.GAME_OVER
                    p.triggerGameOver()
                    Log.d(TAG, "Collision! All hearts depleted -> GAME_OVER")
                } else {
                    state = GameState.HURT
                    p.triggerHurt()
                    Log.d(TAG, "Collision! Hearts remaining: $playerHP -> HURT")
                }
            }
        }

        // 6. Increment score over time
        score += (scrollSpeed * dt * 0.1f).toInt()
    }

    /**
     * Updates game over death animation: player drops off screen under gravity,
     * then resets to TOUCH_TO_START screen.
     */
    private fun updateGameOver(dt: Float) {
        val p = pooch ?: return
        p.update(dt, false)

        // Allow any active hurt enemies to finish their trajectory
        spawnManager?.update(dt, 0f, p.virtualX)

        // When death fall completes off bottom of screen, return to initial splash screen
        if (p.isGameOverAnimFinished) {
            state = GameState.TOUCH_TO_START
            resetPlayer()
            SoundManager.playMainBgm()
            Log.d(TAG, "Game Over death animation finished -> returned to TOUCH_TO_START")
        }
    }

    /**
     * Render the current frame to the provided canvas.
     * ZERO heap allocations in this method.
     */
    fun render(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        when (state) {
            GameState.TOUCH_TO_START -> renderSplash(canvas)
            GameState.PLAYING, GameState.SUPER_MODE, GameState.HURT -> renderPlaying(canvas)
            GameState.GAME_OVER -> renderGameOver(canvas)
        }
    }

    /**
     * Render the "Touch to Start" splash screen.
     */
    private fun renderSplash(canvas: Canvas) {
        renderBackground(canvas)

        pooch?.render(canvas, scaleX, scaleY, false)

        splashPaint.textSize = 80f * scaleX
        canvas.drawText(
            "POOCH RUNNER",
            screenWidth / 2f,
            screenHeight * 0.35f,
            splashPaint
        )

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
        val isSuper = (state == GameState.SUPER_MODE)

        // 1. Background layer
        renderBackground(canvas)

        // 2. Enemies layer
        spawnManager?.render(canvas, scaleX, scaleY)

        // 3. Pooch player sprite (handles hurt flicker automatically)
        pooch?.render(canvas, scaleX, scaleY, isSuper)

        // 4. HUD
        renderHUD(canvas)
    }

    /**
     * Render the game over sequence: shows world and death animation.
     */
    private fun renderGameOver(canvas: Canvas) {
        // 1. Background layer (frozen in place)
        renderBackground(canvas)

        // 2. Enemies layer
        spawnManager?.render(canvas, scaleX, scaleY)

        // 3. Pooch player sprite in death spin
        pooch?.render(canvas, scaleX, scaleY, false)

        // 4. HUD
        renderHUD(canvas)
    }

    /**
     * Render the scrolling background with 10:16 portrait crop and seamless looping.
     */
    private fun renderBackground(canvas: Canvas) {
        val bg = bgBitmap ?: return

        val scrollPixel = bgScrollX.toInt() % bg.width

        val srcLeft = scrollPixel
        val srcRight = (scrollPixel + bgCropWidth).coerceAtMost(bg.width)
        val visibleWidth = srcRight - srcLeft

        srcRect.set(srcLeft, 0, srcRight, bgCropHeight)
        val dstW = (visibleWidth.toFloat() / bgCropWidth) * screenWidth
        dstRect.set(0f, 0f, dstW, screenHeight.toFloat())
        canvas.drawBitmap(bg, srcRect, dstRect, null)

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
        val fullHearts = playerHP.coerceIn(0, GameConstants.MAX_HP)
        val emptyHearts = (GameConstants.MAX_HP - fullHearts).coerceAtLeast(0)
        val heartsText = "❤".repeat(fullHearts) + "♡".repeat(emptyHearts)
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
     * Release all loaded resources.
     */
    fun release() {
        bgBitmap?.recycle()
        bgBitmap = null
        SpriteAtlasManager.releaseAll()
        pooch = null
        spawnManager?.reset()
        spawnManager = null
        Log.i(TAG, "Engine resources released")
    }
}
