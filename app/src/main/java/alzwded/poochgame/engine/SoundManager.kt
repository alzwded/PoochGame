package alzwded.poochgame.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Manages game audio playback:
 * - Background music (BGM) via [MediaPlayer] with seamless looping.
 * - Thread-safe operations across UI thread (lifecycle) and GameLoopThread.
 * - Robust error handling and lifecycle hygiene.
 */
object SoundManager {

    private const val TAG = "SoundManager"

    const val BGM_MAIN = "audio/bg_main.m4a"

    private var appContext: Context? = null
    private var bgmPlayer: MediaPlayer? = null
    private var currentTrack: String? = null
    private var isBgmPlaying: Boolean = false
    private var wasPlayingBeforePause: Boolean = false

    /**
     * Initialize the audio manager with the application context.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.i(TAG, "SoundManager initialized")
        }
    }

    /**
     * Start playing the main background music in a loop.
     * If the main BGM is already actively playing, this is a no-op to prevent audio interruption.
     */
    @Synchronized
    fun playMainBgm() {
        playBgm(BGM_MAIN)
    }

    /**
     * Play a specified BGM asset file with seamless looping.
     *
     * @param assetPath Path relative to assets/ (e.g. [BGM_MAIN]).
     */
    @Synchronized
    fun playBgm(assetPath: String) {
        val context = appContext ?: run {
            Log.w(TAG, "Cannot play BGM: SoundManager not initialized with context")
            return
        }

        // If the requested track is already playing, do nothing
        if (currentTrack == assetPath && bgmPlayer?.isPlaying == true) {
            return
        }

        try {
            stopBgmInternal()

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)

                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    mp.reset()
                    isBgmPlaying = false
                    true
                }
            }

            var afd: AssetFileDescriptor? = null
            try {
                afd = context.assets.openFd(assetPath)
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } finally {
                afd?.close()
            }

            player.prepare()
            player.start()

            bgmPlayer = player
            currentTrack = assetPath
            isBgmPlaying = true
            wasPlayingBeforePause = true
            Log.i(TAG, "BGM playing in loop: $assetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BGM: $assetPath", e)
            bgmPlayer?.release()
            bgmPlayer = null
            currentTrack = null
            isBgmPlaying = false
        }
    }

    /**
     * Pause BGM playback (e.g. when app goes to background / onPause).
     */
    @Synchronized
    fun pauseBgm() {
        try {
            bgmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    wasPlayingBeforePause = true
                    isBgmPlaying = false
                    Log.i(TAG, "BGM paused")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing BGM", e)
        }
    }

    /**
     * Resume BGM playback (e.g. when app returns to foreground / onResume).
     */
    @Synchronized
    fun resumeBgm() {
        try {
            if (wasPlayingBeforePause) {
                bgmPlayer?.let { player ->
                    if (!player.isPlaying) {
                        player.start()
                        isBgmPlaying = true
                        Log.i(TAG, "BGM resumed")
                    }
                } ?: run {
                    // Re-create player if it was released or null
                    currentTrack?.let { playBgm(it) } ?: playMainBgm()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming BGM", e)
        }
    }

    /**
     * Stop BGM playback completely.
     */
    @Synchronized
    fun stopBgm() {
        wasPlayingBeforePause = false
        stopBgmInternal()
    }

    private fun stopBgmInternal() {
        try {
            bgmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BGM", e)
        } finally {
            bgmPlayer = null
            currentTrack = null
            isBgmPlaying = false
        }
    }

    /**
     * Release all audio resources (called on Activity onDestroy).
     */
    @Synchronized
    fun release() {
        stopBgm()
        appContext = null
        Log.i(TAG, "SoundManager released")
    }
}
