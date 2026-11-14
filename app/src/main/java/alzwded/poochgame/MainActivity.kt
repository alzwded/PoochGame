package alzwded.poochgame

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        // Create and set the game surface as the content view
        gameSurfaceView = GameSurfaceView(this)
        setContentView(gameSurfaceView)

        // Go full-screen immersive (must be called after setContentView so DecorView exists)
        setupImmersiveMode()
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
     */
    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

