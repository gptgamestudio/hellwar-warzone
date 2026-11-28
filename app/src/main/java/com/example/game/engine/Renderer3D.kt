package com.example.game.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.example.game.model.EmpireType
import com.example.game.model.HelicopterClass
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.AmberWarning
import kotlin.math.cos
import kotlin.math.sin

class Renderer3D {

    private val tmpPath = Path()
    private var cachedSkyHeight: Float = -1f
    private var cachedSkyEmpire: EmpireType? = null
    private var cachedSkyAltBucket: Int = -1
    private var cachedHorizonYInt: Int = -1
    private var cachedSkyBrush: Brush? = null
    private var cachedHazeBrush: Brush? = null
    private var cachedGroundDepthBrush: Brush? = null

    private val aaBarrelOffsets = floatArrayOf(-6f, -2f, 2f, 6f)
    private val samWheelFactors = floatArrayOf(-0.35f, 0f, 0.35f)

    internal fun playerModelScale(heloClass: HelicopterClass): Float = when (heloClass) {
        HelicopterClass.SCOUT -> 1.78f
        HelicopterClass.HEAVY_GUNSHIP -> 1.64f
        HelicopterClass.STEALTH -> 1.72f
        HelicopterClass.ULTIMATE_WAR -> 1.68f
    }

    internal fun enemyVisualScale(category: EnemyCategory): Float = when (category) {
        EnemyCategory.INFANTRY_SOLDIER -> 2.45f
        EnemyCategory.ROCKET_SOLDIER -> 2.35f
        EnemyCategory.WATCHTOWER_SNIPER -> 2.05f
        EnemyCategory.AA_TURRET -> 1.95f
        EnemyCategory.MISSILE_LAUNCHER -> 1.85f
        EnemyCategory.GROUND_TANK -> 1.85f
        EnemyCategory.ATTACK_HELICOPTER -> 1.85f
        EnemyCategory.FIGHTER_AIRCRAFT -> 1.85f
    }

    internal fun enemyHealthBarOffset(category: EnemyCategory): Float = when (category) {
        EnemyCategory.INFANTRY_SOLDIER -> 58f
        EnemyCategory.ROCKET_SOLDIER -> 64f
        EnemyCategory.WATCHTOWER_SNIPER -> 64f
        EnemyCategory.AA_TURRET -> 68f
        EnemyCategory.MISSILE_LAUNCHER -> 74f
        EnemyCategory.GROUND_TANK -> 76f
        EnemyCategory.ATTACK_HELICOPTER -> 78f
        EnemyCategory.FIGHTER_AIRCRAFT -> 74f
    }

    fun render(
        scope: DrawScope,
        engine: GameEngine,
        width: Float,
        height: Float
    ) {
        val camera = engine.camera
        val minVisY = camera.y - 18f
        val maxVisY = camera.y + 165f
        val minVisX = camera.x - 220f
        val maxVisX = camera.x + 220f

        val rawHorizonY = camera.project(Vector3D(camera.x, camera.y + 240f, 0f), width, height)?.screenPos?.y
            ?: (height * 0.43f)
        val horizonY = rawHorizonY.coerceIn(height * 0.24f, height * 0.58f)

        // 1. Altitude-Responsive Sky / Atmosphere & Parallax Horizon
        drawAtmosphere(scope, camera, engine.player.pos.z, horizonY, width, height, engine.selectedEmpire)

        // 2. 3D Scrolling Battlefield Ground & Terrain Landscape
        drawGroundTerrain(scope, camera, horizonY, width, height, engine.selectedEmpire)

        // 3. 3D Terrain Structures & Installations
        drawStructures(scope, camera, engine.structures, width, height)

        // 3b. 50 Enemy Barracks Objectives in 3D
        drawBarracks3D(scope, camera, engine.allBarracks, width, height)

        // 3c. Enemy Headquarters in 3D
        engine.enemyHeadquarters?.let { hq ->
            drawHeadquarters3D(scope, camera, hq, width, height)
        }

        // 4. Environmental Hazards (Lava Geysers, Plasma Lightning, Smog Pits, EMP Grid)
        drawHazards(scope, camera, engine.hazards, width, height)

        // 5. Ground Shadows
        drawShadows(scope, camera, engine, width, height)

        // 4. Ground-level Enemies (Tanks, Turrets, Missile Launchers) - zero-allocation pass
        for (enemy in engine.enemies) {
            if (enemy.isGroundUnit &&
                enemy.pos.y in minVisY..maxVisY &&
                enemy.pos.x in minVisX..maxVisX
            ) {
                drawEnemy3D(scope, camera, enemy, width, height)
            }
        }

        // 5. Aerial Enemies (Attack Helicopters, Fighters) - zero-allocation pass
        for (enemy in engine.enemies) {
            if (!enemy.isGroundUnit &&
                enemy.pos.y in minVisY..maxVisY &&
                enemy.pos.x in minVisX..maxVisX
            ) {
                drawEnemy3D(scope, camera, enemy, width, height)
            }
        }

        // 6. Boss Entity in 3D
        engine.activeBoss?.let { boss ->
            drawBoss3D(scope, camera, boss, width, height)
        }

        // 7. Player Helicopter
        drawPlayerHelicopter3D(scope, camera, engine.player, width, height)

        // 8. Multiple Rocket Launcher (MRL) Rockets
        for (rocket in engine.rockets) {
            if (rocket.pos.y in (minVisY - 30f)..(maxVisY + 30f)) {
                drawRocket3D(scope, camera, rocket, width, height)
            }
        }

        // 9. Guided Missiles
        for (missile in engine.missiles) {
            if (missile.pos.y in (minVisY - 30f)..(maxVisY + 30f)) {
                drawMissile3D(scope, camera, missile, width, height)
            }
        }

        // 10. Bullets & Tracers
        for (bullet in engine.bullets) {
            if (bullet.pos.y in minVisY..maxVisY && bullet.pos.x in minVisX..maxVisX) {
                drawBullet3D(scope, camera, bullet, width, height)
            }
        }

        // 11. Particles & Explosions
        for (particle in engine.particles) {
            if (particle.pos.y in minVisY..maxVisY && particle.pos.x in minVisX..maxVisX) {
                drawParticle(scope, camera, particle, width, height)
            }
        }

        // 12. 3D Target-Lock Reticle
        drawTargetLockReticle(scope, camera, engine, width, height)
    }

    private fun drawAtmosphere(
        scope: DrawScope,
        camera: Camera3D,
        playerAltitude: Float,
        horizonY: Float,
        width: Float,
        height: Float,
        empire: EmpireType
    ) {
        val baseSkyTop: Color
        val baseSkyMid: Color
        val baseSkyHorizon: Color
        val ridgeFarColor: Color
        val ridgeNearColor: Color
        val hazeColor: Color
        val accentGlowColor: Color

        when (empire) {
            EmpireType.DRAGON -> {
                baseSkyTop = Color(0xFF140706)
                baseSkyMid = Color(0xFF3E150C)
                baseSkyHorizon = Color(0xFF963214)
                ridgeFarColor = Color(0xFF331510)
                ridgeNearColor = Color(0xFF1D0C09)
                hazeColor = Color(0xFFBD471B)
                accentGlowColor = Color(0x44FF7043)
            }
            EmpireType.ALIEN -> {
                baseSkyTop = Color(0xFF07051A)
                baseSkyMid = Color(0xFF191242)
                baseSkyHorizon = Color(0xFF244A68)
                ridgeFarColor = Color(0xFF201A4A)
                ridgeNearColor = Color(0xFF130F30)
                hazeColor = Color(0xFF2C6B7C)
                accentGlowColor = Color(0x3D00E5FF)
            }
            EmpireType.ORC -> {
                baseSkyTop = Color(0xFF1B1610)
                baseSkyMid = Color(0xFF473623)
                baseSkyHorizon = Color(0xFF8C6335)
                ridgeFarColor = Color(0xFF382C1E)
                ridgeNearColor = Color(0xFF241B12)
                hazeColor = Color(0xFFA67842)
                accentGlowColor = Color(0x38FFB74D)
            }
            EmpireType.HUMAN -> {
                baseSkyTop = Color(0xFF18314A)
                baseSkyMid = Color(0xFF345B7D)
                baseSkyHorizon = Color(0xFF6C92AC)
                ridgeFarColor = Color(0xFF456070)
                ridgeNearColor = Color(0xFF2D454B)
                hazeColor = Color(0xFF93B0BE)
                accentGlowColor = Color(0x36FFE6B0)
            }
        }

        val altFactor = ((playerAltitude - 7.5f) / 5.5f).coerceIn(0f, 1f)
        val altBucket = (altFactor * 20f).toInt()
        val horizonBucket = horizonY.toInt()
        val skyTop = lerp(baseSkyTop, Color.Black, altFactor * 0.22f)
        val skyMid = lerp(baseSkyMid, baseSkyTop, altFactor * 0.14f)
        val skyHorizon = lerp(baseSkyHorizon, hazeColor, (1f - altFactor) * 0.14f)

        val hazeTop = (horizonY - height * 0.14f).coerceAtLeast(0f)
        val hazeBottom = (horizonY + height * 0.10f).coerceAtMost(height).coerceAtLeast(hazeTop + 1f)

        if (cachedSkyBrush == null || cachedHazeBrush == null || cachedGroundDepthBrush == null ||
            cachedSkyHeight != height || cachedSkyEmpire != empire ||
            cachedSkyAltBucket != altBucket || cachedHorizonYInt != horizonBucket
        ) {
            val horizonEndY = (horizonY + height * 0.04f).coerceAtLeast(1f)
            cachedSkyBrush = Brush.verticalGradient(
                colors = listOf(skyTop, skyMid, skyHorizon),
                startY = 0f,
                endY = horizonEndY
            )
            val hazeAlpha = 0.46f - altFactor * 0.14f
            cachedHazeBrush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, hazeColor.copy(alpha = hazeAlpha), Color.Transparent),
                startY = hazeTop,
                endY = hazeBottom
            )
            val groundDepthTop = (horizonY - height * 0.06f).coerceAtLeast(0f)
            cachedGroundDepthBrush = Brush.verticalGradient(
                colors = listOf(hazeColor.copy(alpha = hazeAlpha + 0.06f), Color.Transparent, Color(0x28000000)),
                startY = groundDepthTop,
                endY = height.coerceAtLeast(1f)
            )
            cachedSkyHeight = height
            cachedSkyEmpire = empire
            cachedSkyAltBucket = altBucket
            cachedHorizonYInt = horizonBucket
        }

        scope.drawRect(
            brush = cachedSkyBrush!!,
            size = Size(width, height)
        )

        // Subtle atmospheric sun / aurora / volcanic glow tracking horizon & subtle horizontal parallax
        val glowParallaxX = (-camera.x * 0.22f).coerceIn(-width * 0.12f, width * 0.12f)
        val glowTop = (horizonY - height * 0.26f + altFactor * height * 0.015f).coerceAtLeast(0f)
        scope.drawOval(
            color = accentGlowColor,
            topLeft = Offset(width * 0.12f + glowParallaxX, glowTop),
            size = Size(width * 0.76f, height * 0.25f)
        )

        // Layered drifting cloud / battle smoke strata with horizontal parallax and altitude shift
        val cloudSpan = width.coerceAtLeast(1f)
        val cloudParallax1 = ((-camera.x * 0.55f) % cloudSpan + cloudSpan) % cloudSpan
        val cloudParallax2 = ((-camera.x * 0.90f) % cloudSpan + cloudSpan) % cloudSpan
        val cloudParallax3 = ((-camera.x * 1.25f) % cloudSpan + cloudSpan) % cloudSpan
        val cloudY1 = (horizonY * (0.22f + altFactor * 0.04f)).coerceAtLeast(4f)
        val cloudY2 = (horizonY * (0.33f + altFactor * 0.05f)).coerceAtLeast(12f)
        val cloudY3 = (horizonY * (0.50f + altFactor * 0.06f)).coerceAtLeast(24f)

        for (tile in -1..0) {
            val base1 = cloudParallax1 + tile * cloudSpan
            val base2 = cloudParallax2 + tile * cloudSpan
            val base3 = cloudParallax3 + tile * cloudSpan
            scope.drawOval(
                Color.White.copy(alpha = 0.055f + altFactor * 0.025f),
                Offset(base1 + width * 0.05f, cloudY1),
                Size(width * 0.42f, height * (0.050f + altFactor * 0.01f))
            )
            scope.drawOval(
                Color.White.copy(alpha = 0.045f + altFactor * 0.02f),
                Offset(base2 + width * 0.48f, cloudY2),
                Size(width * 0.46f, height * (0.055f + altFactor * 0.01f))
            )
            scope.drawOval(
                Color.Black.copy(alpha = 0.13f - altFactor * 0.03f),
                Offset(base3 + width * 0.22f, cloudY3),
                Size(width * 0.54f, height * 0.048f)
            )
        }

        // Distant seamless panoramic horizon ridges themed per Empire with dual-layer parallax
        val ridgeBaseY = horizonY + height * 0.03f
        val farTileX = ((-camera.x * 0.65f) % cloudSpan + cloudSpan) % cloudSpan
        for (tile in -1..0) {
            val ox = farTileX + tile * cloudSpan
            val ridgeFar = tmpPath.apply {
                reset()
                moveTo(ox, horizonY - height * 0.045f)
                lineTo(ox + width * 0.14f, horizonY - height * 0.088f)
                lineTo(ox + width * 0.31f, horizonY - height * 0.060f)
                lineTo(ox + width * 0.51f, horizonY - height * 0.105f)
                lineTo(ox + width * 0.69f, horizonY - height * 0.070f)
                lineTo(ox + width * 0.87f, horizonY - height * 0.092f)
                lineTo(ox + width, horizonY - height * 0.045f)
                lineTo(ox + width, ridgeBaseY)
                lineTo(ox, ridgeBaseY)
                close()
            }
            scope.drawPath(ridgeFar, ridgeFarColor.copy(alpha = 0.84f), style = Fill)
        }

        val nearTileX = ((-camera.x * 1.25f) % cloudSpan + cloudSpan) % cloudSpan
        for (tile in -1..0) {
            val ox = nearTileX + tile * cloudSpan
            val ridgeNear = tmpPath.apply {
                reset()
                moveTo(ox, horizonY - height * 0.022f)
                lineTo(ox + width * 0.21f, horizonY - height * 0.054f)
                lineTo(ox + width * 0.44f, horizonY - height * 0.030f)
                lineTo(ox + width * 0.64f, horizonY - height * 0.064f)
                lineTo(ox + width * 0.83f, horizonY - height * 0.040f)
                lineTo(ox + width, horizonY - height * 0.022f)
                lineTo(ox + width, ridgeBaseY)
                lineTo(ox, ridgeBaseY)
                close()
            }
            scope.drawPath(ridgeNear, ridgeNearColor.copy(alpha = 0.94f), style = Fill)
        }

        // Soft atmospheric horizon mist blending ridges cleanly into projected horizon
        scope.drawRect(
            brush = cachedHazeBrush!!,
            topLeft = Offset(0f, hazeTop),
            size = Size(width, (hazeBottom - hazeTop).coerceAtLeast(1f))
        )
    }

    private fun drawGroundTerrain(
        scope: DrawScope,
        camera: Camera3D,
        horizonY: Float,
        width: Float,
        height: Float,
        empire: EmpireType
    ) {
        val startY = (camera.y / 15f).toInt() * 15f - 20f
        val endY = startY + 135f
        val safeNearY = camera.y - 22f
        val safeFarY = camera.y + 240f

        val baseTerrainColor: Color
        val soilPatchOuter: Color
        val soilPatchInner: Color
        val bankColor: Color
        val riverMainColor: Color
        val riverHighlightColor: Color
        val roadBaseColor: Color
        val roadCenterColor: Color

        when (empire) {
            EmpireType.DRAGON -> {
                baseTerrainColor = Color(0xFF2B201C)      // Deep volcanic basalt & ash
                soilPatchOuter = Color(0xFF47291E)        // Baked cinder crust
                soilPatchInner = Color(0xFF6E2712)        // Smoldering ember soil
                bankColor = Color(0xFF1B1311)             // Obsidian lava banks
                riverMainColor = Color(0xFFD84315)        // Molten magma flow
                riverHighlightColor = Color(0xFFFFB74D)   // Superheated lava core
                roadBaseColor = Color(0xFF241C19)         // Basalt warpath
                roadCenterColor = Color(0xFF8D5B4C)
            }
            EmpireType.ALIEN -> {
                baseTerrainColor = Color(0xFF1A313A)      // Dark xenomorphic alloy terrain
                soilPatchOuter = Color(0xFF282046)        // Crystal moss crust
                soilPatchInner = Color(0xFF392B63)        // Bio-luminous spore bed
                bankColor = Color(0xFF122129)             // Dark alloy shoreline
                riverMainColor = Color(0xFF007C91)        // Ionized plasma channel
                riverHighlightColor = Color(0xFF4DD0E1)   // Bio-luminescent pulse
                roadBaseColor = Color(0xFF263238)         // Metallic alien causeway
                roadCenterColor = Color(0xFF00E5FF)
            }
            EmpireType.ORC -> {
                baseTerrainColor = Color(0xFF4F462F)      // Scorched sulfur mudland
                soilPatchOuter = Color(0xFF3E3221)        // Diesel-soaked scrap dirt
                soilPatchInner = Color(0xFF2D2317)        // Churning mud trench
                bankColor = Color(0xFF443626)             // Muddy refinery trench
                riverMainColor = Color(0xFF3C4A22)        // Toxic acid/sludge canal
                riverHighlightColor = Color(0xFF8BC34A)   // Chemical sheen
                roadBaseColor = Color(0xFF3B332A)         // Tracked convoy road
                roadCenterColor = Color(0xFF8C735B)
            }
            EmpireType.HUMAN -> {
                baseTerrainColor = Color(0xFF3E5B3A)      // Tactical coastal olive woodland
                soilPatchOuter = Color(0xFF5D4B37)        // Natural earth & mud patches
                soilPatchInner = Color(0xFF4C3C2B)        // Rich forest loam
                bankColor = Color(0xFF8A7A62)             // Weathered coastal gravel bank
                riverMainColor = Color(0xFF1C4E6B)        // Deep naval ocean channel
                riverHighlightColor = Color(0xFF4FA2C4)   // Sunlit sea crest
                roadBaseColor = Color(0xFF323940)         // Military asphalt / tarmac
                roadCenterColor = Color(0xFFD9AE48)       // Tactical runway / convoy stripe
            }
        }

        // 1. Open-world ground plane anchored dynamically to the projected horizon
        val groundFillTop = (horizonY - 2f).coerceAtLeast(0f)
        scope.drawRect(
            color = baseTerrainColor,
            topLeft = Offset(0f, groundFillTop),
            size = Size(width, (height - groundFillTop).coerceAtLeast(1f))
        )
        val horizL = camera.project(Vector3D(camera.x - 420f, safeFarY, 0f), width, height)
        val horizR = camera.project(Vector3D(camera.x + 420f, safeFarY, 0f), width, height)
        val nearL = camera.project(Vector3D(camera.x - 420f, safeNearY, 0f), width, height)
        val nearR = camera.project(Vector3D(camera.x + 420f, safeNearY, 0f), width, height)

        if (horizL != null && horizR != null && nearL != null && nearR != null) {
            val terrainPath = tmpPath.apply {
                reset()
                moveTo(horizL.screenPos.x, horizL.screenPos.y)
                lineTo(horizR.screenPos.x, horizR.screenPos.y)
                lineTo(nearR.screenPos.x, nearR.screenPos.y)
                lineTo(nearL.screenPos.x, nearL.screenPos.y)
                close()
            }
            scope.drawPath(terrainPath, baseTerrainColor, style = Fill)
        }

        // 1b. Deterministic Ground Patches anchored in world space
        val cellSize = 18
        val startCellX = ((camera.x - 110f) / cellSize).toInt()
        val endCellX = ((camera.x + 110f) / cellSize).toInt()
        val startCellY = (startY / cellSize).toInt()
        val endCellY = (endY / cellSize).toInt()

        for (gx in startCellX..endCellX) {
            for (gy in startCellY..endCellY) {
                val hash = (gx * 73856093) xor (gy * 19349663)
                if ((hash and 3) != 0) {
                    val patchX = gx * cellSize + ((hash ushr 3) % 9 - 4).toFloat()
                    val patchY = gy * cellSize + ((hash ushr 7) % 9 - 4).toFloat()
                    val patchW = 6.5f + ((hash ushr 11) and 3) * 2.2f
                    val patchH = 4.5f + ((hash ushr 13) and 3) * 1.8f

                    val pL1 = camera.project(Vector3D(patchX - patchW, patchY - patchH, 0.002f), width, height)
                    val pR1 = camera.project(Vector3D(patchX + patchW, patchY - patchH, 0.002f), width, height)
                    val pL2 = camera.project(Vector3D(patchX - patchW * 0.85f, patchY + patchH, 0.002f), width, height)
                    val pR2 = camera.project(Vector3D(patchX + patchW * 0.85f, patchY + patchH, 0.002f), width, height)

                    if (pL1 != null && pR1 != null && pL2 != null && pR2 != null) {
                        val earthQuad = tmpPath.apply {
                            reset()
                            moveTo(pL1.screenPos.x, pL1.screenPos.y)
                            lineTo(pR1.screenPos.x, pR1.screenPos.y)
                            lineTo(pR2.screenPos.x, pR2.screenPos.y)
                            lineTo(pL2.screenPos.x, pL2.screenPos.y)
                            close()
                        }
                        scope.drawPath(earthQuad, soilPatchOuter.copy(alpha = 0.72f), style = Fill)

                        if ((hash and 7) < 4) {
                            val center = camera.project(Vector3D(patchX, patchY, 0.004f), width, height)
                            if (center != null) {
                                val rPx = patchW * (center.scale / 18f) * 1.1f
                                scope.drawOval(
                                    soilPatchInner.copy(alpha = 0.65f),
                                    Offset(center.screenPos.x - rPx, center.screenPos.y - rPx * 0.45f),
                                    Size(rPx * 2f, rPx * 0.9f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Themed Empire Waterway (Magma River / Ion Channel / Sludge Canal / Naval Channel)
        val riverStep = 10f
        var ry = startY
        while (ry <= endY - riverStep) {
            val nextY = ry + riverStep
            val rx1 = sin(ry * 0.045f) * 32f - 6f
            val rx2 = sin(nextY * 0.045f) * 32f - 6f

            val bankHalfW = 18f
            val waterHalfW = 13f

            val bL1 = camera.project(Vector3D(rx1 - bankHalfW, ry, 0.005f), width, height)
            val bR1 = camera.project(Vector3D(rx1 + bankHalfW, ry, 0.005f), width, height)
            val bL2 = camera.project(Vector3D(rx2 - bankHalfW, nextY, 0.005f), width, height)
            val bR2 = camera.project(Vector3D(rx2 + bankHalfW, nextY, 0.005f), width, height)

            if (bL1 != null && bR1 != null && bL2 != null && bR2 != null) {
                val bankQuad = tmpPath.apply {
                    reset()
                    moveTo(bL1.screenPos.x, bL1.screenPos.y)
                    lineTo(bR1.screenPos.x, bR1.screenPos.y)
                    lineTo(bR2.screenPos.x, bR2.screenPos.y)
                    lineTo(bL2.screenPos.x, bL2.screenPos.y)
                    close()
                }
                scope.drawPath(bankQuad, bankColor, style = Fill)
            }

            val wL1 = camera.project(Vector3D(rx1 - waterHalfW, ry, 0.015f), width, height)
            val wR1 = camera.project(Vector3D(rx1 + waterHalfW, ry, 0.015f), width, height)
            val wL2 = camera.project(Vector3D(rx2 - waterHalfW, nextY, 0.015f), width, height)
            val wR2 = camera.project(Vector3D(rx2 + waterHalfW, nextY, 0.015f), width, height)

            if (wL1 != null && wR1 != null && wL2 != null && wR2 != null) {
                val waterQuad = tmpPath.apply {
                    reset()
                    moveTo(wL1.screenPos.x, wL1.screenPos.y)
                    lineTo(wR1.screenPos.x, wR1.screenPos.y)
                    lineTo(wR2.screenPos.x, wR2.screenPos.y)
                    lineTo(wL2.screenPos.x, wL2.screenPos.y)
                    close()
                }
                scope.drawPath(waterQuad, riverMainColor, style = Fill)

                val m1 = (wL1.screenPos + wR1.screenPos) / 2f
                val m2 = (wL2.screenPos + wR2.screenPos) / 2f
                scope.drawLine(riverHighlightColor.copy(alpha = 0.7f), m1, m2, (wL1.screenPos - wR1.screenPos).getDistance() * 0.35f, StrokeCap.Round)
            }

            ry += riverStep
        }

        // 3. Themed Supply Road / Convoy Highway / Airbase Tarmac
        val roadStep = 12f
        var dy = startY
        while (dy <= endY - roadStep) {
            val nextDY = dy + roadStep
            val dx1 = cos(dy * 0.04f) * 36f + 16f
            val dx2 = cos(nextDY * 0.04f) * 36f + 16f
            val roadHalfW = 6.5f

            val rL1 = camera.project(Vector3D(dx1 - roadHalfW, dy, 0.02f), width, height)
            val rR1 = camera.project(Vector3D(dx1 + roadHalfW, dy, 0.02f), width, height)
            val rL2 = camera.project(Vector3D(dx2 - roadHalfW, nextDY, 0.02f), width, height)
            val rR2 = camera.project(Vector3D(dx2 + roadHalfW, nextDY, 0.02f), width, height)

            if (rL1 != null && rR1 != null && rL2 != null && rR2 != null) {
                val roadQuad = tmpPath.apply {
                    reset()
                    moveTo(rL1.screenPos.x, rL1.screenPos.y)
                    lineTo(rR1.screenPos.x, rR1.screenPos.y)
                    lineTo(rR2.screenPos.x, rR2.screenPos.y)
                    lineTo(rL2.screenPos.x, rL2.screenPos.y)
                    close()
                }
                scope.drawPath(roadQuad, roadBaseColor, style = Fill)

                // Dashed military convoy / runway centerline every alternate road segment
                if (((dy / roadStep).toInt() and 1) == 0) {
                    val m1 = (rL1.screenPos + rR1.screenPos) / 2f
                    val m2 = (rL2.screenPos + rR2.screenPos) / 2f
                    scope.drawLine(roadCenterColor, m1, m2, 2.8f, StrokeCap.Round)
                }
            }

            dy += roadStep
        }

        // 4. Subtle terrain elevation / tactical sector lines for 3D motion & depth
        val contourStep = 24f
        var cyLine = (camera.y / contourStep).toInt() * contourStep - contourStep
        while (cyLine <= camera.y + 130f) {
            val lProj = camera.project(Vector3D(camera.x - 160f, cyLine, 0.008f), width, height)
            val rProj = camera.project(Vector3D(camera.x + 160f, cyLine, 0.008f), width, height)
            if (lProj != null && rProj != null) {
                scope.drawLine(
                    color = Color.Black.copy(alpha = 0.14f),
                    start = lProj.screenPos,
                    end = rProj.screenPos,
                    strokeWidth = 1.4f
                )
            }
            cyLine += contourStep
        }

        // 5. Perspective horizon haze / near-ground vignette overlay
        cachedGroundDepthBrush?.let { depthBrush ->
            val depthTop = (horizonY - height * 0.06f).coerceAtLeast(0f)
            scope.drawRect(
                brush = depthBrush,
                topLeft = Offset(0f, depthTop),
                size = Size(width, (height - depthTop).coerceAtLeast(1f))
            )
        }
    }

    private fun drawStructures(scope: DrawScope, camera: Camera3D, structures: List<TerrainStructure>, width: Float, height: Float) {
        val minVisY = camera.y - 24f
        val maxVisY = camera.y + 180f
        val minVisX = camera.x - 240f
        val maxVisX = camera.x + 240f

        for (struct in structures) {
            if (struct.type == StructureType.BARRACKS_FORT || struct.type == StructureType.HEADQUARTERS_FORT) continue
            if (struct.pos.y < minVisY || struct.pos.y > maxVisY || struct.pos.x < minVisX || struct.pos.x > maxVisX) continue

            val baseProj = camera.project(struct.pos, width, height) ?: continue
            val topPos = Vector3D(struct.pos.x, struct.pos.y, struct.pos.z + struct.height)
            val topProj = camera.project(topPos, width, height) ?: continue

            val s = baseProj.scale
            val cx = baseProj.screenPos.x
            val cy = baseProj.screenPos.y
            val tcx = topProj.screenPos.x
            val tcy = topProj.screenPos.y

            val sw = struct.width * (s / 20f) * 1.5f
            val sh = (cy - tcy).coerceAtLeast(10f)

            when (struct.type) {
                StructureType.VOLCANIC_SPIRE -> {
                    val peakPath = tmpPath.apply {
                        reset()
                        moveTo(cx - sw, cy)
                        lineTo(tcx, tcy)
                        lineTo(cx + sw, cy)
                        close()
                    }
                    scope.drawPath(peakPath, Color(0xFF7D6B5D), style = Fill)
                    scope.drawPath(peakPath, Color(0xFFA69282), style = Stroke(2f))
                    scope.drawCircle(Color(0xFFD98A4E), sw * 0.25f, Offset(tcx, tcy))
                }
                StructureType.DRAGON_CASTLE -> {
                    scope.drawRect(Color(0xFF8C7A6B), Offset(cx - sw, tcy), Size(sw * 2, sh))
                    scope.drawRect(Color(0xFFB59F8E), Offset(cx - sw, tcy), Size(sw * 2, sh), style = Stroke(2f))
                    scope.drawRect(Color(0xFF6B5B4E), Offset(cx - sw - 4f, tcy - 8f), Size(10f, 16f))
                    scope.drawRect(Color(0xFF6B5B4E), Offset(cx + sw - 6f, tcy - 8f), Size(10f, 16f))
                    scope.drawCircle(Color(0xFFE59A4C), 4f, Offset(cx - sw, tcy - 8f))
                    scope.drawCircle(Color(0xFFE59A4C), 4f, Offset(cx + sw, tcy - 8f))
                }
                StructureType.ALIEN_PYLON -> {
                    val pylonPath = tmpPath.apply {
                        reset()
                        moveTo(tcx, tcy - 8f)
                        lineTo(cx + sw * 0.6f, cy - sh * 0.5f)
                        lineTo(cx, cy)
                        lineTo(cx - sw * 0.6f, cy - sh * 0.5f)
                        close()
                    }
                    scope.drawPath(pylonPath, Color(0xFF283A44), style = Fill)
                    scope.drawPath(pylonPath, Color(0xFF00E5FF), style = Stroke(2.2f))
                    scope.drawCircle(Color(0xFF4DD0E1), sw * 0.34f, Offset(cx, cy - sh * 0.5f))
                    scope.drawCircle(Color(0xFFE0F7FA), sw * 0.14f, Offset(cx, cy - sh * 0.5f))
                }
                StructureType.MOTHERSHIP_PORTAL -> {
                    scope.drawOval(Color(0xFF1F2E38), Offset(cx - sw, cy - sw * 0.5f), Size(sw * 2, sw))
                    scope.drawOval(Color(0xFF00B8D4), Offset(cx - sw, cy - sw * 0.5f), Size(sw * 2, sw), style = Stroke(3f))
                    scope.drawOval(Color(0xFF4DD0E1).copy(alpha = 0.55f), Offset(cx - sw * 0.6f, cy - sw * 0.3f), Size(sw * 1.2f, sw * 0.6f))
                }
                StructureType.ORC_WATCHTOWER -> {
                    scope.drawLine(Color(0xFF6B482B), Offset(cx - sw * 0.7f, cy), Offset(cx - sw * 0.4f, tcy), 3.5f)
                    scope.drawLine(Color(0xFF6B482B), Offset(cx + sw * 0.7f, cy), Offset(cx + sw * 0.4f, tcy), 3.5f)
                    scope.drawLine(Color(0xFF6B482B), Offset(cx - sw * 0.6f, cy - sh * 0.5f), Offset(cx + sw * 0.6f, cy - sh * 0.5f), 2.5f)
                    scope.drawRect(Color(0xFF7D5836), Offset(cx - sw * 0.6f, tcy - 4f), Size(sw * 1.2f, 10f))
                    scope.drawRect(Color(0xFF4A331E), Offset(cx - sw * 0.6f, tcy - 4f), Size(sw * 1.2f, 10f), style = Stroke(1.5f))
                }
                StructureType.SIEGE_FORTRESS -> {
                    scope.drawRect(Color(0xFF6B6054), Offset(cx - sw, tcy), Size(sw * 2, sh))
                    scope.drawRect(Color(0xFF4E6B38), Offset(cx - sw, tcy), Size(sw * 2, sh), style = Stroke(2f))
                    scope.drawRect(Color(0xFF5D4028), Offset(cx - sw * 0.5f, cy - sh * 0.5f), Size(sw, 4f))
                }
                StructureType.MILITARY_HANGAR -> {
                    val hangarPath = tmpPath.apply {
                        reset()
                        moveTo(cx - sw, cy)
                        lineTo(cx - sw, cy - sh * 0.6f)
                        quadraticTo(cx, tcy, cx + sw, cy - sh * 0.6f)
                        lineTo(cx + sw, cy)
                        close()
                    }
                    scope.drawPath(hangarPath, Color(0xFF687069), style = Fill)
                    scope.drawPath(hangarPath, Color(0xFF425243), style = Stroke(2f))
                    scope.drawRect(Color(0xFF2E3532), Offset(cx - sw * 0.5f, cy - sh * 0.5f), Size(sw, sh * 0.5f))
                }
                StructureType.RADAR_DOME -> {
                    scope.drawRect(Color(0xFF606861), Offset(cx - sw * 0.5f, cy - sh * 0.4f), Size(sw, sh * 0.4f))
                    scope.drawOval(Color(0xFFA7B0AB), Offset(cx - sw * 0.7f, tcy), Size(sw * 1.4f, sw * 1.1f))
                    scope.drawOval(Color(0xFF3E635B), Offset(cx - sw * 0.7f, tcy), Size(sw * 1.4f, sw * 1.1f), style = Stroke(2f))
                }
                StructureType.MISSILE_SILO -> {
                    scope.drawOval(Color(0xFF5D635B), Offset(cx - sw, cy - sw * 0.5f), Size(sw * 2, sw))
                    scope.drawOval(Color(0xFF3B403A), Offset(cx - sw, cy - sw * 0.5f), Size(sw * 2, sw), style = Stroke(2.5f))
                    scope.drawCircle(Color(0xFF282E2C), sw * 0.35f, Offset(cx, cy))
                    scope.drawCircle(Color(0xFFD96A2B), sw * 0.16f, Offset(cx, cy))
                }
                StructureType.AIRCRAFT_CARRIER_DECK -> {
                    scope.drawRect(Color(0xFF8C877B), Offset(cx - sw, cy - sh), Size(sw * 2, sh))
                }
                StructureType.BARRACKS_FORT -> {
                    // Handled cleanly with dedicated empire-themed drawBarracks3D architectural pass
                }
                StructureType.HEADQUARTERS_FORT -> {
                    // Handled with dedicated HQ render pass in drawHeadquarters3D
                }
                StructureType.DENSE_FOREST -> {
                    // Natural brown tree trunks & lush multi-shade green woodland canopy
                    scope.drawLine(Color(0xFF6E4E34), Offset(cx, cy), Offset(cx, cy - sh * 0.35f), (3.5f * (s / 20f)).coerceAtLeast(2f), StrokeCap.Round)
                    scope.drawCircle(Color(0xFF3E6F36), sw * 0.85f, Offset(cx - sw * 0.3f, cy - sh * 0.3f))
                    scope.drawCircle(Color(0xFF4F8745), sw * 0.95f, Offset(cx + sw * 0.2f, cy - sh * 0.4f))
                    scope.drawCircle(Color(0xFF68A158), sw * 0.7f, Offset(cx, tcy))
                }
                StructureType.MOUNTAIN_HILL -> {
                    // Natural earthy brown-stone mountain ridge with crisp white snow peak
                    val mtnPath = tmpPath.apply {
                        reset()
                        moveTo(cx - sw * 1.4f, cy)
                        lineTo(tcx, tcy)
                        lineTo(cx + sw * 1.4f, cy)
                        close()
                    }
                    scope.drawPath(mtnPath, Color(0xFF8A7561), style = Fill)
                    scope.drawPath(mtnPath, Color(0xFFA38E79), style = Stroke(2f))
                    scope.drawCircle(Color(0xFFFAFAFA), sw * 0.26f, Offset(tcx, tcy))
                }
            }
        }
    }

    private fun drawBarracks3D(scope: DrawScope, camera: Camera3D, barracksList: List<EnemyBarracks>, width: Float, height: Float) {
        val minVisY = camera.y - 24f
        val maxVisY = camera.y + 180f
        val minVisX = camera.x - 165f
        val maxVisX = camera.x + 165f

        for (barracks in barracksList) {
            if (barracks.pos.y < minVisY || barracks.pos.y > maxVisY || barracks.pos.x < minVisX || barracks.pos.x > maxVisX) continue
            val baseProj = camera.project(barracks.pos, width, height) ?: continue
            val topPos = Vector3D(barracks.pos.x, barracks.pos.y, barracks.pos.z + 5.5f)
            val topProj = camera.project(topPos, width, height) ?: continue

            val s = baseProj.scale
            val cx = baseProj.screenPos.x
            val cy = baseProj.screenPos.y
            val tcy = topProj.screenPos.y

            val bw = 36f * (s / 20f)
            val bh = (cy - tcy).coerceAtLeast(14f)

            if (barracks.isDestroyed) {
                // Natural dusty brown earth crater & weathered stone rubble
                scope.drawOval(Color(0xFF7A644E), Offset(cx - bw * 0.85f, cy - bh * 0.42f), Size(bw * 1.7f, bh * 0.84f))
                scope.drawOval(Color(0xFF5D4D3E), Offset(cx - bw * 0.55f, cy - bh * 0.25f), Size(bw * 1.1f, bh * 0.5f))
                scope.drawCircle(Color(0xFFD97736), 4.5f * (s / 20f), Offset(cx, cy))
                scope.drawCircle(Color(0xFF6B5340), 7f * (s / 20f), Offset(cx + 4f, cy - 2f))
            } else {
                when (barracks.empire) {
                    EmpireType.HUMAN -> {
                        // Reinforced Olive-Steel & Weathered Concrete Military Barracks
                        scope.drawRect(Color(0xFF7A7E75), Offset(cx - bw, tcy), Size(bw * 2, bh))
                        scope.drawRect(Color(0xFF454C44), Offset(cx - bw, tcy), Size(bw * 2, bh), style = Stroke(2.2f))
                        scope.drawRect(Color(0xFF3E563B), Offset(cx - bw * 0.94f, tcy + 2f), Size(bw * 1.88f, (bh * 0.18f).coerceAtLeast(5f)))
                        scope.drawRect(Color(0xFF28302E), Offset(cx - bw * 0.35f, cy - bh * 0.48f), Size(bw * 0.7f, bh * 0.48f))
                        scope.drawCircle(Color(0xFFA7B0AB), 5f * (s / 20f), Offset(cx, tcy - 8f))
                        scope.drawCircle(Color(0xFF38BDF8), 2.3f * (s / 20f), Offset(cx, tcy - 8f))
                    }
                    EmpireType.ORC -> {
                        // Weathered Iron-Stone & Charred Timber Stronghold
                        scope.drawRect(Color(0xFF6B6053), Offset(cx - bw, tcy), Size(bw * 2, bh))
                        scope.drawRect(Color(0xFF453B31), Offset(cx - bw, tcy), Size(bw * 2, bh), style = Stroke(2.2f))
                        val spikePath = tmpPath.apply {
                            reset()
                            moveTo(cx - bw, tcy)
                            lineTo(cx - bw * 0.6f, tcy - 10f)
                            lineTo(cx - bw * 0.2f, tcy)
                            lineTo(cx, tcy - 14f)
                            lineTo(cx + bw * 0.2f, tcy)
                            lineTo(cx + bw * 0.6f, tcy - 10f)
                            lineTo(cx + bw, tcy)
                            close()
                        }
                        scope.drawPath(spikePath, Color(0xFF6E4B2D))
                        scope.drawPath(spikePath, Color(0xFF3D2716), style = Stroke(1.5f))
                        scope.drawRoundRect(Color(0xFF4A2E1A), Offset(cx - bw * 0.4f, cy - bh * 0.6f), Size(bw * 0.8f, bh * 0.6f), androidx.compose.ui.geometry.CornerRadius(4f))
                        scope.drawCircle(Color(0xFFD5CCBD), 5f * (s / 20f), Offset(cx, tcy + bh * 0.25f))
                        scope.drawCircle(Color(0xFF2E231C), 1.5f * (s / 20f), Offset(cx - 2f, tcy + bh * 0.25f))
                        scope.drawCircle(Color(0xFF2E231C), 1.5f * (s / 20f), Offset(cx + 2f, tcy + bh * 0.25f))
                        scope.drawRect(Color(0xFF5A4E43), Offset(cx - bw * 0.85f, cy - bh * 0.5f), Size(bw * 0.35f, bh * 0.5f))
                        scope.drawRect(Color(0xFF5A4E43), Offset(cx + bw * 0.50f, cy - bh * 0.5f), Size(bw * 0.35f, bh * 0.5f))
                        scope.drawCircle(Color(0xFF8BC34A), 2.5f * (s / 20f), Offset(cx - bw * 0.68f, cy - bh * 0.55f))
                        scope.drawCircle(Color(0xFF8BC34A), 2.5f * (s / 20f), Offset(cx + bw * 0.68f, cy - bh * 0.55f))
                    }
                    EmpireType.DRAGON -> {
                        // Obsidian Basalt & Glowing Magma Bastion
                        val spirePath = tmpPath.apply {
                            reset()
                            moveTo(cx - bw * 1.1f, cy)
                            lineTo(cx - bw * 0.5f, tcy - 8f)
                            lineTo(cx, tcy + 4f)
                            lineTo(cx + bw * 0.5f, tcy - 8f)
                            lineTo(cx + bw * 1.1f, cy)
                            close()
                        }
                        scope.drawPath(spirePath, Color(0xFF473731))
                        scope.drawPath(spirePath, Color(0xFF291E1A), style = Stroke(2.2f))
                        scope.drawLine(Color(0xFFD84315), Offset(cx - bw * 0.5f, tcy - 8f), Offset(cx - bw * 0.2f, cy), strokeWidth = 3f)
                        scope.drawLine(Color(0xFFFF9800), Offset(cx + bw * 0.5f, tcy - 8f), Offset(cx + bw * 0.2f, cy), strokeWidth = 3f)
                        scope.drawCircle(Color(0xFFFF5722), 4.5f * (s / 20f), Offset(cx, tcy + bh * 0.35f))
                        scope.drawCircle(Color(0xFFFFD54F), 2f * (s / 20f), Offset(cx, tcy + bh * 0.35f))
                    }
                    EmpireType.ALIEN -> {
                        // Dark Obsidian Alloy & Ionized Cyan Bio-Dome
                        scope.drawOval(Color(0xFF24343D), Offset(cx - bw * 1.05f, tcy - bh * 0.2f), Size(bw * 2.1f, bh * 1.2f))
                        scope.drawOval(Color(0xFF00ACC1), Offset(cx - bw * 1.05f, tcy - bh * 0.2f), Size(bw * 2.1f, bh * 1.2f), style = Stroke(2.5f))
                        scope.drawCircle(Color(0xFF00E5FF), 6f * (s / 20f), Offset(cx, cy - bh * 0.4f))
                        scope.drawCircle(Color(0xFFE0F7FA), 2.5f * (s / 20f), Offset(cx, cy - bh * 0.4f))
                        scope.drawLine(Color(0xFF00ACC1), Offset(cx, tcy - bh * 0.2f), Offset(cx, tcy - 12f), strokeWidth = 2f)
                        scope.drawCircle(Color(0xFF00E5FF), 3f * (s / 20f), Offset(cx, tcy - 12f))
                    }
                }

                // Crisp Natural-Framed Barracks Objective Health Bar
                val barW = bw * 1.8f
                val barH = 5.5f
                val barY = tcy - 18f
                val hpPct = (barracks.health / barracks.maxHealth).coerceIn(0f, 1f)
                scope.drawRect(Color(0xDD1F2922), Offset(cx - barW / 2 - 1f, barY - 1f), Size(barW + 2f, barH + 2f))
                scope.drawRect(Color(0xAAFFFFFF), Offset(cx - barW / 2 - 1f, barY - 1f), Size(barW + 2f, barH + 2f), style = Stroke(1f))
                val hpColor = when (barracks.empire) {
                    EmpireType.HUMAN -> Color(0xFF4F8745)
                    EmpireType.ORC -> Color(0xFF9B6B43)
                    EmpireType.DRAGON -> Color(0xFFD46A3C)
                    EmpireType.ALIEN -> Color(0xFF4D948E)
                }
                scope.drawRect(hpColor, Offset(cx - barW / 2, barY), Size(barW * hpPct, barH))
            }
        }
    }

    private fun drawHeadquarters3D(scope: DrawScope, camera: Camera3D, hq: EnemyHeadquarters, width: Float, height: Float) {
        val baseProj = camera.project(hq.pos, width, height) ?: return
        val topPos = Vector3D(hq.pos.x, hq.pos.y, hq.pos.z + 14f)
        val topProj = camera.project(topPos, width, height) ?: return

        val s = baseProj.scale
        val cx = baseProj.screenPos.x
        val cy = baseProj.screenPos.y
        val tcy = topProj.screenPos.y

        val bw = 68f * (s / 20f)
        val bh = (cy - tcy).coerceAtLeast(28f)

        if (hq.isDestroyed) {
            scope.drawOval(Color(0xFF6E5A47), Offset(cx - bw * 1.2f, cy - bh * 0.5f), Size(bw * 2.4f, bh))
            scope.drawCircle(Color(0xFFD97736), 16f * (s / 20f), Offset(cx, cy))
            scope.drawCircle(Color(0xFFE8A458), 10f * (s / 20f), Offset(cx - 10f, cy + 4f))
        } else {
            when (hq.empire) {
                EmpireType.ORC -> {
                    val orcHqPath = tmpPath.apply {
                        reset()
                        moveTo(cx - bw * 1.4f, cy)
                        lineTo(cx - bw * 0.9f, tcy - 16f)
                        lineTo(cx - bw * 0.4f, tcy - 6f)
                        lineTo(cx, tcy - 24f)
                        lineTo(cx + bw * 0.4f, tcy - 6f)
                        lineTo(cx + bw * 0.9f, tcy - 16f)
                        lineTo(cx + bw * 1.4f, cy)
                        close()
                    }
                    scope.drawPath(orcHqPath, Color(0xFF9A8B7A))
                    scope.drawPath(orcHqPath, Color(0xFF7D5636), style = Stroke(4f))
                    scope.drawCircle(Color(0xFFF4F1EA), 14f * (s / 20f), Offset(cx, tcy + bh * 0.2f))
                    scope.drawCircle(Color(0xFF4A3B31), 4f * (s / 20f), Offset(cx - 5f, tcy + bh * 0.2f))
                    scope.drawCircle(Color(0xFF4A3B31), 4f * (s / 20f), Offset(cx + 5f, tcy + bh * 0.2f))
                }
                EmpireType.DRAGON -> {
                    val dragonHqPath = tmpPath.apply {
                        reset()
                        moveTo(cx - bw * 1.3f, cy)
                        lineTo(cx - bw * 0.7f, tcy - 20f)
                        lineTo(cx, tcy - 30f)
                        lineTo(cx + bw * 0.7f, tcy - 20f)
                        lineTo(cx + bw * 1.3f, cy)
                        close()
                    }
                    scope.drawPath(dragonHqPath, Color(0xFF42322B))
                    scope.drawPath(dragonHqPath, Color(0xFFD84315), style = Stroke(4f))
                    scope.drawLine(Color(0xFFFF9800), Offset(cx - bw * 0.4f, tcy - 10f), Offset(cx - bw * 0.1f, cy), strokeWidth = 5f)
                    scope.drawLine(Color(0xFFFF5722), Offset(cx + bw * 0.4f, tcy - 10f), Offset(cx + bw * 0.1f, cy), strokeWidth = 5f)
                }
                EmpireType.ALIEN -> {
                    scope.drawOval(Color(0xFF1F2D36), Offset(cx - bw * 1.4f, tcy - 12f), Size(bw * 2.8f, bh * 1.3f))
                    scope.drawOval(Color(0xFF00B8D4), Offset(cx - bw * 1.4f, tcy - 12f), Size(bw * 2.8f, bh * 1.3f), style = Stroke(4f))
                    scope.drawCircle(Color(0xFF00E5FF), 18f * (s / 20f), Offset(cx, cy - bh * 0.3f))
                    scope.drawCircle(Color(0xFFE0F7FA), 7f * (s / 20f), Offset(cx, cy - bh * 0.3f))
                }
                EmpireType.HUMAN -> {
                    val hqPath = tmpPath.apply {
                        reset()
                        moveTo(cx - bw * 1.3f, cy)
                        lineTo(cx - bw * 0.8f, tcy)
                        lineTo(cx + bw * 0.8f, tcy)
                        lineTo(cx + bw * 1.3f, cy)
                        close()
                    }
                    scope.drawPath(hqPath, Color(0xFF6B7268), style = Fill)
                    scope.drawPath(hqPath, Color(0xFF3E563B), style = Stroke(4f))
                    scope.drawOval(Color(0xFF2C5E73), Offset(cx - bw * 0.45f, tcy - 16f), Size(bw * 0.9f, 20f))
                    scope.drawOval(Color(0xFF90CAF9), Offset(cx - bw * 0.2f, tcy - 12f), Size(bw * 0.4f, 8f))
                }
            }

            // Headquarters Forcefield, Danger Zone Perimeter Ring & 1,000-Hit Hull Bar
            val pulse = (sin((hq.pos.y + hq.hitsRemaining) * 0.2f) * 0.5f + 0.5f)
            if (!hq.isUnlocked) {
                val shieldColor = hq.empire.primaryColor.copy(alpha = 0.55f)
                scope.drawRoundRect(
                    color = shieldColor,
                    topLeft = Offset(cx - bw * 1.45f, cy - bh * 0.45f),
                    size = Size(bw * 2.9f, bh * 0.9f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
                    style = Stroke(3.5f)
                )
            } else if (!hq.isBossDefeated) {
                // HQ unlocked -> Warning Danger Zone alert ring while Boss is inside HQ awaiting player entry
                val zoneColor = if (hq.isDangerZoneTriggered) Color(0xFFFF1744) else Color(0xFFFF9100)
                scope.drawOval(
                    color = zoneColor.copy(alpha = 0.45f + pulse * 0.35f),
                    topLeft = Offset(cx - bw * 1.9f, cy - bh * 0.7f),
                    size = Size(bw * 3.8f, bh * 1.4f),
                    style = Stroke(4f)
                )
            } else {
                // Boss defeated -> HQ Vulnerable (1,000 Hits Hull Bar + Breach Highlight)
                scope.drawOval(
                    color = if (hq.hitFlashTimer > 0f) Color.White else Color(0xFF00E676).copy(alpha = 0.6f),
                    topLeft = Offset(cx - bw * 1.6f, cy - bh * 0.55f),
                    size = Size(bw * 3.2f, bh * 1.1f),
                    style = Stroke(3.5f)
                )
            }

            // HQ Hull Integrity Bar (1,000 hits total)
            val hqBarW = bw * 2.4f
            val hqBarH = 7f
            val hqBarY = tcy - 34f
            val hqPct = (hq.hitsRemaining.toFloat() / hq.maxHits.toFloat()).coerceIn(0f, 1f)
            scope.drawRect(Color(0xEE0D1520), Offset(cx - hqBarW / 2 - 1f, hqBarY - 1f), Size(hqBarW + 2f, hqBarH + 2f))
            scope.drawRect(Color(0xAAFFFFFF), Offset(cx - hqBarW / 2 - 1f, hqBarY - 1f), Size(hqBarW + 2f, hqBarH + 2f), style = Stroke(1f))
            val hqBarColor = when {
                !hq.isUnlocked -> Color(0xFF78909C)
                !hq.isBossDefeated -> Color(0xFFFF9100)
                else -> Color(0xFFFF1744)
            }
            scope.drawRect(hqBarColor, Offset(cx - hqBarW / 2, hqBarY), Size(hqBarW * hqPct, hqBarH))
        }
    }

    private fun drawHazards(scope: DrawScope, camera: Camera3D, hazards: List<EnvironmentHazard>, width: Float, height: Float) {
        val minVisY = camera.y - 20f
        val maxVisY = camera.y + 165f
        val minVisX = camera.x - 135f
        val maxVisX = camera.x + 135f

        for (hazard in hazards) {
            if (hazard.pos.y < minVisY || hazard.pos.y > maxVisY || hazard.pos.x < minVisX || hazard.pos.x > maxVisX) continue
            val baseProj = camera.project(hazard.pos, width, height) ?: continue
            val s = baseProj.scale
            val cx = baseProj.screenPos.x
            val cy = baseProj.screenPos.y
            val rPx = hazard.radius * (s / 20f) * 1.6f

            when (hazard.state) {
                HazardState.WARNING -> {
                    // Pre-impact artillery warning: Scorched dirt crater & rising dust puff (Natural battlefield hazard)
                    val pulse = (sin(hazard.cycleTimer * 10.0f) * 0.5f + 0.5f)
                    scope.drawOval(
                        Color(0xFF261D15).copy(alpha = 0.5f + pulse * 0.3f),
                        Offset(cx - rPx, cy - rPx * 0.45f),
                        Size(rPx * 2, rPx * 0.9f),
                        style = Fill
                    )
                    // Natural earth crater rim
                    scope.drawOval(
                        Color(0xFF4A3828),
                        Offset(cx - rPx, cy - rPx * 0.45f),
                        Size(rPx * 2, rPx * 0.9f),
                        style = Stroke(1.5f)
                    )
                }
                HazardState.ACTIVE -> {
                    // Active explosion / burning wreckage: Real dark charcoal smoke plume & ground crater
                    scope.drawOval(Color(0xFF1E1712), Offset(cx - rPx, cy - rPx * 0.45f), Size(rPx * 2, rPx * 0.9f))
                    // Rising charcoal smoke particles
                    for (step in 1..4) {
                        val smokeY = cy - step * 16f * (s / 20f)
                        val smokeX = cx + sin(step.toFloat() * 1.2f + hazard.cycleTimer * 3f) * 10f * (s / 20f)
                        val smokeR = (rPx * 0.4f) + step * 5f * (s / 20f)
                        scope.drawCircle(
                            Color(0xFF2A2A2A).copy(alpha = (0.55f - step * 0.1f).coerceAtLeast(0.1f)),
                            smokeR,
                            Offset(smokeX, smokeY)
                        )
                    }
                }
                HazardState.DORMANT -> {
                    scope.drawOval(Color(0xFF2A221A).copy(alpha = 0.35f), Offset(cx - rPx * 0.6f, cy - rPx * 0.25f), Size(rPx * 1.2f, rPx * 0.5f))
                }
            }
        }
    }

    private fun drawShadows(scope: DrawScope, camera: Camera3D, engine: GameEngine, width: Float, height: Float) {
        val playerAlt = engine.player.pos.z.coerceAtLeast(1f)
        val playerShadowSpread = (1f + (playerAlt - 9.5f) * 0.03f).coerceIn(0.85f, 1.18f)
        val playerShadowAlpha = (0.46f - (playerAlt - 9.5f) * 0.025f).coerceIn(0.28f, 0.52f)
        val shadowPos = Vector3D(
            engine.player.pos.x + 0.16f * playerAlt,
            engine.player.pos.y - 0.11f * playerAlt,
            0.05f
        )
        val proj = camera.project(shadowPos, width, height)
        if (proj != null) {
            val mScale = playerModelScale(engine.player.heloClass)
            val (baseRx, baseRy) = when (engine.player.heloClass) {
                HelicopterClass.SCOUT -> 1.85f to 1.10f
                HelicopterClass.HEAVY_GUNSHIP -> 2.15f to 1.26f
                HelicopterClass.STEALTH -> 1.95f to 1.16f
                HelicopterClass.ULTIMATE_WAR -> 2.08f to 1.22f
            }
            val rx = proj.scale * baseRx * mScale * playerShadowSpread
            val ry = proj.scale * baseRy * mScale * playerShadowSpread
            // Soft outer penumbra + dense inner umbra
            scope.drawOval(
                Color.Black.copy(alpha = playerShadowAlpha * 0.45f),
                Offset(proj.screenPos.x - rx * 1.18f, proj.screenPos.y - ry * 1.18f),
                Size(rx * 2.36f, ry * 2.36f)
            )
            scope.drawOval(
                Color.Black.copy(alpha = playerShadowAlpha),
                Offset(proj.screenPos.x - rx, proj.screenPos.y - ry),
                Size(rx * 2f, ry * 2f)
            )
        }

        val minVisY = camera.y - 18f
        val maxVisY = camera.y + 165f
        for (enemy in engine.enemies) {
            if (enemy.pos.y !in minVisY..maxVisY) continue
            val isAir = !enemy.isGroundUnit
            val eScale = enemyVisualScale(enemy.category)
            val eAlt = if (isAir) enemy.pos.z.coerceAtLeast(1f) else 0f
            val sPos = Vector3D(
                enemy.pos.x + (if (isAir) 0.13f * eAlt else 0.38f),
                enemy.pos.y - (if (isAir) 0.10f * eAlt else 0.28f),
                0.03f
            )
            val eProj = camera.project(sPos, width, height) ?: continue
            val airSpread = if (isAir) (1f + (eAlt - 9.5f) * 0.025f).coerceIn(0.88f, 1.15f) else 1f
            val rx = eProj.scale * (if (isAir) 1.45f else 1.15f) * eScale * airSpread
            val ry = eProj.scale * (if (isAir) 0.85f else 0.7f) * eScale * airSpread
            val sAlpha = if (isAir) (0.38f - (eAlt - 9.5f) * 0.02f).coerceIn(0.24f, 0.44f) else 0.32f
            scope.drawOval(
                Color.Black.copy(alpha = sAlpha),
                Offset(eProj.screenPos.x - rx, eProj.screenPos.y - ry),
                Size(rx * 2f, ry * 2f)
            )
        }

        engine.activeBoss?.let { boss ->
            val bossAlt = boss.pos.z.coerceAtLeast(1f)
            val bSpread = (1f + (bossAlt - 10.5f) * 0.025f).coerceIn(0.9f, 1.15f)
            val bAlpha = (0.50f - (bossAlt - 10.5f) * 0.02f).coerceIn(0.32f, 0.54f)
            val bProj = camera.project(
                Vector3D(boss.pos.x + 0.19f * bossAlt, boss.pos.y - 0.14f * bossAlt, 0.05f),
                width,
                height
            )
            if (bProj != null) {
                val rx = bProj.scale * 4.5f * bSpread
                val ry = bProj.scale * 2.8f * bSpread
                scope.drawOval(
                    Color.Black.copy(alpha = bAlpha * 0.45f),
                    Offset(bProj.screenPos.x - rx * 1.15f, bProj.screenPos.y - ry * 1.15f),
                    Size(rx * 2.3f, ry * 2.3f)
                )
                scope.drawOval(
                    Color.Black.copy(alpha = bAlpha),
                    Offset(bProj.screenPos.x - rx, bProj.screenPos.y - ry),
                    Size(rx * 2f, ry * 2f)
                )
            }
        }
    }

    private fun drawPlayerHelicopter3D(scope: DrawScope, camera: Camera3D, player: PlayerHelo, width: Float, height: Float) {
        val proj = camera.project(player.pos, width, height) ?: return
        val s = proj.scale
        val mScale = playerModelScale(player.heloClass)
        val ps = (s / 20f) * mScale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        val roll = player.rollAngle
        val pitch = player.pitchAngle
        val cosR = cos(roll)
        val sinR = sin(roll)
        val cosP = cos(pitch)
        val sinP = sin(pitch)

        fun trans(lx: Float, ly: Float, lz: Float): Offset {
            // Apply forward/back pitch rotation first around X, then bank roll rotation around Y
            val sx = lx * mScale
            val sy = ly * mScale
            val sz = lz * mScale
            val py = sy * cosP - sz * sinP
            val pz = sy * sinP + sz * cosP
            val rx = sx * cosR - pz * sinR
            val rz = sx * sinR + pz * cosR
            val worldPt = Vector3D(player.pos.x + rx, player.pos.y + py, player.pos.z + rz)
            return camera.project(worldPt, width, height)?.screenPos ?: Offset(cx, cy)
        }

        // Paint fuselage from active HelicopterSkin + class shading
        val armorBody = player.skin.primaryColor
        val accentTrim = player.skin.accentColor
        val armorTopLit = lerp(armorBody, Color.White, 0.24f)
        val armorShadow = lerp(armorBody, Color.Black, 0.36f)
        val panelSeam = lerp(armorBody, Color.Black, 0.65f)

        val heloClass = player.heloClass
        val bodyScaleX = when (heloClass) {
            HelicopterClass.SCOUT -> 0.78f
            HelicopterClass.HEAVY_GUNSHIP -> 1.24f
            HelicopterClass.STEALTH -> 1.04f
            HelicopterClass.ULTIMATE_WAR -> 1.15f
        }
        val bodyScaleY = when (heloClass) {
            HelicopterClass.SCOUT -> 0.90f
            HelicopterClass.HEAVY_GUNSHIP -> 1.10f
            HelicopterClass.STEALTH -> 1.02f
            HelicopterClass.ULTIMATE_WAR -> 1.12f
        }
        val wingSpan = when (heloClass) {
            HelicopterClass.SCOUT -> 2.8f
            HelicopterClass.HEAVY_GUNSHIP -> 4.9f
            HelicopterClass.STEALTH -> 3.6f
            HelicopterClass.ULTIMATE_WAR -> 4.5f
        }

        // 1. Tail Boom & Empennage (Class-differentiated)
        val tailEnd = trans(0f, -5.8f * bodyScaleY, 0.5f)
        val tailFinTop = trans(0f, -6.3f * bodyScaleY, 2.5f)
        val tailFinBase = trans(0f, -5.0f * bodyScaleY, 0.4f)
        val tailBody = trans(0f, -1.7f * bodyScaleY, 0.3f)

        val tailBoomWidth = if (heloClass == HelicopterClass.HEAVY_GUNSHIP) 7f else 5f
        val tailBoomPath = tmpPath.apply {
            reset()
            moveTo(tailBody.x - tailBoomWidth * ps, tailBody.y)
            lineTo(tailEnd.x, tailEnd.y)
            lineTo(tailBody.x + tailBoomWidth * ps, tailBody.y)
            close()
        }
        scope.drawPath(tailBoomPath, armorShadow, style = Fill)
        scope.drawPath(tailBoomPath, panelSeam, style = Stroke(width = 1.5f * mScale))

        // Vertical Fin / Twin Stealth V-Tail
        if (heloClass == HelicopterClass.STEALTH) {
            val vL = trans(-1.0f, -5.8f, 2.3f)
            val vR = trans(1.0f, -5.8f, 2.3f)
            scope.drawLine(armorBody, tailEnd, vL, 4.5f * ps, StrokeCap.Round)
            scope.drawLine(armorBody, tailEnd, vR, 4.5f * ps, StrokeCap.Round)
            scope.drawLine(accentTrim, tailFinBase, vL, 1.8f * mScale, StrokeCap.Round)
            scope.drawLine(accentTrim, tailFinBase, vR, 1.8f * mScale, StrokeCap.Round)
        } else {
            val finPath = tmpPath.apply {
                reset()
                moveTo(tailFinBase.x, tailFinBase.y)
                lineTo(tailFinTop.x, tailFinTop.y)
                lineTo(tailEnd.x, tailEnd.y)
                close()
            }
            scope.drawPath(finPath, armorBody, style = Fill)
            scope.drawPath(finPath, panelSeam, style = Stroke(width = 1.5f * mScale))
        }

        // Horizontal Stabilizer Wings
        val stabSpan = if (heloClass == HelicopterClass.SCOUT) 1.3f else 2.0f
        val stabL = trans(-stabSpan, -4.8f * bodyScaleY, 0.4f)
        val stabR = trans(stabSpan, -4.8f * bodyScaleY, 0.4f)
        scope.drawLine(armorTopLit, stabL, stabR, 5f * ps, StrokeCap.Round)
        scope.drawLine(accentTrim.copy(alpha = 0.75f), stabL, stabR, 1.8f * ps, StrokeCap.Round)

        // Tail Rotor / Ducted Fenestron (Stealth)
        val tailRotorHub = trans(0.35f, -5.9f * bodyScaleY, 1.7f)
        val trAngle = player.tailRotorAngle
        val trRadius = 21f * ps
        if (heloClass == HelicopterClass.STEALTH) {
            scope.drawCircle(armorShadow, trRadius * 0.9f, tailRotorHub, style = Stroke(4.5f * mScale))
            scope.drawCircle(accentTrim.copy(alpha = 0.45f), trRadius * 0.65f, tailRotorHub)
        } else {
            scope.drawCircle(Color(0x33B0BEC5), trRadius, tailRotorHub)
            val tr1 = Offset(tailRotorHub.x + cos(trAngle) * trRadius, tailRotorHub.y + sin(trAngle) * trRadius)
            val tr2 = Offset(tailRotorHub.x - cos(trAngle) * trRadius, tailRotorHub.y - sin(trAngle) * trRadius)
            scope.drawLine(Color(0xFFE2E8F0), tr1, tr2, 2.5f * mScale, StrokeCap.Round)
            scope.drawCircle(panelSeam, 3f * ps, tailRotorHub)
        }

        // 2. Wings & Ordnance Pylons
        val wingRoot = trans(0f, 0.2f, 0.1f)
        val leftWingTip = trans(-wingSpan, if (heloClass == HelicopterClass.ULTIMATE_WAR) 0.9f else 0.1f, -0.1f)
        val rightWingTip = trans(wingSpan, if (heloClass == HelicopterClass.ULTIMATE_WAR) 0.9f else 0.1f, -0.1f)

        val wingThickness = when (heloClass) {
            HelicopterClass.SCOUT -> 5.5f
            HelicopterClass.HEAVY_GUNSHIP -> 10.5f
            else -> 8f
        }
        scope.drawLine(armorBody, wingRoot, leftWingTip, wingThickness * ps, StrokeCap.Round)
        scope.drawLine(armorBody, wingRoot, rightWingTip, wingThickness * ps, StrokeCap.Round)
        scope.drawLine(accentTrim, wingRoot, leftWingTip, 2.4f * ps, StrokeCap.Round)
        scope.drawLine(accentTrim, wingRoot, rightWingTip, 2.4f * ps, StrokeCap.Round)

        // Rocket Pods (not protruding on Stealth internal-bay frame)
        if (heloClass != HelicopterClass.STEALTH) {
            val podScale = if (heloClass == HelicopterClass.HEAVY_GUNSHIP) 1.35f else 0.95f
            val podW = 10f * podScale * ps
            val podH = 22f * podScale * ps
            val podPosL = trans(-wingSpan * 0.65f, 0.3f, -0.2f)
            val podPosR = trans(wingSpan * 0.65f, 0.3f, -0.2f)
            val podBodyColor = lerp(armorShadow, Color(0xFF1F262B), 0.5f)
            scope.drawRoundRect(podBodyColor, Offset(podPosL.x - podW / 2, podPosL.y - podH / 2), Size(podW, podH), androidx.compose.ui.geometry.CornerRadius(3f * mScale))
            scope.drawRoundRect(podBodyColor, Offset(podPosR.x - podW / 2, podPosR.y - podH / 2), Size(podW, podH), androidx.compose.ui.geometry.CornerRadius(3f * mScale))
            scope.drawRoundRect(accentTrim.copy(alpha = 0.7f), Offset(podPosL.x - podW / 2, podPosL.y - podH / 2), Size(podW, podH), androidx.compose.ui.geometry.CornerRadius(3f * mScale), style = Stroke(1.2f * mScale))
            scope.drawRoundRect(accentTrim.copy(alpha = 0.7f), Offset(podPosR.x - podW / 2, podPosR.y - podH / 2), Size(podW, podH), androidx.compose.ui.geometry.CornerRadius(3f * mScale), style = Stroke(1.2f * mScale))
            scope.drawCircle(Color(0xFF111711), 3.5f * ps, Offset(podPosL.x, podPosL.y + podH / 2))
            scope.drawCircle(Color(0xFF111711), 3.5f * ps, Offset(podPosR.x, podPosR.y + podH / 2))
        }

        // Wingtip Missiles / Plasma Conduits
        val misPosL = trans(-wingSpan, if (heloClass == HelicopterClass.ULTIMATE_WAR) 0.9f else 0.4f, -0.3f)
        val misPosR = trans(wingSpan, if (heloClass == HelicopterClass.ULTIMATE_WAR) 0.9f else 0.4f, -0.3f)
        val wingTipColor = if (heloClass == HelicopterClass.ULTIMATE_WAR) accentTrim else Color(0xFF424D42)
        scope.drawLine(wingTipColor, Offset(misPosL.x, misPosL.y - 12f * ps), Offset(misPosL.x, misPosL.y + 12f * ps), 3.8f * mScale, StrokeCap.Round)
        scope.drawLine(wingTipColor, Offset(misPosR.x, misPosR.y - 12f * ps), Offset(misPosR.x, misPosR.y + 12f * ps), 3.8f * mScale, StrokeCap.Round)

        // 3. Main Fuselage (Distinct Silhouette per Class)
        val nosePt = trans(0f, 4.8f * bodyScaleY, -0.2f)
        val noseSideL = trans(-1.45f * bodyScaleX, 3.0f * bodyScaleY, 0f)
        val noseSideR = trans(1.45f * bodyScaleX, 3.0f * bodyScaleY, 0f)
        val sponsonL = trans(-2.35f * bodyScaleX, 0.4f * bodyScaleY, 0.2f)
        val sponsonR = trans(2.35f * bodyScaleX, 0.4f * bodyScaleY, 0.2f)
        val bodyRearL = trans(-1.75f * bodyScaleX, -1.8f * bodyScaleY, 0.3f)
        val bodyRearR = trans(1.75f * bodyScaleX, -1.8f * bodyScaleY, 0.3f)
        val bodyTail = trans(0f, -2.6f * bodyScaleY, 0.3f)

        val fusePath = tmpPath.apply {
            reset()
            moveTo(nosePt.x, nosePt.y)
            lineTo(noseSideR.x, noseSideR.y)
            lineTo(sponsonR.x, sponsonR.y)
            lineTo(bodyRearR.x, bodyRearR.y)
            lineTo(bodyTail.x, bodyTail.y)
            lineTo(bodyRearL.x, bodyRearL.y)
            lineTo(sponsonL.x, sponsonL.y)
            lineTo(noseSideL.x, noseSideL.y)
            close()
        }
        scope.drawPath(fusePath, armorBody, style = Fill)
        scope.drawPath(fusePath, if (heloClass == HelicopterClass.STEALTH || heloClass == HelicopterClass.ULTIMATE_WAR) accentTrim else panelSeam, style = Stroke(width = 2.2f * mScale))

        // Dorsal highlight, upper-left directional rim specular & trim racing stripe
        scope.drawLine(armorTopLit, nosePt, bodyTail, 4.2f * ps, StrokeCap.Round)
        scope.drawLine(lerp(armorTopLit, Color.White, 0.38f).copy(alpha = 0.78f), nosePt, noseSideL, 2.2f * ps, StrokeCap.Round)
        scope.drawLine(accentTrim.copy(alpha = 0.85f), noseSideL, sponsonL, 2.0f * ps, StrokeCap.Round)
        scope.drawLine(accentTrim.copy(alpha = 0.85f), noseSideR, sponsonR, 2.0f * ps, StrokeCap.Round)

        // Engine Exhaust Fairings / Plasma Nacelles
        val engL = trans(-1.35f * bodyScaleX, -0.8f * bodyScaleY, 0.8f)
        val engR = trans(1.35f * bodyScaleX, -0.8f * bodyScaleY, 0.8f)
        scope.drawOval(armorShadow, Offset(engL.x - 5f * ps, engL.y - 7f * ps), Size(10f * ps, 14f * ps))
        scope.drawOval(armorShadow, Offset(engR.x - 5f * ps, engR.y - 7f * ps), Size(10f * ps, 14f * ps))
        if (heloClass == HelicopterClass.ULTIMATE_WAR) {
            scope.drawCircle(accentTrim.copy(alpha = 0.8f), 3.2f * ps, engL)
            scope.drawCircle(accentTrim.copy(alpha = 0.8f), 3.2f * ps, engR)
        }

        // 4. Cockpit Canopy
        val canopyNose = trans(0f, 3.8f * bodyScaleY, 0.5f)
        val canopyGunnerL = trans(-0.85f * bodyScaleX, 2.5f * bodyScaleY, 0.7f)
        val canopyGunnerR = trans(0.85f * bodyScaleX, 2.5f * bodyScaleY, 0.7f)
        val canopyPilotL = trans(-0.95f * bodyScaleX, 0.9f * bodyScaleY, 0.9f)
        val canopyPilotR = trans(0.95f * bodyScaleX, 0.9f * bodyScaleY, 0.9f)
        val canopyAft = trans(0f, 0.2f, 0.8f)

        val canopyPath = tmpPath.apply {
            reset()
            moveTo(canopyNose.x, canopyNose.y)
            lineTo(canopyGunnerR.x, canopyGunnerR.y)
            lineTo(canopyPilotR.x, canopyPilotR.y)
            lineTo(canopyAft.x, canopyAft.y)
            lineTo(canopyPilotL.x, canopyPilotL.y)
            lineTo(canopyGunnerL.x, canopyGunnerL.y)
            close()
        }
        val canopyGlass = if (heloClass == HelicopterClass.ULTIMATE_WAR) Color(0xFF0A2E3D) else Color(0xFF141D26)
        scope.drawPath(canopyPath, canopyGlass, style = Fill)
        scope.drawPath(canopyPath, accentTrim.copy(alpha = 0.65f), style = Stroke(width = 2.0f * mScale))
        scope.drawLine(Color.White.copy(alpha = 0.45f), canopyNose, canopyPilotR, 1.5f * mScale)
        if (heloClass == HelicopterClass.HEAVY_GUNSHIP || heloClass == HelicopterClass.ULTIMATE_WAR) {
            scope.drawLine(panelSeam, canopyGunnerL, canopyGunnerR, 2f * mScale)
        }

        // 5. Chin-Mounted Chain / Plasma Turret
        val turretGimbal = trans(0f, 4.4f * bodyScaleY, -0.6f)
        val barrelEnd = trans(0f, 5.8f * bodyScaleY, -0.7f)
        scope.drawCircle(panelSeam, 6f * ps, turretGimbal)
        scope.drawLine(Color(0xFF111411), turretGimbal, barrelEnd, 4.5f * ps, StrokeCap.Square)
        scope.drawCircle(if (heloClass == HelicopterClass.ULTIMATE_WAR) accentTrim else Color(0xFF2A342A), 3f * ps, barrelEnd)

        if (player.activeMuzzleFlash) {
            scope.drawCircle(if (heloClass == HelicopterClass.ULTIMATE_WAR) accentTrim else Color(0xFFFFB300), 16f * ps, barrelEnd)
            scope.drawCircle(Color.White, 7f * ps, barrelEnd)
        }

        // 6. Main Rotor Assembly (Class-differentiated blade count & coaxial rotor on APEX)
        val mastHub = trans(0f, 0.4f, 1.6f)
        val rotorR = when (heloClass) {
            HelicopterClass.SCOUT -> 72f * ps
            HelicopterClass.HEAVY_GUNSHIP -> 96f * ps
            HelicopterClass.STEALTH -> 84f * ps
            HelicopterClass.ULTIMATE_WAR -> 92f * ps
        }

        scope.drawCircle(panelSeam, 7f * ps, mastHub)
        scope.drawCircle(accentTrim.copy(alpha = 0.8f), 4f * ps, mastHub)

        scope.drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x44B0BEC5), Color(0x18B0BEC5), Color.Transparent),
                center = mastHub,
                radius = rotorR
            ),
            radius = rotorR,
            center = mastHub
        )

        val bladeCount = when (heloClass) {
            HelicopterClass.SCOUT -> 3
            HelicopterClass.STEALTH -> 5
            else -> 4
        }
        val angle = player.mainRotorAngle
        for (i in 0 until bladeCount) {
            val bladeAngle = angle + (i * (kotlin.math.PI.toFloat() * 2f) / bladeCount)
            val bladeEnd = Offset(
                mastHub.x + cos(bladeAngle) * rotorR * cosR,
                mastHub.y + sin(bladeAngle) * rotorR * 0.72f
            )
            scope.drawLine(Color(0xFF212529), mastHub, bladeEnd, 4.0f * mScale, StrokeCap.Round)
            val tipPos = Offset(
                mastHub.x + cos(bladeAngle) * (rotorR * 0.88f) * cosR,
                mastHub.y + sin(bladeAngle) * (rotorR * 0.88f) * 0.72f
            )
            scope.drawLine(accentTrim, tipPos, bladeEnd, 4.2f * mScale, StrokeCap.Round)
        }
        if (heloClass == HelicopterClass.ULTIMATE_WAR) {
            val upperHub = trans(0f, 0.4f, 2.0f)
            for (i in 0 until 4) {
                val contraAngle = -angle * 1.15f + (i * kotlin.math.PI.toFloat() / 2f)
                val contraEnd = Offset(
                    upperHub.x + cos(contraAngle) * rotorR * 0.78f * cosR,
                    upperHub.y + sin(contraAngle) * rotorR * 0.56f
                )
                scope.drawLine(Color(0xFF37474F), upperHub, contraEnd, 3.0f * mScale, StrokeCap.Round)
            }
        }

        // 7. Combat Status Auras: Countermeasure Flare Pulse, Overdrive Ready Glow, and Hit Flash
        if (player.flareActiveTimer > 0f) {
            val flareR = 76f * ps
            scope.drawCircle(
                Color(0x5500E5FF),
                flareR,
                Offset(cx, cy),
                style = Stroke(4f * mScale)
            )
            scope.drawCircle(
                Color(0x22FFD600),
                flareR * 0.8f,
                Offset(cx, cy)
            )
        }
        if (player.overdriveBeamTimer > 0f || player.overdriveCharge >= 100f) {
            scope.drawCircle(
                Color(0x6600E5FF),
                48f * ps,
                Offset(cx, cy),
                style = Stroke(2.5f * mScale)
            )
        }
        if (player.hitFlashTimer > 0f) {
            scope.drawCircle(
                Color(0x66FF1744),
                56f * ps,
                Offset(cx, cy),
                style = Stroke(3.5f * mScale)
            )
        }
    }

    /**
     * Renders 3D models for all 8 Enemy Types with enlarged proportional scale and high combat visibility
     */
    private fun drawEnemy3D(scope: DrawScope, camera: Camera3D, enemy: EnemyTarget, width: Float, height: Float) {
        val proj = camera.project(enemy.pos, width, height) ?: return
        val s = proj.scale
        val unitScale = s / 20f
        val eScale = enemyVisualScale(enemy.category)
        val es = unitScale * eScale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        when (enemy.category) {
            EnemyCategory.ATTACK_HELICOPTER -> {
                // Hostile Gunship: Enlarged heavily armored helicopter with tandem canopy, wing pylons & dual spinning rotor
                val w = 46f * es
                val h = 64f * es

                // Fuselage
                val fusePath = tmpPath.apply {
                    reset()
                    moveTo(cx, cy + h * 0.45f) // Nose facing player/downwards
                    lineTo(cx + w * 0.45f, cy + h * 0.15f)
                    lineTo(cx + w * 0.35f, cy - h * 0.35f)
                    lineTo(cx, cy - h * 0.5f) // Tail
                    lineTo(cx - w * 0.35f, cy - h * 0.35f)
                    lineTo(cx - w * 0.45f, cy + h * 0.15f)
                    close()
                }
                scope.drawPath(fusePath, Color(0xFF7F1D1D), style = Fill)
                scope.drawPath(fusePath, Color(0xFFEF4444), style = Stroke((2.5f * eScale).coerceAtLeast(2f)))
                // Directional sun-facing rim highlight (upper-left)
                scope.drawLine(Color(0x88FCA5A5), Offset(cx - w * 0.42f, cy + h * 0.14f), Offset(cx - w * 0.32f, cy - h * 0.33f), (2.2f * eScale).coerceAtLeast(1.5f), StrokeCap.Round)

                // Red/Orange Cockpit + Glass Glint
                scope.drawOval(
                    color = Color(0xFFFF5722),
                    topLeft = Offset(cx - w * 0.2f, cy + h * 0.1f),
                    size = Size(w * 0.4f, h * 0.25f)
                )
                scope.drawOval(
                    color = Color(0x99FFF8E1),
                    topLeft = Offset(cx - w * 0.13f, cy + h * 0.13f),
                    size = Size(w * 0.16f, h * 0.10f)
                )

                // Weapon stub wings & Rocket Pods scaled proportionally
                scope.drawLine(Color(0xFF374151), Offset(cx - w * 0.6f, cy), Offset(cx + w * 0.6f, cy), (5f * eScale).coerceAtLeast(3f), StrokeCap.Round)
                val podW = 10f * es
                val podH = 16f * es
                scope.drawRect(Color(0xFF1F2937), Offset(cx - w * 0.65f, cy - podH / 2f), Size(podW, podH))
                scope.drawRect(Color(0xFF1F2937), Offset(cx + w * 0.65f - podW, cy - podH / 2f), Size(podW, podH))

                // Spinning Dual Main Rotor
                val rR = 54f * es
                val rAngle = enemy.rotorAngle
                scope.drawLine(
                    Color(0xFFF1F5F9),
                    Offset(cx + cos(rAngle) * rR, cy + sin(rAngle) * rR * 0.65f),
                    Offset(cx - cos(rAngle) * rR, cy - sin(rAngle) * rR * 0.65f),
                    (4f * eScale).coerceAtLeast(2.5f),
                    StrokeCap.Round
                )
                scope.drawLine(
                    Color(0xFF94A3B8),
                    Offset(cx - sin(rAngle) * rR * 0.8f, cy + cos(rAngle) * rR * 0.55f),
                    Offset(cx + sin(rAngle) * rR * 0.8f, cy - cos(rAngle) * rR * 0.55f),
                    (3f * eScale).coerceAtLeast(2f),
                    StrokeCap.Round
                )
                scope.drawCircle(Color(0xFFE2E8F0), 4f * es, Offset(cx, cy))
            }

            EnemyCategory.FIGHTER_AIRCRAFT -> {
                // High-Speed Interceptor Jet: Large delta wings, twin engine glows, and cockpit
                val span = 56f * es
                val len = 68f * es
                val nose = Offset(cx, cy + len * 0.55f)
                val wingL = Offset(cx - span, cy - len * 0.15f)
                val wingR = Offset(cx + span, cy - len * 0.15f)
                val tail = Offset(cx, cy - len * 0.45f)

                val jetPath = tmpPath.apply {
                    reset()
                    moveTo(nose.x, nose.y)
                    lineTo(wingR.x, wingR.y)
                    lineTo(cx + span * 0.35f, cy - len * 0.3f)
                    lineTo(tail.x, tail.y)
                    lineTo(cx - span * 0.35f, cy - len * 0.3f)
                    lineTo(wingL.x, wingL.y)
                    close()
                }
                scope.drawPath(jetPath, Color(0xFF1E3A8A), style = Fill)
                scope.drawPath(jetPath, Color(0xFF60A5FA), style = Stroke((2.5f * eScale).coerceAtLeast(2f)))
                scope.drawLine(Color(0x99BFDBFE), nose, wingL, (2.2f * eScale).coerceAtLeast(1.5f), StrokeCap.Round)
                scope.drawOval(Color(0xFF93C5FD), Offset(cx - span * 0.10f, cy + len * 0.12f), Size(span * 0.20f, len * 0.22f))

                // Twin Jet Engine Afterburners
                val aftY = tail.y + 4f * es
                scope.drawCircle(Color(0xFF00E5FF), 7f * es, Offset(cx - span * 0.2f, aftY))
                scope.drawCircle(Color(0xFF00E5FF), 7f * es, Offset(cx + span * 0.2f, aftY))
                scope.drawCircle(Color.White, 3f * es, Offset(cx - span * 0.2f, aftY))
                scope.drawCircle(Color.White, 3f * es, Offset(cx + span * 0.2f, aftY))
            }

            EnemyCategory.GROUND_TANK -> {
                // Heavy Ground Battle Tank: Natural olive-earth armored chassis, treads, rotating cannon
                val tw = 48f * es
                val th = 56f * es

                val treadW = 10f * es
                val cornerR = androidx.compose.ui.geometry.CornerRadius(3f * eScale)
                scope.drawRoundRect(Color(0xFF2B2825), Offset(cx - tw / 2 - treadW * 0.6f, cy - th / 2), Size(treadW, th), cornerR)
                scope.drawRoundRect(Color(0xFF2B2825), Offset(cx + tw / 2 - treadW * 0.4f, cy - th / 2), Size(treadW, th), cornerR)
                scope.drawRoundRect(Color(0xFF5A524A), Offset(cx - tw / 2 - treadW * 0.6f, cy - th / 2), Size(treadW, th), cornerR, style = Stroke(1.5f * eScale))
                scope.drawRoundRect(Color(0xFF5A524A), Offset(cx + tw / 2 - treadW * 0.4f, cy - th / 2), Size(treadW, th), cornerR, style = Stroke(1.5f * eScale))

                val hullR = androidx.compose.ui.geometry.CornerRadius(6f * eScale)
                scope.drawRoundRect(Color(0xFF5B664E), Offset(cx - tw / 2, cy - th * 0.45f), Size(tw, th * 0.9f), hullR)
                scope.drawRoundRect(Color(0xFF3E4734), Offset(cx - tw / 2, cy - th * 0.45f), Size(tw, th * 0.9f), hullR, style = Stroke(2f * eScale))
                scope.drawLine(Color(0x66DCE7D0), Offset(cx - tw * 0.42f, cy - th * 0.36f), Offset(cx + tw * 0.36f, cy - th * 0.36f), (2.2f * eScale).coerceAtLeast(1.4f), StrokeCap.Round)

                // Rotating Heavy Turret & Extended Cannon Barrel pointing accurately toward player
                val tAngle = enemy.turretAngle
                val bLen = 34f * es
                val barrelEnd = Offset(cx + sin(tAngle) * bLen, cy - cos(tAngle) * bLen)
                scope.drawLine(Color(0xFF2B3028), Offset(cx, cy), barrelEnd, (6f * es).coerceAtLeast(3f), StrokeCap.Round)
                scope.drawLine(Color(0xFFDDE3D8), Offset(cx, cy), barrelEnd, (2f * es).coerceAtLeast(1.2f), StrokeCap.Round)
                scope.drawCircle(Color(0xFF47523C), 15f * es, Offset(cx, cy))
                scope.drawCircle(Color(0xFFF2EFE9), 5f * es, Offset(cx, cy))
            }

            EnemyCategory.AA_TURRET -> {
                // Ground Fortified AA Flak Bunker with aiming quad barrels
                val r = 28f * es
                scope.drawCircle(Color(0xFF8C8275), r, Offset(cx, cy))
                scope.drawCircle(Color(0xFF5D544A), r, Offset(cx, cy), style = Stroke(3f * eScale))
                scope.drawCircle(Color(0xFF4B5548), r * 0.65f, Offset(cx, cy))

                val tAngle = enemy.turretAngle
                val dirX = sin(tAngle)
                val dirY = cos(tAngle)
                val perpX = cos(tAngle)
                val perpY = sin(tAngle)
                val bLen = 28f * es
                for (offsetPx in aaBarrelOffsets) {
                    val scaledOff = offsetPx * es
                    val bx = cx + perpX * scaledOff
                    val by = cy + perpY * scaledOff
                    val ex = bx + dirX * bLen
                    val ey = by - dirY * bLen
                    scope.drawLine(Color(0xFF333B36), Offset(bx, by), Offset(ex, ey), (4f * es).coerceAtLeast(2f), StrokeCap.Square)
                }
            }

            EnemyCategory.MISSILE_LAUNCHER -> {
                // Mobile SAM Launcher Truck
                val w = 42f * es
                val h = 64f * es
                val wheelW = 5f * es
                val wheelH = 10f * es
                for (factor in samWheelFactors) {
                    val wy = h * factor
                    scope.drawRect(Color(0xFF2B2825), Offset(cx - w / 2 - wheelW * 0.8f, cy + wy - wheelH / 2f), Size(wheelW, wheelH))
                    scope.drawRect(Color(0xFF2B2825), Offset(cx + w / 2 - wheelW * 0.2f, cy + wy - wheelH / 2f), Size(wheelW, wheelH))
                }
                val truckCorner = androidx.compose.ui.geometry.CornerRadius(4f * eScale)
                scope.drawRoundRect(Color(0xFF5C6658), Offset(cx - w / 2, cy - h / 2), Size(w, h), truckCorner)
                scope.drawRoundRect(Color(0xFF3D453B), Offset(cx - w / 2, cy - h / 2), Size(w, h), truckCorner, style = Stroke(2f * eScale))

                scope.drawRoundRect(Color(0xFF3D423F), Offset(cx - w * 0.35f, cy - h * 0.25f), Size(w * 0.7f, h * 0.45f), androidx.compose.ui.geometry.CornerRadius(3f * eScale))
                for (i in -1..1) {
                    scope.drawCircle(Color(0xFFD95338), 4f * es, Offset(cx + i * 8f * es, cy - h * 0.3f))
                    scope.drawCircle(Color.White, 1.5f * es, Offset(cx + i * 8f * es, cy - h * 0.3f))
                }
            }

            EnemyCategory.INFANTRY_SOLDIER -> {
                // Ground Machine Gun Infantry Soldier with animated patrol stride and rotating rifle
                val r = 15f * es
                val stride = sin(enemy.strafePhase * 8f) * (r * 0.35f)
                // Ground shadow anchored under boots for clear contrast against terrain
                scope.drawOval(Color(0x55221A12), Offset(cx - r * 0.85f, cy + r * 0.35f), Size(r * 1.7f, r * 0.75f))
                // Walking boots / legs
                scope.drawLine(Color(0xFF3D342B), Offset(cx - r * 0.28f, cy), Offset(cx - r * 0.28f, cy + r * 0.65f + stride), (3f * es).coerceAtLeast(2.2f), StrokeCap.Round)
                scope.drawLine(Color(0xFF3D342B), Offset(cx + r * 0.28f, cy), Offset(cx + r * 0.28f, cy + r * 0.65f - stride), (3f * es).coerceAtLeast(2.2f), StrokeCap.Round)
                // Tactical vest torso & sunlit olive helmet
                scope.drawCircle(Color(0xFF4A5D3F), r * 0.78f, Offset(cx, cy))
                scope.drawCircle(Color(0xFF283322), r * 0.78f, Offset(cx, cy), style = Stroke(1.5f * eScale))
                scope.drawCircle(Color(0xFF7B945E), r * 0.48f, Offset(cx, cy - r * 0.38f))
                // Aimed rifle barrel tracking player
                val tAngle = enemy.turretAngle
                val bLen = 19f * es
                scope.drawLine(Color(0xFF262A2C), Offset(cx, cy), Offset(cx + sin(tAngle) * bLen, cy - cos(tAngle) * bLen), (3.5f * es).coerceAtLeast(2.4f), StrokeCap.Round)
            }

            EnemyCategory.ROCKET_SOLDIER -> {
                // Anti-Air RPG Trooper with animated stride and shoulder rocket tube
                val r = 16.5f * es
                val stride = cos(enemy.strafePhase * 7f) * (r * 0.32f)
                scope.drawOval(Color(0x55221A12), Offset(cx - r * 0.85f, cy + r * 0.35f), Size(r * 1.7f, r * 0.75f))
                scope.drawLine(Color(0xFF3D342B), Offset(cx - r * 0.3f, cy), Offset(cx - r * 0.3f, cy + r * 0.65f + stride), (3.2f * es).coerceAtLeast(2.4f), StrokeCap.Round)
                scope.drawLine(Color(0xFF3D342B), Offset(cx + r * 0.3f, cy), Offset(cx + r * 0.3f, cy + r * 0.65f - stride), (3.2f * es).coerceAtLeast(2.4f), StrokeCap.Round)
                // Brown earth armor vest & terracotta helmet
                scope.drawCircle(Color(0xFF6B4C38), r * 0.82f, Offset(cx, cy))
                scope.drawCircle(Color(0xFF3D291C), r * 0.82f, Offset(cx, cy), style = Stroke(1.5f * eScale))
                scope.drawCircle(Color(0xFFD96B38), r * 0.48f, Offset(cx, cy - r * 0.38f))
                // Shoulder rocket launcher aiming toward player
                val tAngle = enemy.turretAngle
                val bLen = 23f * es
                val tipX = cx + sin(tAngle) * bLen
                val tipY = cy - cos(tAngle) * bLen
                scope.drawLine(Color(0xFF2B3033), Offset(cx, cy), Offset(tipX, tipY), (5f * es).coerceAtLeast(3f), StrokeCap.Square)
                scope.drawCircle(Color(0xFFE06D38), 3.2f * es, Offset(tipX, tipY))
            }

            EnemyCategory.WATCHTOWER_SNIPER -> {
                // Elevated timber & stone sniper watchtower with rotating sniper rifle
                val r = 20f * es
                scope.drawRect(Color(0xFF8E6D4B), Offset(cx - r, cy - r), Size(r * 2, r * 2))
                scope.drawRect(Color(0xFF5C432B), Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(2f * eScale))
                scope.drawCircle(Color(0xFF4B6342), r * 0.44f, Offset(cx, cy))
                val tAngle = enemy.turretAngle
                val bLen = 26f * es
                scope.drawLine(Color(0xFF212529), Offset(cx, cy), Offset(cx + sin(tAngle) * bLen, cy - cos(tAngle) * bLen), (3f * es).coerceAtLeast(2.2f), StrokeCap.Round)
            }
        }

        // Hit Flash / Alert Indicator & Damage Smoke Wisps
        if (enemy.hitFlashTimer > 0f) {
            scope.drawCircle(Color(0x88FFFFFF), 24f * es, Offset(cx, cy), style = Stroke(3f * eScale))
        }

        // High-Contrast Threat Health Bar & Identity Indicator (offset scaled per enlarged sprite)
        val barW = 54f * unitScale * (1f + (eScale - 1f) * 0.35f)
        val barH = 6f
        val barY = cy - enemyHealthBarOffset(enemy.category) * unitScale
        val hpPct = (enemy.health / enemy.maxHealth).coerceIn(0f, 1f)

        if (hpPct < 0.5f) {
            scope.drawCircle(Color(0x66222222), 10f * es, Offset(cx - 4f * eScale, cy - 12f * eScale))
        }

        if (enemy.isAlerted) {
            // Hostile alert diamond marker beside enemy health bar
            scope.drawCircle(Color(0xFFFF1744), 3.5f, Offset(cx - barW / 2 - 7f, barY + barH / 2))
        }

        // Health Bar Background & Border
        scope.drawRect(Color(0xDD000000), Offset(cx - barW / 2 - 1f, barY - 1f), Size(barW + 2f, barH + 2f))
        scope.drawRect(Color(0x55FFFFFF), Offset(cx - barW / 2 - 1f, barY - 1f), Size(barW + 2f, barH + 2f), style = Stroke(1f))
        val barColor = if (hpPct > 0.45f) enemy.empire.primaryColor else Color(0xFFFF1744)
        scope.drawRect(barColor, Offset(cx - barW / 2, barY), Size(barW * hpPct, barH))
    }

    /**
     * Renders massive 3D Boss Entities with Subsystems, Weak Points, and Signature Attack Visuals
     */
    private fun drawBoss3D(scope: DrawScope, camera: Camera3D, boss: BossCombatEntity, width: Float, height: Float) {
        val currentPos = boss.pos + boss.chargeOffset
        val proj = camera.project(currentPos, width, height) ?: return
        val s = proj.scale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        when (boss.empire) {
            EmpireType.DRAGON -> {
                // Ignis Rex: Giant Wyrm with flapping wings, magma breath & glowing heart core
                val wingSpan = 85f * (s / 20f)
                val flap = sin(boss.animationPhase * 4.5f) * 22f
                val head = Offset(cx, cy + 38f * (s / 20f))
                val tail = Offset(cx, cy - 44f * (s / 20f))
                val wingL = Offset(cx - wingSpan, cy + flap)
                val wingR = Offset(cx + wingSpan, cy + flap)

                // Wing membranes
                val body = tmpPath.apply {
                    reset()
                    moveTo(head.x, head.y)
                    lineTo(wingR.x, wingR.y)
                    lineTo(tail.x, tail.y)
                    lineTo(wingL.x, wingL.y)
                    close()
                }
                scope.drawPath(body, Color(0xFF870000), style = Fill)
                scope.drawPath(body, Color(0xFFFF3D00), style = Stroke(3.5f))
                scope.drawLine(Color(0x99FFAB91), head, wingL, 3f, StrokeCap.Round)

                // Spines along back
                scope.drawLine(Color(0xFFFFD600), head, tail, 4f)

                // Glowing Dragon Heart Weak Point on Chest
                boss.weakPoint?.let { wp ->
                    val wpProj = camera.project(currentPos + wp.offset, width, height)
                    if (wpProj != null) {
                        val wpx = wpProj.screenPos.x
                        val wpy = wpProj.screenPos.y
                        val wpRadius = wp.radius * (s / 20f) * 2.5f
                        val pulse = (sin(boss.animationPhase * 6f) * 0.5f + 0.5f)

                        scope.drawCircle(Color(0xFFFFD600), wpRadius * (0.8f + pulse * 0.4f), Offset(wpx, wpy))
                        scope.drawCircle(Color.White, wpRadius * 0.4f, Offset(wpx, wpy))
                        scope.drawCircle(Color(0xFFFF1744), wpRadius * 1.3f, Offset(wpx, wpy), style = Stroke(2f))
                    }
                }

                // Fire Breath Cone Stream Visual
                if (boss.currentAttackMode == BossAttackMode.FIRE_BREATH) {
                    val breathPath = tmpPath.apply {
                        reset()
                        moveTo(head.x, head.y)
                        lineTo(cx - 50f, cy + 180f)
                        lineTo(cx + 50f, cy + 180f)
                        close()
                    }
                    scope.drawPath(breathPath, Color(0xFFFF3D00).copy(alpha = 0.55f), style = Fill)
                    scope.drawPath(breathPath, Color(0xFFFFEA00).copy(alpha = 0.85f), style = Stroke(2.5f))
                    // Fiery tongues
                    scope.drawCircle(Color(0xFFFFD600), 16f, head)
                }
            }

            EmpireType.ALIEN -> {
                // Overseer Prime: Massive Armored Flying Saucer with Energy Shield & Plasma Pods
                val r = 58f * (s / 20f)
                scope.drawOval(Color(0xFF4A148C), Offset(cx - r, cy - r * 0.65f), Size(r * 2, r * 1.3f))
                scope.drawOval(Color(0xFF00E5FF), Offset(cx - r, cy - r * 0.65f), Size(r * 2, r * 1.3f), style = Stroke(3f))
                scope.drawOval(Color(0x55B388FF), Offset(cx - r * 0.72f, cy - r * 0.50f), Size(r * 0.95f, r * 0.48f))
                // Rotating perimeter lights
                for (i in 0 until 8) {
                    val angle = (boss.animationPhase * 2f) + (i * kotlin.math.PI.toFloat() * 2f / 8f)
                    val lx = cx + cos(angle) * (r * 0.85f)
                    val ly = cy + sin(angle) * (r * 0.55f)
                    scope.drawCircle(Color(0xFF00E5FF), 4f, Offset(lx, ly))
                }

                // Central Warp Core
                scope.drawCircle(Color(0xFFD500F9), r * 0.4f, Offset(cx, cy))
                scope.drawCircle(Color.White, r * 0.18f, Offset(cx, cy))

                // Plasma Pod Subsystems (Port & Starboard)
                for (sub in boss.subsystems) {
                    val subProj = camera.project(currentPos + sub.offset, width, height)
                    if (subProj != null) {
                        val sx = subProj.screenPos.x
                        val sy = subProj.screenPos.y
                        val subR = 10f * (s / 20f)
                        if (sub.isDestroyed) {
                            scope.drawCircle(Color(0xFF212121), subR, Offset(sx, sy))
                            scope.drawCircle(Color(0xFF616161), subR, Offset(sx, sy), style = Stroke(1.5f))
                        } else {
                            scope.drawCircle(Color(0xFF00E5FF), subR, Offset(sx, sy))
                            scope.drawCircle(Color(0xFFE040FB), subR * 1.4f, Offset(sx, sy), style = Stroke(2f))
                        }
                    }
                }

                // Energy Shield Bubble if shield active
                if (boss.shield > 0f) {
                    val shieldAlpha = (boss.shield / boss.maxShield).coerceIn(0.2f, 0.65f)
                    scope.drawOval(
                        Color(0xFF00E5FF).copy(alpha = shieldAlpha),
                        Offset(cx - r * 1.25f, cy - r * 0.85f),
                        Size(r * 2.5f, r * 1.7f),
                        style = Stroke(3f)
                    )
                }
            }

            EmpireType.ORC -> {
                // Warlord Gashrak: Massive Ironclad Scrap War Zeppelin
                val w = 62f * (s / 20f)
                val h = 82f * (s / 20f)
                scope.drawRoundRect(Color(0xFF3E2723), Offset(cx - w / 2, cy - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(12f))
                scope.drawRoundRect(Color(0xFFC2410C), Offset(cx - w / 2, cy - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(12f), style = Stroke(3.5f))
                scope.drawLine(Color(0x88FFAB40), Offset(cx - w * 0.38f, cy - h * 0.42f), Offset(cx + w * 0.34f, cy - h * 0.42f), 3f, StrokeCap.Round)

                // Front Spiked Ram
                val ram = tmpPath.apply {
                    reset()
                    moveTo(cx - w * 0.35f, cy + h * 0.5f)
                    lineTo(cx, cy + h * 0.72f)
                    lineTo(cx + w * 0.35f, cy + h * 0.5f)
                    close()
                }
                scope.drawPath(ram, Color(0xFF212121), style = Fill)
                scope.drawPath(ram, Color(0xFF84CC16), style = Stroke(2f))

                // Twin Heavy Siege Cannon Barrels
                scope.drawLine(Color(0xFF1E1A18), Offset(cx - 16f, cy + 10f), Offset(cx - 16f, cy + 42f), 6f, StrokeCap.Round)
                scope.drawLine(Color(0xFF1E1A18), Offset(cx + 16f, cy + 10f), Offset(cx + 16f, cy + 42f), 6f, StrokeCap.Round)

                // Exposed Boiler Exhaust Weak Point (Rear Center)
                boss.weakPoint?.let { wp ->
                    val wpProj = camera.project(currentPos + wp.offset, width, height)
                    if (wpProj != null) {
                        val wpx = wpProj.screenPos.x
                        val wpy = wpProj.screenPos.y
                        val wpR = wp.radius * (s / 20f) * 2.2f
                        scope.drawCircle(Color(0xFFFF3D00), wpR, Offset(wpx, wpy))
                        scope.drawCircle(Color(0xFFFFD600), wpR * 0.5f, Offset(wpx, wpy))
                        scope.drawCircle(Color.White, wpR * 1.3f, Offset(wpx, wpy), style = Stroke(2f))
                    }
                }
            }

            EmpireType.HUMAN -> {
                // Project Titan: Heavy Tandem-Rotor Fortress Gunship
                val w = 68f * (s / 20f)
                val h = 88f * (s / 20f)
                scope.drawRoundRect(Color(0xFF0F172A), Offset(cx - w / 2, cy - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(10f))
                scope.drawRoundRect(Color(0xFF0284C7), Offset(cx - w / 2, cy - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(10f), style = Stroke(3.5f))
                scope.drawLine(Color(0x997DD3FC), Offset(cx - w * 0.38f, cy - h * 0.42f), Offset(cx + w * 0.34f, cy - h * 0.42f), 3f, StrokeCap.Round)

                // Front & Rear Tandem Rotors spinning in opposite directions
                val rotorR = 42f * (s / 20f)
                val frontAngle = boss.animationPhase * 10f
                val rearAngle = -boss.animationPhase * 10f
                val frontY = cy + h * 0.3f
                val rearY = cy - h * 0.3f

                // Front Rotor (3 blades)
                for (b in 0..2) {
                    val a = frontAngle + b * (kotlin.math.PI * 2.0 / 3.0)
                    scope.drawLine(Color(0xFFECEFF1), Offset(cx, frontY), Offset(cx + cos(a).toFloat() * rotorR, frontY + sin(a).toFloat() * rotorR * 0.5f), 3f)
                }
                // Rear Rotor (3 blades)
                for (b in 0..2) {
                    val a = rearAngle + b * (kotlin.math.PI * 2.0 / 3.0)
                    scope.drawLine(Color(0xFF90A4AE), Offset(cx, rearY), Offset(cx + cos(a).toFloat() * rotorR, rearY + sin(a).toFloat() * rotorR * 0.5f), 3f)
                }

                // Subsystems: 30mm Vulcan Gatling + Left/Right Missile Pods
                for (sub in boss.subsystems) {
                    val subProj = camera.project(currentPos + sub.offset, width, height)
                    if (subProj != null) {
                        val sx = subProj.screenPos.x
                        val sy = subProj.screenPos.y
                        val subR = 8f * (s / 20f)
                        if (sub.isDestroyed) {
                            scope.drawCircle(Color(0xFF263238), subR, Offset(sx, sy))
                            scope.drawCircle(Color(0xFFFF1744), subR * 1.2f, Offset(sx, sy), style = Stroke(1.5f))
                        } else {
                            scope.drawCircle(Color(0xFFF59E0B), subR, Offset(sx, sy))
                            scope.drawCircle(Color(0xFF0284C7), subR * 1.3f, Offset(sx, sy), style = Stroke(2f))
                        }
                    }
                }

                // Kinetic Shield Bubble if active
                if (boss.shield > 0f) {
                    scope.drawRoundRect(
                        Color(0xFF38BDF8).copy(alpha = 0.45f),
                        Offset(cx - w * 0.65f, cy - h * 0.6f),
                        Size(w * 1.3f, h * 1.2f),
                        androidx.compose.ui.geometry.CornerRadius(14f),
                        style = Stroke(2.5f)
                    )
                }
            }
        }

        // Boss Evasive Boost & Hit Flash Visual Feedback
        if (boss.isEvading) {
            scope.drawOval(
                Color(0x6600E5FF),
                Offset(cx - 72f * (s / 20f), cy - 32f * (s / 20f)),
                Size(144f * (s / 20f), 64f * (s / 20f)),
                style = Stroke(3.5f)
            )
        }
        if (boss.hitFlashTimer > 0f) {
            scope.drawCircle(
                Color(0x77FFFFFF),
                56f * (s / 20f),
                Offset(cx, cy),
                style = Stroke(4f)
            )
        }
    }

    private fun drawRocket3D(scope: DrawScope, camera: Camera3D, rocket: RocketCluster, width: Float, height: Float) {
        val proj = camera.project(rocket.pos, width, height) ?: return
        val s = proj.scale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        // Smoke trail
        for (i in 0 until rocket.smokeTrail.size - 1) {
            val p1 = camera.project(rocket.smokeTrail[i], width, height)
            val p2 = camera.project(rocket.smokeTrail[i + 1], width, height)
            if (p1 != null && p2 != null) {
                scope.drawLine(Color(0x99CFD8DC), p1.screenPos, p2.screenPos, 3f, StrokeCap.Round)
            }
        }

        // Rocket body
        val rw = 3.5f * (s / 20f)
        val rh = 10f * (s / 20f)
        scope.drawRoundRect(Color(0xFFECEFF1), Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh), androidx.compose.ui.geometry.CornerRadius(1f))
        scope.drawCircle(Color(0xFFFF9100), rw * 1.1f, Offset(cx, cy - rh * 0.5f))
    }

    private fun drawMissile3D(scope: DrawScope, camera: Camera3D, missile: GuidedMissile, width: Float, height: Float) {
        val proj = camera.project(missile.pos, width, height) ?: return
        val s = proj.scale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        for (i in 0 until missile.smokeTrail.size - 1) {
            val p1 = camera.project(missile.smokeTrail[i], width, height)
            val p2 = camera.project(missile.smokeTrail[i + 1], width, height)
            if (p1 != null && p2 != null) {
                val alpha = (i.toFloat() / missile.smokeTrail.size) * 0.7f
                scope.drawLine(Color.White.copy(alpha = alpha), p1.screenPos, p2.screenPos, (p1.scale * 0.2f).coerceIn(2f, 7f), StrokeCap.Round)
            }
        }

        val mw = 4.5f * (s / 20f)
        val mh = 14f * (s / 20f)
        val bodyColor = if (missile.isEnemy) Color(0xFFFF1744) else Color(0xFFECEFF1)
        scope.drawRoundRect(bodyColor, Offset(cx - mw / 2, cy - mh / 2), Size(mw, mh), androidx.compose.ui.geometry.CornerRadius(2f))
        scope.drawCircle(Color(0xFFFF9100), mw * 1.2f, Offset(cx, cy - mh * 0.5f))
    }

    private fun drawBullet3D(scope: DrawScope, camera: Camera3D, bullet: TracerBullet, width: Float, height: Float) {
        val proj = camera.project(bullet.pos, width, height) ?: return
        val s = proj.scale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y

        val tracerLen = if (bullet.isPlayerPlasma) 24f * (s / 20f) else 16f * (s / 20f)
        val color = when {
            bullet.isPlayerPlasma -> Color(0xFF00E5FF)
            bullet.isPlayer -> Color(0xFFFFD600)
            bullet.isTankShell -> Color(0xFFFF9100)
            bullet.isFlakBurst -> Color(0xFFFF3D00)
            else -> Color(0xFFFF1744)
        }

        val thickness = when {
            bullet.isPlayerPlasma -> 6.5f
            bullet.isTankShell -> 6.0f
            bullet.isPlayer -> 3.5f
            else -> 4.0f
        }
        scope.drawLine(color, Offset(cx, cy - tracerLen * 0.5f), Offset(cx, cy + tracerLen * 0.5f), thickness, StrokeCap.Round)
        if (bullet.isPlayerPlasma) {
            scope.drawLine(Color.White, Offset(cx, cy - tracerLen * 0.35f), Offset(cx, cy + tracerLen * 0.35f), 2.5f, StrokeCap.Round)
        }
    }

    private fun drawParticle(scope: DrawScope, camera: Camera3D, particle: VisualParticle, width: Float, height: Float) {
        val proj = camera.project(particle.pos, width, height) ?: return
        val lifePct = (particle.life / particle.maxLife).coerceIn(0f, 1f)
        val radius = particle.radius * (proj.scale / 20f) * (0.4f + lifePct * 0.6f)
        scope.drawCircle(particle.color.copy(alpha = lifePct), radius.coerceAtLeast(1f), proj.screenPos)
    }

    private fun drawTargetLockReticle(scope: DrawScope, camera: Camera3D, engine: GameEngine, width: Float, height: Float) {
        val targetId = engine.player.lockedTargetId ?: return
        val targetPos = engine.activeBoss?.pos ?: engine.enemies.find { it.id == targetId }?.pos ?: return
        val proj = camera.project(targetPos, width, height) ?: return

        val s = proj.scale
        val cx = proj.screenPos.x
        val cy = proj.screenPos.y
        val rSize = 38f * (s / 20f)

        val lockColor = if (engine.player.lockProgress >= 0.9f) Color(0xFFFF1744) else Color(0xFFFF9100)
        // 4 corner brackets
        val bLen = 8f * (s / 20f)
        scope.drawLine(lockColor, Offset(cx - rSize, cy - rSize), Offset(cx - rSize + bLen, cy - rSize), 2.5f)
        scope.drawLine(lockColor, Offset(cx - rSize, cy - rSize), Offset(cx - rSize, cy - rSize + bLen), 2.5f)

        scope.drawLine(lockColor, Offset(cx + rSize, cy - rSize), Offset(cx + rSize - bLen, cy - rSize), 2.5f)
        scope.drawLine(lockColor, Offset(cx + rSize, cy - rSize), Offset(cx + rSize, cy - rSize + bLen), 2.5f)

        scope.drawLine(lockColor, Offset(cx - rSize, cy + rSize), Offset(cx - rSize + bLen, cy + rSize), 2.5f)
        scope.drawLine(lockColor, Offset(cx - rSize, cy + rSize), Offset(cx - rSize, cy + rSize - bLen), 2.5f)

        scope.drawLine(lockColor, Offset(cx + rSize, cy + rSize), Offset(cx + rSize - bLen, cy + rSize), 2.5f)
        scope.drawLine(lockColor, Offset(cx + rSize, cy + rSize), Offset(cx + rSize, cy + rSize - bLen), 2.5f)
    }
}
