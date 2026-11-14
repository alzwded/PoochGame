package alzwded.poochgame.engine

/**
 * Describes a single sprite frame within an atlas sheet.
 *
 * Parsed from the JSON descriptor array that accompanies each sprite sheet image.
 *
 * @property id Unique string identifier for this frame (e.g. "walk_0", "jump_2").
 *              The engine parses the prefix and numeric suffix to group animation sequences.
 * @property x Left pixel coordinate of this frame's region in the source sheet.
 * @property y Top pixel coordinate of this frame's region in the source sheet.
 * @property width Width of this frame's region in pixels.
 * @property height Height of this frame's region in pixels.
 * @property originX Horizontal pivot/anchor point within the frame (pixels from left).
 * @property originY Vertical pivot/anchor point within the frame (pixels from top).
 * @property chromaKey Optional hex color string (e.g. "#ffffff") for chroma-key transparency.
 * @property chromaThreshold RGB Euclidean distance threshold (0–255) for the chroma key.
 */
data class SpriteFrame(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val originX: Int,
    val originY: Int,
    val chromaKey: String? = null,
    val chromaThreshold: Int = 0
)
