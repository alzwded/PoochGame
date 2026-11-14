package alzwded.poochgame

import alzwded.poochgame.engine.SpriteJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SpriteJsonParser] — validates JSON parsing without Android context.
 */
class SpriteJsonParserTest {

    private val sampleJson = """
    [
      {
        "id": "walk_0",
        "x": 26, "y": 126,
        "width": 206, "height": 117,
        "originX": 132, "originY": 50,
        "key": "#ffffff",
        "threshold": 41
      },
      {
        "id": "walk_1",
        "x": 288, "y": 121,
        "width": 206, "height": 117,
        "originX": 132, "originY": 50,
        "key": "#ffffff",
        "threshold": 41
      },
      {
        "id": "jump_0",
        "x": 19, "y": 844,
        "width": 219, "height": 138,
        "originX": 140, "originY": 59,
        "chromaKey": "#ffffff",
        "chromaThreshold": 24
      }
    ]
    """.trimIndent()

    @Test
    fun `parseJsonText returns correct number of frames`() {
        val frames = SpriteJsonParser.parseJsonText(sampleJson)
        assertEquals(3, frames.size)
    }

    @Test
    fun `parseJsonText parses frame coordinates correctly`() {
        val frames = SpriteJsonParser.parseJsonText(sampleJson)
        val walk0 = frames[0]

        assertEquals("walk_0", walk0.id)
        assertEquals(26, walk0.x)
        assertEquals(126, walk0.y)
        assertEquals(206, walk0.width)
        assertEquals(117, walk0.height)
        assertEquals(132, walk0.originX)
        assertEquals(50, walk0.originY)
    }

    @Test
    fun `parseJsonText handles legacy key and threshold fields`() {
        val frames = SpriteJsonParser.parseJsonText(sampleJson)
        val walk0 = frames[0]

        assertEquals("#ffffff", walk0.chromaKey)
        assertEquals(41, walk0.chromaThreshold)
    }

    @Test
    fun `parseJsonText handles canonical chromaKey and chromaThreshold fields`() {
        val frames = SpriteJsonParser.parseJsonText(sampleJson)
        val jump0 = frames[2]

        assertEquals("#ffffff", jump0.chromaKey)
        assertEquals(24, jump0.chromaThreshold)
    }

    @Test
    fun `parseJsonText handles missing chroma key fields`() {
        val json = """
        [
          {
            "id": "test_0",
            "x": 0, "y": 0,
            "width": 100, "height": 100,
            "originX": 50, "originY": 50
          }
        ]
        """.trimIndent()

        val frames = SpriteJsonParser.parseJsonText(json)
        assertNull(frames[0].chromaKey)
        assertEquals(0, frames[0].chromaThreshold)
    }

    @Test
    fun `groupAnimations groups frames by prefix`() {
        val frames = SpriteJsonParser.parseJsonText(sampleJson)
        val groups = SpriteJsonParser.groupAnimations(frames)

        assertEquals(2, groups.size)
        assertNotNull(groups["walk"])
        assertNotNull(groups["jump"])
        assertEquals(2, groups["walk"]!!.size)
        assertEquals(1, groups["jump"]!!.size)
    }

    @Test
    fun `groupAnimations sorts frames by numeric suffix`() {
        val json = """
        [
          { "id": "run_2", "x": 0, "y": 0, "width": 10, "height": 10, "originX": 5, "originY": 5 },
          { "id": "run_0", "x": 0, "y": 0, "width": 10, "height": 10, "originX": 5, "originY": 5 },
          { "id": "run_1", "x": 0, "y": 0, "width": 10, "height": 10, "originX": 5, "originY": 5 }
        ]
        """.trimIndent()

        val frames = SpriteJsonParser.parseJsonText(json)
        val groups = SpriteJsonParser.groupAnimations(frames)
        val runFrames = groups["run"]!!

        assertEquals("run_0", runFrames[0].id)
        assertEquals("run_1", runFrames[1].id)
        assertEquals("run_2", runFrames[2].id)
    }
}
