package alzwded.poochgame.engine

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Custom [SurfaceView] that hosts the game rendering surface.
 *
 * Manages the [GameLoopThread] lifecycle in response to surface creation/destruction,
 * and forwards touch input to the [GameEngine].
 */
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GameSurfaceView"
    }

    val engine: GameEngine = GameEngine(context)
    private var gameThread: GameLoopThread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created")
        // Engine init is deferred to surfaceChanged where we have dimensions
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x$height format=$format")

        // Initialize the engine with actual surface dimensions
        engine.init(width, height)

        // Start the game loop thread
        startGameLoop()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed")
        stopGameLoop()
        engine.release()
    }

    // --- Touch Input ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            engine.onTap(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }

    // --- Thread Lifecycle ---

    /**
     * Start or restart the game loop thread.
     */
    private fun startGameLoop() {
        stopGameLoop()

        val thread = GameLoopThread(holder, engine)
        thread.running = true
        thread.start()
        gameThread = thread
        Log.i(TAG, "Game loop thread started")
    }

    /**
     * Stop the game loop thread and wait for it to finish.
     */
    fun stopGameLoop() {
        gameThread?.let { thread ->
            thread.running = false
            try {
                thread.join(1000) // Wait up to 1 second
                if (thread.isAlive) {
                    thread.interrupt()
                    thread.join(500)
                }
            } catch (_: InterruptedException) {
                // Proceed with shutdown
            }
            gameThread = null
            Log.i(TAG, "Game loop thread stopped")
        }
    }

    /**
     * Pause the game loop (called from Activity.onPause).
     */
    fun pause() {
        stopGameLoop()
    }

    /**
     * Resume the game loop (called from Activity.onResume).
     */
    fun resume() {
        // Thread is re-created in surfaceChanged, which fires on resume
    }
}
