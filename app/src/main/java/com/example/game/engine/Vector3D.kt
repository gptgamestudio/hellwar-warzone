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
        return if (len > 0.0001f) Vector3D(x / len, y / len, z / len) else Vector3D(0f, 0f, 0f)
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
        velY: Float = 0f
    ) {
        // Smooth aerial camera tracking with subtle velocity look-ahead
        val leadX = (velX * 0.14f).coerceIn(-4.5f, 4.5f)
        val leadY = (velY * 0.12f).coerceIn(-3.5f, 5.5f)
        val targetCamX = targetX + leadX
        val targetCamY = targetY - 12.5f + leadY
        val targetCamZ = 27.5f

        val smoothX = 1f - kotlin.math.exp(-7.5f * delta)
        val smoothY = 1f - kotlin.math.exp(-8.0f * delta)
        val smoothZ = 1f - kotlin.math.exp(-4.0f * delta)

        x += (targetCamX - x) * smoothX
        y += (targetCamY - y) * smoothY
        z += (targetCamZ - z) * smoothZ

        if (screenShake > 0.02f) {
            shakeOffsetX = (Math.random().toFloat() - 0.5f) * screenShake
            shakeOffsetY = (Math.random().toFloat() - 0.5f) * screenShake * 0.6f
            screenShake -= screenShake * (1f - kotlin.math.exp(-7.5f * delta))
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

        if (depth <= 1.0f) return null // Behind camera plane

        // High FOV scale ensuring player helicopter spans 20-28% screen width
        val fov = 520.0f
        val scale = fov / depth

        val projX = (screenWidth / 2f) + (relX * scale)
        // Position player helicopter prominently at 63% screen height (lower-middle area above center-bottom D-pad)
        val projY = (screenHeight * 0.63f) - (height * scale)

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
