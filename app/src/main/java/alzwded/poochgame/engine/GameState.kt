package alzwded.poochgame.engine

/**
 * Represents the discrete states of the game's state machine.
 */
enum class GameState {
    /** Splash screen – waiting for the player to tap to begin. */
    TOUCH_TO_START,
    /** Normal gameplay – running, jumping, collecting. */
    PLAYING,
    /** Automated cutscene set piece when 10 fudges are collected. */
    SUPER_TRANSITION,
    /** Powered-up state after collecting 10 fudges – smash through everything. */
    SUPER_MODE,
    /** Brief invincibility after taking a hit – flickering/flinching. */
    HURT,
    /** Player has lost all hearts – death animation playing. */
    GAME_OVER
}
