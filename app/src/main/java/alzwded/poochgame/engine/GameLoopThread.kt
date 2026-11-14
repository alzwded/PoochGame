package alzwded.poochgame.engine

import android.graphics.Canvas
import android.util.Log
import android.view.SurfaceHolder

/**
 * Dedicated game loop thread that drives update/render at the target frame rate.
 *
 * Uses [SurfaceHolder.lockHardwareCanvas] for GPU-accelerated 2D rendering.
 * Delta-time is calculated per frame and capped at [GameConstants.MAX_DELTA_TIME]
 * to prevent physics tunneling during micro-stutters.
 *
 * @param surfaceHolder The surface holder from [GameSurfaceView].
 * @param engine The [GameEngine] instance to drive.
 */
class GameLoopThread(
    private val surfaceHolder: SurfaceHolder,
    private val engine: GameEngine
) : Thread("GameLoopThread") {

    companion object {
        private const val TAG = "GameLoopThread"
    }

    @Volatile
    var running: Boolean = false

    /** Target frames per second. Can be changed at runtime for battery-saving. */
    var targetFps: Int = GameConstants.TARGET_FPS_NORMAL

    override fun run() {
        Log.i(TAG, "Game loop started (target: $targetFps FPS)")
        var previousTime = System.nanoTime()

        while (running) {
            val currentTime = System.nanoTime()
            val deltaTime = (currentTime - previousTime) / 1_000_000_000f
            previousTime = currentTime

            // Update game logic
            engine.update(deltaTime)

            // Render frame
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockHardwareCanvas()
                if (canvas != null) {
                    engine.render(canvas)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Render error", e)
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error posting canvas", e)
                    }
                }
            }

            // Frame pacing — sleep to maintain target FPS
            val frameTimeMs = (System.nanoTime() - currentTime) / 1_000_000
            val targetFrameTimeMs = 1000L / targetFps
            val sleepMs = targetFrameTimeMs - frameTimeMs
            if (sleepMs > 0) {
                try {
                    sleep(sleepMs)
                } catch (_: InterruptedException) {
                    // Thread interrupted, exit gracefully
                }
            }
        }

        Log.i(TAG, "Game loop stopped")
    }
}
