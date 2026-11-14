package alzwded.poochgame

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import alzwded.poochgame.engine.GameSurfaceView

/**
 * Main (and only) Activity for Pooch Runner.
 *
 * Sets up a full-screen immersive portrait display hosting the [GameSurfaceView].
 * All rendering and game logic lives in the engine layer; this Activity only
 * manages Android lifecycle bridging.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameSurfaceView: GameSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Go full-screen immersive
        setupImmersiveMode()

        // Create and set the game surface as the content view
        gameSurfaceView = GameSurfaceView(this)
        setContentView(gameSurfaceView)
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        gameSurfaceView.resume()
    }

    override fun onPause() {
        super.onPause()
        gameSurfaceView.pause()
    }

    /**
     * Configure full-screen immersive sticky mode.
     * Hides status bar, navigation bar, and system UI for a true full-screen game experience.
     * Min API 31 — no legacy flag checks needed.
     */
    private fun setupImmersiveMode() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
