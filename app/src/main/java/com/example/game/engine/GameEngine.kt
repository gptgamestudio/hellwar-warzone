package com.example.game.engine

import androidx.compose.ui.graphics.Color
import com.example.game.audio.CombatAudioSystem
import com.example.game.model.EmpireType
import com.example.game.network.BarracksDestructionResult
import com.example.game.network.WarNetCampaignService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class GameHudState(
    val health: Float = 100f,
    val maxHealth: Float = 100f,
    val armor: Float = 60f,
    val maxArmor: Float = 60f,
    val shield: Float = 40f,
    val maxShield: Float = 40f,
    val score: Int = 0,
    val kills: Int = 0,
    val missionProgressPercent: Float = 0f,

    // Weapons
    val rocketAmmo: Int = 16,
    val maxRocketAmmo: Int = 16,
    val rocketCooldownPercent: Float = 1.0f,
    val isRocketReady: Boolean = true,

    val missileAmmo: Int = 8,
    val maxMissileAmmo: Int = 8,
    val missileReadyPercent: Float = 1.0f,
    val isMissileReady: Boolean = true,
    val lockedTargetName: String? = null,

    val isAutoFiring: Boolean = true,
    val activeThreat: ThreatAlert? = null,
    val isGameOver: Boolean = false,
    val isVictory: Boolean = false,
    val activeEmpire: EmpireType = EmpireType.DRAGON,

    // Fleet Info
    val helicopterClass: com.example.game.model.HelicopterClass = com.example.game.model.HelicopterClass.SCOUT,

    // Boss State
    val bossEntity: BossCombatEntity? = null,
    val creditsEarned: Int = 0,

    // Open-World 50 Barracks & Headquarters Mission Progression
    val barracksDestroyedCount: Int = 0,
    val totalBarracks: Int = 50,
    val isHeadquartersUnlocked: Boolean = false,
    val headquartersPos: Vector3D? = null
)

class GameEngine(
    var selectedEmpire: EmpireType = EmpireType.DRAGON,
    val audioSystem: CombatAudioSystem? = null,
    val initialHeloClass: com.example.game.model.HelicopterClass = com.example.game.model.HelicopterClass.SCOUT,
    val initialSkin: com.example.game.model.HelicopterSkin = com.example.game.model.HelicopterSkin.TACTICAL_BLACK,
    val initialUpgrades: com.example.game.model.HelicopterUpgrades = com.example.game.model.HelicopterUpgrades(),
    val campaignService: WarNetCampaignService? = null
) {
    val player = PlayerHelo()
    val camera = Camera3D()

    // 4 Independent directional input states (held = true)
    var isUpPressed: Boolean = false
    var isDownPressed: Boolean = false
    var isLeftPressed: Boolean = false
    var isRightPressed: Boolean = false

    // Entities in active combat
    val bullets = mutableListOf<TracerBullet>()
    val rockets = mutableListOf<RocketCluster>()
    val missiles = mutableListOf<GuidedMissile>()
    val enemies = mutableListOf<EnemyTarget>()
    val particles = mutableListOf<VisualParticle>()
    val hazards = mutableListOf<EnvironmentHazard>()
    val structures = mutableListOf<TerrainStructure>()
    var activeBoss: BossCombatEntity? = null
    var furthestGeneratedY: Float = 0f

    // 50 Enemy Barracks Objectives & Headquarters (Open-World Mission Architecture)
    val allBarracks = mutableListOf<EnemyBarracks>()
    var enemyHeadquarters: EnemyHeadquarters? = null
    var barracksDestroyedCount: Int = 0

    // Game stats
    var score: Int = 0
    var kills: Int = 0
    var creditsEarned: Int = 0
    var missionProgress: Float = 0f
    var isGameOver: Boolean = false
    var isVictory: Boolean = false
    var threatAlert: ThreatAlert? = null

    // Open-world expansive boundaries (Free-flight exploration)
    val minX: Float = -240.0f
    val maxX: Float = 240.0f
    val minY: Float = -20.0f
    val maxY: Float = 1080.0f

    // Flight physics constants
    private val acceleration: Float = 72.0f
    private val deceleration: Float = 58.0f
    private val maxBankAngleRad: Float = Math.toRadians(22.0).toFloat()
    private val maxPitchAngleRad: Float = Math.toRadians(10.0).toFloat()

    // Wave and spawner timers
    private var enemySpawnTimer: Float = 1.0f
    private var gameTime: Float = 0f
    var bossSpawned: Boolean = false
    private var waveNumber: Int = 1

    private val _hudState = MutableStateFlow(GameHudState())
    val hudState: StateFlow<GameHudState> = _hudState.asStateFlow()

    init {
        player.applyConfiguration(initialHeloClass, initialSkin, initialUpgrades)
        resetBattle()
    }

    fun resetBattle() {
        player.applyConfiguration(player.heloClass, player.skin, player.upgrades)
        player.pos = Vector3D(0f, 0f, 9.5f)
        player.velocity = Vector3D(0f, 0f, 0f)
        player.health = player.maxHealth
        player.armor = player.maxArmor
        player.shield = player.maxShield
        player.rocketAmmo = player.maxRocketAmmo
        player.missileAmmo = player.maxMissileAmmo
        player.rocketCooldownTimer = 0f
        player.missileTimer = 0f
        player.rollAngle = 0f
        player.pitchAngle = 0f
        player.lockedTargetId = null

        isUpPressed = false
        isDownPressed = false
        isLeftPressed = false
        isRightPressed = false

        bullets.clear()
        rockets.clear()
        missiles.clear()
        enemies.clear()
        particles.clear()
        hazards.clear()
        structures.clear()
        activeBoss = null
        furthestGeneratedY = 0f

        camera.x = 0f
        camera.y = -12.5f
        camera.z = 27.5f
        camera.screenShake = 0f

        score = 0
        kills = 0
        creditsEarned = 0
        missionProgress = 0f
        gameTime = 0f
        waveNumber = 1
        bossSpawned = false
        isGameOver = false
        isVictory = false
        threatAlert = ThreatAlert("SORTIE COMMENCED: AIRSPACE CONTESTED", selectedEmpire.primaryColor, 3.5f)

        // Generate seamless initial environment
        generateInitialEnvironment()

        // Spawn initial combat squadron
        spawnInitialSquadron()
        updateHud()
    }

    private fun spawnInitialSquadron() {
        spawnEnemy(EnemyCategory.ATTACK_HELICOPTER, -5f, 22f)
        spawnEnemy(EnemyCategory.GROUND_TANK, 4f, 26f)
        spawnEnemy(EnemyCategory.AA_TURRET, 0f, 32f)
    }

    fun update(delta: Float) {
        if (isGameOver) return

        gameTime += delta
        missionProgress = (gameTime / 100.0f).coerceIn(0f, 1f)

        // Boss trigger at 40% mission progress
        if (missionProgress >= 0.40f && !bossSpawned && activeBoss == null) {
            spawnEmpireBoss()
        }

        // 1. Update 4-Directional Player Movement
        updatePlayerMovement(delta)

        // 2. Camera tracking with velocity look-ahead
        camera.updateFollow(player.pos.x, player.pos.y, delta, player.velocity.x, player.velocity.y)

        // 3. Rotor spin animations
        player.mainRotorAngle = (player.mainRotorAngle + 38.0f * delta) % (kotlin.math.PI.toFloat() * 2f)
        player.tailRotorAngle = (player.tailRotorAngle + 48.0f * delta) % (kotlin.math.PI.toFloat() * 2f)

        // 4. Weapons processing (Machine guns, Rockets, Missiles)
        updateWeapons(delta)

        // 5. Update Target-Lock system
        updateTargetLock(delta)

        // 6. Progressive Endless Environment & Hazards
        updateEnvironment(delta)

        // 7. Enemy AI & Spawner
        updateEnemies(delta)

        // 8. Boss AI
        updateBoss(delta)

        // 9. Projectile Physics
        updateProjectiles(delta)

        // 10. Collision Detection
        checkCollisions()

        // 10. Particle lifecycles
        updateParticles(delta)

        // 11. Threat alerts
        threatAlert?.let {
            it.timer -= delta
            if (it.timer <= 0f) {
                threatAlert = null
            }
        }

        updateHud()
    }

    /**
     * Precision 4-Directional Movement (Preserved from Phase 1):
     * - Left and Right NEVER rotate the helicopter heading.
     * - Nose strictly points towards top of screen (+Y).
     * - Independent touch controls with smooth acceleration, deceleration, and diagonal flight.
     * - Battlefield boundary enforcement.
     */
    private fun updatePlayerMovement(delta: Float) {
        var inputX = 0f
        var inputY = 0f

        if (isLeftPressed) inputX -= 1f
        if (isRightPressed) inputX += 1f
        if (isUpPressed) inputY += 1f
        if (isDownPressed) inputY -= 1f

        val inputLen = kotlin.math.sqrt(inputX * inputX + inputY * inputY)
        val dirX = if (inputLen > 0.001f) (inputX / inputLen) else 0f
        val dirY = if (inputLen > 0.001f) (inputY / inputLen) else 0f

        val effectiveMaxSpeed = player.flightSpeed
        val targetVelX = dirX * effectiveMaxSpeed
        // Hover cleanly at 0f when directional buttons are released; responsive forward & reverse flight when held
        val targetVelY = if (dirY > 0f) {
            dirY * effectiveMaxSpeed
        } else if (dirY < 0f) {
            dirY * (effectiveMaxSpeed * 0.92f)
        } else {
            0f
        }

        // Acceleration / Deceleration on X
        if (dirX != 0f) {
            player.velocity.x += (targetVelX - player.velocity.x).coerceIn(-acceleration * delta, acceleration * delta)
        } else {
            val frictionX = deceleration * delta
            if (kotlin.math.abs(player.velocity.x) <= frictionX) {
                player.velocity.x = 0f
            } else {
                player.velocity.x -= kotlin.math.sign(player.velocity.x) * frictionX
            }
        }

        // Acceleration / Deceleration on Y (Forward, Backward, & Clean Hover Stop)
        if (dirY != 0f) {
            val accelY = if (dirY < 0f) acceleration * 1.25f else acceleration
            player.velocity.y += (targetVelY - player.velocity.y).coerceIn(-accelY * delta, accelY * delta)
        } else {
            val frictionY = deceleration * delta
            if (kotlin.math.abs(player.velocity.y) <= frictionY) {
                player.velocity.y = 0f
            } else {
                player.velocity.y -= kotlin.math.sign(player.velocity.y) * frictionY
            }
        }

        player.pos.x += player.velocity.x * delta
        player.pos.y += player.velocity.y * delta

        // Open-world boundary clamping (never tethered to lagged camera position)
        player.pos.x = player.pos.x.coerceIn(minX, maxX)
        player.pos.y = player.pos.y.coerceIn(minY, maxY)

        // Banking roll on strafe (left input banks left wing down -> positive rollAngle)
        val targetRoll = -dirX * maxBankAngleRad
        player.rollAngle += (targetRoll - player.rollAngle) * (1f - kotlin.math.exp(-12.0f * delta))

        // Pitch tilt on forward/backward flight
        val targetPitch = dirY * maxPitchAngleRad
        player.pitchAngle += (targetPitch - player.pitchAngle) * (1f - kotlin.math.exp(-10.0f * delta))
    }

    private fun updateWeapons(delta: Float) {
        // 1. Automatic Machine Gun
        if (player.isAutoFiring) {
            player.mgTimer += delta
            if (player.mgTimer >= player.mgFireRate) {
                player.mgTimer = 0f
                player.activeMuzzleFlash = true

                val leftMuzzle = Vector3D(player.pos.x - 1.1f, player.pos.y + 1.8f, player.pos.z - 0.2f)
                val rightMuzzle = Vector3D(player.pos.x + 1.1f, player.pos.y + 1.8f, player.pos.z - 0.2f)
                val bulletSpeed = 75.0f

                val dmg = player.mgDamage
                bullets.add(TracerBullet(leftMuzzle, Vector3D(0f, bulletSpeed, 0f), damage = dmg, isPlayer = true))
                bullets.add(TracerBullet(rightMuzzle, Vector3D(0f, bulletSpeed, 0f), damage = dmg, isPlayer = true))
                audioSystem?.playMachineGun()
            } else {
                player.activeMuzzleFlash = false
            }
        }

        // 2. Shield Regeneration: Recharges slowly when not under immediate fire
        if (player.shield < player.maxShield && player.health > 0f) {
            val rechargeRate = 6.0f + (player.upgrades.defenseLevel * 2.0f)
            player.shield = kotlin.math.min(player.maxShield, player.shield + delta * rechargeRate)
        }

        // 3. Rocket Cooldown
        if (player.rocketCooldownTimer > 0f) {
            player.rocketCooldownTimer -= delta
            if (player.rocketCooldownTimer < 0f) player.rocketCooldownTimer = 0f
        }

        // 4. Guided Missile Cooldown
        if (player.missileTimer > 0f) {
            player.missileTimer -= delta
            if (player.missileTimer < 0f) player.missileTimer = 0f
        }

        // Ammo replenishment over time
        if (player.rocketAmmo < player.maxRocketAmmo && player.rocketCooldownTimer <= 0f && Math.random() < 0.005) {
            player.rocketAmmo = (player.rocketAmmo + 4).coerceAtMost(player.maxRocketAmmo)
        }
        if (player.missileAmmo < player.maxMissileAmmo && player.missileTimer <= 0f && Math.random() < 0.003) {
            player.missileAmmo = (player.missileAmmo + 2).coerceAtMost(player.maxMissileAmmo)
        }
    }

    /**
     * Target-Lock Guided Missile Acquisition System
     */
    private fun updateTargetLock(delta: Float) {
        // Find best target in forward cone (boss or closest high-priority enemy) within radar range
        val maxDist = player.radarRange
        val candidate = activeBoss ?: enemies.filter { it.pos.y > player.pos.y && (it.pos - player.pos).length() <= maxDist }.minByOrNull { (it.pos - player.pos).length() }

        if (candidate != null) {
            val candidateId = if (candidate is BossCombatEntity) candidate.id else (candidate as EnemyTarget).id
            if (player.lockedTargetId == candidateId) {
                val lockSpeed = 2.0f + (player.upgrades.radarLevel * 0.5f)
                player.lockProgress = (player.lockProgress + delta * lockSpeed).coerceAtMost(1.0f)
                if (player.lockProgress >= 1.0f && Math.random() < 0.05) {
                    audioSystem?.playTargetLockBeep()
                }
            } else {
                player.lockedTargetId = candidateId
                player.lockProgress = 0.2f
            }
        } else {
            player.lockedTargetId = null
            player.lockProgress = 0f
        }
    }

    /**
     * Multiple Rocket Launcher (MRL): Salvo of 4 cluster rockets with fan spread
     */
    fun launchRocketBarrage() {
        if (player.rocketCooldownTimer > 0f || player.rocketAmmo < 4 || isGameOver) return

        player.rocketAmmo -= 4
        player.rocketCooldownTimer = player.rocketCooldownMax
        camera.screenShake = 2.0f
        audioSystem?.playRocketLaunch()

        val spreads = listOf(-3.0f, -1.0f, 1.0f, 3.0f)
        for (spread in spreads) {
            val podOffset = Vector3D(player.pos.x + (spread * 0.35f), player.pos.y + 1.2f, player.pos.z - 0.2f)
            val rocket = RocketCluster(
                id = UUID.randomUUID().toString(),
                pos = podOffset,
                velocity = Vector3D(spread * 1.5f, 26.0f, 0f),
                damage = player.rocketDamage,
                splashRadius = 4.5f
            )
            rockets.add(rocket)
            createExplosion(podOffset, Color(0xFFFF9100), count = 4, speed = 3f)
        }
    }

    /**
     * Target-Lock Guided Missiles: Launches dual homing Hellfire missiles
     */
    fun launchMissiles() {
        if (player.missileTimer > 0f || player.missileAmmo < 2 || isGameOver) return

        player.missileAmmo -= 2
        player.missileTimer = player.missileCooldown
        camera.screenShake = 3.0f
        audioSystem?.playMissileLaunch()

        val targetId = player.lockedTargetId

        val leftPod = Vector3D(player.pos.x - 1.8f, player.pos.y + 1.0f, player.pos.z - 0.3f)
        val rightPod = Vector3D(player.pos.x + 1.8f, player.pos.y + 1.0f, player.pos.z - 0.3f)

        val dmg = player.missileDamage
        missiles.add(GuidedMissile(UUID.randomUUID().toString(), leftPod, Vector3D(-2.5f, 22.0f, 0f), targetId, damage = dmg))
        missiles.add(GuidedMissile(UUID.randomUUID().toString(), rightPod, Vector3D(2.5f, 22.0f, 0f), targetId, damage = dmg))

        createExplosion(leftPod, Color(0xFFFF6D00), count = 8, speed = 5f)
        createExplosion(rightPod, Color(0xFFFF6D00), count = 8, speed = 5f)
    }

    /**
     * AI implementation for the 5 Enemy Types
     */
    private fun updateEnemies(delta: Float) {
        // Progressive spawn with wave scaling (capped for mobile stability)
        if (enemies.size < 7 && activeBoss == null) {
            enemySpawnTimer -= delta
            if (enemySpawnTimer <= 0f) {
                enemySpawnTimer = (2.4f - (missionProgress * 1.0f)).coerceAtLeast(0.9f)
                spawnProgressiveEnemy()
            }
        }

        val it = enemies.iterator()
        while (it.hasNext()) {
            val enemy = it.next()
            enemy.strafePhase += delta

            val dx = player.pos.x - enemy.pos.x
            val dy = player.pos.y - enemy.pos.y
            val distToPlayer = kotlin.math.sqrt(dx * dx + dy * dy)

            when (enemy.category) {
                EnemyCategory.ATTACK_HELICOPTER -> {
                    // Circle-strafing gunship holding tactical combat distance ahead of player
                    enemy.rotorAngle = (enemy.rotorAngle + 35.0f * delta) % (kotlin.math.PI.toFloat() * 2f)
                    enemy.turretAngle = atan2(dx, dy)
                    val desiredY = player.pos.y + 18.0f
                    val yDiff = desiredY - enemy.pos.y
                    val moveY = yDiff.coerceIn(-7.5f, 7.5f)
                    enemy.pos.y += moveY * delta
                    enemy.pos.x = (enemy.pos.x + sin(enemy.strafePhase * 2.2f) * 7.0f * delta).coerceIn(minX + 4f, maxX - 4f)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 1.4f && distToPlayer < 45f && enemy.pos.y > player.pos.y - 6f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = enemy.pos.z - 0.2f), aim * 28.0f, damage = 12f, isPlayer = false))
                        if (Math.random() < 0.4) {
                            bullets.add(TracerBullet(Vector3D(enemy.pos.x - 0.8f, enemy.pos.y, enemy.pos.z), aim * 24.0f, damage = 18f, isPlayer = false))
                            bullets.add(TracerBullet(Vector3D(enemy.pos.x + 0.8f, enemy.pos.y, enemy.pos.z), aim * 24.0f, damage = 18f, isPlayer = false))
                        }
                    }
                }

                EnemyCategory.FIGHTER_AIRCRAFT -> {
                    // High-speed supersonic interceptor flyby with subtle sweeping bank
                    enemy.pos.y += enemy.velocity.y * delta
                    enemy.pos.x = (enemy.pos.x + (enemy.velocity.x + cos(enemy.strafePhase * 3.0f) * 3.5f) * delta).coerceIn(minX, maxX)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 0.25f && distToPlayer < 50f && enemy.pos.y > player.pos.y) {
                        enemy.attackTimer = 0f
                        bullets.add(TracerBullet(enemy.pos.copy(), Vector3D(0f, -40.0f, 0f), damage = 15f, isPlayer = false))
                    }
                }

                EnemyCategory.GROUND_TANK -> {
                    // Heavy battle tank patrolling tactical lane, holding firing range when engaged
                    val patrolSpeedY = if (dy < -24f) -2.8f else if (dy > -12f) 1.4f else 0f
                    enemy.pos.y = (enemy.pos.y + patrolSpeedY * delta).coerceIn(minY, maxY)
                    enemy.pos.x = (enemy.pos.x + sin(enemy.strafePhase * 0.9f) * 2.2f * delta).coerceIn(minX + 2f, maxX - 2f)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.2f && distToPlayer < 45f && enemy.pos.y > player.pos.y - 8f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = 0.8f), aim * 32.0f, damage = 25f, isPlayer = false, isTankShell = true))
                        createExplosion(enemy.pos.copy(z = 0.8f), Color(0xFFFF9100), count = 6, speed = 4f)
                    }
                }

                EnemyCategory.AA_TURRET -> {
                    // Stationary ground AA fortification rotating barrels toward player
                    enemy.turretAngle = atan2(dx, dy)
                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.0f && distToPlayer < 46f && enemy.pos.y > player.pos.y - 6f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = 1.0f), aim * 30.0f, damage = 22f, isPlayer = false, isFlakBurst = true))
                    }
                }

                EnemyCategory.MISSILE_LAUNCHER -> {
                    // Mobile SAM truck maneuvering slowly into firing range and rotating launcher
                    val samMoveY = if (dy < -28f) -2.2f else 0f
                    enemy.pos.y = (enemy.pos.y + samMoveY * delta).coerceIn(minY, maxY)
                    enemy.pos.x = (enemy.pos.x + cos(enemy.strafePhase * 0.7f) * 1.6f * delta).coerceIn(minX + 2f, maxX - 2f)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 3.2f && distToPlayer < 52f && enemy.pos.y > player.pos.y - 10f) {
                        enemy.attackTimer = 0f
                        val missile = GuidedMissile(
                            id = UUID.randomUUID().toString(),
                            pos = enemy.pos.copy(z = 1.2f),
                            velocity = Vector3D(0f, -14.0f, 4.0f),
                            targetId = "PLAYER",
                            damage = 38f,
                            isEnemy = true
                        )
                        missiles.add(missile)
                        createExplosion(enemy.pos.copy(z = 1.2f), Color(0xFFFF3D00), count = 10, speed = 5f)
                    }
                }

                EnemyCategory.INFANTRY_SOLDIER -> {
                    // Ground infantry soldier actively patrolling & flanking around outpost while aiming at player
                    enemy.pos.x = (enemy.pos.x + cos(enemy.strafePhase * 1.9f) * 2.4f * delta).coerceIn(minX + 1f, maxX - 1f)
                    enemy.pos.y = (enemy.pos.y + sin(enemy.strafePhase * 1.2f) * 1.1f * delta).coerceIn(minY, maxY)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 1.6f && distToPlayer < 40f && enemy.pos.y > player.pos.y - 10f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = 0.5f), aim * 24.0f, damage = 8f, isPlayer = false))
                    }
                }

                EnemyCategory.ROCKET_SOLDIER -> {
                    // RPG trooper repositioning tactically and tracking player helicopter
                    enemy.pos.x = (enemy.pos.x + sin(enemy.strafePhase * 1.6f) * 2.0f * delta).coerceIn(minX + 1f, maxX - 1f)
                    enemy.pos.y = (enemy.pos.y + cos(enemy.strafePhase * 1.0f) * 0.9f * delta).coerceIn(minY, maxY)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.8f && distToPlayer < 42f && enemy.pos.y > player.pos.y - 12f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = 0.8f), aim * 20.0f, damage = 25f, isPlayer = false, isFlakBurst = true))
                    }
                }

                EnemyCategory.WATCHTOWER_SNIPER -> {
                    // Elevated sentry aiming high-caliber rounds toward player helicopter
                    enemy.turretAngle = atan2(dx, dy)
                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.2f && distToPlayer < 46f && enemy.pos.y > player.pos.y - 8f) {
                        enemy.attackTimer = 0f
                        val aim = (player.pos - enemy.pos).normalized()
                        bullets.add(TracerBullet(enemy.pos.copy(z = 4.0f), aim * 32.0f, damage = 18f, isPlayer = false))
                    }
                }
            }

            if (enemy.pos.y < camera.y - 16.0f && enemy.category == EnemyCategory.FIGHTER_AIRCRAFT) {
                it.remove()
            } else if (enemy.pos.y < camera.y - 24.0f) {
                it.remove()
            }
        }
    }

    private fun spawnProgressiveEnemy() {
        val spawnX = (player.pos.x + ((Math.random().toFloat() - 0.5f) * 44f)).coerceIn(minX + 4f, maxX - 4f)
        val spawnY = (player.pos.y + 34.0f + (Math.random().toFloat() * 10f)).coerceAtMost(maxY - 20f)

        val categories = EnemyCategory.values()
        val category = categories.random()
        spawnEnemy(category, spawnX, spawnY)
    }

    private fun spawnEnemy(category: EnemyCategory, x: Float, y: Float) {
        val hpMultiplier = 1.0f + (missionProgress * 0.5f)
        val isGround = category != EnemyCategory.ATTACK_HELICOPTER && category != EnemyCategory.FIGHTER_AIRCRAFT
        val z = if (isGround) 0.1f else 9.5f
        val velY = when (category) {
            EnemyCategory.FIGHTER_AIRCRAFT -> -26.0f
            EnemyCategory.ATTACK_HELICOPTER -> -4.5f
            EnemyCategory.GROUND_TANK -> -1.5f
            EnemyCategory.AA_TURRET -> 0f
            EnemyCategory.MISSILE_LAUNCHER -> -1.2f
            EnemyCategory.INFANTRY_SOLDIER -> 0f
            EnemyCategory.ROCKET_SOLDIER -> 0f
            EnemyCategory.WATCHTOWER_SNIPER -> 0f
        }

        enemies.add(
            EnemyTarget(
                id = UUID.randomUUID().toString(),
                empire = selectedEmpire,
                category = category,
                pos = Vector3D(x, y, z),
                velocity = Vector3D(0f, velY, 0f),
                health = category.baseHp * hpMultiplier,
                maxHealth = category.baseHp * hpMultiplier,
                scoreValue = category.scoreVal,
                unitName = "${selectedEmpire.name} ${category.label}",
                isGroundUnit = isGround
            )
        )
    }

    /**
     * Boss System: 4 Unique Empire Bosses with Weak Points, Destructible Subsystems, and 3 Combat Phases
     */
    fun spawnEmpireBoss() {
        bossSpawned = true
        val bossName = when (selectedEmpire) {
            EmpireType.DRAGON -> "Ignis Rex: Dragon Emperor"
            EmpireType.ALIEN -> "Overseer Prime: Alien Mothership"
            EmpireType.ORC -> "Warlord Gashrak: Ironclad Sky Fortress"
            EmpireType.HUMAN -> "Project Titan: Supreme Gunship"
        }

        val bossHp = when (selectedEmpire) {
            EmpireType.DRAGON -> 1800f
            EmpireType.ALIEN -> 2200f
            EmpireType.ORC -> 2400f
            EmpireType.HUMAN -> 2600f
        }

        val bossShield = when (selectedEmpire) {
            EmpireType.DRAGON -> 200f
            EmpireType.ALIEN -> 650f
            EmpireType.ORC -> 0f
            EmpireType.HUMAN -> 500f
        }

        val weakPoint = when (selectedEmpire) {
            EmpireType.DRAGON -> BossWeakPoint("Dragon Heart Core", Vector3D(0f, -0.6f, 0.2f), radius = 2.4f, damageMultiplier = 2.2f)
            EmpireType.ORC -> BossWeakPoint("Boiler Exhaust Vent", Vector3D(0f, 2.8f, 0.5f), radius = 2.2f, damageMultiplier = 2.5f)
            else -> null
        }

        val subsystems = mutableListOf<BossSubsystem>()
        when (selectedEmpire) {
            EmpireType.ALIEN -> {
                subsystems.add(BossSubsystem("A_L_TURRET", "PORT PLASMA CANNON", 90f, 90f, offset = Vector3D(-4.2f, 0f, 0f)))
                subsystems.add(BossSubsystem("A_R_TURRET", "STARBOARD PLASMA CANNON", 90f, 90f, offset = Vector3D(4.2f, 0f, 0f)))
            }
            EmpireType.HUMAN -> {
                subsystems.add(BossSubsystem("H_GATLING", "30MM ROTARY VULCAN", 110f, 110f, offset = Vector3D(0f, -3.2f, -0.5f)))
                subsystems.add(BossSubsystem("H_L_MISSILE", "PORT STINGER POD", 85f, 85f, offset = Vector3D(-3.8f, 1.0f, 0f)))
                subsystems.add(BossSubsystem("H_R_MISSILE", "STARBOARD STINGER POD", 85f, 85f, offset = Vector3D(3.8f, 1.0f, 0f)))
            }
            else -> {}
        }

        activeBoss = BossCombatEntity(
            id = UUID.randomUUID().toString(),
            empire = selectedEmpire,
            name = bossName,
            pos = Vector3D(0f, player.pos.y + 34.0f, 10.5f),
            health = bossHp,
            maxHealth = bossHp,
            shield = bossShield,
            maxShield = bossShield,
            phase = 1,
            weakPoint = weakPoint,
            subsystems = subsystems
        )
        audioSystem?.playBossWarning()
        threatAlert = ThreatAlert("BOSS WARNING: $bossName ENGAGING AIRSPACE", Color(0xFFFF1744), 6.0f)
        camera.screenShake = 4.5f
    }

    private fun updateBoss(delta: Float) {
        val boss = activeBoss ?: return
        boss.animationPhase += delta * 2.2f * boss.enrageFactor

        // Check and update phase transitions continuously based on HP percentage
        val hpPct = boss.health / boss.maxHealth
        if (hpPct <= 0.33f && boss.phase < 3) {
            boss.phase = 3
            boss.enrageFactor = 1.5f
            boss.weakPoint?.isExposed = true
            threatAlert = ThreatAlert("BOSS WARNING: ${boss.name} ENTERED PHASE 3 ENRAGED", Color(0xFFFF1744), 5.0f)
        } else if (hpPct <= 0.66f && boss.phase < 2) {
            boss.phase = 2
            boss.enrageFactor = 1.25f
            threatAlert = ThreatAlert("BOSS WARNING: ${boss.name} ENTERED PHASE 2", Color(0xFFFF9100), 4.0f)
        }

        // Base hover tracking
        val targetY = player.pos.y + 22.0f
        boss.pos.y += (targetY - boss.pos.y) * 1.5f * delta
        boss.pos.x = sin(boss.animationPhase * 0.8f * boss.enrageFactor) * 8.5f

        // Handle Active Attack State Machines
        when (boss.currentAttackMode) {
            BossAttackMode.AERIAL_CHARGE -> {
                boss.modeTimer += delta
                val diveT = (boss.modeTimer / 1.2f).coerceIn(0f, 1f)
                val diveY = sin(diveT * kotlin.math.PI.toFloat()) * 18.0f
                boss.chargeOffset = Vector3D(0f, -diveY, -diveY * 0.25f)
                val currentPos = boss.pos + boss.chargeOffset
                if ((currentPos - player.pos).length() < 3.8f) {
                    player.takeDamage(40f * delta * 4f)
                    camera.screenShake = 3.5f
                    createExplosion(player.pos, Color(0xFFFF3D00), count = 4, speed = 5f)
                }
                if (boss.modeTimer >= 1.2f) {
                    boss.currentAttackMode = BossAttackMode.CRUISING
                    boss.chargeOffset = Vector3D(0f, 0f, 0f)
                }
            }
            BossAttackMode.RAMMING_SURGE -> {
                boss.modeTimer += delta
                val surgeT = (boss.modeTimer / 1.3f).coerceIn(0f, 1f)
                val surgeY = sin(surgeT * kotlin.math.PI.toFloat()) * 16.0f
                boss.chargeOffset = Vector3D(0f, -surgeY, 0f)
                val currentPos = boss.pos + boss.chargeOffset
                if ((currentPos - player.pos).length() < 4.2f) {
                    player.takeDamage(45f * delta * 4f)
                    camera.screenShake = 4.0f
                }
                if (boss.modeTimer >= 1.3f) {
                    boss.currentAttackMode = BossAttackMode.CRUISING
                    boss.chargeOffset = Vector3D(0f, 0f, 0f)
                }
            }
            BossAttackMode.FIRE_BREATH -> {
                boss.modeTimer += delta
                val aim = (player.pos - (boss.pos + boss.chargeOffset)).normalized()
                bullets.add(TracerBullet(boss.pos.copy(y = boss.pos.y - 1.5f), aim * 34f, damage = 14f, isPlayer = false, isFlakBurst = true))
                createExplosion(boss.pos.copy(y = boss.pos.y - 1.5f), Color(0xFFFF5722), count = 3, speed = 6f)
                if (boss.modeTimer >= 1.5f) {
                    boss.currentAttackMode = BossAttackMode.CRUISING
                }
            }
            else -> {}
        }

        boss.attackTimer += delta * boss.enrageFactor

        // Boss Attack Dispatcher
        if (boss.attackTimer >= (2.2f / boss.enrageFactor)) {
            boss.attackTimer = 0f
            val aim = (player.pos - (boss.pos + boss.chargeOffset)).normalized()
            val roll = Math.random()

            when (boss.empire) {
                EmpireType.DRAGON -> {
                    when {
                        boss.phase == 1 -> {
                            if (roll < 0.6) {
                                boss.currentAttackMode = BossAttackMode.FIREBALL_SALVO
                                for (offset in listOf(-2.8f, 0f, 2.8f)) {
                                    val fireDir = Vector3D(aim.x + (offset * 0.08f), aim.y, aim.z).normalized()
                                    bullets.add(TracerBullet(boss.pos.copy(z = 10f), fireDir * 28.0f, damage = 18f, isPlayer = false))
                                }
                            } else {
                                boss.currentAttackMode = BossAttackMode.WING_SHOCKWAVE
                                bullets.add(TracerBullet(boss.pos.copy(), Vector3D(-24f, -12f, 0f), damage = 16f, isPlayer = false, isFlakBurst = true))
                                bullets.add(TracerBullet(boss.pos.copy(), Vector3D(24f, -12f, 0f), damage = 16f, isPlayer = false, isFlakBurst = true))
                                camera.screenShake = 2.0f
                            }
                        }
                        boss.phase == 2 -> {
                            when {
                                roll < 0.35 -> {
                                    boss.currentAttackMode = BossAttackMode.FIRE_BREATH
                                    boss.modeTimer = 0f
                                    audioSystem?.playDragonRoar()
                                }
                                roll < 0.70 -> {
                                    boss.currentAttackMode = BossAttackMode.AERIAL_CHARGE
                                    boss.modeTimer = 0f
                                    audioSystem?.playDragonRoar()
                                    threatAlert = ThreatAlert("DRAGON AERIAL CHARGE DETECTED", Color(0xFFFF3D00), 1.5f)
                                }
                                else -> {
                                    boss.currentAttackMode = BossAttackMode.FIREBALL_SALVO
                                    for (i in -2..2) {
                                        val fireDir = Vector3D(aim.x + (i * 0.12f), aim.y, aim.z).normalized()
                                        bullets.add(TracerBullet(boss.pos.copy(z = 10f), fireDir * 32.0f, damage = 20f, isPlayer = false))
                                    }
                                }
                            }
                        }
                        else -> {
                            // Phase 3: Enraged
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.AERIAL_CHARGE
                                boss.modeTimer = 0f
                                audioSystem?.playDragonRoar()
                                threatAlert = ThreatAlert("ENRAGED DRAGON SWOOP CHARGE!", Color(0xFFFF1744), 1.5f)
                            } else {
                                boss.currentAttackMode = BossAttackMode.FIRE_BREATH
                                boss.modeTimer = 0f
                                audioSystem?.playDragonRoar()
                            }
                            for (i in -3..3) {
                                val fireDir = Vector3D(aim.x + (i * 0.15f), aim.y, aim.z).normalized()
                                bullets.add(TracerBullet(boss.pos.copy(z = 10f), fireDir * 36.0f, damage = 24f, isPlayer = false))
                            }
                        }
                    }
                }

                EmpireType.ALIEN -> {
                    val leftAlive = boss.subsystems.find { it.id == "A_L_TURRET" }?.isDestroyed == false
                    val rightAlive = boss.subsystems.find { it.id == "A_R_TURRET" }?.isDestroyed == false

                    when {
                        boss.phase == 1 -> {
                            boss.currentAttackMode = BossAttackMode.PLASMA_CANNONS
                            audioSystem?.playPlasmaCharge()
                            if (leftAlive) {
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x - 4.2f), aim * 26f, damage = 18f, isPlayer = false))
                            }
                            if (rightAlive) {
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x + 4.2f), aim * 26f, damage = 18f, isPlayer = false))
                            }
                        }
                        boss.phase == 2 -> {
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.SUMMON_UFOS
                                spawnEnemy(EnemyCategory.FIGHTER_AIRCRAFT, boss.pos.x - 3f, boss.pos.y + 4f)
                                spawnEnemy(EnemyCategory.FIGHTER_AIRCRAFT, boss.pos.x + 3f, boss.pos.y + 4f)
                                threatAlert = ThreatAlert("WARNING: ESCORT UFO FIGHTERS DEPLOYED", Color(0xFFD500F9), 2.0f)
                            } else {
                                boss.currentAttackMode = BossAttackMode.HOMING_PLASMA
                                val m1 = GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x - 3f), Vector3D(-2f, -16f, 0f), "PLAYER", 32f, isEnemy = true)
                                val m2 = GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x + 3f), Vector3D(2f, -16f, 0f), "PLAYER", 32f, isEnemy = true)
                                missiles.add(m1)
                                missiles.add(m2)
                            }
                        }
                        else -> {
                            // Phase 3: Antimatter sweep
                            boss.currentAttackMode = BossAttackMode.ANTIMATTER_SWEEP
                            audioSystem?.playPlasmaCharge()
                            for (i in -4..4) {
                                bullets.add(TracerBullet(boss.pos.copy(), Vector3D(aim.x + i * 0.09f, -34f, 0f), damage = 22f, isPlayer = false))
                            }
                            if (leftAlive || rightAlive) {
                                val m = GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(), Vector3D(0f, -18f, 0f), "PLAYER", 35f, isEnemy = true)
                                missiles.add(m)
                            }
                        }
                    }
                }

                EmpireType.ORC -> {
                    when {
                        boss.phase == 1 -> {
                            boss.currentAttackMode = BossAttackMode.SIEGE_CANNONS
                            audioSystem?.playHeavyCannon()
                            bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x - 2.5f), aim * 24f, damage = 35f, isPlayer = false, isTankShell = true))
                            bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x + 2.5f), aim * 24f, damage = 35f, isPlayer = false, isTankShell = true))
                        }
                        boss.phase == 2 -> {
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.FLAK_MINEFIELD
                                for (i in -2..2) {
                                    bullets.add(TracerBullet(boss.pos.copy(), Vector3D(aim.x + i * 0.15f, aim.y * 0.8f, 0f) * 22f, damage = 20f, isPlayer = false, isFlakBurst = true))
                                }
                            } else {
                                boss.currentAttackMode = BossAttackMode.SUMMON_REINFORCEMENTS
                                spawnEnemy(EnemyCategory.ATTACK_HELICOPTER, boss.pos.x - 4f, boss.pos.y + 4f)
                                spawnEnemy(EnemyCategory.ATTACK_HELICOPTER, boss.pos.x + 4f, boss.pos.y + 4f)
                                threatAlert = ThreatAlert("WARNING: ORC REINFORCEMENTS AIRBORNE", Color(0xFFC2410C), 2.0f)
                            }
                        }
                        else -> {
                            // Phase 3: Enraged ramming surge
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.RAMMING_SURGE
                                boss.modeTimer = 0f
                                threatAlert = ThreatAlert("WARNING: SKY FORTRESS RAMMING SURGE!", Color(0xFFFF1744), 1.5f)
                            } else {
                                boss.currentAttackMode = BossAttackMode.SIEGE_CANNONS
                                audioSystem?.playHeavyCannon()
                                for (i in -2..2) {
                                    bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x + i * 1.5f), aim * 28f, damage = 30f, isPlayer = false, isTankShell = true))
                                }
                            }
                        }
                    }
                }

                EmpireType.HUMAN -> {
                    val gatlingAlive = boss.subsystems.find { it.id == "H_GATLING" }?.isDestroyed == false
                    val leftMissileAlive = boss.subsystems.find { it.id == "H_L_MISSILE" }?.isDestroyed == false
                    val rightMissileAlive = boss.subsystems.find { it.id == "H_R_MISSILE" }?.isDestroyed == false

                    when {
                        boss.phase == 1 -> {
                            boss.currentAttackMode = BossAttackMode.GATLING_BARRAGE
                            if (gatlingAlive) {
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x - 1f, y = boss.pos.y - 2.5f), aim * 36f, damage = 16f, isPlayer = false))
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x + 1f, y = boss.pos.y - 2.5f), aim * 36f, damage = 16f, isPlayer = false))
                            }
                        }
                        boss.phase == 2 -> {
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.MISSILE_SALVO
                                if (leftMissileAlive) {
                                    missiles.add(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x - 3.8f), Vector3D(-2f, -16f, 0f), "PLAYER", 35f, isEnemy = true))
                                }
                                if (rightMissileAlive) {
                                    missiles.add(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x + 3.8f), Vector3D(2f, -16f, 0f), "PLAYER", 35f, isEnemy = true))
                                }
                            } else {
                                boss.currentAttackMode = BossAttackMode.JET_AIRSTRIKE_SUPPORT
                                spawnEnemy(EnemyCategory.FIGHTER_AIRCRAFT, -8f, player.pos.y + 40f)
                                spawnEnemy(EnemyCategory.FIGHTER_AIRCRAFT, 8f, player.pos.y + 42f)
                                threatAlert = ThreatAlert("WARNING: STEALTH FIGHTER AIRSTRIKE INCOMING", Color(0xFF0284C7), 2.0f)
                            }
                        }
                        else -> {
                            // Phase 3: Titan Overdrive
                            boss.currentAttackMode = BossAttackMode.TITAN_OVERDRIVE
                            if (gatlingAlive) {
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x - 1f, y = boss.pos.y - 2.5f), aim * 38f, damage = 18f, isPlayer = false))
                                bullets.add(TracerBullet(boss.pos.copy(x = boss.pos.x + 1f, y = boss.pos.y - 2.5f), aim * 38f, damage = 18f, isPlayer = false))
                            }
                            if (leftMissileAlive) {
                                missiles.add(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x - 3.8f), Vector3D(-2.5f, -18f, 0f), "PLAYER", 36f, isEnemy = true))
                            }
                            if (rightMissileAlive) {
                                missiles.add(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x + 3.8f), Vector3D(2.5f, -18f, 0f), "PLAYER", 36f, isEnemy = true))
                            }
                        }
                    }
                }
            }
        }

        // Check Boss Defeat
        if (boss.health <= 0f) {
            triggerBossDestruction(boss)
            activeBoss = null
            isVictory = true
            threatAlert = ThreatAlert("SUPREME VICTORY: ${boss.name} DESTROYED (+5000 CREDITS)", Color(0xFF00E676), 12.0f)
        }
    }

    private fun triggerBossDestruction(boss: BossCombatEntity) {
        kills++
        score += 25000
        creditsEarned += 5000
        camera.screenShake = 6.0f
        audioSystem?.playExplosion()

        // Massive explosion sequence
        for (i in 0 until 12) {
            val offset = Vector3D(
                (Math.random().toFloat() - 0.5f) * 14.0f,
                (Math.random().toFloat() - 0.5f) * 14.0f,
                (Math.random().toFloat() - 0.5f) * 4.0f
            )
            createExplosion(boss.pos + offset, boss.empire.primaryColor, count = 35, speed = 14f)
        }
    }

    fun applyDamageToBoss(damage: Float, hitPos: Vector3D): Boolean {
        val boss = activeBoss ?: return false
        val currentBossPos = boss.pos + boss.chargeOffset

        // 1. Check Destructible Subsystems
        for (subsystem in boss.subsystems) {
            if (!subsystem.isDestroyed) {
                val subPos = currentBossPos + subsystem.offset
                if ((hitPos - subPos).length() < 3.2f) {
                    val wasDestroyed = subsystem.takeDamage(damage)
                    createExplosion(subPos, Color(0xFFFF9100), count = 6, speed = 5f)
                    if (wasDestroyed) {
                        createExplosion(subPos, Color(0xFFFF3D00), count = 30, speed = 12f)
                        score += 3500
                        camera.screenShake = 3.0f
                        audioSystem?.playExplosion()
                        threatAlert = ThreatAlert("SUBSYSTEM DESTROYED: ${subsystem.name}", Color(0xFF00E676), 3.5f)
                    }
                    return true
                }
            }
        }

        // 2. Check Weak Point
        boss.weakPoint?.let { wp ->
            if (wp.isExposed) {
                val wpPos = currentBossPos + wp.offset
                if ((hitPos - wpPos).length() < wp.radius) {
                    boss.takeDamage(damage, isWeakPointHit = true)
                    createExplosion(wpPos, Color(0xFFFFD600), count = 16, speed = 8f)
                    camera.screenShake = 2.4f
                    return true
                }
            }
        }

        // 3. Main Hull Hit
        if ((hitPos - currentBossPos).length() < 7.8f) {
            boss.takeDamage(damage, isWeakPointHit = false)
            createExplosion(hitPos, boss.empire.primaryColor, count = 8, speed = 6f)
            if (boss.health <= 0f) {
                triggerBossDestruction(boss)
                activeBoss = null
                isVictory = true
                threatAlert = ThreatAlert("SUPREME VICTORY: ${boss.name} DESTROYED (+5000 CREDITS)", Color(0xFF00E676), 12.0f)
            }
            return true
        }

        return false
    }

    private fun updateProjectiles(delta: Float) {
        // Bullets
        val bIt = bullets.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            b.pos.x += b.velocity.x * delta
            b.pos.y += b.velocity.y * delta
            b.pos.z += b.velocity.z * delta
            b.life -= delta
            if (b.life <= 0f) {
                if (b.isFlakBurst) {
                    createExplosion(b.pos, Color(0xFFFF9100), count = 10, speed = 6f)
                }
                bIt.remove()
            }
        }

        // Rockets (Multiple Rocket Launcher)
        val rIt = rockets.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            r.life -= delta
            r.smokeTrail.add(r.pos.copy())
            if (r.smokeTrail.size > 6) r.smokeTrail.removeAt(0)

            r.velocity.y += 45.0f * delta
            r.pos.x += r.velocity.x * delta
            r.pos.y += r.velocity.y * delta
            r.pos.z += r.velocity.z * delta

            if (r.life <= 0f) {
                createExplosion(r.pos, Color(0xFFFF6D00), count = 12, speed = 8f)
                rIt.remove()
            }
        }

        // Missiles with target tracking
        val mIt = missiles.iterator()
        while (mIt.hasNext()) {
            val m = mIt.next()
            m.life -= delta
            m.smokeTrail.add(m.pos.copy())
            if (m.smokeTrail.size > 8) m.smokeTrail.removeAt(0)

            val targetPos = if (m.isEnemy) {
                player.pos
            } else {
                activeBoss?.pos ?: enemies.find { it.id == m.targetId }?.pos
            }

            if (targetPos != null) {
                val toTarget = (targetPos - m.pos).normalized()
                val currentDir = m.velocity.normalized()
                val turnRate = 6.0f * delta
                val newDir = Vector3D(
                    currentDir.x + (toTarget.x - currentDir.x) * turnRate,
                    currentDir.y + (toTarget.y - currentDir.y) * turnRate,
                    currentDir.z + (toTarget.z - currentDir.z) * turnRate
                ).normalized()
                val speed = (m.velocity.length() + 38f * delta).coerceAtMost(62f)
                m.velocity = newDir * speed
            } else {
                val speed = (m.velocity.length() + 25f * delta).coerceAtMost(55f)
                m.velocity.y = if (m.isEnemy) -speed else speed
            }

            m.pos.x += m.velocity.x * delta
            m.pos.y += m.velocity.y * delta
            m.pos.z += m.velocity.z * delta

            if (m.life <= 0f) {
                createExplosion(m.pos, Color(0xFFFF3D00), count = 16, speed = 9f)
                mIt.remove()
            }
        }
    }

    private fun checkCollisions() {
        // 1. Player Bullets vs Enemies & Boss
        val bIt = bullets.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            if (!b.isPlayer) continue

            // Boss check
            if (applyDamageToBoss(b.damage, b.pos)) {
                bIt.remove()
                continue
            }

            // Barracks check
            var hitBarracks = false
            for (barracks in allBarracks) {
                if (!barracks.isDestroyed && (b.pos - barracks.pos).length() < 5.0f) {
                    hitBarracks = true
                    damageBarracks(barracks, b.damage)
                    createExplosion(b.pos, Color(0xFFFFD600), count = 5, speed = 6f)
                    bIt.remove()
                    break
                }
            }
            if (hitBarracks) continue

            // Headquarters check
            enemyHeadquarters?.let { hq ->
                if (hq.isUnlocked && !hq.isDestroyed && (b.pos - hq.pos).length() < 9.0f) {
                    hq.health -= b.damage
                    createExplosion(b.pos, Color(0xFFFF9100), count = 6, speed = 7f)
                    bIt.remove()
                    if (hq.health <= 0f) {
                        destroyHeadquarters()
                    }
                    hitBarracks = true
                }
            }
            if (hitBarracks) continue

            for (enemy in enemies) {
                val hitDist = if (enemy.isGroundUnit) 3.0f else 2.4f
                if ((b.pos - enemy.pos).length() < hitDist) {
                    enemy.health -= b.damage
                    createExplosion(b.pos, Color(0xFFFFD600), count = 4, speed = 5f)
                    bIt.remove()
                    break
                }
            }
        }

        // 2. Rockets (MRL) vs Enemies & Boss
        val rIt = rockets.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            var exploded = false

            // Boss check
            if (applyDamageToBoss(r.damage, r.pos)) {
                exploded = true
            }

            if (!exploded) {
                // Barracks splash check
                for (barracks in allBarracks) {
                    if (!barracks.isDestroyed && (r.pos - barracks.pos).length() < r.splashRadius + 2.0f) {
                        damageBarracks(barracks, r.damage)
                        exploded = true
                        break
                    }
                }
            }

            if (!exploded) {
                // HQ splash check
                enemyHeadquarters?.let { hq ->
                    if (hq.isUnlocked && !hq.isDestroyed && (r.pos - hq.pos).length() < r.splashRadius + 5.0f) {
                        hq.health -= r.damage
                        if (hq.health <= 0f) destroyHeadquarters()
                        exploded = true
                    }
                }
            }

            if (!exploded) {
                for (enemy in enemies) {
                    if ((r.pos - enemy.pos).length() < r.splashRadius) {
                        enemy.health -= r.damage
                        exploded = true
                        // Splash to nearby
                        for (other in enemies) {
                            if (other.id != enemy.id && (other.pos - r.pos).length() < r.splashRadius) {
                                other.health -= r.damage * 0.6f
                            }
                        }
                        break
                    }
                }
            }

            if (exploded) {
                createExplosion(r.pos, Color(0xFFFF6D00), count = 16, speed = 10f)
                audioSystem?.playExplosion()
                camera.screenShake = 2.2f
                rIt.remove()
            }
        }

        // 3. Guided Missiles vs Enemies, Boss, Barracks & HQ
        val mIt = missiles.iterator()
        while (mIt.hasNext()) {
            val m = mIt.next()
            var impacted = false

            if (!m.isEnemy) {
                // Player missile vs Boss
                if (applyDamageToBoss(m.damage, m.pos)) {
                    impacted = true
                }

                // Player missile vs Barracks
                if (!impacted) {
                    for (barracks in allBarracks) {
                        if (!barracks.isDestroyed && (m.pos - barracks.pos).length() < 5.5f) {
                            damageBarracks(barracks, m.damage)
                            impacted = true
                            break
                        }
                    }
                }

                // Player missile vs HQ
                if (!impacted) {
                    enemyHeadquarters?.let { hq ->
                        if (hq.isUnlocked && !hq.isDestroyed && (m.pos - hq.pos).length() < 9.5f) {
                            hq.health -= m.damage
                            if (hq.health <= 0f) destroyHeadquarters()
                            impacted = true
                        }
                    }
                }

                // Player missile vs Enemies
                if (!impacted) {
                    for (enemy in enemies) {
                        if ((m.pos - enemy.pos).length() < 3.2f) {
                            enemy.health -= m.damage
                            impacted = true
                            for (other in enemies) {
                                if (other.id != enemy.id && (other.pos - m.pos).length() < 6.5f) {
                                    other.health -= m.damage * 0.5f
                                }
                            }
                            break
                        }
                    }
                }
            } else {
                // Enemy missile vs Player
                if ((m.pos - player.pos).length() < 2.2f) {
                    player.takeDamage(m.damage)
                    impacted = true
                    camera.screenShake = 3.5f
                }
            }

            if (impacted) {
                createExplosion(m.pos, Color(0xFFFF3D00), count = 28, speed = 13f)
                audioSystem?.playExplosion()
                mIt.remove()
            }
        }

        // 4. Enemy Bullets vs Player
        val ebIt = bullets.iterator()
        while (ebIt.hasNext()) {
            val b = ebIt.next()
            if (b.isPlayer) continue

            val hitDist = if (b.isFlakBurst) 3.0f else 1.8f
            if ((b.pos - player.pos).length() < hitDist) {
                player.takeDamage(b.damage)
                camera.screenShake = 2.0f
                createExplosion(b.pos, Color(0xFF00E5FF), count = 6, speed = 5f)
                ebIt.remove()

                if (player.health <= 0f && !isGameOver) {
                    isGameOver = true
                    threatAlert = ThreatAlert("HULL BREACH: HELICOPTER DESTROYED", Color(0xFFFF1744), 10f)
                    createExplosion(player.pos, Color(0xFFFF3D00), count = 45, speed = 16f)
                    audioSystem?.playExplosion()
                }
            }
        }

        // 5. Check Destroyed Enemies
        val eIt = enemies.iterator()
        while (eIt.hasNext()) {
            val enemy = eIt.next()
            if (enemy.health <= 0f) {
                kills++
                score += enemy.scoreValue
                createExplosion(enemy.pos, enemy.empire.primaryColor, count = 22, speed = 10f)
                audioSystem?.playExplosion()
                camera.screenShake = 1.6f
                eIt.remove()
            }
        }
    }

    fun damageBarracks(barracks: EnemyBarracks, damage: Float): Boolean {
        if (barracks.isDestroyed) return false
        barracks.health -= damage
        createExplosion(barracks.pos, Color(0xFFFF9100), count = 5, speed = 6f)
        if (barracks.health <= 0f) {
            barracks.health = 0f
            barracks.isDestroyed = true

            // Enforce persistent online record & anti-duplicate reward checks
            val destructionResult = campaignService?.recordBarracksDestruction(barracks)
                ?: BarracksDestructionResult.FirstTimeDestroyed(barracks.rewardCredits, barracks.scoreReward, barracksDestroyedCount + 1)

            when (destructionResult) {
                is BarracksDestructionResult.FirstTimeDestroyed -> {
                    barracksDestroyedCount = allBarracks.count { it.isDestroyed }
                    score += destructionResult.scoreAwarded
                    creditsEarned += destructionResult.creditsAwarded
                }
                is BarracksDestructionResult.AlreadyDestroyed -> {
                    // Zero duplicate reward if already destroyed in persistent campaign
                    barracksDestroyedCount = allBarracks.count { it.isDestroyed }
                }
            }

            audioSystem?.playExplosion()
            createExplosion(barracks.pos, Color(0xFFFF3D00), count = 26, speed = 12f)
            camera.screenShake = 2.5f

            val isHqUnlocked = barracksDestroyedCount >= 50 || (campaignService?.isHeadquartersUnlocked(selectedEmpire) == true)
            if (isHqUnlocked) {
                enemyHeadquarters?.isUnlocked = true
                threatAlert = ThreatAlert(
                    "ALL 50 ${selectedEmpire.name} BARRACKS DESTROYED! HEADQUARTERS FORCEFIELD DOWN! ELIMINATE ${selectedEmpire.bossName}!",
                    Color(0xFFFF1744),
                    8.0f
                )
                audioSystem?.playBossWarning()
                spawnEmpireBoss()
            } else {
                threatAlert = ThreatAlert(
                    "${selectedEmpire.name} BARRACKS #${barracks.index} DESTROYED! [$barracksDestroyedCount / 50]",
                    Color(0xFFFFD600),
                    2.2f
                )
            }
            return true
        }
        return false
    }

    fun destroyHeadquarters() {
        val hq = enemyHeadquarters ?: return
        if (hq.isDestroyed) return
        hq.health = 0f
        hq.isDestroyed = true
        campaignService?.recordHeadquartersDestruction(selectedEmpire)
        score += 15000
        creditsEarned += 2500
        isVictory = true
        threatAlert = ThreatAlert("SUPREME VICTORY: ${selectedEmpire.title} HEADQUARTERS ANNIHILATED!", Color(0xFF00E676), 10.0f)
        createExplosion(hq.pos, Color(0xFFFF1744), count = 60, speed = 18f)
        createExplosion(hq.pos + Vector3D(4f, 4f, 2f), Color(0xFFFF9100), count = 40, speed = 15f)
        createExplosion(hq.pos + Vector3D(-4f, -4f, 2f), Color(0xFFFFD600), count = 40, speed = 15f)
        audioSystem?.playExplosion()
        camera.screenShake = 5.0f
    }


    private fun updateParticles(delta: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.pos.x += p.velocity.x * delta
            p.pos.y += p.velocity.y * delta
            p.pos.z += p.velocity.z * delta
            p.life -= delta
            if (p.life <= 0f) {
                it.remove()
            }
        }
    }

    fun createExplosion(center: Vector3D, color: Color, count: Int, speed: Float) {
        for (i in 0 until count) {
            val theta = Math.random().toFloat() * kotlin.math.PI.toFloat() * 2f
            val phi = (Math.random().toFloat() - 0.5f) * kotlin.math.PI.toFloat()
            val spd = (Math.random().toFloat() * 0.8f + 0.2f) * speed
            val vel = Vector3D(
                cos(phi) * cos(theta) * spd,
                cos(phi) * sin(theta) * spd,
                sin(phi) * spd
            )
            val life = Math.random().toFloat() * 0.45f + 0.25f
            particles.add(
                VisualParticle(
                    pos = center.copy(),
                    velocity = vel,
                    color = color,
                    radius = (Math.random().toFloat() * 4.5f + 3f),
                    life = life,
                    maxLife = life
                )
            )
        }
    }

    private fun updateHud() {
        val rocketCdPct = (1.0f - (player.rocketCooldownTimer / player.rocketCooldownMax)).coerceIn(0f, 1f)
        val missileCdPct = (1.0f - (player.missileTimer / player.missileCooldown)).coerceIn(0f, 1f)

        val lockedName = activeBoss?.name ?: enemies.find { it.id == player.lockedTargetId }?.unitName

        _hudState.value = GameHudState(
            health = player.health,
            maxHealth = player.maxHealth,
            armor = player.armor,
            maxArmor = player.maxArmor,
            shield = player.shield,
            maxShield = player.maxShield,
            score = score,
            kills = kills,
            missionProgressPercent = missionProgress,
            rocketAmmo = player.rocketAmmo,
            maxRocketAmmo = player.maxRocketAmmo,
            rocketCooldownPercent = rocketCdPct,
            isRocketReady = player.rocketCooldownTimer <= 0f && player.rocketAmmo >= 4,
            missileAmmo = player.missileAmmo,
            maxMissileAmmo = player.maxMissileAmmo,
            missileReadyPercent = missileCdPct,
            isMissileReady = player.missileTimer <= 0f && player.missileAmmo >= 2,
            lockedTargetName = if (player.lockProgress >= 0.9f) lockedName else null,
            isAutoFiring = player.isAutoFiring,
            activeThreat = threatAlert,
            isGameOver = isGameOver,
            isVictory = isVictory,
            activeEmpire = selectedEmpire,
            helicopterClass = player.heloClass,
            bossEntity = activeBoss,
            creditsEarned = creditsEarned,
            barracksDestroyedCount = barracksDestroyedCount,
            totalBarracks = 50,
            isHeadquartersUnlocked = enemyHeadquarters?.isUnlocked ?: false,
            headquartersPos = enemyHeadquarters?.pos
        )
    }

    fun transitionToEmpire(newEmpire: EmpireType) {
        selectedEmpire = newEmpire
        threatAlert = ThreatAlert("WAR THEATER: ${newEmpire.title} OPEN-WORLD SORTIE", newEmpire.primaryColor, 5.0f)
        hazards.clear()
        structures.clear()
        allBarracks.clear()
        barracksDestroyedCount = 0
        enemyHeadquarters = null
        furthestGeneratedY = player.pos.y
        initOpenWorldObjectives()
        generateEnvironmentChunk(furthestGeneratedY)
    }

    private fun initOpenWorldObjectives() {
        allBarracks.clear()
        // Procedurally distribute 50 distinct barracks across the expansive open-world theater
        // Distributed across X: -170f to +170f and Y: 35f to 950f (non-clustered, expansive territory)
        val random = java.util.Random(selectedEmpire.ordinal * 1000L + 42L)
        val empireDefenders = when (selectedEmpire) {
            EmpireType.HUMAN -> listOf(EnemyCategory.INFANTRY_SOLDIER, EnemyCategory.ROCKET_SOLDIER, EnemyCategory.GROUND_TANK)
            EmpireType.ORC -> listOf(EnemyCategory.INFANTRY_SOLDIER, EnemyCategory.ROCKET_SOLDIER, EnemyCategory.GROUND_TANK)
            EmpireType.DRAGON -> listOf(EnemyCategory.ATTACK_HELICOPTER, EnemyCategory.AA_TURRET, EnemyCategory.ROCKET_SOLDIER)
            EmpireType.ALIEN -> listOf(EnemyCategory.FIGHTER_AIRCRAFT, EnemyCategory.AA_TURRET, EnemyCategory.INFANTRY_SOLDIER)
        }

        for (i in 1..50) {
            val posX = (random.nextFloat() * 340f - 170f)
            val posY = 35f + (i * 18.0f) + (random.nextFloat() * 8f - 4f)
            val barracksId = "BARRACKS_${selectedEmpire.name}_$i"
            val barracksName = "${selectedEmpire.name} BARRACKS OUTPOST #$i"

            val isAlreadyDestroyed = campaignService?.isBarracksDestroyed(barracksId) == true

            val barracks = EnemyBarracks(
                id = barracksId,
                index = i,
                empire = selectedEmpire,
                name = barracksName,
                pos = Vector3D(posX, posY, 0.1f),
                health = if (isAlreadyDestroyed) 0f else 320f,
                maxHealth = 320f,
                isDestroyed = isAlreadyDestroyed,
                isDiscovered = isAlreadyDestroyed,
                defenderTypes = empireDefenders
            )
            allBarracks.add(barracks)

            // Register Barracks Physical 3D Structure in world
            structures.add(
                TerrainStructure(
                    id = "STRUCT_BARRACKS_$i",
                    type = StructureType.BARRACKS_FORT,
                    empire = selectedEmpire,
                    pos = Vector3D(posX, posY, 0.1f),
                    width = 8.0f,
                    length = 8.0f,
                    height = 5.0f
                )
            )

            // Spawn localized active barracks defenders only if barracks is still standing
            if (!isAlreadyDestroyed) {
                spawnEnemy(empireDefenders[0], posX - 3.5f, posY + 1.5f)
                spawnEnemy(empireDefenders[1], posX + 3.5f, posY + 1.5f)
                if (i % 2 == 0) {
                    spawnEnemy(empireDefenders[2], posX, posY + 4.0f)
                }
            }
        }

        barracksDestroyedCount = allBarracks.count { it.isDestroyed }

        // Fortified Enemy Headquarters located at the apex of the territory (Y = 1020f)
        val hqName = "${selectedEmpire.name} SUPREME CITADEL & HEADQUARTERS"
        val isHqUnlocked = barracksDestroyedCount >= 50 || (campaignService?.isHeadquartersUnlocked(selectedEmpire) == true)
        val isHqDestroyed = campaignService?.isHeadquartersDestroyed(selectedEmpire) == true

        val hq = EnemyHeadquarters(
            id = "HQ_${selectedEmpire.name}",
            empire = selectedEmpire,
            name = hqName,
            pos = Vector3D(0f, 1020f, 0.1f),
            isUnlocked = isHqUnlocked,
            isDestroyed = isHqDestroyed,
            health = if (isHqDestroyed) 0f else 2500f,
            maxHealth = 2500f
        )
        enemyHeadquarters = hq
        structures.add(
            TerrainStructure(
                id = "STRUCT_HQ",
                type = StructureType.HEADQUARTERS_FORT,
                empire = selectedEmpire,
                pos = hq.pos,
                width = 16.0f,
                length = 16.0f,
                height = 10.0f
            )
        )
    }

    private fun generateInitialEnvironment() {
        initOpenWorldObjectives()
        furthestGeneratedY = -20f
        while (furthestGeneratedY < 120f) {
            generateEnvironmentChunk(furthestGeneratedY)
            furthestGeneratedY += 30f
        }
    }

    private fun updateEnvironment(delta: Float) {
        // Stream open-world terrain chunks as player explores
        while (furthestGeneratedY < player.pos.y + 110f) {
            generateEnvironmentChunk(furthestGeneratedY)
            furthestGeneratedY += 30f
        }

        // Discover nearby barracks on exploration radar
        for (b in allBarracks) {
            if (!b.isDiscovered && (b.pos - player.pos).length() < player.radarRange + 25f) {
                b.isDiscovered = true
            }
        }

        val hIt = hazards.iterator()
        while (hIt.hasNext()) {
            val h = hIt.next()
            h.cycleTimer += delta
            if (h.cycleTimer >= 4.2f) {
                h.cycleTimer = 0f
                h.state = HazardState.WARNING
                if (threatAlert == null && (h.pos - player.pos).length() < 30f) {
                    threatAlert = ThreatAlert("WARNING: ${h.type.warningText}", selectedEmpire.primaryColor, 1.8f)
                }
            } else if (h.cycleTimer >= 1.2f && h.state == HazardState.WARNING) {
                h.state = HazardState.ACTIVE
                if ((h.pos - player.pos).length() < 24f) {
                    audioSystem?.playExplosion()
                }
            } else if (h.cycleTimer >= 2.6f && h.state == HazardState.ACTIVE) {
                h.state = HazardState.DORMANT
            }

            if (h.state == HazardState.ACTIVE) {
                val dist = (Vector3D(h.pos.x, h.pos.y, player.pos.z) - player.pos).length()
                if (dist < h.radius) {
                    player.takeDamage(h.damage * delta * 1.5f)
                    camera.screenShake = 1.8f
                    createExplosion(player.pos, selectedEmpire.primaryColor, count = 3, speed = 4f)
                }
            }

            // Safe open-world cleanup of distant hazards
            if (h.pos.y < camera.y - 80.0f) {
                hIt.remove()
            }
        }

        // Clean up distant decorative structures to maintain Android performance (keeping Barracks and HQ intact)
        structures.removeAll {
            it.type != StructureType.BARRACKS_FORT && it.type != StructureType.HEADQUARTERS_FORT && it.pos.y < camera.y - 80.0f
        }
    }

    private fun generateEnvironmentChunk(startY: Float) {
        val rand = java.util.Random(startY.toLong())
        // Natural forests, mountain hills, and empire landmarks scattered across wide coordinates
        for (i in 0..2) {
            val fx = (rand.nextFloat() * 260f - 130f)
            val fy = startY + rand.nextFloat() * 30f
            structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.DENSE_FOREST, selectedEmpire, Vector3D(fx, fy, 0.1f), 7f, 7f, 4f))
        }

        val mx = if (rand.nextBoolean()) (rand.nextFloat() * 80f + 50f) else -(rand.nextFloat() * 80f + 50f)
        structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.MOUNTAIN_HILL, selectedEmpire, Vector3D(mx, startY + 14f, 0.1f), 12f, 12f, 9f))

        when (selectedEmpire) {
            EmpireType.DRAGON -> {
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.VOLCANIC_SPIRE, selectedEmpire, Vector3D(-24f, startY + 6f, 0.1f), 6f, 6f, 11f))
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.DRAGON_CASTLE, selectedEmpire, Vector3D(28f, startY + 16f, 0.1f), 8f, 10f, 12f))
                hazards.add(EnvironmentHazard(UUID.randomUUID().toString(), HazardType.LAVA_GEYSER, Vector3D(-6f, startY + 12f, 0.1f), radius = 4.5f, damage = 28f))
            }
            EmpireType.ALIEN -> {
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.ALIEN_PYLON, selectedEmpire, Vector3D(-20f, startY + 8f, 0.1f), 5f, 5f, 12f))
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.MOTHERSHIP_PORTAL, selectedEmpire, Vector3D(22f, startY + 18f, 0.1f), 8f, 8f, 9f))
                hazards.add(EnvironmentHazard(UUID.randomUUID().toString(), HazardType.PLASMA_LIGHTNING, Vector3D(12f, startY + 14f, 0.1f), radius = 4.2f, damage = 25f))
            }
            EmpireType.ORC -> {
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.ORC_WATCHTOWER, selectedEmpire, Vector3D(-18f, startY + 10f, 0.1f), 5f, 6f, 9f))
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.SIEGE_FORTRESS, selectedEmpire, Vector3D(25f, startY + 15f, 0.1f), 9f, 11f, 10f))
                hazards.add(EnvironmentHazard(UUID.randomUUID().toString(), HazardType.SMOG_FIREPIT, Vector3D(-10f, startY + 8f, 0.1f), radius = 4.0f, damage = 22f))
            }
            EmpireType.HUMAN -> {
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.MILITARY_HANGAR, selectedEmpire, Vector3D(-26f, startY + 6f, 0.1f), 8f, 12f, 6f))
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.RADAR_DOME, selectedEmpire, Vector3D(24f, startY + 14f, 0.1f), 6f, 6f, 8f))
                structures.add(TerrainStructure(UUID.randomUUID().toString(), StructureType.MISSILE_SILO, selectedEmpire, Vector3D(-14f, startY + 20f, 0.1f), 5f, 5f, 3f))
                hazards.add(EnvironmentHazard(UUID.randomUUID().toString(), HazardType.EMP_GRID, Vector3D(16f, startY + 11f, 0.1f), radius = 4.2f, damage = 30f))
            }
        }
    }
}
