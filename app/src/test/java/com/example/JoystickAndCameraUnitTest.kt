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

    @Test
    fun automaticDynamicFlightAltitudeRemainsBoundedAndSettlesSmoothly() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        assertEquals(9.5f, engine.player.pos.z, 0.01f)

        // Sustained forward flight gently climbs within safe envelope without uncontrolled runaway
        engine.setJoystickInput(0f, 1f)
        repeat(180) {
            engine.update(0.016f)
            assertTrue("Altitude Z must stay within safe bounds", engine.player.pos.z in 7.5f..13.0f)
        }
        val forwardZ = engine.player.pos.z
        assertTrue("Forward cruise should climb smoothly above resting 9.5f (was $forwardZ)", forwardZ > 11.0f && forwardZ <= 12.6f)

        // Reverse flight gently dips below cruise altitude without clipping ground
        engine.setJoystickInput(0f, -1f)
        repeat(120) {
            engine.update(0.016f)
            assertTrue("Reverse dip must stay clear of ground", engine.player.pos.z >= 7.5f)
        }
        val reverseZ = engine.player.pos.z
        assertTrue("Reverse flight should dip smoothly below resting 9.5f (was $reverseZ)", reverseZ < 9.0f && reverseZ >= 7.5f)

        // Releasing joystick settles smoothly back near resting hover altitude (~9.5f)
        engine.setJoystickInput(0f, 0f)
        repeat(150) { engine.update(0.016f) }
        assertTrue("Hover altitude should settle back near 9.5f (was ${engine.player.pos.z})", engine.player.pos.z in 9.2f..9.8f)
    }

    @Test
    fun cameraFollowsPlayerAltitudeAndVelocitySmoothlyWithoutJitter() {
        val camera = Camera3D(x = 0f, y = -12.5f, z = 27.5f)
        val initialZ = camera.z

        // Climb to 12.5f with upward velocity
        repeat(45) {
            camera.updateFollow(
                targetX = 0f,
                targetY = 10f,
                delta = 0.016f,
                velX = 0f,
                velY = 18f,
                targetZ = 12.5f,
                velZ = 2.5f
            )
        }
        assertTrue("Camera Z should rise smoothly to follow higher altitude", camera.z > initialZ + 1.5f)
        assertTrue("Camera Z must remain clamped within safe ceiling", camera.z <= 34.5f)
        assertTrue("Camera Z must remain finite", camera.z.isFinite())

        // Return to resting altitude 9.5f
        repeat(120) {
            camera.updateFollow(
                targetX = 0f,
                targetY = 10f,
                delta = 0.016f,
                velX = 0f,
                velY = 0f,
                targetZ = 9.5f,
                velZ = 0f
            )
        }
        assertEquals(27.5f, camera.z, 0.1f)
    }
}
