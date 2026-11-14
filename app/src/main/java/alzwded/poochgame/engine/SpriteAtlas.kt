package alzwded.poochgame.engine

import android.graphics.Bitmap

/**
 * Holds a loaded, chroma-keyed sprite sheet bitmap and its parsed frame descriptors.
 *
 * @property key Unique identifier for this atlas (e.g. "pooch", "shyguy").
 * @property bitmap The GPU-compatible ARGB_8888 bitmap with chroma-key transparency applied.
 * @property frames All sprite frame descriptors contained in this sheet.
 */
class SpriteAtlas(
    val key: String,
    val bitmap: Bitmap?,
    val frames: List<SpriteFrame>
) {
    private val frameMap: Map<String, SpriteFrame> = frames.associateBy { it.id }

    /** Look up a frame descriptor by its id (e.g. "walk_0"). */
    fun getFrame(id: String): SpriteFrame? = frameMap[id]

    /** Release bitmap memory when no longer needed. */
    fun release() {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
