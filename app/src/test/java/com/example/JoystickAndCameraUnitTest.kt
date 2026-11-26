package com.example

import com.example.game.engine.Camera3D
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoystickAndCameraUnitTest {

    @Test
    fun engineMovesSmoothlyIn360DirectionsAndStopsWhenJoystickReturnsToCenter() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN, gameMode = GameMode.CAMPAIGN)
        val startX = engine.player.pos.x
        val startY = engine.player.pos.y

        // Arbitrary 30-degree vector (cos 30° ≈ 0.866, sin 30° = 0.5)
        engine.setJoystickInput(0.866f, 0.5f)
        repeat(20) { engine.update(0.016f) }
        assertTrue("360° analog input must move player +X", engine.player.pos.x > startX + 0.5f)
        assertTrue("360° analog input must move player +Y", engine.player.pos.y > startY + 0.2f)
        assertTrue("Strafing should be faster laterally at 30° than vertically", engine.player.velocity.x > engine.player.velocity.y)

        // Return to center -> helicopter decelerates smoothly to hover
        engine.setJoystickInput(0f, 0f)
        repeat(60) { engine.update(0.016f) }
        assertEquals(0f, engine.player.velocity.x, 0.05f)
        assertEquals(0f, engine.player.velocity.y, 0.05f)

        // Check all 4 quadrants smoothly
        val directions = listOf(
            0f to 1f,
            0.707f to 0.707f,
            1f to 0f,
            0.707f to -0.707f,
            0f to -1f,
            -0.707f to -0.707f,
            -1f to 0f,
            -0.707f to 0.707f
        )
        for ((dx, dy) in directions) {
            engine.clearMovementInputs()
            engine.player.velocity = Vector3D(0f, 0f, 0f)
            engine.setJoystickInput(dx, dy)
            repeat(10) { engine.update(0.016f) }
            if (dx > 0.1f) assertTrue(engine.player.velocity.x > 0f)
            if (dx < -0.1f) assertTrue(engine.player.velocity.x < 0f)
            if (dy > 0.1f) assertTrue(engine.player.velocity.y > 0f)
            if (dy < -0.1f) assertTrue(engine.player.velocity.y < 0f)
        }
    }

    @Test
    fun cameraProjectionScalesProportionallyForLandscapeResolutions() {
        val camera = Camera3D(x = 0f, y = -12.5f, z = 27.5f)
        val phonePlayer = camera.project(Vector3D(0f, 0f, 9.5f), 2400f, 1080f)
        val phoneHorizon = camera.project(Vector3D(0f, 227.5f, 0f), 2400f, 1080f)
        val hdPlayer = camera.project(Vector3D(0f, 0f, 9.5f), 1280f, 720f)
        val hdHorizon = camera.project(Vector3D(0f, 227.5f, 0f), 1280f, 720f)

        assertNotNull(phonePlayer)
        assertNotNull(phoneHorizon)
        assertNotNull(hdPlayer)
        assertNotNull(hdHorizon)

        assertEquals(1200f, phonePlayer!!.screenPos.x, 1f)
        assertEquals(640f, hdPlayer!!.screenPos.x, 1f)

        val phonePlayerRatio = phonePlayer.screenPos.y / 1080f
        val hdPlayerRatio = hdPlayer.screenPos.y / 720f
        assertTrue("Phone landscape player Y ratio out of bounds: $phonePlayerRatio", phonePlayerRatio in 0.60f..0.72f)
        assertTrue("720p landscape player Y ratio out of bounds: $hdPlayerRatio", hdPlayerRatio in 0.60f..0.72f)

        val phoneHorizonRatio = phoneHorizon!!.screenPos.y / 1080f
        val hdHorizonRatio = hdHorizon!!.screenPos.y / 720f
        assertTrue("Phone landscape horizon ratio out of bounds: $phoneHorizonRatio", phoneHorizonRatio in 0.34f..0.46f)
        assertTrue("720p landscape horizon ratio out of bounds: $hdHorizonRatio", hdHorizonRatio in 0.34f..0.46f)
    }
}
