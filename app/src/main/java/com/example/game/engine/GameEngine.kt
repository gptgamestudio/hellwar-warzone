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

enum class GameMode {
    CAMPAIGN,
    ENDLESS_SURVIVAL
}

enum class EndlessSyncStatus {
    IDLE,
    SYNCED,
    PENDING_OFFLINE
}

data class EndlessWaveCompletionEvent(
    val waveNumber: Int,
    val waveKills: Int,
    val empire: EmpireType,
    val nextEmpire: EmpireType,
    val survivalScore: Int
)

data class GameHudState(
    val health: Float = 100f,
    val maxHealth: Float = 100f,
    val armor: Float = 60f,
    val maxArmor: Float = 60f,
    val shield: Float = 40f,
    val maxShield: Float = 40f,
    val hearts: Int = 5,
    val maxHearts: Int = 5,
    val hitsRemainingInHeart: Int = 10,
    val hitsPerHeart: Int = 10,
    val score: Int = 0,
    val kills: Int = 0,
    val missionProgressPercent: Float = 0f,

    // Weapons (All auto-recharge)
    val rocketAmmo: Int = 16,
    val maxRocketAmmo: Int = 16,
    val rocketCooldownPercent: Float = 1.0f,
    val isRocketReady: Boolean = true,

    val missileAmmo: Int = 8,
    val maxMissileAmmo: Int = 8,
    val missileReadyPercent: Float = 1.0f,
    val isMissileReady: Boolean = true,
    val lockedTargetName: String? = null,

    val flareCooldownPercent: Float = 1.0f,
    val isFlareReady: Boolean = true,
    val overdriveChargePercent: Float = 0.4f,
    val isOverdriveReady: Boolean = false,

    val isAutoFiring: Boolean = true,
    val activeThreat: ThreatAlert? = null,
    val isGameOver: Boolean = false,
    val isVictory: Boolean = false,
    val activeEmpire: EmpireType = EmpireType.DRAGON,
    val stageNumber: Int = 1,

    // Fleet Info
    val helicopterClass: com.example.game.model.HelicopterClass = com.example.game.model.HelicopterClass.SCOUT,

    // Boss State
    val bossEntity: BossCombatEntity? = null,
    val creditsEarned: Int = 0,

    // Open-World 50 Barracks & Headquarters Mission Progression
    val barracksDestroyedCount: Int = 0,
    val totalBarracks: Int = 50,
    val isHeadquartersUnlocked: Boolean = false,
    val isHqBossDefeated: Boolean = false,
    val isDangerZoneTriggered: Boolean = false,
    val hqHitsRemaining: Int = 1000,
    val hqMaxHits: Int = 1000,
    val headquartersPos: Vector3D? = null,

    // Endless Survival Mode State
    val gameMode: GameMode = GameMode.CAMPAIGN,
    val currentWave: Int = 1,
    val wavesCompleted: Int = 0,
    val waveEnemiesRemaining: Int = 0,
    val waveEnemiesTotal: Int = 0,
    val endlessBestWave: Int = 0,
    val endlessBestScore: Int = 0,
    val endlessSyncStatus: EndlessSyncStatus = EndlessSyncStatus.IDLE
)

class GameEngine(
    var selectedEmpire: EmpireType = EmpireType.DRAGON,
    val audioSystem: CombatAudioSystem? = null,
    val initialHeloClass: com.example.game.model.HelicopterClass = com.example.game.model.HelicopterClass.SCOUT,
    val initialSkin: com.example.game.model.HelicopterSkin = com.example.game.model.HelicopterSkin.TACTICAL_BLACK,
    val initialUpgrades: com.example.game.model.HelicopterUpgrades = com.example.game.model.HelicopterUpgrades(),
    val campaignService: WarNetCampaignService? = null,
    initialScreenShakeEnabled: Boolean = true,
    private val initialAutoFire: Boolean = true,
    val gameMode: GameMode = GameMode.CAMPAIGN,
    private val onEndlessWaveCompleted: ((EndlessWaveCompletionEvent) -> Unit)? = null
) {
    val player = PlayerHelo()
    val camera = Camera3D()
    var isScreenShakeEnabled: Boolean = initialScreenShakeEnabled

    // 4 Independent directional input states (held = true) & 360-degree analog virtual joystick vector
    var isUpPressed: Boolean = false
    var isDownPressed: Boolean = false
    var isLeftPressed: Boolean = false
    var isRightPressed: Boolean = false
    var joystickInputX: Float = 0f
    var joystickInputY: Float = 0f

    fun setJoystickInput(x: Float, y: Float) {
        val rawX = if (x.isFinite()) x else 0f
        val rawY = if (y.isFinite()) y else 0f
        val mag = kotlin.math.sqrt(rawX * rawX + rawY * rawY)
        if (mag > 1f) {
            joystickInputX = rawX / mag
            joystickInputY = rawY / mag
        } else {
            joystickInputX = rawX
            joystickInputY = rawY
        }
    }

    fun clearMovementInputs() {
        isUpPressed = false
        isDownPressed = false
        isLeftPressed = false
        isRightPressed = false
        joystickInputX = 0f
        joystickInputY = 0f
    }

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
    private var hudSyncTimer: Float = 0f
    private var bossFireBreathCooldown: Float = 0f
    var bossSpawned: Boolean = false
    private var waveNumber: Int = 1
    private var hqGuardSpawnTimer: Float = 0f
    private var rocketRechargeTimer: Float = 0f
    private var missileRechargeTimer: Float = 0f

    // Endless Survival Wave State
    var currentWave: Int = 1
        private set
    var wavesCompleted: Int = 0
        private set
    var waveTotalEnemies: Int = 4
        private set
    var waveEnemiesSpawned: Int = 0
        private set
    var waveEnemiesDefeated: Int = 0
        private set
    private var waveTransitionTimer: Float = 0f
    private val completedWaveNumbers = mutableSetOf<Int>()
    private val creditedWaveNumbers = mutableSetOf<Int>()
    var endlessBestWave: Int = 0
        private set
    var endlessBestScore: Int = 0
        private set
    var endlessSyncStatus: EndlessSyncStatus = EndlessSyncStatus.IDLE
        private set

    /** Difficulty escalates continuously across barracks (Campaign) or waves (Endless). */
    val difficultyFactor: Float
        get() = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            ((currentWave - 1) / 15f).coerceIn(0f, 1f)
        } else {
            (barracksDestroyedCount / 50f).coerceIn(0f, 1f)
        }

    val currentWaveHpMultiplier: Float
        get() = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            endlessHpMultiplierForWave(currentWave)
        } else {
            1.0f + (difficultyFactor * 0.65f)
        }

    val currentWaveDamageMultiplier: Float
        get() = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            endlessDamageMultiplierForWave(currentWave)
        } else {
            1.0f
        }

    private val _hudState = MutableStateFlow(GameHudState())
    val hudState: StateFlow<GameHudState> = _hudState.asStateFlow()

    companion object {
        const val MAX_BULLETS = 96
        const val MAX_ROCKETS = 24
        const val MAX_MISSILES = 20
        const val MAX_PARTICLES = 180
        const val MAX_ACTIVE_ENEMIES = 18
        const val MAX_HAZARDS = 16
        const val MAX_STRUCTURES = 140
        const val HQ_DANGER_ZONE_RADIUS = 110f
        private const val HUD_SYNC_INTERVAL = 0.04f

        val ENDLESS_THEATER_CYCLE: List<EmpireType> = listOf(
            EmpireType.DRAGON,
            EmpireType.ALIEN,
            EmpireType.ORC,
            EmpireType.HUMAN
        )
        const val ENDLESS_WAVES_PER_THEATER = 5

        fun endlessEmpireForWave(wave: Int): EmpireType {
            val safeWave = wave.coerceAtLeast(1)
            val idx = ((safeWave - 1) / ENDLESS_WAVES_PER_THEATER) % ENDLESS_THEATER_CYCLE.size
            return ENDLESS_THEATER_CYCLE[idx]
        }

        fun endlessEnemyCountForWave(wave: Int): Int {
            val safeWave = wave.coerceAtLeast(1)
            return (4 + (safeWave - 1) * 2).coerceAtMost(36)
        }

        fun endlessHpMultiplierForWave(wave: Int): Float {
            val safeWave = wave.coerceAtLeast(1)
            return 1.0f + (safeWave - 1) * 0.18f
        }

        fun endlessDamageMultiplierForWave(wave: Int): Float {
            val safeWave = wave.coerceAtLeast(1)
            return 1.0f + (safeWave - 1) * 0.12f
        }

        fun endlessWaveRewardCredits(wave: Int): Int {
            val safeWave = wave.coerceAtLeast(1)
            return 150 + ((safeWave - 1).coerceIn(0, 25) * 35)
        }

        fun endlessWaveRewardScore(wave: Int, waveKills: Int): Int {
            val safeWave = wave.coerceAtLeast(1)
            val safeKills = waveKills.coerceIn(0, 40)
            return 500 + safeWave * 250 + safeKills * 200
        }
    }

    private fun addBullet(bullet: TracerBullet) {
        if (bullets.size >= MAX_BULLETS) {
            bullets.removeAt(0)
        }
        bullets.add(bullet)
    }

    private fun addRocket(rocket: RocketCluster) {
        if (rockets.size >= MAX_ROCKETS) {
            rockets.removeAt(0)
        }
        rockets.add(rocket)
    }

    private fun addMissile(missile: GuidedMissile) {
        if (missiles.size >= MAX_MISSILES) {
            missiles.removeAt(0)
        }
        missiles.add(missile)
    }

    init {
        player.applyConfiguration(initialHeloClass, initialSkin, initialUpgrades)
        resetBattle()
    }

    fun resetBattle() {
        player.applyConfiguration(player.heloClass, player.skin, player.upgrades)
        player.pos = Vector3D(0f, 0f, 9.5f)
        player.velocity = Vector3D(0f, 0f, 0f)
        player.restoreAllHearts()
        player.invulnerableTimer = 1.5f
        player.rocketAmmo = player.maxRocketAmmo
        player.missileAmmo = player.maxMissileAmmo
        player.rocketCooldownTimer = 0f
        player.missileTimer = 0f
        player.flareCooldownTimer = 0f
        player.flareActiveTimer = 0f
        player.overdriveCharge = 40f
        player.overdriveBeamTimer = 0f
        player.rollAngle = 0f
        player.pitchAngle = 0f
        player.lockedTargetId = null
        player.isAutoFiring = initialAutoFire

        clearMovementInputs()

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
        hudSyncTimer = 0f
        bossFireBreathCooldown = 0f
        hqGuardSpawnTimer = 0f
        rocketRechargeTimer = 0f
        missileRechargeTimer = 0f
        waveNumber = 1
        bossSpawned = false
        isGameOver = false
        isVictory = false

        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            selectedEmpire = endlessEmpireForWave(1)
            currentWave = 1
            wavesCompleted = 0
            waveTotalEnemies = endlessEnemyCountForWave(1)
            waveEnemiesSpawned = 0
            waveEnemiesDefeated = 0
            waveTransitionTimer = 0f
            enemySpawnTimer = 0.4f
            completedWaveNumbers.clear()
            creditedWaveNumbers.clear()
            allBarracks.clear()
            enemyHeadquarters = null
            barracksDestroyedCount = 0
            threatAlert = ThreatAlert(
                "ENDLESS SURVIVAL — WAVE 1 // ${selectedEmpire.title}",
                selectedEmpire.primaryColor,
                4.0f
            )
        } else {
            threatAlert = ThreatAlert(
                "STAGE ${getStageNumber(selectedEmpire)}: DESTROY 50 BARRACKS TO UNLOCK HQ",
                selectedEmpire.primaryColor,
                4.0f
            )
        }

        // Generate seamless initial environment
        generateInitialEnvironment()

        // Spawn initial combat squadron
        spawnInitialSquadron()
        updateHud()
    }

    private fun getStageNumber(empire: EmpireType): Int {
        return com.example.game.network.CampaignProgressionManager.STAGE_ORDER.indexOf(empire)
            .let { if (it >= 0) it + 1 else 1 }
    }

    fun exportCheckpoint(): com.example.game.network.SortieCheckpoint {
        val hq = enemyHeadquarters
        return com.example.game.network.SortieCheckpoint(
            empire = selectedEmpire,
            stageNumber = getStageNumber(selectedEmpire),
            playerX = player.pos.x,
            playerY = player.pos.y,
            hearts = player.hearts.coerceIn(1, player.maxHearts),
            hitsRemainingInHeart = player.hitsRemainingInHeart.coerceIn(1, player.hitsPerHeart),
            score = score,
            kills = kills,
            creditsEarned = creditsEarned,
            destroyedBarracksIds = allBarracks.filter { it.isDestroyed }.map { it.id }.toSet(),
            bossSpawned = bossSpawned,
            bossDefeated = hq?.isBossDefeated == true,
            bossHitsRemaining = activeBoss?.bossHitsRemaining ?: if (hq?.isBossDefeated == true) 0 else 100,
            hqUnlocked = hq?.isUnlocked == true,
            hqHitsRemaining = hq?.hitsRemaining ?: 1000,
            overdriveCharge = player.overdriveCharge
        )
    }

    fun restoreFromCheckpoint(checkpoint: com.example.game.network.SortieCheckpoint) {
        if (checkpoint.empire != selectedEmpire) {
            selectedEmpire = checkpoint.empire
            resetBattle()
        }
        player.pos = Vector3D(
            checkpoint.playerX.coerceIn(minX, maxX),
            checkpoint.playerY.coerceIn(minY, maxY),
            9.5f
        )
        player.velocity = Vector3D(0f, 0f, 0f)
        player.hearts = checkpoint.hearts.coerceIn(1, player.maxHearts)
        player.hitsRemainingInHeart = checkpoint.hitsRemainingInHeart.coerceIn(1, player.hitsPerHeart)
        player.health = player.maxHealth * (player.hitsRemainingInHeart.toFloat() / player.hitsPerHeart.toFloat())
        player.overdriveCharge = checkpoint.overdriveCharge.coerceIn(0f, 100f)
        player.invulnerableTimer = 2.0f

        score = checkpoint.score
        kills = checkpoint.kills
        creditsEarned = checkpoint.creditsEarned
        isGameOver = false
        isVictory = false

        for (b in allBarracks) {
            val allowedByServer = campaignService == null || campaignService.isBarracksDestroyed(b.id)
            if (checkpoint.destroyedBarracksIds.contains(b.id) && allowedByServer) {
                b.isDestroyed = true
                b.health = 0f
                b.hitsRemaining = 0
                b.isDiscovered = true
            }
        }
        barracksDestroyedCount = allBarracks.count { it.isDestroyed }

        enemyHeadquarters?.let { hq ->
            hq.isUnlocked = if (campaignService != null) {
                barracksDestroyedCount >= 50 && campaignService.isHeadquartersUnlocked(selectedEmpire)
            } else {
                checkpoint.hqUnlocked || barracksDestroyedCount >= 50
            }
            hq.isBossDefeated = checkpoint.bossDefeated && hq.isUnlocked
            hq.hitsRemaining = checkpoint.hqHitsRemaining.coerceIn(1, hq.maxHits)
            hq.health = hq.hitsRemaining.toFloat()
        }

        if (checkpoint.bossSpawned && !(enemyHeadquarters?.isBossDefeated ?: false) && (enemyHeadquarters?.isUnlocked == true)) {
            spawnEmpireBoss()
            activeBoss?.bossHitsRemaining = checkpoint.bossHitsRemaining.coerceIn(1, 100)
        } else {
            bossSpawned = checkpoint.bossSpawned && (enemyHeadquarters?.isUnlocked == true)
        }

        camera.x = player.pos.x
        camera.y = player.pos.y - 12.5f
        camera.z = 27.5f
        threatAlert = ThreatAlert(
            "RESUMED STAGE ${checkpoint.stageNumber}: ${barracksDestroyedCount}/50 BARRACKS DESTROYED",
            Color(0xFF00E676),
            3.5f
        )
        updateHud()
    }

    fun exportEndlessCheckpoint(
        sessionId: String? = null,
        settledWaves: Set<Int> = emptySet(),
        pendingClaimWaves: Map<Int, Int> = emptyMap()
    ): com.example.game.network.EndlessCheckpoint {
        return com.example.game.network.EndlessCheckpoint(
            currentWave = currentWave.coerceAtLeast(1),
            wavesCompleted = wavesCompleted.coerceAtLeast(0),
            activeEmpire = selectedEmpire,
            playerX = player.pos.x,
            playerY = player.pos.y,
            hearts = player.hearts.coerceIn(1, player.maxHearts),
            hitsRemainingInHeart = player.hitsRemainingInHeart.coerceIn(1, player.hitsPerHeart),
            score = score,
            kills = kills,
            confirmedCreditsEarned = creditsEarned,
            waveEnemiesDefeated = waveEnemiesDefeated.coerceIn(0, waveTotalEnemies),
            waveTotalEnemies = waveTotalEnemies.coerceAtLeast(1),
            overdriveCharge = player.overdriveCharge,
            endlessSessionId = sessionId,
            settledWaves = settledWaves,
            pendingClaimWaves = pendingClaimWaves
        )
    }

    fun restoreFromEndlessCheckpoint(checkpoint: com.example.game.network.EndlessCheckpoint) {
        if (gameMode != GameMode.ENDLESS_SURVIVAL) return
        currentWave = checkpoint.currentWave.coerceAtLeast(1)
        wavesCompleted = checkpoint.wavesCompleted.coerceAtLeast(0)
        selectedEmpire = endlessEmpireForWave(currentWave)
        waveTotalEnemies = endlessEnemyCountForWave(currentWave)
        waveEnemiesDefeated = checkpoint.waveEnemiesDefeated.coerceIn(0, waveTotalEnemies - 1)
        waveEnemiesSpawned = waveEnemiesDefeated
        waveTransitionTimer = 0f
        enemySpawnTimer = 0.4f
        completedWaveNumbers.clear()
        for (w in 1 until currentWave) {
            completedWaveNumbers.add(w)
        }
        creditedWaveNumbers.clear()
        creditedWaveNumbers.addAll(checkpoint.settledWaves)

        player.pos = Vector3D(
            checkpoint.playerX.coerceIn(minX, maxX),
            checkpoint.playerY.coerceAtLeast(minY),
            9.5f
        )
        player.velocity = Vector3D(0f, 0f, 0f)
        player.hearts = checkpoint.hearts.coerceIn(1, player.maxHearts)
        player.hitsRemainingInHeart = checkpoint.hitsRemainingInHeart.coerceIn(1, player.hitsPerHeart)
        player.health = player.maxHealth * (player.hitsRemainingInHeart.toFloat() / player.hitsPerHeart.toFloat())
        player.overdriveCharge = checkpoint.overdriveCharge.coerceIn(0f, 100f)
        player.invulnerableTimer = 2.0f

        score = checkpoint.score.coerceAtLeast(0)
        kills = checkpoint.kills.coerceAtLeast(0)
        creditsEarned = checkpoint.confirmedCreditsEarned.coerceAtLeast(0)
        isGameOver = false
        isVictory = false

        enemies.clear()
        bullets.clear()
        rockets.clear()
        missiles.clear()
        hazards.clear()
        structures.clear()
        allBarracks.clear()
        enemyHeadquarters = null
        activeBoss = null
        bossSpawned = false

        furthestGeneratedY = (player.pos.y - 30f).coerceAtLeast(-20f)
        while (furthestGeneratedY < player.pos.y + 120f) {
            generateEnvironmentChunk(furthestGeneratedY)
            furthestGeneratedY += 30f
        }

        val initialBurst = minOf(3, (waveTotalEnemies - waveEnemiesSpawned).coerceAtLeast(0))
        repeat(initialBurst) {
            spawnEndlessWaveEnemy()
        }

        camera.x = player.pos.x
        camera.y = player.pos.y - 12.5f
        camera.z = 27.5f
        threatAlert = ThreatAlert(
            "RESUMED WAVE $currentWave // ${selectedEmpire.title} ($waveEnemiesDefeated/$waveTotalEnemies)",
            Color(0xFF00E676),
            3.5f
        )
        updateHud()
    }

    fun applyEndlessWaveCredits(waveNumber: Int, awardedCredits: Int) {
        if (awardedCredits > 0 && creditedWaveNumbers.add(waveNumber)) {
            creditsEarned += awardedCredits
            updateHud()
        }
    }

    fun updateEndlessMetadata(bestWave: Int, bestScore: Int, syncStatus: EndlessSyncStatus) {
        endlessBestWave = maxOf(endlessBestWave, bestWave)
        endlessBestScore = maxOf(endlessBestScore, bestScore)
        endlessSyncStatus = syncStatus
        updateHud()
    }

    /**
     * Restores all 5 Main Hearts (50 hits) after watching a Rewarded Ad so player continues seamlessly.
     */
    fun continueAfterAdReward() {
        player.restoreAllHearts()
        bullets.removeAll { !it.isPlayer && (it.pos - player.pos).length() < 32f }
        missiles.removeAll { it.isEnemy && (it.pos - player.pos).length() < 32f }
        isGameOver = false
        threatAlert = ThreatAlert("AD CONTINUE: +5 HEARTS RESTORED! PRESS ATTACK!", Color(0xFF00E676), 3.8f)
        updateHud()
    }

    private fun spawnInitialSquadron() {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            val initialBurst = minOf(3, waveTotalEnemies - waveEnemiesSpawned)
            repeat(initialBurst) {
                spawnEndlessWaveEnemy()
            }
        } else {
            spawnEnemy(EnemyCategory.ATTACK_HELICOPTER, -5f, 22f)
            spawnEnemy(EnemyCategory.GROUND_TANK, 4f, 26f)
            spawnEnemy(EnemyCategory.AA_TURRET, 0f, 32f)
        }
    }

    fun update(delta: Float) {
        if (isGameOver || isVictory) return
        val safeDelta = if (delta.isFinite()) delta.coerceIn(0.0001f, 0.16f) else 0.016f
        updateStep(safeDelta)
    }

    private fun updateStep(safeDelta: Float) {
        gameTime += safeDelta
        missionProgress = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            (waveEnemiesDefeated.toFloat() / waveTotalEnemies.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        } else {
            (barracksDestroyedCount / 50f).coerceIn(0f, 1f)
        }

        // Cooldown / flash timers
        if (player.invulnerableTimer > 0f) player.invulnerableTimer = (player.invulnerableTimer - safeDelta).coerceAtLeast(0f)
        if (player.hitFlashTimer > 0f) player.hitFlashTimer = (player.hitFlashTimer - safeDelta).coerceAtLeast(0f)
        if (player.flareActiveTimer > 0f) player.flareActiveTimer = (player.flareActiveTimer - safeDelta).coerceAtLeast(0f)
        if (player.overdriveBeamTimer > 0f) player.overdriveBeamTimer = (player.overdriveBeamTimer - safeDelta).coerceAtLeast(0f)

        allBarracks.forEach { b ->
            if (b.hitFlashTimer > 0f) b.hitFlashTimer = (b.hitFlashTimer - safeDelta).coerceAtLeast(0f)
        }
        enemyHeadquarters?.let { hq ->
            if (hq.hitFlashTimer > 0f) hq.hitFlashTimer = (hq.hitFlashTimer - safeDelta).coerceAtLeast(0f)
        }

        // Big Boss remains inside Main Headquarters until all 50 barracks are destroyed
        // AND the player triggers the HQ Warning Danger Zone!
        enemyHeadquarters?.let { hq ->
            if (!hq.isDestroyed) {
                val distToHq = kotlin.math.hypot(player.pos.x - hq.pos.x, player.pos.y - hq.pos.y)
                val inDangerZone = distToHq <= HQ_DANGER_ZONE_RADIUS || player.pos.y >= hq.pos.y - HQ_DANGER_ZONE_RADIUS
                if (inDangerZone) {
                    if (!hq.isDangerZoneTriggered) {
                        hq.isDangerZoneTriggered = true
                        if (!hq.isUnlocked) {
                            threatAlert = ThreatAlert(
                                "WARNING: HQ DANGER ZONE! CLEAR ALL 50 BARRACKS (${barracksDestroyedCount}/50)",
                                Color(0xFFFF9100),
                                4.0f
                            )
                        }
                        spawnHqPerimeterDefense(hq)
                    }
                    // Heavy HQ garrison patrols while HQ stands
                    hqGuardSpawnTimer -= safeDelta
                    if (hqGuardSpawnTimer <= 0f && enemies.size < MAX_ACTIVE_ENEMIES) {
                        hqGuardSpawnTimer = 4.2f
                        spawnHqPerimeterDefense(hq)
                    }
                    // Deploy Boss from Main HQ once all 50 barracks are cleared!
                    if (hq.isUnlocked && !hq.isBossDefeated && !bossSpawned && activeBoss == null) {
                        spawnEmpireBoss()
                    }
                }
            }
        }

        // 1. Update 4-Directional Player Movement
        updatePlayerMovement(safeDelta)

        // 2. Camera tracking with velocity look-ahead (respecting tactical camera shake toggle)
        if (!isScreenShakeEnabled) {
            camera.screenShake = 0f
        }
        camera.updateFollow(
            player.pos.x,
            player.pos.y,
            safeDelta,
            player.velocity.x,
            player.velocity.y,
            player.pos.z,
            player.velocity.z
        )

        // 3. Rotor spin animations
        player.mainRotorAngle = (player.mainRotorAngle + 38.0f * safeDelta) % (kotlin.math.PI.toFloat() * 2f)
        player.tailRotorAngle = (player.tailRotorAngle + 48.0f * safeDelta) % (kotlin.math.PI.toFloat() * 2f)

        // 4. Weapons processing (Machine guns, Rockets, Missiles, Flares, Overdrive)
        updateWeapons(safeDelta)

        // 5. Update Target-Lock system
        updateTargetLock(safeDelta)

        // 6. Progressive Endless Environment & Hazards
        updateEnvironment(safeDelta)

        // 7. Enemy AI & Spawner
        updateEnemies(safeDelta)

        // 8. Boss AI
        updateBoss(safeDelta)

        // 9. Projectile Physics
        updateProjectiles(safeDelta)

        // 10. Collision Detection
        checkCollisions()

        // 11. Particle lifecycles
        updateParticles(safeDelta)

        // 12. Threat alerts
        threatAlert?.let {
            it.timer -= safeDelta
            if (it.timer <= 0f) {
                threatAlert = null
            }
        }

        hudSyncTimer += safeDelta
        if (hudSyncTimer >= HUD_SYNC_INTERVAL || isGameOver || isVictory) {
            hudSyncTimer = 0f
            updateHud()
        }
    }

    private fun spawnHqPerimeterDefense(hq: EnemyHeadquarters) {
        if (enemies.size >= MAX_ACTIVE_ENEMIES) return
        spawnEnemy(EnemyCategory.GROUND_TANK, hq.pos.x - 14f, hq.pos.y - 16f, alerted = true)
        spawnEnemy(EnemyCategory.GROUND_TANK, hq.pos.x + 14f, hq.pos.y - 16f, alerted = true)
        spawnEnemy(EnemyCategory.MISSILE_LAUNCHER, hq.pos.x - 8f, hq.pos.y - 24f, alerted = true)
        spawnEnemy(EnemyCategory.ATTACK_HELICOPTER, hq.pos.x + 8f, hq.pos.y - 10f, alerted = true)
    }

    /**
     * Precision 4-Directional Movement (Preserved from Phase 1):
     * - Left and Right NEVER rotate the helicopter heading.
     * - Nose strictly points towards top of screen (+Y).
     * - Independent touch controls with smooth acceleration, deceleration, and diagonal flight.
     * - Battlefield boundary enforcement.
     */
    private fun updatePlayerMovement(delta: Float) {
        var inputX = joystickInputX.coerceIn(-1f, 1f)
        var inputY = joystickInputY.coerceIn(-1f, 1f)

        if (isLeftPressed) inputX -= 1f
        if (isRightPressed) inputX += 1f
        if (isUpPressed) inputY += 1f
        if (isDownPressed) inputY -= 1f

        val inputLen = kotlin.math.sqrt(inputX * inputX + inputY * inputY)
        val dirX: Float
        val dirY: Float
        if (inputLen > 1f) {
            dirX = inputX / inputLen
            dirY = inputY / inputLen
        } else if (inputLen > 0.001f) {
            dirX = inputX
            dirY = inputY
        } else {
            dirX = 0f
            dirY = 0f
        }

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

        // Open-world boundary clamping (unbounded +Y in Endless Survival)
        player.pos.x = player.pos.x.coerceIn(minX, maxX)
        player.pos.y = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            player.pos.y.coerceAtLeast(minY)
        } else {
            player.pos.y.coerceIn(minY, maxY)
        }

        // Option 1: Smooth, bounded automatic dynamic flight altitude (predictable cruise envelope)
        val hoverBob = if (inputLen <= 0.001f) sin(gameTime * 2.0f) * 0.12f else 0f
        val targetZ = (9.5f + (if (dirY >= 0f) dirY * 2.6f else dirY * 1.4f) + kotlin.math.abs(dirX) * 0.35f + hoverBob)
            .coerceIn(7.8f, 12.6f)
        val zDiff = targetZ - player.pos.z
        val desiredVelZ = (zDiff * 3.2f).coerceIn(-5.5f, 5.5f)
        val velZSmooth = 1f - kotlin.math.exp(-7.0f * delta)
        player.velocity.z += (desiredVelZ - player.velocity.z) * velZSmooth
        player.pos.z = (player.pos.z + player.velocity.z * delta).coerceIn(7.5f, 13.0f)

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
                addBullet(TracerBullet(leftMuzzle, Vector3D(0f, bulletSpeed, 0f), damage = dmg, isPlayer = true))
                addBullet(TracerBullet(rightMuzzle, Vector3D(0f, bulletSpeed, 0f), damage = dmg, isPlayer = true))
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

        // 3. Rocket Cooldown & Reliable Deterministic Auto-Recharge
        if (player.rocketCooldownTimer > 0f) {
            player.rocketCooldownTimer = (player.rocketCooldownTimer - delta).coerceAtLeast(0f)
        }
        if (player.rocketAmmo < player.maxRocketAmmo) {
            rocketRechargeTimer += delta
            if (rocketRechargeTimer >= 1.35f) {
                rocketRechargeTimer = 0f
                player.rocketAmmo = (player.rocketAmmo + 1).coerceAtMost(player.maxRocketAmmo)
            }
        } else {
            rocketRechargeTimer = 0f
        }

        // 4. Guided Missile Cooldown & Reliable Deterministic Auto-Recharge
        if (player.missileTimer > 0f) {
            player.missileTimer = (player.missileTimer - delta).coerceAtLeast(0f)
        }
        if (player.missileAmmo < player.maxMissileAmmo) {
            missileRechargeTimer += delta
            if (missileRechargeTimer >= 2.2f) {
                missileRechargeTimer = 0f
                player.missileAmmo = (player.missileAmmo + 1).coerceAtMost(player.maxMissileAmmo)
            }
        } else {
            missileRechargeTimer = 0f
        }

        // 5. Countermeasure Flares Cooldown (Auto-Recharges every 7.5s)
        if (player.flareCooldownTimer > 0f) {
            player.flareCooldownTimer = (player.flareCooldownTimer - delta).coerceAtLeast(0f)
        }

        // 6. Overdrive Plasma Cannon Auto-Charge (+3.5%/sec + combat hits)
        if (player.overdriveCharge < 100f) {
            player.overdriveCharge = (player.overdriveCharge + delta * 3.5f).coerceAtMost(100f)
        }
    }

    /**
     * Tactical Weapon 1: Chaff / EMP Countermeasure Flares — intercepts incoming enemy bullets & missiles.
     */
    fun activateFlareCountermeasure() {
        if (player.flareCooldownTimer > 0f || isGameOver) return
        player.flareCooldownTimer = player.flareCooldownMax
        player.flareActiveTimer = 1.4f
        player.invulnerableTimer = kotlin.math.max(player.invulnerableTimer, 1.2f)

        bullets.removeAll { !it.isPlayer && (it.pos - player.pos).length() < 28f }
        missiles.removeAll { it.isEnemy && (it.pos - player.pos).length() < 32f }

        // Disrupt nearby aerial threats
        for (enemy in enemies) {
            if ((enemy.pos - player.pos).length() < 26f) {
                enemy.attackTimer = -1.5f
                enemy.hitFlashTimer = 0.2f
            }
        }

        createExplosion(player.pos, Color(0xFFFFD600), count = 24, speed = 12f)
        createExplosion(player.pos, Color(0xFF00E5FF), count = 16, speed = 10f)
        audioSystem?.playPlasmaCharge()
        threatAlert = ThreatAlert("FLARE COUNTERMEASURES DEPLOYED — MISSILES DEFLECTED!", Color(0xFF00E5FF), 2.2f)
        updateHud()
    }

    /**
     * Tactical Weapon 2: Overdrive Plasma Cannon — unleashes high-impact piercing bolts.
     */
    fun activateOverdriveBeam() {
        if (player.overdriveCharge < 100f || isGameOver) return
        player.overdriveCharge = 0f
        player.overdriveBeamTimer = 0.85f
        camera.screenShake = 3.5f
        audioSystem?.playPlasmaCharge()

        val offsets = listOf(-2.4f, -1.2f, 0f, 1.2f, 2.4f)
        for (xOff in offsets) {
            val origin = Vector3D(player.pos.x + xOff, player.pos.y + 2.5f, player.pos.z - 0.1f)
            addBullet(
                TracerBullet(
                    pos = origin,
                    velocity = Vector3D(xOff * 1.4f, 88.0f, 0f),
                    damage = player.mgDamage * 3.8f,
                    isPlayer = true,
                    life = 2.0f,
                    isPlayerPlasma = true
                )
            )
        }
        createExplosion(Vector3D(player.pos.x, player.pos.y + 3f, player.pos.z), Color(0xFF00E5FF), count = 18, speed = 8f)
        threatAlert = ThreatAlert("OVERDRIVE PLASMA CANNON FIRED!", Color(0xFF00E5FF), 2.0f)
        updateHud()
    }

    /**
     * Target-Lock Guided Missile Acquisition System
     */
    private fun updateTargetLock(delta: Float) {
        // Find best target in forward cone (boss or closest high-priority enemy) within radar range without allocating temporary lists
        val boss = activeBoss
        val candidateId: String? = if (boss != null) {
            boss.id
        } else {
            val maxDistSq = player.radarRange * player.radarRange
            var bestId: String? = null
            var bestDistSq = Float.MAX_VALUE
            for (enemy in enemies) {
                if (enemy.pos.y > player.pos.y) {
                    val dx = enemy.pos.x - player.pos.x
                    val dy = enemy.pos.y - player.pos.y
                    val dz = enemy.pos.z - player.pos.z
                    val dSq = dx * dx + dy * dy + dz * dz
                    if (dSq <= maxDistSq && dSq < bestDistSq) {
                        bestDistSq = dSq
                        bestId = enemy.id
                    }
                }
            }
            bestId
        }

        if (candidateId != null) {
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
            addRocket(rocket)
            createExplosion(podOffset, Color(0xFFFF9100), count = 4, speed = 3f)
        }
        updateHud()
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
        addMissile(GuidedMissile(UUID.randomUUID().toString(), leftPod, Vector3D(-2.5f, 22.0f, 0f), targetId, damage = dmg))
        addMissile(GuidedMissile(UUID.randomUUID().toString(), rightPod, Vector3D(2.5f, 22.0f, 0f), targetId, damage = dmg))

        createExplosion(leftPod, Color(0xFFFF6D00), count = 8, speed = 5f)
        createExplosion(rightPod, Color(0xFFFF6D00), count = 8, speed = 5f)
        updateHud()
    }

    /**
     * AI implementation for the 8 Enemy Types with Medium -> Hard progression scaling
     * and Territory Proximity Alertness.
     */
    private fun updateEnemies(delta: Float) {
        val diff = difficultyFactor // 0.0 -> 1.0
        val fireRateScale = 1.0f - (diff * 0.40f)
        val speedScale = 1.0f + (diff * 0.35f)
        val dmgMult = currentWaveDamageMultiplier

        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            if (waveTransitionTimer > 0f) {
                waveTransitionTimer = (waveTransitionTimer - delta).coerceAtLeast(0f)
                if (waveTransitionTimer <= 0f) {
                    val burst = minOf(3, (waveTotalEnemies - waveEnemiesSpawned).coerceAtLeast(0))
                    repeat(burst) {
                        spawnEndlessWaveEnemy()
                    }
                }
            } else if (waveEnemiesSpawned < waveTotalEnemies && enemies.size < 8 && activeBoss == null) {
                enemySpawnTimer -= delta
                if (enemySpawnTimer <= 0f) {
                    enemySpawnTimer = (1.65f - (diff * 0.85f)).coerceAtLeast(0.55f)
                    spawnEndlessWaveEnemy()
                }
            }
        } else {
            // Campaign progressive spawn with wave scaling (capped for mobile stability)
            if (enemies.size < 8 && activeBoss == null) {
                enemySpawnTimer -= delta
                if (enemySpawnTimer <= 0f) {
                    enemySpawnTimer = (2.4f - (diff * 1.15f)).coerceAtLeast(0.85f)
                    spawnProgressiveEnemy()
                }
            }
        }

        val it = enemies.iterator()
        while (it.hasNext()) {
            val enemy = it.next()
            enemy.strafePhase += delta
            if (enemy.hitFlashTimer > 0f) enemy.hitFlashTimer = (enemy.hitFlashTimer - delta).coerceAtLeast(0f)

            val dx = player.pos.x - enemy.pos.x
            val dy = player.pos.y - enemy.pos.y
            val distToPlayer = kotlin.math.sqrt(dx * dx + dy * dy)

            // Trigger alertness as soon as player approaches territory
            if (!enemy.isAlerted && distToPlayer < 58f) {
                enemy.isAlerted = true
            }

            // Predictive lead aim scales with difficulty
            val leadSec = 0.25f * diff
            val predictedTarget = Vector3D(
                player.pos.x + player.velocity.x * leadSec,
                player.pos.y + player.velocity.y * leadSec,
                player.pos.z
            )
            val aimToPlayer = (predictedTarget - enemy.pos).normalized()
            val alertBoost = if (enemy.isAlerted) speedScale else 1.0f

            when (enemy.category) {
                EnemyCategory.ATTACK_HELICOPTER -> {
                    enemy.rotorAngle = (enemy.rotorAngle + 35.0f * delta) % (kotlin.math.PI.toFloat() * 2f)
                    enemy.turretAngle = atan2(dx, dy)
                    val desiredY = player.pos.y + 18.0f
                    val yDiff = desiredY - enemy.pos.y
                    val moveY = yDiff.coerceIn(-7.5f * alertBoost, 7.5f * alertBoost)
                    enemy.pos.y += moveY * delta
                    val flankPush = if (enemy.isAlerted) (player.pos.x - enemy.pos.x).coerceIn(-6f, 6f) * 0.35f else 0f
                    enemy.pos.x = (enemy.pos.x + (sin(enemy.strafePhase * 2.2f) * 7.5f * alertBoost + flankPush) * delta).coerceIn(minX + 4f, maxX - 4f)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 1.4f * fireRateScale && distToPlayer < 48f && enemy.pos.y > player.pos.y - 6f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = enemy.pos.z - 0.2f), aimToPlayer * (28.0f * speedScale), damage = 12f * dmgMult, isPlayer = false))
                        if (diff > 0.35f || Math.random() < 0.4) {
                            addBullet(TracerBullet(Vector3D(enemy.pos.x - 0.9f, enemy.pos.y, enemy.pos.z), aimToPlayer * 26.0f, damage = 14f * dmgMult, isPlayer = false))
                            addBullet(TracerBullet(Vector3D(enemy.pos.x + 0.9f, enemy.pos.y, enemy.pos.z), aimToPlayer * 26.0f, damage = 14f * dmgMult, isPlayer = false))
                        }
                    }
                }

                EnemyCategory.FIGHTER_AIRCRAFT -> {
                    enemy.pos.y += enemy.velocity.y * speedScale * delta
                    enemy.pos.x = (enemy.pos.x + (enemy.velocity.x + cos(enemy.strafePhase * 3.0f) * 4.2f) * delta).coerceIn(minX, maxX)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 0.28f * fireRateScale && distToPlayer < 52f && enemy.pos.y > player.pos.y) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(), Vector3D(aimToPlayer.x * 12f, -42.0f, 0f), damage = 15f * dmgMult, isPlayer = false))
                    }
                }

                EnemyCategory.GROUND_TANK -> {
                    val patrolSpeedY = if (dy < -22f) -3.4f * alertBoost else if (dy > -12f) 1.6f else 0f
                    enemy.pos.y = clampEnemyY(enemy.pos.y + patrolSpeedY * delta)
                    val trackX = if (enemy.isAlerted) (player.pos.x - enemy.pos.x).coerceIn(-3f, 3f) * 0.45f else 0f
                    enemy.pos.x = (enemy.pos.x + (sin(enemy.strafePhase * 0.95f) * 2.5f + trackX) * delta).coerceIn(minX + 2f, maxX - 2f)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.1f * fireRateScale && distToPlayer < 48f && enemy.pos.y > player.pos.y - 8f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = 0.8f), aimToPlayer * (33.0f * speedScale), damage = 24f * dmgMult, isPlayer = false, isTankShell = true))
                        createExplosion(enemy.pos.copy(z = 0.8f), Color(0xFFFF9100), count = 6, speed = 4f)
                    }
                }

                EnemyCategory.AA_TURRET -> {
                    enemy.turretAngle = atan2(dx, dy)
                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 1.9f * fireRateScale && distToPlayer < 48f && enemy.pos.y > player.pos.y - 6f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = 1.0f), aimToPlayer * 31.0f, damage = 20f * dmgMult, isPlayer = false, isFlakBurst = true))
                    }
                }

                EnemyCategory.MISSILE_LAUNCHER -> {
                    val samMoveY = if (dy < -28f) -2.5f * alertBoost else 0f
                    enemy.pos.y = clampEnemyY(enemy.pos.y + samMoveY * delta)
                    enemy.pos.x = (enemy.pos.x + cos(enemy.strafePhase * 0.7f) * 1.8f * delta).coerceIn(minX + 2f, maxX - 2f)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 3.1f * fireRateScale && distToPlayer < 55f && enemy.pos.y > player.pos.y - 10f) {
                        enemy.attackTimer = 0f
                        val missile = GuidedMissile(
                            id = UUID.randomUUID().toString(),
                            pos = enemy.pos.copy(z = 1.2f),
                            velocity = Vector3D(aimToPlayer.x * 6f, -15.0f, 4.0f),
                            targetId = "PLAYER",
                            damage = 34f * dmgMult,
                            isEnemy = true
                        )
                        addMissile(missile)
                        createExplosion(enemy.pos.copy(z = 1.2f), Color(0xFFFF3D00), count = 8, speed = 5f)
                    }
                }

                EnemyCategory.INFANTRY_SOLDIER -> {
                    val flankMove = if (enemy.isAlerted) (player.pos.x - enemy.pos.x).coerceIn(-4f, 4f) * 0.4f else 0f
                    enemy.pos.x = (enemy.pos.x + (cos(enemy.strafePhase * 1.9f) * 2.5f + flankMove) * delta).coerceIn(minX + 1f, maxX - 1f)
                    enemy.pos.y = clampEnemyY(enemy.pos.y + sin(enemy.strafePhase * 1.2f) * 1.2f * delta)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 1.55f * fireRateScale && distToPlayer < 42f && enemy.pos.y > player.pos.y - 10f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = 0.5f), aimToPlayer * 25.0f, damage = 8f * dmgMult, isPlayer = false))
                    }
                }

                EnemyCategory.ROCKET_SOLDIER -> {
                    enemy.pos.x = (enemy.pos.x + sin(enemy.strafePhase * 1.6f) * 2.2f * delta).coerceIn(minX + 1f, maxX - 1f)
                    enemy.pos.y = clampEnemyY(enemy.pos.y + cos(enemy.strafePhase * 1.0f) * 1.0f * delta)
                    enemy.turretAngle = atan2(dx, dy)

                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.65f * fireRateScale && distToPlayer < 45f && enemy.pos.y > player.pos.y - 12f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = 0.8f), aimToPlayer * 22.0f, damage = 22f * dmgMult, isPlayer = false, isFlakBurst = true))
                    }
                }

                EnemyCategory.WATCHTOWER_SNIPER -> {
                    enemy.turretAngle = atan2(dx, dy)
                    enemy.attackTimer += delta
                    if (enemy.attackTimer >= 2.1f * fireRateScale && distToPlayer < 48f && enemy.pos.y > player.pos.y - 8f) {
                        enemy.attackTimer = 0f
                        addBullet(TracerBullet(enemy.pos.copy(z = 4.0f), aimToPlayer * 34.0f, damage = 16f * dmgMult, isPlayer = false))
                    }
                }
            }

            if (enemy.pos.y < camera.y - 16.0f && enemy.category == EnemyCategory.FIGHTER_AIRCRAFT) {
                it.remove()
                if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                    waveEnemiesSpawned = (waveEnemiesSpawned - 1).coerceAtLeast(waveEnemiesDefeated)
                }
            } else if (enemy.pos.y < camera.y - 24.0f) {
                it.remove()
                if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                    waveEnemiesSpawned = (waveEnemiesSpawned - 1).coerceAtLeast(waveEnemiesDefeated)
                }
            }
        }
    }

    private fun clampEnemyY(y: Float): Float {
        return if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            y.coerceAtLeast(minY)
        } else {
            y.coerceIn(minY, maxY)
        }
    }

    private fun spawnEndlessWaveEnemy() {
        if (waveEnemiesSpawned >= waveTotalEnemies || enemies.size >= MAX_ACTIVE_ENEMIES) return
        val spawnX = (player.pos.x + ((Math.random().toFloat() - 0.5f) * 38f)).coerceIn(minX + 4f, maxX - 4f)
        val spawnY = player.pos.y + 30f + (Math.random().toFloat() * 12f)
        val categories = when (selectedEmpire) {
            EmpireType.DRAGON -> listOf(EnemyCategory.ATTACK_HELICOPTER, EnemyCategory.AA_TURRET, EnemyCategory.ROCKET_SOLDIER, EnemyCategory.GROUND_TANK)
            EmpireType.ALIEN -> listOf(EnemyCategory.FIGHTER_AIRCRAFT, EnemyCategory.AA_TURRET, EnemyCategory.MISSILE_LAUNCHER, EnemyCategory.INFANTRY_SOLDIER)
            EmpireType.ORC -> listOf(EnemyCategory.GROUND_TANK, EnemyCategory.ROCKET_SOLDIER, EnemyCategory.INFANTRY_SOLDIER, EnemyCategory.ATTACK_HELICOPTER)
            EmpireType.HUMAN -> listOf(EnemyCategory.FIGHTER_AIRCRAFT, EnemyCategory.GROUND_TANK, EnemyCategory.MISSILE_LAUNCHER, EnemyCategory.WATCHTOWER_SNIPER)
        }
        val category = if (currentWave <= 2) {
            categories.take(3).random()
        } else {
            EnemyCategory.values().random()
        }
        if (spawnEnemy(category, spawnX, spawnY, alerted = true)) {
            waveEnemiesSpawned++
        }
    }

    private fun spawnProgressiveEnemy() {
        val spawnX = (player.pos.x + ((Math.random().toFloat() - 0.5f) * 44f)).coerceIn(minX + 4f, maxX - 4f)
        val spawnY = (player.pos.y + 34.0f + (Math.random().toFloat() * 10f)).coerceAtMost(maxY - 20f)

        val categories = EnemyCategory.values()
        val category = categories.random()
        spawnEnemy(category, spawnX, spawnY, alerted = true)
    }

    private fun spawnEnemy(category: EnemyCategory, x: Float, y: Float, alerted: Boolean = false): Boolean {
        if (enemies.size >= MAX_ACTIVE_ENEMIES) return false
        val hpMultiplier = currentWaveHpMultiplier
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
                isGroundUnit = isGround,
                isAlerted = alerted
            )
        )
        return true
    }

    /**
     * Boss System: Stays inside Main Headquarters until all 50 Barracks are destroyed
     * AND Player triggers the HQ Danger Zone. 100-Hit endurance with Medium->Hard evasion AI.
     */
    fun spawnEmpireBoss() {
        if (activeBoss != null) return
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

        val spawnPos = enemyHeadquarters?.let { hq ->
            if (kotlin.math.abs(player.pos.y - hq.pos.y) <= HQ_DANGER_ZONE_RADIUS + 40f) {
                Vector3D(hq.pos.x, hq.pos.y - 10f, 10.5f)
            } else {
                Vector3D(player.pos.x, player.pos.y + 34.0f, 10.5f)
            }
        } ?: Vector3D(0f, player.pos.y + 34.0f, 10.5f)

        activeBoss = BossCombatEntity(
            id = UUID.randomUUID().toString(),
            empire = selectedEmpire,
            name = bossName,
            pos = spawnPos,
            health = bossHp,
            maxHealth = bossHp,
            shield = bossShield,
            maxShield = bossShield,
            phase = 1,
            weakPoint = weakPoint,
            subsystems = subsystems,
            bossHitsRemaining = 100,
            maxBossHits = 100
        )
        audioSystem?.playBossWarning()
        threatAlert = ThreatAlert("HQ DANGER ZONE: $bossName EMERGED! (100 HITS TO DEFEAT)", Color(0xFFFF1744), 6.0f)
        camera.screenShake = 5.0f
        createExplosion(spawnPos, selectedEmpire.primaryColor, count = 24, speed = 10f)
    }

    private fun updateBoss(delta: Float) {
        val boss = activeBoss ?: return
        boss.animationPhase += delta * 2.2f * boss.enrageFactor
        if (boss.hitFlashTimer > 0f) boss.hitFlashTimer = (boss.hitFlashTimer - delta).coerceAtLeast(0f)
        if (boss.evadeCooldownTimer > 0f) boss.evadeCooldownTimer = (boss.evadeCooldownTimer - delta).coerceAtLeast(0f)

        // Check phase transitions by HP OR remaining hit threshold (66 / 33 out of 100)
        val hitPct = boss.bossHitsRemaining.toFloat() / boss.maxBossHits.toFloat()
        val hpPct = kotlin.math.min(boss.health / boss.maxHealth, hitPct)
        if (hpPct <= 0.33f && boss.phase < 3) {
            boss.phase = 3
            boss.enrageFactor = 1.5f
            boss.weakPoint?.isExposed = true
            threatAlert = ThreatAlert("BOSS WARNING: ${boss.name} ENTERED PHASE 3 ENRAGED!", Color(0xFFFF1744), 5.0f)
        } else if (hpPct <= 0.66f && boss.phase < 2) {
            boss.phase = 2
            boss.enrageFactor = 1.25f
            threatAlert = ThreatAlert("BOSS WARNING: ${boss.name} ENTERED PHASE 2 AGGRESSIVE!", Color(0xFFFF9100), 4.0f)
        }

        // Intelligent Evasive AI: Boss dodges incoming player rockets & guided missiles laterally
        val incomingThreat = missiles.any { !it.isEnemy && (it.pos - boss.pos).length() < 24f } ||
            rockets.any { (it.pos - boss.pos).length() < 20f }
        if (incomingThreat && !boss.isEvading && boss.evadeCooldownTimer <= 0f) {
            boss.isEvading = true
            boss.evadeTimer = 0.65f
            boss.evadeCooldownTimer = if (boss.phase >= 2) 2.4f else 3.4f
            boss.evadeDirection = if (boss.pos.x >= player.pos.x) -1f else 1f
            createExplosion(boss.pos, Color(0xFF00E5FF), count = 6, speed = 5f)
        }

        // Hover & Evasion Flight Positioning
        val targetY = (player.pos.y + 22.0f).coerceIn(minY + 12f, maxY - 12f)
        boss.pos.y += (targetY - boss.pos.y) * 1.8f * delta
        if (boss.isEvading) {
            boss.evadeTimer -= delta
            boss.pos.x = (boss.pos.x + boss.evadeDirection * 24f * delta).coerceIn(minX + 12f, maxX - 12f)
            if (boss.evadeTimer <= 0f) {
                boss.isEvading = false
            }
        } else {
            val weaveTargetX = player.pos.x + sin(boss.animationPhase * 0.85f * boss.enrageFactor) * 10.5f
            boss.pos.x += (weaveTargetX - boss.pos.x) * 2.2f * delta
        }
        when (boss.currentAttackMode) {
            BossAttackMode.AERIAL_CHARGE -> {
                boss.modeTimer += delta
                val diveT = (boss.modeTimer / 1.2f).coerceIn(0f, 1f)
                val diveY = sin(diveT * kotlin.math.PI.toFloat()) * 18.0f
                boss.chargeOffset = Vector3D(0f, -diveY, -diveY * 0.25f)
                val currentPos = boss.pos + boss.chargeOffset
                if ((currentPos - player.pos).length() < 3.8f) {
                    applyPlayerIncomingDamage(rawDamage = 32f, hitWeight = 1)
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
                    applyPlayerIncomingDamage(rawDamage = 36f, hitWeight = 1)
                    camera.screenShake = 4.0f
                }
                if (boss.modeTimer >= 1.3f) {
                    boss.currentAttackMode = BossAttackMode.CRUISING
                    boss.chargeOffset = Vector3D(0f, 0f, 0f)
                }
            }
            BossAttackMode.FIRE_BREATH -> {
                boss.modeTimer += delta
                bossFireBreathCooldown -= delta
                if (bossFireBreathCooldown <= 0f) {
                    bossFireBreathCooldown = 0.12f
                    val aim = (player.pos - (boss.pos + boss.chargeOffset)).normalized()
                    addBullet(TracerBullet(boss.pos.copy(y = boss.pos.y - 1.5f), aim * 34f, damage = 14f, isPlayer = false, isFlakBurst = true))
                    createExplosion(boss.pos.copy(y = boss.pos.y - 1.5f), Color(0xFFFF5722), count = 3, speed = 6f)
                }
                if (boss.modeTimer >= 1.5f) {
                    boss.currentAttackMode = BossAttackMode.CRUISING
                    bossFireBreathCooldown = 0f
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
                                    addBullet(TracerBullet(boss.pos.copy(z = 10f), fireDir * 28.0f, damage = 18f, isPlayer = false))
                                }
                            } else {
                                boss.currentAttackMode = BossAttackMode.WING_SHOCKWAVE
                                addBullet(TracerBullet(boss.pos.copy(), Vector3D(-24f, -12f, 0f), damage = 16f, isPlayer = false, isFlakBurst = true))
                                addBullet(TracerBullet(boss.pos.copy(), Vector3D(24f, -12f, 0f), damage = 16f, isPlayer = false, isFlakBurst = true))
                                camera.screenShake = 2.0f
                            }
                        }
                        boss.phase == 2 -> {
                            when {
                                roll < 0.35 -> {
                                    boss.currentAttackMode = BossAttackMode.FIRE_BREATH
                                    boss.modeTimer = 0f
                                    bossFireBreathCooldown = 0f
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
                                        addBullet(TracerBullet(boss.pos.copy(z = 10f), fireDir * 32.0f, damage = 20f, isPlayer = false))
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
                                bossFireBreathCooldown = 0f
                                audioSystem?.playDragonRoar()
                            }
                            for (i in -3..3) {
                                val fireDir = Vector3D(aim.x + (i * 0.15f), aim.y, aim.z).normalized()
                                addBullet(TracerBullet(boss.pos.copy(z = 10f), fireDir * 36.0f, damage = 24f, isPlayer = false))
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
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x - 4.2f), aim * 26f, damage = 18f, isPlayer = false))
                            }
                            if (rightAlive) {
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x + 4.2f), aim * 26f, damage = 18f, isPlayer = false))
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
                                addMissile(m1)
                                addMissile(m2)
                            }
                        }
                        else -> {
                            // Phase 3: Antimatter sweep
                            boss.currentAttackMode = BossAttackMode.ANTIMATTER_SWEEP
                            audioSystem?.playPlasmaCharge()
                            for (i in -4..4) {
                                addBullet(TracerBullet(boss.pos.copy(), Vector3D(aim.x + i * 0.09f, -34f, 0f), damage = 22f, isPlayer = false))
                            }
                            if (leftAlive || rightAlive) {
                                val m = GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(), Vector3D(0f, -18f, 0f), "PLAYER", 35f, isEnemy = true)
                                addMissile(m)
                            }
                        }
                    }
                }

                EmpireType.ORC -> {
                    when {
                        boss.phase == 1 -> {
                            boss.currentAttackMode = BossAttackMode.SIEGE_CANNONS
                            audioSystem?.playHeavyCannon()
                            addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x - 2.5f), aim * 24f, damage = 35f, isPlayer = false, isTankShell = true))
                            addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x + 2.5f), aim * 24f, damage = 35f, isPlayer = false, isTankShell = true))
                        }
                        boss.phase == 2 -> {
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.FLAK_MINEFIELD
                                for (i in -2..2) {
                                    addBullet(TracerBullet(boss.pos.copy(), Vector3D(aim.x + i * 0.15f, aim.y * 0.8f, 0f) * 22f, damage = 20f, isPlayer = false, isFlakBurst = true))
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
                                    addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x + i * 1.5f), aim * 28f, damage = 30f, isPlayer = false, isTankShell = true))
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
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x - 1f, y = boss.pos.y - 2.5f), aim * 36f, damage = 16f, isPlayer = false))
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x + 1f, y = boss.pos.y - 2.5f), aim * 36f, damage = 16f, isPlayer = false))
                            }
                        }
                        boss.phase == 2 -> {
                            if (roll < 0.5) {
                                boss.currentAttackMode = BossAttackMode.MISSILE_SALVO
                                if (leftMissileAlive) {
                                    addMissile(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x - 3.8f), Vector3D(-2f, -16f, 0f), "PLAYER", 35f, isEnemy = true))
                                }
                                if (rightMissileAlive) {
                                    addMissile(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x + 3.8f), Vector3D(2f, -16f, 0f), "PLAYER", 35f, isEnemy = true))
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
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x - 1f, y = boss.pos.y - 2.5f), aim * 38f, damage = 18f, isPlayer = false))
                                addBullet(TracerBullet(boss.pos.copy(x = boss.pos.x + 1f, y = boss.pos.y - 2.5f), aim * 38f, damage = 18f, isPlayer = false))
                            }
                            if (leftMissileAlive) {
                                addMissile(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x - 3.8f), Vector3D(-2.5f, -18f, 0f), "PLAYER", 36f, isEnemy = true))
                            }
                            if (rightMissileAlive) {
                                addMissile(GuidedMissile(UUID.randomUUID().toString(), boss.pos.copy(x = boss.pos.x + 3.8f), Vector3D(2.5f, -18f, 0f), "PLAYER", 36f, isEnemy = true))
                            }
                        }
                    }
                }
            }
        }

        // Check Boss Defeat (100 Hits OR HP <= 0 unlocks Final Siege on 1000-Hit Main Headquarters)
        if (boss.bossHitsRemaining <= 0 || boss.health <= 0f) {
            onBossDefeated(boss)
        }
    }

    private fun onBossDefeated(boss: BossCombatEntity) {
        triggerBossDestruction(boss)
        activeBoss = null
        enemyHeadquarters?.isBossDefeated = true
        threatAlert = ThreatAlert(
            "BOSS DEFEATED! DESTROY MAIN HEADQUARTERS CITADEL (1,000 HITS)!",
            Color(0xFF00E676),
            8.0f
        )
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

    fun applyDamageToBoss(damage: Float, hitPos: Vector3D, discreteHits: Int = 1): Boolean {
        val boss = activeBoss ?: return false
        val currentBossPos = boss.pos + boss.chargeOffset

        // 1. Check Destructible Subsystems
        for (subsystem in boss.subsystems) {
            if (!subsystem.isDestroyed) {
                val subPos = currentBossPos + subsystem.offset
                if ((hitPos - subPos).length() < 3.2f) {
                    val wasDestroyed = subsystem.takeDamage(damage)
                    boss.registerHit(discreteHits, isWeakPointHit = false)
                    createExplosion(subPos, Color(0xFFFF9100), count = 6, speed = 5f)
                    if (wasDestroyed) {
                        createExplosion(subPos, Color(0xFFFF3D00), count = 30, speed = 12f)
                        score += 3500
                        camera.screenShake = 3.0f
                        audioSystem?.playExplosion()
                        threatAlert = ThreatAlert("SUBSYSTEM DESTROYED: ${subsystem.name}", Color(0xFF00E676), 3.5f)
                    }
                    if (boss.bossHitsRemaining <= 0 || boss.health <= 0f) {
                        onBossDefeated(boss)
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
                    boss.registerHit(discreteHits, isWeakPointHit = true)
                    createExplosion(wpPos, Color(0xFFFFD600), count = 16, speed = 8f)
                    camera.screenShake = 2.4f
                    if (boss.bossHitsRemaining <= 0 || boss.health <= 0f) {
                        onBossDefeated(boss)
                    }
                    return true
                }
            }
        }

        // 3. Main Hull Hit
        if ((hitPos - currentBossPos).length() < 7.8f) {
            boss.takeDamage(damage, isWeakPointHit = false)
            boss.registerHit(discreteHits, isWeakPointHit = false)
            createExplosion(hitPos, boss.empire.primaryColor, count = 8, speed = 6f)
            if (boss.bossHitsRemaining <= 0 || boss.health <= 0f) {
                onBossDefeated(boss)
            }
            return true
        }

        return false
    }

    private fun updateProjectiles(delta: Float) {
        val minProjY = camera.y - 30f
        val maxProjY = player.pos.y + 85f

        // Bullets
        val bIt = bullets.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            b.pos.x += b.velocity.x * delta
            b.pos.y += b.velocity.y * delta
            b.pos.z += b.velocity.z * delta
            b.life -= delta
            val outOfCorridor = b.pos.y < minProjY || b.pos.y > maxProjY || kotlin.math.abs(b.pos.x - player.pos.x) > 85f
            if (b.life <= 0f || outOfCorridor) {
                if (b.life <= 0f && b.isFlakBurst) {
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

            if (r.life <= 0f || r.pos.y > maxProjY) {
                if (r.life <= 0f) {
                    createExplosion(r.pos, Color(0xFFFF6D00), count = 12, speed = 8f)
                }
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
                val turnRate = (6.0f * delta).coerceAtMost(1.0f)
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

            if (m.life <= 0f || m.pos.y < minProjY - 15f || m.pos.y > maxProjY + 25f) {
                if (m.life <= 0f) {
                    createExplosion(m.pos, Color(0xFFFF3D00), count = 16, speed = 9f)
                }
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
            val bulletHits = if (b.isPlayerPlasma) 6 else 1
            val barracksBulletDmg = if (b.isPlayerPlasma) 6f else 1f
            val hqBulletHits = if (b.isPlayerPlasma) 15 else 2

            // Boss check
            if (applyDamageToBoss(b.damage, b.pos, discreteHits = bulletHits)) {
                player.overdriveCharge = (player.overdriveCharge + 1.2f).coerceAtMost(100f)
                bIt.remove()
                continue
            }

            // Barracks check (100 hits per barracks)
            var hitBarracks = false
            for (barracks in allBarracks) {
                if (!barracks.isDestroyed && (b.pos - barracks.pos).length() < 5.0f) {
                    hitBarracks = true
                    damageBarracks(barracks, barracksBulletDmg)
                    player.overdriveCharge = (player.overdriveCharge + 1.0f).coerceAtMost(100f)
                    createExplosion(b.pos, if (b.isPlayerPlasma) Color(0xFF00E5FF) else Color(0xFFFFD600), count = 5, speed = 6f)
                    bIt.remove()
                    break
                }
            }
            if (hitBarracks) continue

            // Headquarters check (1,000 hits after 50/50 barracks & Boss defeat)
            enemyHeadquarters?.let { hq ->
                if (!hq.isDestroyed && (b.pos - hq.pos).length() < 10.5f) {
                    if (hq.isUnlocked && hq.isBossDefeated) {
                        val explodedHq = hq.takeHitDamage(hqBulletHits)
                        player.overdriveCharge = (player.overdriveCharge + 1.5f).coerceAtMost(100f)
                        createExplosion(b.pos, Color(0xFFFF9100), count = 6, speed = 7f)
                        if (explodedHq) {
                            destroyHeadquarters()
                        }
                    } else if ((b.pos - hq.pos).length() < 10.5f) {
                        createExplosion(b.pos, Color(0xFF00E5FF), count = 3, speed = 4f)
                    }
                    bIt.remove()
                    hitBarracks = true
                }
            }
            if (hitBarracks) continue

            for (enemy in enemies) {
                val hitDist = if (enemy.isGroundUnit) 3.0f else 2.4f
                if ((b.pos - enemy.pos).length() < hitDist) {
                    enemy.health -= b.damage
                    enemy.isAlerted = true
                    enemy.hitFlashTimer = 0.14f
                    player.overdriveCharge = (player.overdriveCharge + 1.8f).coerceAtMost(100f)
                    createExplosion(b.pos, if (b.isPlayerPlasma) Color(0xFF00E5FF) else Color(0xFFFFD600), count = 4, speed = 5f)
                    if (!b.isPlayerPlasma) {
                        bIt.remove()
                    }
                    break
                }
            }
        }

        // 2. Rockets (MRL) vs Enemies & Boss
        val rIt = rockets.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            var exploded = false

            // Boss check (6 hits per rocket)
            if (applyDamageToBoss(r.damage, r.pos, discreteHits = 6)) {
                exploded = true
            }

            if (!exploded) {
                // Barracks splash check (8 hits per rocket)
                for (barracks in allBarracks) {
                    if (!barracks.isDestroyed && (r.pos - barracks.pos).length() < r.splashRadius + 2.0f) {
                        damageBarracks(barracks, 8f)
                        exploded = true
                        break
                    }
                }
            }

            if (!exploded) {
                // HQ splash check (12 hits per rocket)
                enemyHeadquarters?.let { hq ->
                    if (hq.isUnlocked && hq.isBossDefeated && !hq.isDestroyed && (r.pos - hq.pos).length() < r.splashRadius + 5.0f) {
                        if (hq.takeHitDamage(12)) destroyHeadquarters()
                        exploded = true
                    }
                }
            }

            if (!exploded) {
                for (enemy in enemies) {
                    if ((r.pos - enemy.pos).length() < r.splashRadius) {
                        enemy.health -= r.damage
                        enemy.isAlerted = true
                        enemy.hitFlashTimer = 0.18f
                        exploded = true
                        for (other in enemies) {
                            if (other.id != enemy.id && (other.pos - r.pos).length() < r.splashRadius) {
                                other.health -= r.damage * 0.6f
                                other.isAlerted = true
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
                // Player missile vs Boss (12 hits per missile)
                if (applyDamageToBoss(m.damage, m.pos, discreteHits = 12)) {
                    impacted = true
                }

                // Player missile vs Barracks (15 hits per missile)
                if (!impacted) {
                    for (barracks in allBarracks) {
                        if (!barracks.isDestroyed && (m.pos - barracks.pos).length() < 5.5f) {
                            damageBarracks(barracks, 15f)
                            impacted = true
                            break
                        }
                    }
                }

                // Player missile vs HQ (25 hits per missile)
                if (!impacted) {
                    enemyHeadquarters?.let { hq ->
                        if (hq.isUnlocked && hq.isBossDefeated && !hq.isDestroyed && (m.pos - hq.pos).length() < 10.5f) {
                            if (hq.takeHitDamage(25)) destroyHeadquarters()
                            impacted = true
                        }
                    }
                }

                // Player missile vs Enemies
                if (!impacted) {
                    for (enemy in enemies) {
                        if ((m.pos - enemy.pos).length() < 3.2f) {
                            enemy.health -= m.damage
                            enemy.isAlerted = true
                            enemy.hitFlashTimer = 0.2f
                            impacted = true
                            for (other in enemies) {
                                if (other.id != enemy.id && (other.pos - m.pos).length() < 6.5f) {
                                    other.health -= m.damage * 0.5f
                                    other.isAlerted = true
                                }
                            }
                            break
                        }
                    }
                }
            } else {
                // Enemy missile vs Player
                if ((m.pos - player.pos).length() < 2.2f) {
                    applyPlayerIncomingDamage(rawDamage = m.damage, hitWeight = 1)
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
                applyPlayerIncomingDamage(rawDamage = b.damage, hitWeight = 1)
                camera.screenShake = 2.0f
                createExplosion(b.pos, Color(0xFF00E5FF), count = 6, speed = 5f)
                ebIt.remove()
            }
        }

        // 5. Check Destroyed Enemies
        val eIt = enemies.iterator()
        while (eIt.hasNext()) {
            val enemy = eIt.next()
            if (enemy.health <= 0f) {
                kills++
                score += enemy.scoreValue
                if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                    waveEnemiesDefeated++
                    endlessBestScore = maxOf(endlessBestScore, score)
                }
                player.overdriveCharge = (player.overdriveCharge + 8f).coerceAtMost(100f)
                createExplosion(enemy.pos, enemy.empire.primaryColor, count = 22, speed = 10f)
                audioSystem?.playExplosion()
                camera.screenShake = 1.6f
                eIt.remove()
            }
        }

        if (gameMode == GameMode.ENDLESS_SURVIVAL &&
            waveTransitionTimer <= 0f &&
            waveEnemiesDefeated >= waveTotalEnemies &&
            enemies.isEmpty()
        ) {
            completeCurrentEndlessWave()
        }
    }

    private fun completeCurrentEndlessWave() {
        if (gameMode != GameMode.ENDLESS_SURVIVAL || isGameOver) return
        val completedWave = currentWave
        if (!completedWaveNumbers.add(completedWave)) return

        val waveKills = waveTotalEnemies
        val currentEmp = selectedEmpire
        val nextWave = completedWave + 1
        val nextEmp = endlessEmpireForWave(nextWave)

        wavesCompleted++
        score += endlessWaveRewardScore(completedWave, waveKills)
        endlessBestWave = maxOf(endlessBestWave, nextWave)
        endlessBestScore = maxOf(endlessBestScore, score)

        onEndlessWaveCompleted?.invoke(
            EndlessWaveCompletionEvent(
                waveNumber = completedWave,
                waveKills = waveKills,
                empire = currentEmp,
                nextEmpire = nextEmp,
                survivalScore = score
            )
        )

        currentWave = nextWave
        waveTotalEnemies = endlessEnemyCountForWave(nextWave)
        waveEnemiesSpawned = 0
        waveEnemiesDefeated = 0
        waveTransitionTimer = 1.6f
        enemySpawnTimer = 0.4f

        if (nextEmp != currentEmp) {
            transitionEndlessEmpire(nextEmp, nextWave)
        } else {
            threatAlert = ThreatAlert(
                "WAVE $completedWave CLEARED! INCOMING WAVE $nextWave (${waveTotalEnemies} HOSTILES)",
                Color(0xFF00E676),
                3.2f
            )
            updateHud()
        }
    }

    private fun transitionEndlessEmpire(newEmpire: EmpireType, waveNumberForBanner: Int) {
        selectedEmpire = newEmpire
        hazards.clear()
        structures.clear()
        allBarracks.clear()
        enemyHeadquarters = null
        activeBoss = null
        bossSpawned = false
        furthestGeneratedY = (player.pos.y - 20f).coerceAtLeast(-20f)
        while (furthestGeneratedY < player.pos.y + 120f) {
            generateEnvironmentChunk(furthestGeneratedY)
            furthestGeneratedY += 30f
        }
        threatAlert = ThreatAlert(
            "WAVE $waveNumberForBanner // ENTERING ${newEmpire.title} THEATER!",
            newEmpire.primaryColor,
            4.5f
        )
        updateHud()
    }

    /**
     * Routes incoming combat damage through Energy Shield absorption and Titanium Armor
     * mitigation before penetrating into the 5-Heart x 10-Hit survival system.
     */
    fun applyPlayerIncomingDamage(rawDamage: Float, hitWeight: Int = 1) {
        if (isGameOver || isVictory || player.invulnerableTimer > 0f) return
        var remaining = rawDamage.coerceAtLeast(1f)

        if (player.shield > 0f) {
            val absorbed = kotlin.math.min(player.shield, remaining)
            player.shield = (player.shield - absorbed).coerceAtLeast(0f)
            remaining -= absorbed
            if (remaining <= 0.01f) {
                player.invulnerableTimer = 0.08f
                createExplosion(player.pos, Color(0xFF00E5FF), count = 5, speed = 4f)
                return
            }
        }

        if (remaining > 0f && player.armor > 0f) {
            val armorMitigated = kotlin.math.min(player.armor, remaining * 0.65f)
            player.armor = (player.armor - armorMitigated).coerceAtLeast(0f)
            remaining -= armorMitigated
            if (armorMitigated > 0f && remaining <= 2.0f) {
                player.hitFlashTimer = 0.12f
                player.invulnerableTimer = 0.08f
                createExplosion(player.pos, Color(0xFFFFB300), count = 4, speed = 4f)
                return
            }
        }

        applyPlayerHit(hitWeight)
    }

    /**
     * Central 5-Heart x 10-Hit player survival processor.
     */
    fun applyPlayerHit(hits: Int = 1) {
        if (isGameOver || isVictory || player.invulnerableTimer > 0f) return
        val heartLost = player.applyCombatHit(hits)
        if (player.hearts <= 0) {
            isGameOver = true
            threatAlert = ThreatAlert(
                "0/5 HEARTS REMAINING — WATCH AD TO CONTINUE?",
                Color(0xFFFF1744),
                10f
            )
            createExplosion(player.pos, Color(0xFFFF3D00), count = 45, speed = 16f)
            audioSystem?.playExplosion()
        } else if (heartLost) {
            threatAlert = ThreatAlert(
                "HEART LOST! ${player.hearts}/${player.maxHearts} HEARTS REMAINING (10 HITS/HEART)",
                Color(0xFFFF9100),
                2.5f
            )
            createExplosion(player.pos, Color(0xFFFF9100), count = 18, speed = 9f)
            audioSystem?.playExplosion()
        }
        updateHud()
    }

    fun damageBarracks(barracks: EnemyBarracks, damage: Float): Boolean {
        if (gameMode == GameMode.ENDLESS_SURVIVAL || barracks.isDestroyed) return false
        val wasDestroyed = barracks.takeDamage(damage)
        createExplosion(barracks.pos, Color(0xFFFF9100), count = 5, speed = 6f)
        if (wasDestroyed) {
            player.overdriveCharge = (player.overdriveCharge + 14f).coerceAtMost(100f)

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
                    barracksDestroyedCount = allBarracks.count { it.isDestroyed }
                }
            }

            audioSystem?.playExplosion()
            createExplosion(barracks.pos, Color(0xFFFF3D00), count = 26, speed = 12f)
            camera.screenShake = 2.5f

            val isHqUnlocked = barracksDestroyedCount >= 50 || (campaignService?.isHeadquartersUnlocked(selectedEmpire) == true)
            if (isHqUnlocked) {
                enemyHeadquarters?.let { hq ->
                    hq.isUnlocked = true
                    val distToHq = kotlin.math.hypot(player.pos.x - hq.pos.x, player.pos.y - hq.pos.y)
                    val inDangerZone = distToHq <= HQ_DANGER_ZONE_RADIUS || player.pos.y >= hq.pos.y - HQ_DANGER_ZONE_RADIUS
                    if (inDangerZone && !hq.isBossDefeated && !bossSpawned && activeBoss == null) {
                        spawnEmpireBoss()
                    } else {
                        threatAlert = ThreatAlert(
                            "ALL 50 ${selectedEmpire.name} BARRACKS DESTROYED! ADVANCE TO HQ DANGER ZONE FOR BOSS BATTLE!",
                            Color(0xFFFF1744),
                            8.0f
                        )
                    }
                }
                audioSystem?.playBossWarning()
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
        if (gameMode == GameMode.ENDLESS_SURVIVAL) return
        val hq = enemyHeadquarters ?: return
        if (hq.isDestroyed || (!hq.isUnlocked && !hq.isBossDefeated)) return
        if (campaignService != null &&
            barracksDestroyedCount < 50 &&
            !campaignService.isHeadquartersUnlocked(selectedEmpire)
        ) {
            return
        }
        hq.health = 0f
        hq.hitsRemaining = 0
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
        updateHud()
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
        val availableHeadroom = (MAX_PARTICLES - particles.size).coerceAtLeast(0)
        val spawnCount = if (particles.size >= MAX_PARTICLES) {
            minOf(count, 4)
        } else {
            minOf(count, maxOf(4, availableHeadroom))
        }
        for (i in 0 until spawnCount) {
            if (particles.size >= MAX_PARTICLES) {
                particles.removeAt(0)
            }
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
        val flareCdPct = (1.0f - (player.flareCooldownTimer / player.flareCooldownMax)).coerceIn(0f, 1f)
        val overdrivePct = (player.overdriveCharge / 100f).coerceIn(0f, 1f)

        val lockedName = activeBoss?.name ?: enemies.find { it.id == player.lockedTargetId }?.unitName
        val hq = enemyHeadquarters

        _hudState.value = GameHudState(
            health = player.health,
            maxHealth = player.maxHealth,
            armor = player.armor,
            maxArmor = player.maxArmor,
            shield = player.shield,
            maxShield = player.maxShield,
            hearts = player.hearts,
            maxHearts = player.maxHearts,
            hitsRemainingInHeart = player.hitsRemainingInHeart,
            hitsPerHeart = player.hitsPerHeart,
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
            flareCooldownPercent = flareCdPct,
            isFlareReady = player.flareCooldownTimer <= 0f,
            overdriveChargePercent = overdrivePct,
            isOverdriveReady = player.overdriveCharge >= 100f,
            isAutoFiring = player.isAutoFiring,
            activeThreat = threatAlert,
            isGameOver = isGameOver,
            isVictory = if (gameMode == GameMode.ENDLESS_SURVIVAL) false else isVictory,
            activeEmpire = selectedEmpire,
            stageNumber = getStageNumber(selectedEmpire),
            helicopterClass = player.heloClass,
            bossEntity = activeBoss,
            creditsEarned = creditsEarned,
            barracksDestroyedCount = barracksDestroyedCount,
            totalBarracks = if (gameMode == GameMode.ENDLESS_SURVIVAL) 0 else 50,
            isHeadquartersUnlocked = hq?.isUnlocked ?: false,
            isHqBossDefeated = hq?.isBossDefeated ?: false,
            isDangerZoneTriggered = hq?.isDangerZoneTriggered ?: false,
            hqHitsRemaining = hq?.hitsRemaining ?: 1000,
            hqMaxHits = hq?.maxHits ?: 1000,
            headquartersPos = hq?.pos,
            gameMode = gameMode,
            currentWave = currentWave,
            wavesCompleted = wavesCompleted,
            waveEnemiesRemaining = (waveTotalEnemies - waveEnemiesDefeated).coerceAtLeast(0),
            waveEnemiesTotal = waveTotalEnemies,
            endlessBestWave = maxOf(endlessBestWave, currentWave),
            endlessBestScore = maxOf(endlessBestScore, score),
            endlessSyncStatus = endlessSyncStatus
        )
    }

    fun transitionToEmpire(newEmpire: EmpireType) {
        selectedEmpire = newEmpire
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            transitionEndlessEmpire(newEmpire, currentWave)
            return
        }
        threatAlert = ThreatAlert("WAR THEATER: ${newEmpire.title} OPEN-WORLD SORTIE", newEmpire.primaryColor, 5.0f)
        hazards.clear()
        structures.clear()
        allBarracks.clear()
        barracksDestroyedCount = 0
        enemyHeadquarters = null
        activeBoss = null
        bossSpawned = false
        furthestGeneratedY = player.pos.y
        initOpenWorldObjectives()
        generateEnvironmentChunk(furthestGeneratedY)
        updateHud()
    }

    private fun initOpenWorldObjectives() {
        allBarracks.clear()
        // Procedurally distribute 50 distinct barracks across the expansive open-world theater
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
                health = if (isAlreadyDestroyed) 0f else 100f,
                maxHealth = 100f,
                hitsRemaining = if (isAlreadyDestroyed) 0 else 100,
                maxHits = 100,
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

            // Spawn localized defenders initially only for frontline barracks (Y <= 95)
            if (!isAlreadyDestroyed && posY <= 95f) {
                spawnBarracksDefenders(barracks)
            }
        }

        barracksDestroyedCount = allBarracks.count { it.isDestroyed }

        // Fortified Enemy Headquarters located at the apex of the territory (Y = 1020f), 1,000 Hits
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
            health = if (isHqDestroyed) 0f else 1000f,
            maxHealth = 1000f,
            hitsRemaining = if (isHqDestroyed) 0 else 1000,
            maxHits = 1000
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

    private fun spawnBarracksDefenders(barracks: EnemyBarracks) {
        if (barracks.isDestroyed || barracks.defendersSpawned) return
        barracks.defendersSpawned = true
        val defenders = barracks.defenderTypes
        if (defenders.size >= 2) {
            spawnEnemy(defenders[0], barracks.pos.x - 3.5f, barracks.pos.y + 1.5f, alerted = barracks.isAlertTriggered)
            spawnEnemy(defenders[1], barracks.pos.x + 3.5f, barracks.pos.y + 1.5f, alerted = barracks.isAlertTriggered)
        }
        if (defenders.size >= 3 && barracks.index % 2 == 0) {
            spawnEnemy(defenders[2], barracks.pos.x, barracks.pos.y + 4.0f, alerted = barracks.isAlertTriggered)
        }
    }

    private fun generateInitialEnvironment() {
        if (gameMode == GameMode.CAMPAIGN) {
            initOpenWorldObjectives()
        } else {
            allBarracks.clear()
            enemyHeadquarters = null
            barracksDestroyedCount = 0
        }
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

        // Territory proximity trigger: entering a barracks zone alerts the garrison & spawns tank/troop patrol
        for (b in allBarracks) {
            val distToBarracks = (b.pos - player.pos).length()
            if (!b.isDiscovered && distToBarracks < player.radarRange + 25f) {
                b.isDiscovered = true
            }
            if (!b.isDestroyed && !b.isAlertTriggered && distToBarracks < 52f) {
                b.isAlertTriggered = true
                if (threatAlert == null) {
                    threatAlert = ThreatAlert(
                        "TERRITORY ALERT: BARRACKS #${b.index} GARRISON MOBILIZED!",
                        selectedEmpire.primaryColor,
                        2.0f
                    )
                }
                if (enemies.size < MAX_ACTIVE_ENEMIES) {
                    spawnEnemy(EnemyCategory.GROUND_TANK, b.pos.x, b.pos.y - 4f, alerted = true)
                }
            }
            if (!b.isDestroyed && !b.defendersSpawned &&
                b.pos.y >= player.pos.y - 20f && b.pos.y <= player.pos.y + 75f &&
                enemies.size < MAX_ACTIVE_ENEMIES
            ) {
                spawnBarracksDefenders(b)
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
                    applyPlayerIncomingDamage(rawDamage = h.damage, hitWeight = 1)
                    camera.screenShake = 1.8f
                    createExplosion(player.pos, selectedEmpire.primaryColor, count = 3, speed = 4f)
                }
            }

            // Safe open-world cleanup of distant hazards
            if (h.pos.y < camera.y - 80.0f) {
                hIt.remove()
            }
        }
        while (hazards.size > MAX_HAZARDS) {
            hazards.removeAt(0)
        }

        // Clean up distant decorative structures to maintain Android performance (keeping Barracks and HQ intact)
        structures.removeAll {
            it.type != StructureType.BARRACKS_FORT && it.type != StructureType.HEADQUARTERS_FORT && it.pos.y < camera.y - 80.0f
        }
        while (structures.size > MAX_STRUCTURES) {
            val idx = structures.indexOfFirst {
                it.type != StructureType.BARRACKS_FORT && it.type != StructureType.HEADQUARTERS_FORT
            }
            if (idx >= 0) structures.removeAt(idx) else break
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
