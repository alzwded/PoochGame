package alzwded.poochgame

import alzwded.poochgame.engine.GameConstants
import alzwded.poochgame.engine.ShyGuyEnemy
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShyGuyEnemyTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var enemy: ShyGuyEnemy

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
        // 6 walk frames
        for (i in 0..5) {
            frames.add(
                SpriteFrame(
                    id = "walk_$i",
                    x = i * 150, y = 0,
                    width = 134, height = 166,
                    originX = 65, originY = 147,
                    chromaKey = "#ffffff", chromaThreshold = 28
                )
            )
        }
        // 4 hurt frames
        for (i in 0..3) {
            frames.add(
                SpriteFrame(
                    id = "ohoh_$i",
                    x = i * 200, y = 300,
                    width = 180, height = 190,
                    originX = 90, originY = 165,
                    chromaKey = "#ffffff", chromaThreshold = 28
                )
            )
        }
        // 2 wave frames
        for (i in 0..1) {
            frames.add(
                SpriteFrame(
                    id = "wave_$i",
                    x = i * 200, y = 500,
                    width = 176, height = 189,
                    originX = 84, originY = 164,
                    chromaKey = "#ffffff", chromaThreshold = 28
                )
            )
        }

        atlas = SpriteAtlas("shyguy", null, frames)
        enemy = ShyGuyEnemy(atlas)
    }

    @Test
    fun `spawn initializes walking enemy with ground contact at feet`() {
        assertFalse(enemy.isActive)

        enemy.spawn(1200f)
        assertTrue(enemy.isActive)
        assertEquals(ShyGuyEnemy.State.WALKING, enemy.state)
        assertEquals(1200f, enemy.virtualX, 0.001f)

        // Feet should be at GROUND_Y: virtualY = GROUND_Y - 19
        val expectedY = GameConstants.GROUND_Y - (ShyGuyEnemy.GROUND_OFFSET * 1.0f)
        assertEquals(expectedY, enemy.virtualY, 0.001f)
        assertFalse(enemy.hitbox.isEmpty)
        assertFalse(enemy.isCleared)
    }

    @Test
    fun `walking animation cycles through 6 frames`() {
        enemy.spawn(1200f)
        assertEquals("walk_0", enemy.currentFrame.id)

        enemy.update(ShyGuyEnemy.WALK_FRAME_DURATION, 550f, 260f)
        assertEquals("walk_1", enemy.currentFrame.id)

        enemy.update(ShyGuyEnemy.WALK_FRAME_DURATION, 550f, 260f)
        assertEquals("walk_2", enemy.currentFrame.id)

        enemy.update(ShyGuyEnemy.WALK_FRAME_DURATION * 3f, 550f, 260f)
        assertEquals("walk_5", enemy.currentFrame.id)

        // Cycles back to walk_0
        enemy.update(ShyGuyEnemy.WALK_FRAME_DURATION, 550f, 260f)
        assertEquals("walk_0", enemy.currentFrame.id)
    }

    @Test
    fun `cleared when player passes enemy`() {
        enemy.spawn(350f)
        assertFalse(enemy.isCleared)

        // Player is at 260f, so clearance threshold is 260 - 40 = 220f
        enemy.update(0.1f, 550f, 260f) // moves left
        // Move it past player
        enemy.virtualX = 210f
        enemy.update(0.01f, 550f, 260f)
        assertTrue(enemy.isCleared)
    }

    @Test
    fun `collision clears hitbox and initiates hurt fling`() {
        enemy.spawn(1000f)
        assertFalse(enemy.hitbox.isEmpty)

        val collided = enemy.onPlayerCollision()
        assertTrue(collided)
        assertEquals(ShyGuyEnemy.State.HURT_FLING, enemy.state)
        // Hitbox immediately cleared to 0 (disabled)
        assertEquals(0f, enemy.hitbox.left, 0.001f)
        assertEquals(0f, enemy.hitbox.right, 0.001f)
        assertTrue(enemy.isCleared)
        assertEquals(-900f, enemy.velocityY, 0.001f)
        assertTrue(enemy.targetScale == 2.0f || enemy.targetScale == 0.0f)
    }

    @Test
    fun `despawns when walked off screen left`() {
        enemy.spawn(0f)
        enemy.virtualX = -260f
        enemy.update(0.01f, 550f, 260f)
        assertFalse(enemy.isActive)
        assertTrue(enemy.isCleared)
    }

    @Test
    fun `despawns when hurt fling drops off screen bottom`() {
        enemy.spawn(500f)
        enemy.onPlayerCollision()
        enemy.virtualY = GameConstants.VIRTUAL_HEIGHT + 300f
        enemy.update(0.01f, 550f, 260f)
        assertFalse(enemy.isActive)
    }

    @Test
    fun `triggerWave alternates wave_0 and wave_1 every 0_5s`() {
        enemy.triggerWave(820f)
        assertEquals(ShyGuyEnemy.State.WAVING, enemy.state)
        assertEquals("wave_0", enemy.currentFrame.id)
        assertEquals(0f, enemy.hitbox.width(), 0.001f)

        // Advance 0.51s -> switches to wave_1
        enemy.update(0.51f, 0f, 0f)
        assertEquals("wave_1", enemy.currentFrame.id)

        // Advance another 0.51s -> loops back to wave_0
        enemy.update(0.51f, 0f, 0f)
        assertEquals("wave_0", enemy.currentFrame.id)
    }
}
