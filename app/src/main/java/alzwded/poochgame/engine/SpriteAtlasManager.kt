package alzwded.poochgame.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Manages loading, caching, and preprocessing of sprite atlases.
 *
 * Implements load-time 3D RGB Euclidean Distance chroma keying to convert
 * solid background colors (e.g. #ffffff) to transparent pixels.
 *
 * Executing this ONCE at asset load time ensures rendering remains 100% zero-cost per frame.
 */
object SpriteAtlasManager {

    private const val TAG = "SpriteAtlasManager"
    private val atlases = mutableMapOf<String, SpriteAtlas>()

    /**
     * Loads a sprite atlas from assets, applies chroma key transparency, and caches it.
     *
     * @param context Android context for asset access.
     * @param key Unique key to identify and cache the atlas (e.g. "pooch").
     * @param imageAssetPath Relative path to image file within assets/ (e.g. "sprites/pooch.jpg").
     * @param jsonAssetPath Relative path to JSON descriptor within assets/ (e.g. "sprites/pooch.json").
     */
    fun loadAtlas(
        context: Context,
        key: String,
        imageAssetPath: String,
        jsonAssetPath: String
    ): SpriteAtlas {
        atlases[key]?.let { return it }

        Log.i(TAG, "Loading atlas '$key': image=$imageAssetPath, json=$jsonAssetPath")

        // 1. Parse JSON descriptors
        val frames = SpriteJsonParser.parse(context, jsonAssetPath)

        // 2. Decode raw bitmap from assets in ARGB_8888
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val inputStream = context.assets.open(imageAssetPath)
        val rawBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            ?: throw IllegalStateException("Failed to decode bitmap from $imageAssetPath")
        inputStream.close()

        // 3. Process chroma-key transparency across frame regions
        val keyedBitmap = applyChromaKey(rawBitmap, frames)
        if (keyedBitmap != rawBitmap && !rawBitmap.isRecycled) {
            rawBitmap.recycle()
        }

        val atlas = SpriteAtlas(key, keyedBitmap, frames)
        atlases[key] = atlas
        Log.i(TAG, "Atlas '$key' ready: ${frames.size} frames, size=${keyedBitmap.width}x${keyedBitmap.height}, hasAlpha=${keyedBitmap.hasAlpha()}")
        return atlas
    }

    /**
     * Applies 3D RGB Euclidean Distance chroma keying to frame bounding regions.
     *
     * Ensures:
     * - Transparent pixels have A=0, R=0, G=0, B=0 (premultiplied 0x00000000).
     * - The resulting bitmap has hasAlpha() == true so Skia's hardware canvas enables alpha blending.
     *
     * Formula:
     *   distSq = (r - targetR)^2 + (g - targetG)^2 + (b - targetB)^2
     *   if (distSq <= threshold^2) pixel = 0 (Color.TRANSPARENT)
     */
    fun applyChromaKey(source: Bitmap, frames: List<SpriteFrame>): Bitmap {
        val width = source.width
        val height = source.height
        val allPixels = IntArray(width * height)
        source.getPixels(allPixels, 0, width, 0, 0, width, height)

        var totalKeyed = 0

        for (frame in frames) {
            val hexColor = frame.chromaKey ?: continue
            val threshold = frame.chromaThreshold
            if (threshold <= 0) continue

            val cleanHex = if (hexColor.startsWith("#")) hexColor.substring(1) else hexColor
            if (cleanHex.length < 6) continue

            val targetR = Integer.parseInt(cleanHex.substring(0, 2), 16)
            val targetG = Integer.parseInt(cleanHex.substring(2, 4), 16)
            val targetB = Integer.parseInt(cleanHex.substring(4, 6), 16)
            val thresholdSq = threshold * threshold

            val fx = frame.x.coerceIn(0, width - 1)
            val fy = frame.y.coerceIn(0, height - 1)
            val fw = frame.width.coerceAtMost(width - fx)
            val fh = frame.height.coerceAtMost(height - fy)

            if (fw <= 0 || fh <= 0) continue

            for (row in fy until fy + fh) {
                val rowOffset = row * width
                for (col in fx until fx + fw) {
                    val idx = rowOffset + col
                    val p = allPixels[idx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF

                    val dr = r - targetR
                    val dg = g - targetG
                    val db = b - targetB
                    val distSq = dr * dr + dg * dg + db * db

                    if (distSq <= thresholdSq) {
                        allPixels[idx] = 0 // Color.TRANSPARENT: completely transparent (0x00000000)
                        totalKeyed++
                    }
                }
            }
        }

        Log.i(TAG, "Chroma keying complete: $totalKeyed pixels keyed to transparent")

        // Create ARGB_8888 bitmap from processed pixel buffer with alpha enabled
        val keyedBitmap = Bitmap.createBitmap(allPixels, width, height, Bitmap.Config.ARGB_8888)
        keyedBitmap.setHasAlpha(true)
        return keyedBitmap
    }

    /** Retrieve a cached atlas by its key, or null if not loaded. */
    fun getAtlas(key: String): SpriteAtlas? = atlases[key]

    /** Release all cached atlases and their bitmap memory. */
    fun releaseAll() {
        for (atlas in atlases.values) {
            atlas.release()
        }
        atlases.clear()
        Log.i(TAG, "All sprite atlases released")
    }
}
