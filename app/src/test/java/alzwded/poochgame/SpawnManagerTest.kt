package alzwded.poochgame

import android.graphics.RectF
import alzwded.poochgame.engine.GameConstants
import alzwded.poochgame.engine.HeartCollectible
import alzwded.poochgame.engine.FudgeCollectible
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

    private lateinit var shyguyAtlas: SpriteAtlas
    private lateinit var fudgeAtlas: SpriteAtlas
    private lateinit var heartAtlas: SpriteAtlas
    private lateinit var spawner: SpawnManager

    @Before
    fun setUp() {
        val shyguyFrames = mutableListOf<SpriteFrame>()
        for (i in 0..5) {
            shyguyFrames.add(
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
            shyguyFrames.add(
                SpriteFrame(
                    id = "ohoh_$i",
                    x = i * 200, y = 300,
                    width = 180, height = 190,
                    originX = 90, originY = 165,
                    chromaKey = "#ffffff", chromaThreshold = 28
                )
            )
        }
        shyguyAtlas = SpriteAtlas("shyguy", null, shyguyFrames)

        val fudgeFrames = mutableListOf<SpriteFrame>()
        for (i in 0..7) {
            fudgeFrames.add(
                SpriteFrame(
                    id = "fudge_$i",
                    x = i * 100, y = 0,
                    width = 219, height = 212,
                    originX = 110, originY = 162
                )
            )
        }
        fudgeAtlas = SpriteAtlas("fudge", null, fudgeFrames)

        val heartFrames = mutableListOf<SpriteFrame>()
        for (i in 0..7) {
            heartFrames.add(
                SpriteFrame(
                    id = "heart_$i",
                    x = i * 100, y = 0,
                    width = 232, height = 214,
                    originX = 114, originY = 102
                )
            )
        }
        heartAtlas = SpriteAtlas("heart", null, heartFrames)

        spawner = SpawnManager(shyguyAtlas, fudgeAtlas, heartAtlas)
    }

    @Test
    fun `spawner enforces static maximum pools of 64 sprites for all entities`() {
        assertEquals(64, spawner.enemies.size)
        assertEquals(64, spawner.fudges.size)
        assertEquals(64, spawner.hearts.size)
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
    fun `cadence advances slots and updates entities`() {
        val frameDt = 0.016f
        val slotTime = SpawnManager.SLOT_INTERVAL_DISTANCE / 550f

        // Advance 1 slot using realistic frame steps at max HP (no random heart spawn)
        var elapsed = 0f
        while (elapsed <= slotTime) {
            spawner.update(frameDt, 550f, 260f, GameConstants.MAX_HP)
            elapsed += frameDt
        }

        // Slot 0 (even) has ticked -> slotIndex is 1
        assertEquals(1, spawner.slotIndex)

        // Advance slot 1
        elapsed = 0f
        while (elapsed <= slotTime) {
            spawner.update(frameDt, 550f, 260f, GameConstants.MAX_HP)
            elapsed += frameDt
        }
        assertEquals(2, spawner.slotIndex)
    }

    @Test
    fun `spawnHeart manually spawns heart collectible`() {
        val heart = spawner.spawnHeart(1000f, flying = false)
        assertNotNull(heart)
        assertTrue(heart!!.isActive)
        assertEquals(1000f, heart.virtualX, 0.001f)
    }

    @Test
    fun `checkHeartCollision detects player overlap with active heart`() {
        val heart = spawner.spawnHeart(500f, flying = false)
        assertNotNull(heart)

        val playerHitbox = RectF()
        playerHitbox.left = heart!!.hitbox.left
        playerHitbox.top = heart.hitbox.top
        playerHitbox.right = heart.hitbox.right
        playerHitbox.bottom = heart.hitbox.bottom

        val colliding = spawner.checkHeartCollision(playerHitbox)
        assertNotNull(colliding)
        assertEquals(heart, colliding)

        heart.onCollect()
        assertFalse(heart.isActive)
        val collidingAfterCollect = spawner.checkHeartCollision(playerHitbox)
        assertNull(collidingAfterCollect)
    }

    @Test
    fun `spawnFudge manually spawns fudge collectible`() {
        val fudge = spawner.spawnFudge(1000f, flying = true)
        assertNotNull(fudge)
        assertTrue(fudge!!.isActive)
        assertTrue(fudge.isFlying)
    }

    @Test
    fun `checkFudgeCollision detects player overlap with active fudge`() {
        val fudge = spawner.spawnFudge(500f, flying = false)
        assertNotNull(fudge)

        val playerHitbox = RectF()
        playerHitbox.left = fudge!!.hitbox.left
        playerHitbox.top = fudge.hitbox.top
        playerHitbox.right = fudge.hitbox.right
        playerHitbox.bottom = fudge.hitbox.bottom

        val colliding = spawner.checkFudgeCollision(playerHitbox)
        assertNotNull(colliding)
        assertEquals(fudge, colliding)

        fudge.onCollect()
        assertFalse(fudge.isActive)
        assertNull(spawner.checkFudgeCollision(playerHitbox))
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

    @Test
    fun `reset deactivates all pooled enemies fudges and hearts`() {
        val enemy = spawner.spawnShyGuy()
        val fudge = spawner.spawnFudge(500f, false)
        val heart = spawner.spawnHeart(500f, false)

        assertTrue(enemy!!.isActive)
        assertTrue(fudge!!.isActive)
        assertTrue(heart!!.isActive)

        spawner.reset()

        assertFalse(enemy.isActive)
        assertFalse(fudge.isActive)
        assertFalse(heart.isActive)
        assertEquals(0, spawner.slotIndex)
        assertEquals(0f, spawner.accumulatedDistance, 0.001f)
    }
}
