package alzwded.poochgame

import alzwded.poochgame.engine.EntityRegistry
import alzwded.poochgame.engine.FudgeCollectible
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FudgeCollectibleTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var fudge: FudgeCollectible

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
        for (i in 0..7) {
            frames.add(
                SpriteFrame(
                    id = "fudge_$i",
                    x = i * 100, y = 0,
                    width = 219, height = 212,
                    originX = 110, originY = 162
                )
            )
        }
        atlas = SpriteAtlas("fudge", null, frames)
        fudge = FudgeCollectible(atlas)
    }

    @Test
    fun `initial state is inactive`() {
        assertEquals(FudgeCollectible.State.INACTIVE, fudge.state)
        assertFalse(fudge.isActive)
    }

    @Test
    fun `spawn activates fudge item`() {
        fudge.spawn(1000f, flying = false)

        assertTrue(fudge.isActive)
        assertEquals(1000f, fudge.virtualX, 0.001f)
        assertFalse(fudge.isFlying)
        assertEquals(EntityRegistry.FUDGE.baseScale, fudge.scale, 0.001f)
        assertFalse(fudge.hitbox.isEmpty)
    }

    @Test
    fun `spawn flying fudge applies extra flying speed`() {
        fudge.spawn(1000f, flying = true)

        assertTrue(fudge.isActive)
        assertTrue(fudge.isFlying)
        assertEquals(FudgeCollectible.FLYING_SPEED, fudge.extraSpeed, 0.001f)
    }

    @Test
    fun `update advances position and despawns offscreen`() {
        fudge.spawn(1000f, flying = false)
        fudge.update(0.1f, 550f, 260f)
        assertEquals(945f, fudge.virtualX, 0.001f)

        fudge.virtualX = -260f
        fudge.update(0.01f, 550f, 260f)
        assertFalse(fudge.isActive)
    }

    @Test
    fun `onCollect deactivates fudge`() {
        fudge.spawn(500f, flying = false)
        assertTrue(fudge.isActive)

        fudge.onCollect()
        assertFalse(fudge.isActive)
    }
}
