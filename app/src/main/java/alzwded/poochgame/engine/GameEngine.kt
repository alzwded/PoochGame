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
import kotlin.random.Random

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
    private var pendingSuperTransition: Boolean = false

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
    private var superModeTimer: Float = 0f
    var smashedCount: Int = 0
        private set
    private var heartAtlas: SpriteAtlas? = null
    private var fudgeAtlas: SpriteAtlas? = null
    private val topHeartFrameIndices = IntArray(GameConstants.MAX_HP) { Random.nextInt(8) }
    private var heartAnimTimer: Float = 0f

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
        SoundManager.playBgm()
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

        // 3. Load Shy Guy, Fudge & Heart sprite atlases and initialize SpawnManager
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
            val heartAtlas = SpriteAtlasManager.loadAtlas(
                context,
                EntityRegistry.HEART.atlasKey,
                "sprites/hearts.jpg",
                "sprites/hearts.json"
            )
            this.fudgeAtlas = fudgeAtlas
            this.heartAtlas = heartAtlas
            spawnManager = SpawnManager(shyguyAtlas, fudgeAtlas, heartAtlas)
            Log.i(TAG, "SpawnManager initialized with atlases '${shyguyAtlas.key}', '${fudgeAtlas.key}', & '${heartAtlas.key}'")
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
        superModeTimer = 0f
        smashedCount = 0
        bgScrollX = 0f
        pendingSuperTransition = false
    }

    /**
     * Handle a tap input event.
     */
    fun onTap(screenTapX: Float, screenTapY: Float) {
        when (state) {
            GameState.TOUCH_TO_START -> {
                state = GameState.PLAYING
                resetPlayer()
                SoundManager.playBgm()
                Log.d(TAG, "Tap → PLAYING")
            }
            GameState.PLAYING, GameState.HURT -> {
                pooch?.jump()
            }
            GameState.SUPER_MODE, GameState.SUPER_TRANSITION, GameState.GAME_OVER -> {
                // Ignore taps during super mode, cutscenes, and death animations
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
        // Update top-row heart frame randomization timer every 0.5s
        heartAnimTimer += dt
        if (heartAnimTimer >= 0.5f) {
            heartAnimTimer -= 0.5f
            for (i in topHeartFrameIndices.indices) {
                topHeartFrameIndices[i] = Random.nextInt(8)
            }
        }
        when (state) {
            GameState.TOUCH_TO_START -> {
                pooch?.update(dt * 0.75f, false)
            }
            GameState.PLAYING, GameState.SUPER_MODE -> {
                updatePlaying(dt)
            }
            GameState.SUPER_TRANSITION -> {
                updateSuperTransition(dt)
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
     * Entering Super mode cutscene
     */
    private fun updateSuperTransition(dt: Float) {
        val p = pooch ?: return
        p.update(dt, isSuperMode = false)
        // Freeze background scrolling and entity updates during cutscene
        spawnManager?.update(0f, 0f, p.virtualX, playerHP)
        if (p.isCutsceneFinished) {
            state = GameState.SUPER_MODE
            superModeTimer = GameConstants.SUPER_MODE_DURATION
            SoundManager.playBgm("audio/bg_super.m4a")
            Log.d(TAG, "Super Cutscene finished -> ENTERING SUPER_MODE")
        }
    }

    private fun startSuperTransition() {
        val p = pooch ?: return
        state = GameState.SUPER_TRANSITION
        SoundManager.stopBgm()
        p.triggerSuperCutscene()
        Log.d(TAG, "Starting SUPER_TRANSITION cutscene after landing")
    }

    /**
     * Core gameplay update: scroll, player physics, spawning, collisions.
     */
    private fun updatePlaying(dt: Float) {
        val p = pooch
        val isSuper = (state == GameState.SUPER_MODE)

        // Check if player landed from a jump to trigger pending super mode transition
        if (pendingSuperTransition && (p?.isOnGround == true)) {
            pendingSuperTransition = false
            startSuperTransition()
            return
        }

        if (isSuper) {
            superModeTimer -= dt
            if (superModeTimer <= 0f) {
                superModeTimer = 0f
                state = GameState.PLAYING
                p?.exitSuperMode()
                SoundManager.playBgm(SoundManager.BGM_MAIN)
                Log.d(TAG, "Super Mode timer expired -> Returning to PLAYING")
            }
        }

        val superElapsedTime = GameConstants.SUPER_MODE_DURATION - superModeTimer
        val isTowerSpawningActive = isSuper && (superElapsedTime <= GameConstants.TOWER_SPAWN_CUTOFF_SEC)

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
        p?.update(dt, isSuper, superModeTimer)

        // 3. Update spawner & enemies
        val sm = spawnManager
        val playerX = p?.virtualX ?: 0f
        sm?.update(dt, scrollSpeed, playerX, playerHP, isSuperMode = isTowerSpawningActive)

        // 4. Collectible collision checks (Heart & Fudge)
        if (p != null && sm != null && p.animState != PoochPlayer.AnimState.GAME_OVER) {
            val collectedHeart = sm.checkHeartCollision(p.hitbox)
            if (collectedHeart != null) {
                collectedHeart.onCollect()
                if (playerHP < GameConstants.MAX_HP) {
                    playerHP++
                }
                SoundManager.playArfArf()
                Log.d(TAG, "Heart collected! Restored HP to $playerHP")
            }

            val collectedFudge = sm.checkFudgeCollision(p.hitbox)
            if (collectedFudge != null) {
                collectedFudge.onCollect()
                fudgeCount++
                if (Random.nextInt(0, 10) < 8) {
                    SoundManager.playCrunch()
                } else {
                    SoundManager.playArfArf()
                }
                Log.d(TAG, "Fudge collected! Count: $fudgeCount")
                if (fudgeCount >= GameConstants.FUDGES_FOR_SUPER) {
                    fudgeCount = 0
                    state = GameState.SUPER_TRANSITION
                    SoundManager.stopBgm()
                    p.triggerSuperCutscene()
                    Log.d(TAG, "10 Fudges collected -> SUPER_TRANSITION cutscene started")
                }
            }
        }

        // 5. Enemy collision check (only if player is vulnerable or in Super Mode)
        if (p != null && sm != null && (p.isVulnerable() || isSuper)) {
            val collidedEnemy = sm.checkPlayerCollision(p.hitbox)
            if (collidedEnemy != null) {
                collidedEnemy.onPlayerCollision()
                if (isSuper) {
                    SoundManager.playSmash()
                    smashedCount++
                    Log.d(TAG, "Super Pooch smashed Shy Guy! Total smashed: $smashedCount")
                } else {
                    if (Random.nextBoolean()) {
                        SoundManager.playSmash()
                    } else {
                        SoundManager.playSadPup()
                    }
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
        spawnManager?.update(dt, 0f, p.virtualX, playerHP)

        // When death fall completes off bottom of screen, return to initial splash screen
        if (p.isGameOverAnimFinished) {
            state = GameState.TOUCH_TO_START
            resetPlayer()
            SoundManager.playBgm()
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
            GameState.PLAYING, GameState.SUPER_MODE, GameState.SUPER_TRANSITION, GameState.HURT -> renderPlaying(canvas)
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

        // 2. Pooch player sprite (handles hurt flicker automatically)
        // render below sprites, mostly just shy guys being knocked around towards the foreground
        // TODO should have priority on game over
        pooch?.render(canvas, scaleX, scaleY, isSuper)

        // 3. Enemies & pickups layer
        spawnManager?.render(canvas, scaleX, scaleY)

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
     * Render the heads-up display: top hearts row and fudge counter bar.
     */
    private fun renderHUD(canvas: Canvas) {
        val hAtlas = heartAtlas ?: return
        val fAtlas = fudgeAtlas ?: return
        val hFrames = hAtlas.frames
        if (hFrames.isEmpty()) return

        // 1. Top row of hearts evenly distributed across screen width
        val maxHp = GameConstants.MAX_HP
        val heartSlotWidth = GameConstants.VIRTUAL_WIDTH / maxHp.toFloat()
        val heartY = 70f

        for (i in 0 until playerHP.coerceIn(0, maxHp)) {
            val frameIndex = topHeartFrameIndices[i] % hFrames.size
            val frame = hFrames[frameIndex]
            val vx = (i + 0.5f) * heartSlotWidth
            val targetW = heartSlotWidth * 0.4f
            val heartScale = targetW / frame.width.toFloat()

            SpriteRenderer.drawFrame(
                canvas = canvas,
                bitmap = hAtlas.bitmap ?: continue,
                frame = frame,
                virtualX = vx,
                virtualY = heartY,
                scale = heartScale,
                scaleX = scaleX,
                scaleY = scaleY,
                mirrored = false,
                paint = textPaint,
                srcRect = srcRect,
                dstRect = dstRect
            )
        }

        // 2. Row of up to 10 fudge sprites scaled across the whole length of the screen
        val maxFudges = GameConstants.FUDGES_FOR_SUPER
        val fudgeSlotWidth = GameConstants.VIRTUAL_WIDTH / maxFudges.toFloat()
        val fudgeY = 230f
        val clampedFudgeCount = fudgeCount.coerceIn(0, maxFudges)
        for (i in 0 until clampedFudgeCount) {
            val frameName = "fudge_$i"
            val frame = fAtlas.getFrame(frameName) ?: fAtlas.frames[i % fAtlas.frames.size]
            val vx = (i + 0.5f) * fudgeSlotWidth
            val targetW = fudgeSlotWidth * 0.85f
            val fudgeScale = targetW / frame.width.toFloat()
            SpriteRenderer.drawFrame(
                canvas = canvas,
                bitmap = fAtlas.bitmap ?: continue,
                frame = frame,
                virtualX = vx,
                virtualY = fudgeY,
                scale = fudgeScale,
                scaleX = scaleX,
                scaleY = scaleY,
                mirrored = false,
                paint = textPaint,
                srcRect = srcRect,
                dstRect = dstRect
            )
        }

        // 3. Smashed Counter Badge (Visible in Super Mode or after smashing Shy Guys)
        if (smashedCount > 0 || state == GameState.SUPER_MODE) {
            textPaint.textSize = 42f * scaleX
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                "SMASHED: $smashedCount",
                screenWidth - (30f * scaleX),
                110f * scaleY,
                textPaint
            )
            textPaint.textAlign = Paint.Align.LEFT
        }
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
