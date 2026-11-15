package alzwded.poochgame.engine

import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log

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
class SpawnManager(private val shyguyAtlas: SpriteAtlas) {

    companion object {
        private const val TAG = "SpawnManager"

        /** Hard maximum cap on active sprites to guarantee static memory allocation. */
        const val MAX_SPRITES = 64

        /** Virtual distance interval between spawn slots (in virtual pixels). */
        const val SLOT_INTERVAL_DISTANCE = 650f

        /** Off-screen spawn X position. */
        val SPAWN_X: Float get() = GameConstants.VIRTUAL_WIDTH + 120f
    }

    // Pre-allocated static pool of 64 Shy Guy enemies (zero per-frame heap allocations)
    val enemies: Array<ShyGuyEnemy> = Array(MAX_SPRITES) { ShyGuyEnemy(shyguyAtlas) }

    /** Sequential counter for slot ticks. */
    var slotIndex: Int = 0
        private set

    /** Accumulated scroll distance used to trigger slot ticks. */
    var accumulatedDistance: Float = 0f
        private set

    /** Reference to the most recently spawned enemy to verify prior clearance. */
    var lastSpawnedEnemy: ShyGuyEnemy? = null
        private set

    /** Number of currently active enemy sprites. */
    val activeCount: Int
        get() {
            var count = 0
            for (i in 0 until MAX_SPRITES) {
                if (enemies[i].isActive) count++
            }
            return count
        }

    /**
     * True if a new enemy is eligible to spawn:
     * 1. Total active sprites < 64.
     * 2. The previous enemy has been cleared (passed by player, collided with, or despawned).
     */
    val canSpawnEnemy: Boolean
        get() {
            if (activeCount >= MAX_SPRITES) return false
            val last = lastSpawnedEnemy ?: return true
            return last.isCleared || !last.isActive
        }

    /**
     * Reset spawner state and deactivate all pooled enemies.
     */
    fun reset() {
        for (i in 0 until MAX_SPRITES) {
            enemies[i].reset()
        }
        lastSpawnedEnemy = null
        slotIndex = 0
        accumulatedDistance = 0f
    }

    /**
     * Advance spawner distance and update all pooled enemies.
     *
     * @param dt Elapsed frame time in seconds.
     * @param scrollSpeed Current horizontal scroll speed (px/s).
     * @param playerVirtualX Player's horizontal anchor position.
     */
    fun update(dt: Float, scrollSpeed: Float, playerVirtualX: Float) {
        // Advance spawn slot distance
        accumulatedDistance += scrollSpeed * dt
        while (accumulatedDistance >= SLOT_INTERVAL_DISTANCE) {
            accumulatedDistance -= SLOT_INTERVAL_DISTANCE
            onSlotTick()
        }

        // Update all active enemies
        for (i in 0 until MAX_SPRITES) {
            if (enemies[i].isActive) {
                enemies[i].update(dt, scrollSpeed, playerVirtualX)
            }
        }
    }

    /**
     * Executes logic for the current quantized spawn slot.
     */
    private fun onSlotTick() {
        val currentSlot = slotIndex++
        val isEvenSlot = (currentSlot % 2 == 0)

        if (isEvenSlot) {
            // Even slots: eligible for enemy spawn if prior enemy is cleared
            if (canSpawnEnemy) {
                spawnShyGuy()
            }
        } else {
            // Odd slots: reserved for future collectibles (fudge, heart)
            // Long term: spawnFudge() or spawnHeart() will be called here
        }
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
     * Checks if the player's collision bounds intersect with any active walking enemy.
     * ZERO allocations.
     *
     * @param playerHitbox Pre-allocated RectF representing the player's inset hitbox.
     * @return The colliding enemy, or null if no collision occurred.
     */
    fun checkPlayerCollision(playerHitbox: RectF): ShyGuyEnemy? {
        for (i in 0 until MAX_SPRITES) {
            val enemy = enemies[i]
            if (enemy.state == ShyGuyEnemy.State.WALKING &&
                intersects(playerHitbox, enemy.hitbox)
            ) {
                return enemy
            }
        }
        return null
    }

    private fun intersects(a: RectF, b: RectF): Boolean {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }

    /**
     * Renders all active enemies to the canvas.
     * ZERO allocations.
     */
    fun render(canvas: Canvas, scaleX: Float, scaleY: Float) {
        for (i in 0 until MAX_SPRITES) {
            if (enemies[i].isActive) {
                enemies[i].render(canvas, scaleX, scaleY)
            }
        }
    }
}
