package alzwded.poochgame.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Parses sprite atlas JSON descriptor files from the assets directory.
 *
 * Each JSON file is a JSON array of frame objects. The parser supports two field-name
 * conventions for chroma-key data:
 *   - Canonical: `chromaKey` / `chromaThreshold`
 *   - Legacy:    `key` / `threshold`
 *
 * Example JSON entry:
 * ```json
 * {
 *   "id": "walk_0",
 *   "x": 26, "y": 126,
 *   "width": 206, "height": 117,
 *   "originX": 132, "originY": 50,
 *   "key": "#ffffff",
 *   "threshold": 41
 * }
 * ```
 */
object SpriteJsonParser {

    private const val TAG = "SpriteJsonParser"

    /**
     * Loads and parses a sprite atlas JSON descriptor from the app's assets folder.
     *
     * @param context Android context for asset access.
     * @param assetPath Relative path within `assets/` (e.g. "sprites/pooch.json").
     * @return List of [SpriteFrame] descriptors parsed from the JSON array.
     */
    fun parse(context: Context, assetPath: String): List<SpriteFrame> {
        val jsonText = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        return parseJsonText(jsonText, assetPath)
    }

    /**
     * Parses a raw JSON string into a list of [SpriteFrame] descriptors.
     * Useful for testing without Android context.
     *
     * @param jsonText The JSON array string.
     * @param sourceName Optional label for log messages.
     * @return List of [SpriteFrame] descriptors.
     */
    fun parseJsonText(jsonText: String, sourceName: String = "<inline>"): List<SpriteFrame> {
        val jsonArray = JSONArray(jsonText)
        val frames = ArrayList<SpriteFrame>(jsonArray.length())

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            // Support both "chromaKey"/"chromaThreshold" and legacy "key"/"threshold"
            val chromaKey: String? = when {
                obj.has("chromaKey") && !obj.isNull("chromaKey") -> obj.optString("chromaKey")
                obj.has("key") && !obj.isNull("key") -> obj.optString("key")
                else -> null
            }
            val chromaThreshold: Int = when {
                obj.has("chromaThreshold") -> obj.optInt("chromaThreshold", 0)
                obj.has("threshold") -> obj.optInt("threshold", 0)
                else -> 0
            }

            val frame = SpriteFrame(
                id = obj.getString("id"),
                x = obj.getInt("x"),
                y = obj.getInt("y"),
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                originX = obj.getInt("originX"),
                originY = obj.getInt("originY"),
                chromaKey = chromaKey,
                chromaThreshold = chromaThreshold
            )
            frames.add(frame)
        }

        Log.d(TAG, "Parsed ${frames.size} frames from $sourceName")

        // Group and log animation sequences for debugging
        val groups = frames.groupBy { id ->
            val underscoreIdx = id.id.lastIndexOf('_')
            if (underscoreIdx > 0) id.id.substring(0, underscoreIdx) else id.id
        }
        for ((prefix, groupFrames) in groups) {
            Log.d(TAG, "  Animation '$prefix': ${groupFrames.size} frames")
        }

        return frames
    }

    /**
     * Groups a flat list of [SpriteFrame]s into animation sequences keyed by their
     * name prefix (everything before the last underscore + numeric index).
     *
     * For example, frames with ids "walk_0", "walk_1", "walk_2" are grouped under "walk".
     *
     * @param frames List of parsed sprite frames.
     * @return Map from animation name to ordered list of frames.
     */
    fun groupAnimations(frames: List<SpriteFrame>): Map<String, List<SpriteFrame>> {
        return frames.groupBy { frame ->
            val underscoreIdx = frame.id.lastIndexOf('_')
            if (underscoreIdx > 0) frame.id.substring(0, underscoreIdx) else frame.id
        }.mapValues { (_, groupFrames) ->
            // Sort by numeric suffix to ensure correct frame ordering
            groupFrames.sortedBy { frame ->
                val underscoreIdx = frame.id.lastIndexOf('_')
                if (underscoreIdx > 0 && underscoreIdx < frame.id.length - 1) {
                    frame.id.substring(underscoreIdx + 1).toIntOrNull() ?: 0
                } else {
                    0
                }
            }
        }
    }
}
