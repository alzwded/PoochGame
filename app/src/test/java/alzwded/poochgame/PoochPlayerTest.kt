package alzwded.poochgame

import alzwded.poochgame.engine.EntityRegistry
import alzwded.poochgame.engine.GameConstants
import alzwded.poochgame.engine.PoochPlayer
import alzwded.poochgame.engine.SpriteAtlas
import alzwded.poochgame.engine.SpriteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PoochPlayerTest {

    private lateinit var atlas: SpriteAtlas
    private lateinit var player: PoochPlayer

    @Before
    fun setUp() {
        val frames = mutableListOf<SpriteFrame>()
        // 6 walk frames
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
        // 6 jump frames
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
    fun `initial state is walk on ground with walk_0`() {
        assertEquals(PoochPlayer.AnimState.WALK, player.animState)
        assertEquals("walk_0", player.currentFrame.id)
        assertTrue(player.isOnGround)
        val expectedHeadY = GameConstants.GROUND_Y - (PoochPlayer.HEAD_TO_GROUND_OFFSET_UNSCALED * EntityRegistry.POOCH.baseScale)
        assertEquals(expectedHeadY, player.virtualY, 0.001f)
    }

    @Test
    fun `walk animation cycles through frames every sixth of a second`() {
        val frameDuration = 1.0f / 6.0f // ~0.1667s

        // t = 0
        assertEquals("walk_0", player.currentFrame.id)

        // Advance by frameDuration * 0.5 (still frame 0)
        player.update(frameDuration * 0.5f, false)
        assertEquals("walk_0", player.currentFrame.id)

        // Advance to frame 1
        player.update(frameDuration * 0.6f, false)
        assertEquals("walk_1", player.currentFrame.id)

        // Advance to frame 2
        player.update(frameDuration, false)
        assertEquals("walk_2", player.currentFrame.id)

        // Advance to frame 3
        player.update(frameDuration, false)
        assertEquals("walk_3", player.currentFrame.id)

        // Advance to frame 4
        player.update(frameDuration, false)
        assertEquals("walk_4", player.currentFrame.id)

        // Loops back to frame 0 (walk_5 omitted)
        player.update(frameDuration, false)
        assertEquals("walk_0", player.currentFrame.id)
    }

    @Test
    fun `jump starts with jump_0 windup then transitions through ascent, fall, and landing`() {
        // Trigger jump
        val jumped = player.jump()
        assertTrue(jumped)
        assertFalse(player.isOnGround)
        assertEquals(PoochPlayer.AnimState.JUMP_WINDUP, player.animState)
        assertEquals("jump_0", player.currentFrame.id)

        // In windup (0.03s elapsed < 0.06s)
        player.update(0.03f, false)
        assertEquals(PoochPlayer.AnimState.JUMP_WINDUP, player.animState)
        assertEquals("jump_0", player.currentFrame.id)

        // Windup completes (0.04s more -> total 0.07s > 0.06s)
        player.update(0.04f, false)
        assertEquals(PoochPlayer.AnimState.JUMP_ASCENT, player.animState)
        // During early ascent, frame should be jump_1
        assertEquals("jump_1", player.currentFrame.id)

        // Mid ascent -> jump_2
        player.update(0.12f, false)
        assertEquals(PoochPlayer.AnimState.JUMP_ASCENT, player.animState)
        assertEquals("jump_2", player.currentFrame.id)

        // Late ascent approaching apex -> jump_3
        player.update(0.12f, false)
        assertEquals(PoochPlayer.AnimState.JUMP_ASCENT, player.animState)
        assertEquals("jump_3", player.currentFrame.id)

        // Update until apex is crossed (vy >= 0) -> JUMP_FALL with jump_4
        while (player.velocityY < 0f) {
            player.update(0.01f, false)
        }
        assertEquals(PoochPlayer.AnimState.JUMP_FALL, player.animState)
        assertEquals("jump_4", player.currentFrame.id)

        // Continue falling until landing
        while (!player.isOnGround) {
            player.update(0.016f, false)
        }

        // On landing: state is JUMP_LANDING and frame is jump_5
        assertTrue(player.isOnGround)
        assertEquals(PoochPlayer.AnimState.JUMP_LANDING, player.animState)
        assertEquals("jump_5", player.currentFrame.id)

        // After landing duration (~0.08s), returns to WALK with walk_0
        player.update(0.09f, false)
        assertEquals(PoochPlayer.AnimState.WALK, player.animState)
        assertEquals("walk_0", player.currentFrame.id)
    }

    @Test
    fun `cannot jump while in air`() {
        player.jump()
        assertFalse(player.isOnGround)

        // Advance into ascent
        player.update(0.1f, false)
        val secondJump = player.jump()
        assertFalse(secondJump)
    }

    @Test
    fun `can jump again during landing recovery`() {
        player.jump()
        // Run until landing
        while (player.animState != PoochPlayer.AnimState.JUMP_LANDING) {
            player.update(0.02f, false)
        }
        assertEquals(PoochPlayer.AnimState.JUMP_LANDING, player.animState)

        // Should allow jumping again immediately from landing crouch
        val chainedJump = player.jump()
        assertTrue(chainedJump)
        assertEquals(PoochPlayer.AnimState.JUMP_WINDUP, player.animState)
        assertEquals("jump_0", player.currentFrame.id)
    }

    @Test
    fun `cannot jump while super mode is active`() {
        player.update(0.016f, isSuperMode = true)
        assertTrue(player.isSuperMode)

        val jumpResult = player.jump()
        assertFalse(jumpResult)
        assertFalse(player.animState == PoochPlayer.AnimState.JUMP_WINDUP)
    }

    @Test
    fun `exitSuperMode resets position to ground baseline, clears velocity, restores grounded and walk state`() {
        // Put player into super mode
        player.update(0.1f, isSuperMode = true)
        assertTrue(player.isSuperMode)

        // Simulate ending super mode
        player.exitSuperMode()

        assertFalse(player.isSuperMode)
        assertTrue(player.isOnGround)
        assertEquals(PoochPlayer.AnimState.WALK, player.animState)
        assertEquals(0f, player.velocityY, 0.001f)
        val expectedGroundHeadY = player.getGroundHeadY(EntityRegistry.POOCH.baseScale)
        assertEquals(expectedGroundHeadY, player.virtualY, 0.001f)
        assertEquals(EntityRegistry.POOCH.baseScale, player.scale, 0.001f)
    }

    @Test
    fun `super cutscene hops in air then smoothly grows pooch to super scale`() {
        player.triggerSuperCutscene()
        assertEquals(PoochPlayer.AnimState.SUPER_CUTSCENE, player.animState)
        val baseHeadY = player.getGroundHeadY(EntityRegistry.POOCH.baseScale)

        // Mid-hop (at 0.12s of first hop) -> Pooch should be elevated above ground baseline
        player.update(0.12f, false)
        assertEquals(PoochPlayer.AnimState.SUPER_CUTSCENE, player.animState)
        assertTrue(player.virtualY < baseHeadY) // elevated (smaller Y value)

        // Advance past both hops into grow phase
        val hopTotalDur = PoochPlayer.SUPER_HOP_FRAME_DURATION * 6f // 2 hops * 0.24s = 0.48s
        player.update(hopTotalDur, false)
        assertEquals(PoochPlayer.AnimState.SUPER_CUTSCENE, player.animState)

        // Grow phase midway -> scale should be between baseScale and superScale
        val expectedSuperScale = EntityRegistry.POOCH.baseScale * GameConstants.SUPER_MODE_SCALE
        assertTrue(player.scale > EntityRegistry.POOCH.baseScale)
        assertTrue(player.scale < expectedSuperScale)

        // Advance past grow phase -> finish cutscene into WALK at superScale
        player.update(PoochPlayer.SUPER_GROW_DURATION + 0.1f, false)
        assertEquals(PoochPlayer.AnimState.WALK, player.animState)
        assertEquals(expectedSuperScale, player.scale, 0.001f)
        assertTrue(player.isCutsceneFinished)
    }

    @Test
    fun `triggerSplashHop continuously loops hop animation`() {
        player.triggerSplashHop()
        assertEquals(PoochPlayer.AnimState.SPLASH_HOP, player.animState)
        val baseHeadY = player.getGroundHeadY(EntityRegistry.POOCH.baseScale)

        // Hop 1 (facing right) - elevated
        player.update(0.12f, false)
        assertEquals(PoochPlayer.AnimState.SPLASH_HOP, player.animState)
        assertFalse(player.isMirrored)
        assertTrue(player.virtualY < baseHeadY)

        // Hop 2 (facing left) - elevated and mirrored
        player.update(0.24f, false)
        assertEquals(PoochPlayer.AnimState.SPLASH_HOP, player.animState)
        assertTrue(player.isMirrored)
        assertTrue(player.virtualY < baseHeadY)

        // Loops back to Hop 1 (facing right) after completing 2 full hops (0.48s)
        player.update(0.24f, false)
        assertEquals(PoochPlayer.AnimState.SPLASH_HOP, player.animState)
        assertFalse(player.isMirrored)
    }
}
