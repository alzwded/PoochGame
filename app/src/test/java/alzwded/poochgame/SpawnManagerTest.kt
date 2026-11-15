package alzwded.poochgame

import android.graphics.RectF
import alzwded.poochgame.engine.ShyGuyEnemy
import alzwded.poochgame.engine.SpawnManager
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpawnManagerTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var spawner: SpawnManager

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
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
        atlas = SpriteAtlas("shyguy", null, frames)
        spawner = SpawnManager(atlas)
    }

    @Test
    fun `spawner enforces static maximum pool of 64 sprites`() {
        assertEquals(64, spawner.enemies.size)
        assertEquals(0, spawner.activeCount)
    }

    @Test
    fun `spawnShyGuy spawns only when prior enemy is cleared`() {
        // First enemy can spawn
        val enemy1 = spawner.spawnShyGuy()
        assertNotNull(enemy1)
        assertEquals(1, spawner.activeCount)
        assertFalse(enemy1!!.isCleared)

        // Second spawn attempt immediately fails because enemy1 is NOT cleared
        val enemy2 = spawner.spawnShyGuy()
        assertNull(enemy2)
        assertEquals(1, spawner.activeCount)

        // Clear enemy1 (e.g. player jumped over it or collided)
        enemy1.isCleared = true

        // Now enemy2 can spawn
        val enemy3 = spawner.spawnShyGuy()
        assertNotNull(enemy3)
        assertEquals(2, spawner.activeCount)
    }

    @Test
    fun `cadence advances slots and spawns on even slots only`() {
        val frameDt = 0.016f
        val slotTime = SpawnManager.SLOT_INTERVAL_DISTANCE / 550f

        // Advance 1 slot using realistic frame steps
        var elapsed = 0f
        while (elapsed < slotTime) {
            spawner.update(frameDt, 550f, 260f)
            elapsed += frameDt
        }

        // Slot 0 (even) has ticked -> 1 enemy spawned
        assertEquals(1, spawner.activeCount)
        assertEquals(1, spawner.slotIndex)

        // Advance slot 1 (odd slot -> collectible slot, no enemy spawn)
        elapsed = 0f
        while (elapsed < slotTime) {
            spawner.update(frameDt, 550f, 260f)
            elapsed += frameDt
        }
        assertEquals(1, spawner.activeCount) // still 1 enemy
        assertEquals(2, spawner.slotIndex)

        // Slot 2 (even): previous enemy is still in front of player (not cleared yet), so no new spawn
        spawner.update(frameDt, 550f, 260f)
        assertEquals(1, spawner.activeCount)
    }

    @Test
    fun `collision detection finds active walking enemy`() {
        val enemy = spawner.spawnShyGuy()
        assertNotNull(enemy)
        enemy!!.spawn(260f)

        // Player hitbox overlapping enemy
        val playerHitbox = RectF()
        playerHitbox.left = enemy.hitbox.left
        playerHitbox.top = enemy.hitbox.top
        playerHitbox.right = enemy.hitbox.right
        playerHitbox.bottom = enemy.hitbox.bottom

        val colliding = spawner.checkPlayerCollision(playerHitbox)
        assertNotNull(colliding)
        assertEquals(enemy, colliding)

        // When enemy is hurt, collision is ignored
        enemy.onPlayerCollision()
        val collidingAfterHurt = spawner.checkPlayerCollision(playerHitbox)
        assertNull(collidingAfterHurt)
    }
}
