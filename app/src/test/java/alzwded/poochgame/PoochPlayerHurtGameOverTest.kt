package alzwded.poochgame

import alzwded.poochgame.engine.GameConstants
import alzwded.poochgame.engine.PoochPlayer
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PoochPlayerHurtGameOverTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var player: PoochPlayer

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
        for (i in 0..5) {
            frames.add(
                SpriteFrame(
                    id = "walk_$i",
                    x = i * 200, y = 0,
                    width = 206, height = 117,
                    originX = 132, originY = 50,
                    chromaKey = "#ffffff", chromaThreshold = 40
                )
            )
        }
        for (i in 0..5) {
            frames.add(
                SpriteFrame(
                    id = "jump_$i",
                    x = i * 200, y = 200,
                    width = 210, height = 150,
                    originX = 135, originY = 55,
                    chromaKey = "#ffffff", chromaThreshold = 30
                )
            )
        }
        atlas = SpriteAtlas("pooch", null, frames)
        player = PoochPlayer(atlas)
    }

    @Test
    fun `hurt state uses jump_5 and flickers at quarter second intervals for 1s`() {
        assertTrue(player.isVulnerable())

        player.triggerHurt()
        assertEquals(PoochPlayer.AnimState.HURT, player.animState)
        assertEquals("jump_5", player.currentFrame.id)
        assertTrue(player.isInvincible)
        assertFalse(player.isVulnerable())

        // Quarter 0 (0.00..0.25s): Visible
        player.update(0.1f, false)
        assertTrue(player.isVisible)
        assertEquals("jump_5", player.currentFrame.id)

        // Quarter 1 (0.25..0.50s): Hidden
        player.update(0.16f, false) // total 0.26s
        assertFalse(player.isVisible)
        assertEquals("jump_5", player.currentFrame.id)

        // Quarter 2 (0.50..0.75s): Visible
        player.update(0.25f, false) // total 0.51s
        assertTrue(player.isVisible)
        assertEquals("jump_5", player.currentFrame.id)

        // Quarter 3 (0.75..1.00s): Hidden
        player.update(0.25f, false) // total 0.76s
        assertFalse(player.isVisible)

        // End of hurt state (>= 1.00s): Returns to WALK, visible, vulnerable
        player.update(0.25f, false) // total 1.01s
        assertEquals(PoochPlayer.AnimState.WALK, player.animState)
        assertEquals("walk_0", player.currentFrame.id)
        assertTrue(player.isVisible)
        assertFalse(player.isInvincible)
        assertTrue(player.isVulnerable())
    }

    @Test
    fun `cannot jump during hurt state`() {
        player.triggerHurt()
        val jumped = player.jump()
        assertFalse(jumped)
    }

    @Test
    fun `game over anim flings player up, cycles jump_1-3 and mirrored states, scales to targetScale 2_0f, then completes off screen`() {
        val initialScale = player.scale
        assertEquals(1.2f, initialScale, 0.001f)

        player.triggerGameOver()
        assertEquals(PoochPlayer.AnimState.GAME_OVER, player.animState)
        assertEquals(-1200f, player.velocityY, 0.001f)
        assertEquals(2.0f, player.targetScale, 0.001f)
        assertFalse(player.isGameOverAnimFinished)

        // Cycle starts with jump_1 (not mirrored)
        player.update(0.01f, false)
        assertEquals("jump_1", player.currentFrame.id)
        assertFalse(player.isMirrored)
        assertTrue(player.scale > initialScale)

        // Advance to jump_2
        player.update(PoochPlayer.GAME_OVER_FRAME_DURATION, false)
        assertEquals("jump_2", player.currentFrame.id)
        assertFalse(player.isMirrored)

        // Advance to jump_3
        player.update(PoochPlayer.GAME_OVER_FRAME_DURATION, false)
        assertEquals("jump_3", player.currentFrame.id)
        assertFalse(player.isMirrored)

        // Advance to mirrored jump_1
        player.update(PoochPlayer.GAME_OVER_FRAME_DURATION, false)
        assertEquals("jump_1", player.currentFrame.id)
        assertTrue(player.isMirrored)

        // Advance to mirrored jump_2
        player.update(PoochPlayer.GAME_OVER_FRAME_DURATION, false)
        assertEquals("jump_2", player.currentFrame.id)
        assertTrue(player.isMirrored)

        // Advance to mirrored jump_3
        player.update(PoochPlayer.GAME_OVER_FRAME_DURATION, false)
        assertEquals("jump_3", player.currentFrame.id)
        assertTrue(player.isMirrored)

        // Update past fling duration (0.85s) -> scale reaches targetScale (2.0f)
        while (player.gameOverTimer < PoochPlayer.GAME_OVER_FLING_DURATION) {
            player.update(0.02f, false)
        }
        assertEquals(2.0f, player.scale, 0.001f)

        // Update until player falls past bottom of screen
        while (!player.isGameOverAnimFinished) {
            player.update(0.02f, false)
        }

        assertTrue(player.isGameOverAnimFinished)
        assertTrue(player.virtualY > GameConstants.VIRTUAL_HEIGHT + 250f)
    }

    @Test
    fun `game over scale updates correctly when update is called`() {
        player.triggerGameOver()
        assertEquals(1.2f, player.scale, 0.001f)

        // Advance halfway through fling duration
        val halfDuration = PoochPlayer.GAME_OVER_FLING_DURATION / 2f
        player.update(halfDuration, false)

        val expectedScale = 1.2f + (2.0f - 1.2f) * 0.5f
        assertEquals(expectedScale, player.scale, 0.01f)

        // Advance past full fling duration
        player.update(halfDuration + 0.1f, false)
        assertEquals(2.0f, player.scale, 0.001f)
    }
}
