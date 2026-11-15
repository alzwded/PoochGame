package alzwded.poochgame.engine

/**
 * Configuration for an entity type that references a sprite atlas.
 *
 * @property atlasKey Key name used to look up the atlas (e.g. "pooch", "shyguy").
 * @property baseScale Normalizes raw artwork pixels to virtual world units.
 * @property hitboxInsetFactor Fraction (0.0–1.0) to inset collision bounds for fair play.
 */
data class EntityTypeConfig(
    val atlasKey: String,
    val baseScale: Float,
    val hitboxInsetFactor: Float = 0.5f
)

/**
 * Central registry of all game entity type configurations.
 * Each entry maps to a sprite atlas and defines base rendering/physics parameters.
 */
object EntityRegistry {
    val POOCH = EntityTypeConfig("pooch", baseScale = 1.2f)
    val SHY_GUY = EntityTypeConfig("shyguy", baseScale = 1.0f)
    val FUDGE = EntityTypeConfig("fudge", baseScale = 0.85f)
    val HEART = EntityTypeConfig("heart", baseScale = 0.9f)
}
