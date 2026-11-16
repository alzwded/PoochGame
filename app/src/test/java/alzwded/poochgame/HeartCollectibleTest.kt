package alzwded.poochgame

import alzwded.poochgame.engine.EntityRegistry
import alzwded.poochgame.engine.GameConstants
import alzwded.poochgame.engine.HeartCollectible
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeartCollectibleTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var heart: HeartCollectible

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
        for (i in 0..7) {
            frames.add(
                SpriteFrame(
                    id = "heart_$i",
                    x = i * 100, y = 0,
                    width = 232, height = 214,
                    originX = 114, originY = 102
                )
            )
        }
        atlas = SpriteAtlas("heart", null, frames)
        heart = HeartCollectible(atlas)
    }

    @Test
    fun `initial state is inactive`() {
        assertEquals(HeartCollectible.State.INACTIVE, heart.state)
        assertFalse(heart.isActive)
        assertEquals(0f, heart.hitbox.left, 0.001f)
    }

    @Test
    fun `spawn activates heart and sets ground virtualY`() {
        heart.spawn(1000f, flying = false)

        assertTrue(heart.isActive)
        assertEquals(HeartCollectible.State.ACTIVE, heart.state)
        assertEquals(1000f, heart.virtualX, 0.001f)
        assertFalse(heart.isFlying)
        assertEquals(EntityRegistry.HEART.baseScale, heart.scale, 0.001f)

        // Hitbox should be non-empty and updated around virtual position
        assertFalse(heart.hitbox.isEmpty)
        assertTrue(heart.hitbox.left < heart.virtualX)
        assertTrue(heart.hitbox.right > heart.virtualX)
    }

    @Test
    fun `spawn flying heart sets Y position at max jump height`() {
        heart.spawn(1000f, flying = true)

        assertTrue(heart.isActive)
        assertTrue(heart.isFlying)
        val expectedFlyingY = (GameConstants.GROUND_Y - ((heart.currentFrame.height - heart.currentFrame.originY) * heart.scale)) - HeartCollectible.MAX_JUMP_HEIGHT
        assertEquals(expectedFlyingY, heart.virtualY, 0.001f)
    }

    @Test
    fun `update moves heart left and updates hitbox`() {
        heart.spawn(1000f, flying = false)
        val initialHitboxLeft = heart.hitbox.left

        heart.update(0.1f, 550f, 260f)
        // virtualX decreases by 550 * 0.1 = 55
        assertEquals(945f, heart.virtualX, 0.001f)
        assertEquals(initialHitboxLeft - 55f, heart.hitbox.left, 0.001f)
    }

    @Test
    fun `despawns when scrolled far off screen left`() {
        heart.spawn(0f, flying = false)
        heart.virtualX = -260f

        heart.update(0.01f, 550f, 260f)

        assertFalse(heart.isActive)
        assertEquals(0f, heart.hitbox.left, 0.001f)
    }

    @Test
    fun `onCollect resets heart to inactive`() {
        heart.spawn(500f, flying = false)
        assertTrue(heart.isActive)

        heart.onCollect()

        assertFalse(heart.isActive)
        assertEquals(HeartCollectible.State.INACTIVE, heart.state)
        assertEquals(0f, heart.hitbox.left, 0.001f)
    }
}
