package com.example.game.engine

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

data class Vector3D(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    operator fun plus(other: Vector3D) = Vector3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vector3D(x * scalar, y * scalar, z * scalar)

    fun length(): Float = kotlin.math.sqrt(x * x + y * y + z * z)

    fun normalized(): Vector3D {
        val len = length()
        return if (len.isFinite() && len > 0.0001f) Vector3D(x / len, y / len, z / len) else Vector3D(0f, 0f, 0f)
    }
}

class Camera3D(
    var x: Float = 0f,
    var y: Float = -12f,
    var z: Float = 22f,
    var targetLeadY: Float = 8f
) {
    var screenShake: Float = 0f
    private var shakeOffsetX: Float = 0f
    private var shakeOffsetY: Float = 0f

    fun updateFollow(
        targetX: Float,
        targetY: Float,
        delta: Float,
        velX: Float = 0f,
        velY: Float = 0f,
        targetZ: Float = 9.5f,
        velZ: Float = 0f
    ) {
        val safeDelta = if (delta.isFinite()) delta.coerceIn(0.0001f, 0.16f) else 0.016f

        // Smooth aerial camera tracking with horizontal, forward, and vertical velocity look-ahead
        val leadX = (velX * 0.14f).coerceIn(-4.5f, 4.5f)
        val leadY = (velY * 0.12f).coerceIn(-3.5f, 5.5f)
        val leadZ = (velZ * 0.16f).coerceIn(-0.8f, 1.0f)
        val speedZoomZ = (kotlin.math.abs(velY) * 0.03f).coerceIn(0f, 1.0f)
        val targetCamX = targetX + leadX
        val targetCamY = targetY - 12.5f + leadY
        val targetCamZ = (targetZ + 18.0f + leadZ + speedZoomZ).coerceIn(24.0f, 34.0f)

        val smoothX = 1f - kotlin.math.exp(-7.5f * safeDelta)
        val smoothY = 1f - kotlin.math.exp(-8.0f * safeDelta)
        val smoothZ = 1f - kotlin.math.exp(-4.8f * safeDelta)

        x += (targetCamX - x) * smoothX
        y += (targetCamY - y) * smoothY
        z = (z + (targetCamZ - z) * smoothZ).coerceIn(22.0f, 36.0f)

        if (screenShake > 0.02f) {
            shakeOffsetX = (Math.random().toFloat() - 0.5f) * screenShake
            shakeOffsetY = (Math.random().toFloat() - 0.5f) * screenShake * 0.6f
            screenShake -= screenShake * (1f - kotlin.math.exp(-7.5f * safeDelta))
        } else {
            screenShake = 0f
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }
    }

    /**
     * Projects a 3D world coordinate (x, y, z) to 2D screen coordinate.
     * World space:
     *   X: -240 to +240 (horizontal open world)
     *   Y: Forward flight direction (+Y is toward top of screen)
     *   Z: Altitude (0 is ground, 9.5 is helicopter flight altitude)
     */
    fun project(
        world: Vector3D,
        screenWidth: Float,
        screenHeight: Float
    ): ProjectedPoint? {
        // Coherent per-frame camera shake across all projected vertices (zero tearing)
        val relX = world.x - x + shakeOffsetX
        val relY = world.y - y + shakeOffsetY
        val relZ = world.z - z

        // 48-degree camera pitch angle for ideal 3D depth and forward battlefield visibility
        val pitchRad = Math.toRadians(48.0).toFloat()
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)

        // Rotate world Y and Z around camera X axis
        val depth = relY * cosP - relZ * sinP
        val height = relY * sinP + relZ * cosP

        if (!depth.isFinite() || !height.isFinite() || depth <= 1.0f) return null // Behind camera plane

        // Responsive FOV proportional to screen height so landscape & portrait maintain horizon & helicopter scale
        val fov = (screenHeight * 0.265f).coerceIn(120.0f, 560.0f)
        val scale = fov / depth
        if (!scale.isFinite()) return null

        val projX = (screenWidth / 2f) + (relX * scale)
        // Position player helicopter cleanly in lower-center combat zone (clear of upper HUD and lower-left joystick)
        val projY = (screenHeight * 0.64f) - (height * scale)
        if (!projX.isFinite() || !projY.isFinite()) return null

        return ProjectedPoint(
            screenPos = Offset(projX, projY),
            scale = scale,
            depth = depth
        )
    }
}

data class ProjectedPoint(
    val screenPos: Offset,
    val scale: Float,
    val depth: Float
)
