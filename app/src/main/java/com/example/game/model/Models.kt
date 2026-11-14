package com.example.game.model

import androidx.compose.ui.graphics.Color

enum class EmpireType(
    val title: String,
    val subtitle: String,
    val primaryColor: Color,
    val accentColor: Color,
    val description: String,
    val units: List<String>,
    val bossName: String,
    val weapons: String,
    val environmentName: String,
    val hazardName: String,
    val hazardDescription: String,
    val imageResName: String
) {
    DRAGON(
        title = "DRAGON EMPIRE",
        subtitle = "Primordial Wyrm Sovereignty",
        primaryColor = Color(0xFFFF3D00),
        accentColor = Color(0xFFFFD600),
        description = "Ancient winged leviathans and pyro-sorcery fleets ruling the volcanic airspace. Their breath can melt titanium armor in seconds.",
        units = listOf("Pyro Drakes", "Dragon Riders", "Fire Wyverns", "Cinder Skyships"),
        bossName = "Ignis Rex: The World Burner",
        weapons = "Napalm Fireballs, Thermal Shockwaves, Lava Mortars",
        environmentName = "Volcanic Caldera & Ashlands",
        hazardName = "Lava Geyser Surges",
        hazardDescription = "Erupting superheated subterranean magma columns bursting into mid-air.",
        imageResName = "env_dragon_volcano_1794120842626"
    ),
    ALIEN(
        title = "ALIEN EMPIRE",
        subtitle = "Zeta Reticuli Armada",
        primaryColor = Color(0xFFD500F9),
        accentColor = Color(0xFF00E5FF),
        description = "Advanced extraterrestrial invaders utilizing zero-point energy, anti-gravity saucers, and devastating antimatter particle beams.",
        units = listOf("UFO Interceptors", "Plasma Drones", "Tri-Fighter Swarms", "Mothership Harvesters"),
        bossName = "Overseer Prime: Mothership Dreadnought",
        weapons = "Plasma Discs, Tractor Beams, Tachyon Torpedoes",
        environmentName = "Bio-Luminescent Alien Planet",
        hazardName = "Ion Vortex Discharges",
        hazardDescription = "Unstable atmospheric plasma arcs and lightning fields disrupting flight.",
        imageResName = "env_alien_planet_1794120862388"
    ),
    ORC(
        title = "ORC EMPIRE",
        subtitle = "Ironfang Warmongers",
        primaryColor = Color(0xFFC2410C),
        accentColor = Color(0xFF84CC16),
        description = "Brutal mechanical hordes driven by diesel, rage, and scrap metal fortress blimps. They rely on heavy armor, flak cannons, and sheer numbers.",
        units = listOf("Scrap Copters", "Battle Kites", "Bombardier Zeppelins", "Troll Siege Crawlers"),
        bossName = "Warlord Gashrak's Sky Fortress",
        weapons = "Heavy Flak Shrapnel, Rocket Harpoons, Scrap Cannons",
        environmentName = "Barren Fantasy Scrap Wasteland",
        hazardName = "Toxic Smog Firepits",
        hazardDescription = "Blazing sulfur pits and burning scrap refinery trenches emitting corrosive smog.",
        imageResName = "env_orc_wasteland_1794120878509"
    ),
    HUMAN(
        title = "HUMAN EMPIRE",
        subtitle = "Rogue Blackwatch Syndicate",
        primaryColor = Color(0xFF0284C7),
        accentColor = Color(0xFFF59E0B),
        description = "A rogue military-industrial faction equipped with cutting-edge supersonic jets, stealth attack helicopters, and smart cruise missile arrays.",
        units = listOf("Rogue AH-64s", "Su-57 Stealth Jets", "Mobile SAM Launchers", "Hover-Tanks"),
        bossName = "Project Titan: Strategic Fortress Carrier",
        weapons = "Heat-seeking Stingers, 30mm Gatling, Cluster Bombs",
        environmentName = "Naval Offshore Airbase & Tarmac Strip",
        hazardName = "EMP Perimeter Defense Grids",
        hazardDescription = "High-voltage oscillating electronic defense fences zapping airborne systems.",
        imageResName = "env_human_airbase_1794120893118"
    )
}

enum class HelicopterClass(
    val id: String,
    val title: String,
    val role: String,
    val baseHealth: Float,
    val baseArmor: Float,
    val baseSpeed: Float,
    val baseMgDamage: Float,
    val baseMissileDamage: Float,
    val baseRocketCapacity: Int,
    val baseMissileCapacity: Int,
    val baseShield: Float,
    val unlockLevel: Int,
    val unlockCost: Int,
    val themeColor: Color,
    val description: String
) {
    SCOUT(
        id = "helo_scout",
        title = "AH-6X VIPER SCOUT",
        role = "LIGHT RECON / HIGH AGILITY",
        baseHealth = 75f,
        baseArmor = 35f,
        baseSpeed = 30f,
        baseMgDamage = 10f,
        baseMissileDamage = 65f,
        baseRocketCapacity = 12,
        baseMissileCapacity = 6,
        baseShield = 40f,
        unlockLevel = 1,
        unlockCost = 0,
        themeColor = Color(0xFFFFD600),
        description = "Stripped-down ultralight airframe with twin turboshafts. Exceptional acceleration and lateral maneuverability with reduced radar signature."
    ),
    HEAVY_GUNSHIP(
        id = "helo_heavy",
        title = "AH-64 TITAN GUNSHIP",
        role = "HEAVY ASSAULT / FLYING TANK",
        baseHealth = 150f,
        baseArmor = 100f,
        baseSpeed = 19f,
        baseMgDamage = 18f,
        baseMissileDamage = 110f,
        baseRocketCapacity = 24,
        baseMissileCapacity = 12,
        baseShield = 80f,
        unlockLevel = 2,
        unlockCost = 8000,
        themeColor = Color(0xFFC2410C),
        description = "Titanium-reinforced armored gunship carrying 30mm chain guns and dual MRL rocket pods. Built to withstand sustained flak barrages."
    ),
    STEALTH(
        id = "helo_stealth",
        title = "RAH-66 SHADOW GHOST",
        role = "STEALTH RECON / PRECISION STRIKE",
        baseHealth = 90f,
        baseArmor = 50f,
        baseSpeed = 26f,
        baseMgDamage = 14f,
        baseMissileDamage = 95f,
        baseRocketCapacity = 16,
        baseMissileCapacity = 8,
        baseShield = 60f,
        unlockLevel = 3,
        unlockCost = 15000,
        themeColor = Color(0xFF7C4DFF),
        description = "Faceted composite radar-absorbent fuselage with enclosed tail rotor. Advanced target locking and evasive ECM countermeasure suite."
    ),
    ULTIMATE_WAR(
        id = "helo_ultimate",
        title = "AH-99 APEX PREDATOR",
        role = "HYBRID WAR MACHINE / PLASMA WEAPONS",
        baseHealth = 130f,
        baseArmor = 85f,
        baseSpeed = 28f,
        baseMgDamage = 22f,
        baseMissileDamage = 130f,
        baseRocketCapacity = 20,
        baseMissileCapacity = 10,
        baseShield = 120f,
        unlockLevel = 4,
        unlockCost = 25000,
        themeColor = Color(0xFF00E5FF),
        description = "Next-gen prototype integrating alien plasma conduits and military avionics. Maximum upgrade ceiling with continuous energy shields."
    )
}

enum class HelicopterSkin(val id: String, val label: String, val primaryColor: Color, val accentColor: Color) {
    TACTICAL_BLACK("skin_black", "Tactical Midnight", Color(0xFF1E2836), Color(0xFF00E5FF)),
    MILITARY_CAMO("skin_camo", "Desert Mirage", Color(0xFF4A4432), Color(0xFFFFB300)),
    ARCTIC_WHITE("skin_arctic", "Arctic Shiver", Color(0xFFCFD8DC), Color(0xFF00B0FF)),
    BLOOD_DRAGON("skin_dragon", "Crimson Fury", Color(0xFF880E4F), Color(0xFFFF1744)),
    PLASMA_NEON("skin_neon", "Cyber Hazard", Color(0xFF0D47A1), Color(0xFF00E676))
}

data class HelicopterUpgrades(
    var engineLevel: Int = 1,    // 1 to 5: Increases max speed & acceleration (+8% per level)
    var armorLevel: Int = 1,     // 1 to 5: Increases health & armor (+12% per level)
    var weaponLevel: Int = 1,    // 1 to 5: Increases MG damage & rocket ammo (+10% per level)
    var defenseLevel: Int = 1,   // 1 to 5: Increases energy shield & recharge rate (+15% per level)
    var radarLevel: Int = 1      // 1 to 5: Increases target-lock speed & weak-point range
) {
    fun getUpgradeCost(level: Int): Int = level * 1500

    fun calculateEffectiveSpeed(base: Float): Float =
        base * (1.0f + (engineLevel - 1) * 0.08f)

    fun calculateEffectiveHealth(base: Float): Float =
        base * (1.0f + (armorLevel - 1) * 0.12f)

    fun calculateEffectiveArmor(base: Float): Float =
        base * (1.0f + (armorLevel - 1) * 0.12f)

    fun calculateEffectiveMgDamage(base: Float): Float =
        base * (1.0f + (weaponLevel - 1) * 0.10f)

    fun calculateEffectiveMissileDamage(base: Float): Float =
        base * (1.0f + (weaponLevel - 1) * 0.10f)

    fun calculateEffectiveRocketAmmo(base: Int): Int =
        base + (weaponLevel - 1) * 4

    fun calculateEffectiveShield(base: Float): Float =
        base * (1.0f + (defenseLevel - 1) * 0.15f)
}

data class PlayerProfile(
    val callsign: String = "VIPER-7",
    val rank: String = "WING COMMANDER",
    val battleRating: Int = 2480,
    var credits: Int = 18500,
    val totalKills: Int = 412,
    val highScore: Int = 126400,
    val missionsCompleted: Int = 28,
    val selectedEmpire: EmpireType = EmpireType.DRAGON,
    var selectedHelicopterClass: HelicopterClass = HelicopterClass.SCOUT,
    var selectedSkin: HelicopterSkin = HelicopterSkin.TACTICAL_BLACK,
    val unlockedHelicopters: MutableSet<String> = mutableSetOf(HelicopterClass.SCOUT.id),
    val upgradesMap: MutableMap<String, HelicopterUpgrades> = mutableMapOf(
        HelicopterClass.SCOUT.id to HelicopterUpgrades(),
        HelicopterClass.HEAVY_GUNSHIP.id to HelicopterUpgrades(),
        HelicopterClass.STEALTH.id to HelicopterUpgrades(),
        HelicopterClass.ULTIMATE_WAR.id to HelicopterUpgrades()
    )
) {
    fun getUpgrades(heloClass: HelicopterClass): HelicopterUpgrades {
        return upgradesMap.getOrPut(heloClass.id) { HelicopterUpgrades() }
    }
}

data class HelicopterStats(
    val modelName: String = "AH-6X VIPER SCOUT",
    val maxHealth: Float = 75f,
    val maxArmor: Float = 35f,
    val flightSpeed: Float = 30f,
    val machineGunDps: Int = 120,
    val missileDamage: Int = 65,
    val missileCooldownSeconds: Float = 3.2f,
    val maxMissilePayload: Int = 6,
    val shield: Float = 40f
)

data class LeaderboardEntry(
    val rank: Int,
    val callsign: String,
    val empireKilled: EmpireType,
    val score: Int,
    val kills: Int,
    val countryCode: String,
    val isCurrentPlayer: Boolean = false
)

data class WarOrder(
    val id: String,
    val title: String,
    val empire: EmpireType,
    val requirement: String,
    val current: Int,
    val target: Int,
    val rewardCredits: Int,
    val isCompleted: Boolean = false
)
