package alzwded.poochgame.engine

/**
 * Physics and gameplay constants calibrated for the 1080×1728 virtual workspace.
 *
 * All values are in pixels-per-second (or px/s²) relative to the virtual coordinate
 * space. The engine multiplies these by delta-time each frame for frame-rate independence.
 */
object GameConstants {

    // --- Virtual Viewport ---
    /** Virtual workspace width (portrait). */
    const val VIRTUAL_WIDTH = 1080f

    /** Virtual workspace height (portrait, 10:16 aspect ratio). */
    const val VIRTUAL_HEIGHT = 1728f

    // --- Physics ---
    /** Gravity acceleration in px/s². */
    const val GRAVITY = 3800f

    /** Upward velocity applied on jump in px/s (negative = upward). */
    const val JUMP_IMPULSE = -1600f

    /** Maximum delta-time cap to prevent physics tunneling on stutters. */
    const val MAX_DELTA_TIME = 0.05f

    // --- Scroll Speeds ---
    /** Normal horizontal scroll speed in px/s. */
    const val BASE_SCROLL_SPEED = 550f

    /** Super mode horizontal scroll speed in px/s. */
    const val SUPER_SCROLL_SPEED = 1100f

    // --- Super Mode ---
    /** Number of fudge pickups required to trigger super mode. */
    const val FUDGES_FOR_SUPER = 10

    /** Duration of super mode in seconds. */
    const val SUPER_MODE_DURATION = 5.0f

    /** Cutoff time in seconds after which Shy Guy towers stop spawning in Super Mode. */
    const val TOWER_SPAWN_CUTOFF_SEC = 4.0f

    /** Scale multiplier applied to the player during super mode. */
    const val SUPER_MODE_SCALE = 3.2f
    /** Duration of each frame during Super Mode cutscene. */
    const val SUPER_CUTSCENE_FRAME_DURATION = 0.12f
    /** Duration of scale down transition at the end of super mode. */
    const val SUPER_SCALE_DOWN_DURATION = 0.5f

    // --- Player ---
    /** Maximum player hit points. */
    const val MAX_HP = 3

    /** Ground Y position as a fraction of virtual height (from top). */
    const val GROUND_Y_FRACTION = 0.78f

    /** Ground Y position in virtual pixels. */
    val GROUND_Y: Float get() = VIRTUAL_HEIGHT * GROUND_Y_FRACTION

    // --- Spawning ---
    /** Probability (1 in N) that a spawn roll produces a Heart pickup. */
    const val HEART_SPAWN_CHANCE = 100

    // --- Frame Pacing ---
    /** Target frame rate for standard play. */
    const val TARGET_FPS_NORMAL = 60

    /** Target frame rate for battery-saving mode. */
    const val TARGET_FPS_LOW_POWER = 30
}
