package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import kotlin.random.Random


/**
 * Consolidated spawner and entity manager for enemies and obstacles.
 *
 * Implements:
 * - Fixed maximum cap of 64 sprites with pre-allocated static object pool.
 * - Quantized slot cadence: total slots advance every [SLOT_INTERVAL_DISTANCE] px.
 *   - Enemies spawn ONLY on even slots (cadence is half of collectible slots).
 *   - Enemies spawn ONLY when the prior enemy has been cleared (passed or hit).
 *   - Odd slots reserved for future collectibles (fudge, heart) at double frequency.
 * - Strictly zero-allocation per frame update and render loops.
 */
class SpawnManager(
    private val shyguyAtlas: SpriteAtlas,
    private val fudgeAtlas: SpriteAtlas,
    private val heartAtlas: SpriteAtlas
) {
    companion object {
        private const val TAG = "SpawnManager"
        /** Hard maximum cap on active sprites to guarantee static memory allocation. */
        const val MAX_SPRITES = 64
        /** Virtual distance interval between spawn slots (in virtual pixels). */
        const val SLOT_INTERVAL_DISTANCE = 650f
        /** Off-screen spawn X position. */
        val SPAWN_X: Float get() = GameConstants.VIRTUAL_WIDTH + 120f
    }

    // TODO uhm... these need to be optimized to have a total of MAX_SPRITES
    // Pre-allocated static pools (zero per-frame heap allocations)
    val enemies: Array<ShyGuyEnemy> = Array(MAX_SPRITES) { ShyGuyEnemy(shyguyAtlas) }
    val towers: Array<Tower> = Array(MAX_SPRITES) { Tower(shyguyAtlas, fudgeAtlas) }
    val fudges: Array<FudgeCollectible> = Array(MAX_SPRITES) { FudgeCollectible(fudgeAtlas) }
    val hearts: Array<HeartCollectible> = Array(MAX_SPRITES) { HeartCollectible(heartAtlas) }

    /** Sequential counter for slot ticks. */
    var slotIndex: Int = 0
        private set
    /** Accumulated scroll distance used to trigger slot ticks. */
    var accumulatedDistance: Float = 0f
        private set
    /** Reference to the most recently spawned enemy to verify prior clearance. */
    var lastSpawnedEnemy: ShyGuyEnemy? = null
        private set
    var lastSpawnedTower: Tower? = null
        private set

    /** True if the spawner is operating in Super Mode. */
    var isSuperMode: Boolean = false
        private set

    /** Number of currently active enemy sprites. */
    val activeCount: Int
        get() {
            var count = 0
            for (i in 0 until MAX_SPRITES) {
                if (enemies[i].isActive) count++
                if (towers[i].isActive) count += towers[i].height
            }
            return count
        }

    /**
     * True if a new enemy is eligible to spawn:
     * 1. Total active sprites < 64.
     * 2. The previous enemy or tower has been cleared (passed by player, collided with, or despawned).
     */
    val canSpawnEnemy: Boolean
        get() {
            if (activeCount >= MAX_SPRITES) return false
            val lastE = lastSpawnedEnemy
            val lastT = lastSpawnedTower
            val enemyCleared = lastE == null || lastE.isCleared || !lastE.isActive
            val towerCleared = lastT == null || lastT.isCleared || !lastT.isActive
            return enemyCleared && towerCleared
        }

    /**
     * Reset spawner state and deactivate all pooled entities.
     */
    fun reset() {
        for (i in 0 until MAX_SPRITES) {
            enemies[i].reset()
            towers[i].reset()
            fudges[i].reset()
            hearts[i].reset()
        }
        lastSpawnedEnemy = null
        lastSpawnedTower = null
        slotIndex = 0
        accumulatedDistance = 0f
        isSuperMode = false
    }

    /**
     * Advance spawner distance and update all pooled enemies.
     *
     * @param dt Elapsed frame time in seconds.
     * @param scrollSpeed Current horizontal scroll speed (px/s).
     * @param playerVirtualX Player's horizontal anchor position.
     * @param playerHP Current player health.
     * @param isSuperMode True if player is currently in Super Mode.
     */
    fun update(
        dt: Float,
        scrollSpeed: Float,
        playerVirtualX: Float,
        playerHP: Int,
        isSuperMode: Boolean = false
    ) {
        this.isSuperMode = isSuperMode
        // Advance spawn slot distance
        accumulatedDistance += scrollSpeed * dt
        while (accumulatedDistance >= SLOT_INTERVAL_DISTANCE) {
            accumulatedDistance -= SLOT_INTERVAL_DISTANCE
            onSlotTick(playerHP)
        }
        // Update all active entities
        for (i in 0 until MAX_SPRITES) {
            if (enemies[i].isActive) {
                enemies[i].update(dt, scrollSpeed, playerVirtualX)
            }
            if (towers[i].isActive) {
                towers[i].update(dt, scrollSpeed, playerVirtualX)
            }
            if (fudges[i].isActive) {
                fudges[i].update(dt, scrollSpeed, playerVirtualX)
            }
            if (hearts[i].isActive) {
                hearts[i].update(dt, scrollSpeed, playerVirtualX)
            }
        }
    }

    /**
     * Executes logic for the current quantized spawn slot.
     */
    private fun onSlotTick(playerHP: Int) {
        if (isSuperMode) {
            // Super Mode: Exclusively spawn 3-high Shy Guy towers on EVERY slot tick (full collectible spawn rate)
            spawnShyGuyTower(height = 3)
            return
        }

        val currentSlot = slotIndex++
        val isEvenSlot = (currentSlot % 2 == 0)

        // 5% chance to spawn rare heart when HP < 3 (spawns alone)
        if (playerHP < GameConstants.MAX_HP && Random.nextFloat() < 0.05f) {
            val isFlying = Random.nextBoolean()
            spawnHeart(SPAWN_X, isFlying)
            return
        }

        if (isEvenSlot) {
            // Even slot: can spawn Enemy, Fudge, or Both (and Shy Guy towers during Super Mode)
            if (canSpawnEnemy) {
                val roll = Random.nextFloat()
                when {
                    roll < 0.35f -> {
                        // Enemy only
                        spawnShyGuy()
                    }
                    roll < 0.65f -> {
                        // Fudge only
                        val isFlying = Random.nextBoolean()
                        spawnFudge(SPAWN_X, isFlying, if (isFlying) FudgeCollectible.FLYING_SPEED else 0f)
                    }
                    else -> {
                        // BOTH Enemy and Fudge!
                        spawnShyGuy()
                        if (Random.nextBoolean()) {
                            // Flying form at max jump height, matching enemy speed
                            spawnFudge(SPAWN_X, flying = true, speedOffset = ShyGuyEnemy.WALK_SPEED)
                        } else {
                            // Ground form offset in front (+160f) or behind (-160f), matching enemy speed
                            val offset = if (Random.nextBoolean()) 160f else -160f
                            spawnFudge(SPAWN_X + offset, flying = false, speedOffset = ShyGuyEnemy.WALK_SPEED)
                        }
                    }
                }
            } else {
                val isFlying = Random.nextBoolean()
                spawnFudge(SPAWN_X, isFlying, if (isFlying) FudgeCollectible.FLYING_SPEED else 0f)
            }
        } else {
            // Odd slot: Collectible slot (2x rate vs enemies)
            val isFlying = Random.nextBoolean()
            spawnFudge(SPAWN_X, isFlying, if (isFlying) FudgeCollectible.FLYING_SPEED else 0f)
        }
    }

    fun spawnFudge(spawnX: Float, flying: Boolean, speedOffset: Float = 0f): FudgeCollectible? {
        for (i in 0 until MAX_SPRITES) {
            val fudge = fudges[i]
            if (!fudge.isActive) {
                fudge.spawn(spawnX, flying, speedOffset)
                return fudge
            }
        }
        return null
    }

    fun spawnHeart(spawnX: Float, flying: Boolean, speedOffset: Float = 0f): HeartCollectible? {
        for (i in 0 until MAX_SPRITES) {
            val heart = hearts[i]
            if (!heart.isActive) {
                heart.spawn(spawnX, flying, speedOffset)
                Log.d(TAG, "Spawned Heart collectible on slot $slotIndex at index $i")
                return heart
            }
        }
        return null
    }

    fun checkHeartCollision(playerHitbox: RectF): HeartCollectible? {
        for (i in 0 until MAX_SPRITES) {
            val heart = hearts[i]
            if (heart.isActive && intersects(playerHitbox, heart.hitbox)) {
                return heart
            }
        }
        return null
    }

    /**
     * Spawns a Shy Guy enemy at the right edge of the screen using an idle pooled slot.
     *
     * @return The spawned enemy, or null if pool is exhausted or prior enemy uncleared.
     */
    fun spawnShyGuy(): ShyGuyEnemy? {
        if (!canSpawnEnemy) return null
        for (i in 0 until MAX_SPRITES) {
            val enemy = enemies[i]
            if (!enemy.isActive) {
                enemy.spawn(SPAWN_X)
                lastSpawnedEnemy = enemy
                Log.d(TAG, "Spawned Shy Guy on slot $slotIndex at slot index $i")
                return enemy
            }
        }
        Log.w(TAG, "Failed to spawn Shy Guy: pool of $MAX_SPRITES is full")
        return null
    }

    /**
     * Spawns a vertical stack/tower of Shy Guys (e.g. 3 stacked vertically) using a dedicated [Tower] object.
     */
    fun spawnShyGuyTower(height: Int = 3): Tower? {
        if (!canSpawnEnemy) return null
        for (i in 0 until MAX_SPRITES) {
            val tower = towers[i]
            if (!tower.isActive) {
                tower.spawn(SPAWN_X, height)
                lastSpawnedTower = tower
                Log.d(TAG, "Spawned Shy Guy Tower (height $height) on slot $slotIndex at slot index $i")
                return tower
            }
        }
        Log.w(TAG, "Failed to spawn Shy Guy Tower: pool of $MAX_SPRITES is full")
        return null
    }

    /**
     * Checks if the player's collision bounds intersect with any active walking enemy or tower.
     * ZERO allocations.
     *
     * @param playerHitbox Pre-allocated RectF representing the player's inset hitbox.
     * @return True if a collision occurred (and onPlayerCollision was triggered).
     */
    fun checkPlayerCollision(playerHitbox: RectF): Boolean {
        for (i in 0 until MAX_SPRITES) {
            val enemy = enemies[i]
            if (enemy.state == ShyGuyEnemy.State.WALKING &&
                intersects(playerHitbox, enemy.hitbox)
            ) {
                enemy.onPlayerCollision()
                return true
            }
        }
        for (i in 0 until MAX_SPRITES) {
            val tower = towers[i]
            if (tower.state == Tower.State.WALKING &&
                intersects(playerHitbox, tower.hitbox)
            ) {
                tower.onPlayerCollision()
                return true
            }
        }
        return false
    }

    fun checkFudgeCollision(playerHitbox: RectF): FudgeCollectible? {
        for (i in 0 until MAX_SPRITES) {
            val fudge = fudges[i]
            if (fudge.isActive && intersects(playerHitbox, fudge.hitbox)) {
                return fudge
            }
        }
        return null
    }

    private fun intersects(a: RectF, b: RectF): Boolean {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }

    /**
     * Renders all active fudges and enemies to the canvas.
     * ZERO allocations.
     */
    fun render(canvas: Canvas, scaleX: Float, scaleY: Float) {
        for (i in 0 until MAX_SPRITES) {
            if (fudges[i].isActive) {
                fudges[i].render(canvas, scaleX, scaleY)
            }
        }
        for (i in 0 until MAX_SPRITES) {
            if (hearts[i].isActive) {
                hearts[i].render(canvas, scaleX, scaleY)
            }
        }
        for (i in 0 until MAX_SPRITES) {
            if (enemies[i].isActive) {
                enemies[i].render(canvas, scaleX, scaleY)
            }
        }
        for (i in 0 until MAX_SPRITES) {
            if (towers[i].isActive) {
                towers[i].render(canvas, scaleX, scaleY)
            }
        }
    }
}
