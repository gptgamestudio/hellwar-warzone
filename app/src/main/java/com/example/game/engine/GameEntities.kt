package com.example.game.engine

import androidx.compose.ui.graphics.Color
import com.example.game.model.EmpireType

enum class EnemyCategory(val label: String, val baseHp: Float, val scoreVal: Int) {
    ATTACK_HELICOPTER("ATTACK HELICOPTER", 85f, 450),
    FIGHTER_AIRCRAFT("FIGHTER AIRCRAFT", 65f, 550),
    GROUND_TANK("GROUND TANK", 120f, 600),
    AA_TURRET("AA FLAK TURRET", 100f, 500),
    MISSILE_LAUNCHER("SAM MISSILE LAUNCHER", 90f, 650),
    INFANTRY_SOLDIER("MACHINE GUN INFANTRY", 35f, 150),
    ROCKET_SOLDIER("ROCKET LAUNCHER TROOPER", 45f, 220),
    WATCHTOWER_SNIPER("WATCHTOWER SENTRY", 50f, 260)
}

data class EnemyBarracks(
    val id: String,
    val index: Int, // 1 to 50
    val empire: EmpireType,
    val name: String,
    var pos: Vector3D,
    var health: Float = 100f,
    val maxHealth: Float = 100f,
    var isDestroyed: Boolean = false,
    var isDiscovered: Boolean = false,
    val defenderIds: MutableList<String> = mutableListOf(),
    val defenderTypes: List<EnemyCategory> = listOf(EnemyCategory.INFANTRY_SOLDIER, EnemyCategory.ROCKET_SOLDIER),
    val rewardCredits: Int = 200,
    val scoreReward: Int = 1000,
    var defendersSpawned: Boolean = false,
    var hitsRemaining: Int = 100,
    val maxHits: Int = 100,
    var hitFlashTimer: Float = 0f,
    var isAlertTriggered: Boolean = false
) {
    fun takeDamage(amount: Float): Boolean {
        if (isDestroyed) return false
        health = kotlin.math.max(0f, health - amount)
        hitsRemaining = kotlin.math.ceil(health).toInt().coerceIn(0, maxHits)
        hitFlashTimer = 0.16f
        if (health <= 0f || hitsRemaining <= 0) {
            health = 0f
            hitsRemaining = 0
            isDestroyed = true
            return true
        }
        return false
    }
}

data class EnemyHeadquarters(
    val id: String,
    val empire: EmpireType,
    val name: String,
    var pos: Vector3D,
    var isUnlocked: Boolean = false,
    var isDestroyed: Boolean = false,
    var health: Float = 1000f,
    val maxHealth: Float = 1000f,
    var hitsRemaining: Int = 1000,
    val maxHits: Int = 1000,
    var isBossDefeated: Boolean = false,
    var isDangerZoneTriggered: Boolean = false,
    var hitFlashTimer: Float = 0f
) {
    fun takeHitDamage(hits: Int): Boolean {
        if (!isUnlocked || !isBossDefeated || isDestroyed) return false
        val safeHits = hits.coerceAtLeast(1)
        hitsRemaining = (hitsRemaining - safeHits).coerceAtLeast(0)
        health = hitsRemaining.toFloat()
        hitFlashTimer = 0.14f
        if (hitsRemaining <= 0) {
            health = 0f
            isDestroyed = true
            return true
        }
        return false
    }
}

data class PlayerHelo(
    var pos: Vector3D = Vector3D(0f, 0f, 9.5f),
    var velocity: Vector3D = Vector3D(0f, 0f, 0f),
    var rollAngle: Float = 0f, // Banking left/right
    var pitchAngle: Float = 0f, // Pitch forward/back
    var mainRotorAngle: Float = 0f,
    var tailRotorAngle: Float = 0f,

    // Fleet & Customization
    var heloClass: com.example.game.model.HelicopterClass = com.example.game.model.HelicopterClass.SCOUT,
    var skin: com.example.game.model.HelicopterSkin = com.example.game.model.HelicopterSkin.TACTICAL_BLACK,
    var upgrades: com.example.game.model.HelicopterUpgrades = com.example.game.model.HelicopterUpgrades(),

    // Pro Hit-Heart System: 5 Main Hearts, 1 Heart = 10 Hits (50 hits total)
    var hearts: Int = 5,
    val maxHearts: Int = 5,
    var hitsRemainingInHeart: Int = 10,
    val hitsPerHeart: Int = 10,
    var invulnerableTimer: Float = 0f,
    var hitFlashTimer: Float = 0f,

    // Dynamic stats calculated from Class + Upgrades
    var health: Float = 75f,
    var maxHealth: Float = 75f,
    var armor: Float = 35f,
    var maxArmor: Float = 35f,
    var shield: Float = 40f,
    var maxShield: Float = 40f,
    var flightSpeed: Float = 30f,

    // Weapons Ammo and Timers
    var mgDamage: Float = 10f,
    var mgTimer: Float = 0f,
    var mgFireRate: Float = 0.10f,
    var isAutoFiring: Boolean = true,
    var activeMuzzleFlash: Boolean = false,

    // Multiple Rocket Launcher (MRL)
    var rocketDamage: Float = 45f,
    var rocketAmmo: Int = 12,
    var maxRocketAmmo: Int = 12,
    var rocketCooldownTimer: Float = 0f,
    val rocketCooldownMax: Float = 2.0f,

    // Target-Lock Guided Missiles
    var missileDamage: Float = 65f,
    var missileAmmo: Int = 6,
    var maxMissileAmmo: Int = 6,
    var missileTimer: Float = 0f,
    val missileCooldown: Float = 3.2f,

    // Tactical Weapon 1: Chaff / EMP Countermeasure Flares (Auto-recharging)
    var flareCooldownTimer: Float = 0f,
    val flareCooldownMax: Float = 7.5f,
    var flareActiveTimer: Float = 0f,

    // Tactical Weapon 2: Overdrive Plasma Cannon (Auto-recharging + combat charge)
    var overdriveCharge: Float = 40f,
    var overdriveBeamTimer: Float = 0f,

    // Target lock & Radar
    var lockedTargetId: String? = null,
    var lockProgress: Float = 0f,
    var radarRange: Float = 45f
) {
    fun applyConfiguration(
        newClass: com.example.game.model.HelicopterClass,
        newSkin: com.example.game.model.HelicopterSkin,
        newUpgrades: com.example.game.model.HelicopterUpgrades
    ) {
        heloClass = newClass
        skin = newSkin
        upgrades = newUpgrades

        maxHealth = upgrades.calculateEffectiveHealth(newClass.baseHealth)
        health = maxHealth
        maxArmor = upgrades.calculateEffectiveArmor(newClass.baseArmor)
        armor = maxArmor
        maxShield = upgrades.calculateEffectiveShield(newClass.baseShield)
        shield = maxShield
        flightSpeed = upgrades.calculateEffectiveSpeed(newClass.baseSpeed)

        mgDamage = upgrades.calculateEffectiveMgDamage(newClass.baseMgDamage)
        mgFireRate = when (newClass) {
            com.example.game.model.HelicopterClass.SCOUT -> 0.10f
            com.example.game.model.HelicopterClass.HEAVY_GUNSHIP -> 0.09f
            com.example.game.model.HelicopterClass.STEALTH -> 0.095f
            com.example.game.model.HelicopterClass.ULTIMATE_WAR -> 0.08f
        }
        val baseRocketDmg = when (newClass) {
            com.example.game.model.HelicopterClass.SCOUT -> 45f
            com.example.game.model.HelicopterClass.HEAVY_GUNSHIP -> 68f
            com.example.game.model.HelicopterClass.STEALTH -> 56f
            com.example.game.model.HelicopterClass.ULTIMATE_WAR -> 75f
        }
        rocketDamage = upgrades.calculateEffectiveRocketDamage(baseRocketDmg)
        missileDamage = upgrades.calculateEffectiveMissileDamage(newClass.baseMissileDamage)
        maxRocketAmmo = upgrades.calculateEffectiveRocketAmmo(newClass.baseRocketCapacity)
        rocketAmmo = maxRocketAmmo
        maxMissileAmmo = newClass.baseMissileCapacity + (upgrades.weaponLevel - 1) / 2
        missileAmmo = maxMissileAmmo
        radarRange = 35f + (upgrades.radarLevel * 10f)
    }

    fun takeDamage(amount: Float): Float {
        var remaining = amount

        // 1. Absorb with Energy Shield
        if (shield > 0f) {
            val shieldDmg = kotlin.math.min(shield, remaining)
            shield -= shieldDmg
            remaining -= shieldDmg
        }

        // 2. Absorb with Armor Plating
        if (remaining > 0f && armor > 0f) {
            val armorDmg = kotlin.math.min(armor, remaining)
            armor -= armorDmg
            remaining -= armorDmg
        }

        // 3. Apply to Hull
        if (remaining > 0f) {
            health = kotlin.math.max(0f, health - remaining)
        }
        return health
    }

    /**
     * Applies discrete combat hits toward the 5-Heart x 10-Hit survival rule.
     * Returns true if a Main Heart was broken on this hit.
     */
    fun applyCombatHit(hits: Int = 1): Boolean {
        if (hearts <= 0 || invulnerableTimer > 0f) return false
        hitsRemainingInHeart = (hitsRemainingInHeart - hits.coerceAtLeast(1))
        hitFlashTimer = 0.22f
        invulnerableTimer = 0.16f

        if (hitsRemainingInHeart <= 0) {
            hearts = (hearts - 1).coerceAtLeast(0)
            if (hearts > 0) {
                hitsRemainingInHeart = hitsPerHeart
                invulnerableTimer = 0.90f
                shield = maxShield
                armor = maxArmor
                health = maxHealth
            } else {
                hitsRemainingInHeart = 0
                shield = 0f
                armor = 0f
                health = 0f
            }
            return true
        } else {
            health = (maxHealth * (hitsRemainingInHeart.toFloat() / hitsPerHeart.toFloat())).coerceAtLeast(1f)
            return false
        }
    }

    fun restoreAllHearts() {
        hearts = maxHearts
        hitsRemainingInHeart = hitsPerHeart
        health = maxHealth
        armor = maxArmor
        shield = maxShield
        invulnerableTimer = 2.8f
        hitFlashTimer = 0f
    }
}

data class TracerBullet(
    var pos: Vector3D,
    var velocity: Vector3D,
    val damage: Float = 14f,
    val isPlayer: Boolean = true,
    var life: Float = 1.6f,
    val isTankShell: Boolean = false,
    val isFlakBurst: Boolean = false,
    val isPlayerPlasma: Boolean = false
)

data class RocketCluster(
    val id: String,
    var pos: Vector3D,
    var velocity: Vector3D,
    val damage: Float = 42f,
    val splashRadius: Float = 4.5f,
    var life: Float = 2.8f,
    val smokeTrail: MutableList<Vector3D> = mutableListOf()
)

data class GuidedMissile(
    val id: String,
    var pos: Vector3D,
    var velocity: Vector3D,
    var targetId: String? = null,
    val damage: Float = 110f,
    var life: Float = 4.0f,
    val isEnemy: Boolean = false,
    val smokeTrail: MutableList<Vector3D> = mutableListOf()
)

data class EnemyTarget(
    val id: String,
    val empire: EmpireType,
    val category: EnemyCategory,
    var pos: Vector3D,
    var velocity: Vector3D,
    var health: Float,
    val maxHealth: Float,
    val scoreValue: Int,
    val unitName: String,
    var strafePhase: Float = 0f,
    var attackTimer: Float = 0f,
    var turretAngle: Float = 0f,
    val isGroundUnit: Boolean = false,
    var rotorAngle: Float = 0f,
    var isAlerted: Boolean = false,
    var hitFlashTimer: Float = 0f,
    var dodgeTimer: Float = 0f
)

data class BossSubsystem(
    val id: String,
    val name: String,
    var health: Float,
    val maxHealth: Float,
    var isDestroyed: Boolean = false,
    val offset: Vector3D
) {
    fun takeDamage(amount: Float): Boolean {
        if (isDestroyed) return false
        health = kotlin.math.max(0f, health - amount)
        if (health <= 0f) {
            isDestroyed = true
            return true
        }
        return false
    }
}

data class BossWeakPoint(
    val name: String,
    val offset: Vector3D,
    val radius: Float = 2.4f,
    val damageMultiplier: Float = 2.2f,
    var isExposed: Boolean = true
)

enum class BossAttackMode {
    CRUISING,
    // Dragon Emperor Attacks
    FIREBALL_SALVO,
    FIRE_BREATH,
    WING_SHOCKWAVE,
    AERIAL_CHARGE,
    // Alien Overlord Attacks
    PLASMA_CANNONS,
    SUMMON_UFOS,
    HOMING_PLASMA,
    ANTIMATTER_SWEEP,
    // Orc Warlord Attacks
    SIEGE_CANNONS,
    FLAK_MINEFIELD,
    SUMMON_REINFORCEMENTS,
    RAMMING_SURGE,
    // Human Supreme Commander Attacks
    GATLING_BARRAGE,
    MISSILE_SALVO,
    JET_AIRSTRIKE_SUPPORT,
    TITAN_OVERDRIVE
}

data class BossCombatEntity(
    val id: String,
    val empire: EmpireType,
    val name: String,
    var pos: Vector3D,
    var velocity: Vector3D = Vector3D(0f, 0f, 0f),
    var health: Float,
    val maxHealth: Float,
    var phase: Int = 1,
    val maxPhases: Int = 3,
    var attackTimer: Float = 0f,
    var attackState: Int = 0,
    var currentAttackMode: BossAttackMode = BossAttackMode.CRUISING,
    var modeTimer: Float = 0f,
    var isInvulnerable: Boolean = false,
    var shield: Float = 120f,
    val maxShield: Float = 120f,
    var enrageFactor: Float = 1.0f,
    var animationPhase: Float = 0f,
    var chargeOffset: Vector3D = Vector3D(0f, 0f, 0f),
    val weakPoint: BossWeakPoint? = null,
    val subsystems: MutableList<BossSubsystem> = mutableListOf(),
    var bossHitsRemaining: Int = 100,
    val maxBossHits: Int = 100,
    var isEvading: Boolean = false,
    var evadeTimer: Float = 0f,
    var evadeCooldownTimer: Float = 0f,
    var evadeDirection: Float = 1f,
    var hitFlashTimer: Float = 0f
) {
    fun registerHit(hits: Int = 1, isWeakPointHit: Boolean = false): Int {
        if (isInvulnerable) return bossHitsRemaining
        val multiplied = if (isWeakPointHit && weakPoint?.isExposed == true) (hits * 2) else hits
        bossHitsRemaining = (bossHitsRemaining - multiplied.coerceAtLeast(1)).coerceAtLeast(0)
        hitFlashTimer = 0.14f
        return bossHitsRemaining
    }

    fun takeDamage(amount: Float, isWeakPointHit: Boolean = false): Float {
        if (isInvulnerable) return 0f
        val effectiveDamage = if (isWeakPointHit && weakPoint?.isExposed == true) amount * weakPoint.damageMultiplier else amount
        var remaining = effectiveDamage
        if (shield > 0f) {
            val shieldDmg = kotlin.math.min(shield, remaining)
            shield -= shieldDmg
            remaining -= shieldDmg
        }
        if (remaining > 0f) {
            health = kotlin.math.max(0f, health - remaining)
        }
        hitFlashTimer = 0.14f
        // Check phase transitions at 66% and 33%
        val hpPct = health / maxHealth
        if (hpPct <= 0.33f && phase < 3) {
            phase = 3
            enrageFactor = 1.5f
            shield = maxShield
            weakPoint?.isExposed = true
        } else if (hpPct <= 0.66f && phase < 2) {
            phase = 2
            enrageFactor = 1.25f
            shield = maxShield * 0.7f
        }
        return effectiveDamage
    }
}

data class VisualParticle(
    var pos: Vector3D,
    var velocity: Vector3D,
    val color: Color,
    val radius: Float,
    var life: Float,
    val maxLife: Float
)

data class ThreatAlert(
    val message: String,
    val color: Color,
    var timer: Float = 3.0f
)

enum class HazardType(val label: String, val warningText: String, val defaultDamage: Float) {
    LAVA_GEYSER("LAVA GEYSER", "MAGMA GEYSER SURGE DETECTED", 28f),
    PLASMA_LIGHTNING("PLASMA LIGHTNING", "ION VORTEX DISCHARGE DETECTED", 24f),
    SMOG_FIREPIT("SMOG FIREPIT", "TOXIC SCRAP FIRE HAZARD", 22f),
    EMP_GRID("EMP DEFENSE GRID", "HIGH VOLTAGE DEFENSE PERIMETER", 30f)
}

data class EnvironmentHazard(
    val id: String,
    val type: HazardType,
    val pos: Vector3D,
    val radius: Float = 3.8f,
    val damage: Float = 25f,
    var warningTimer: Float = 1.0f,
    var activeTimer: Float = 1.2f,
    var cycleTimer: Float = 0f,
    var state: HazardState = HazardState.DORMANT
)

enum class HazardState { DORMANT, WARNING, ACTIVE }

enum class StructureType {
    DRAGON_CASTLE,
    VOLCANIC_SPIRE,
    ALIEN_PYLON,
    MOTHERSHIP_PORTAL,
    ORC_WATCHTOWER,
    SIEGE_FORTRESS,
    MILITARY_HANGAR,
    MISSILE_SILO,
    RADAR_DOME,
    AIRCRAFT_CARRIER_DECK,
    BARRACKS_FORT,
    HEADQUARTERS_FORT,
    DENSE_FOREST,
    MOUNTAIN_HILL
}

data class TerrainStructure(
    val id: String,
    val type: StructureType,
    val empire: EmpireType,
    val pos: Vector3D,
    val width: Float,
    val length: Float,
    val height: Float
)
