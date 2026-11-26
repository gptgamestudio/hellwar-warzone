package com.example

import com.example.game.engine.BossCombatEntity
import com.example.game.engine.EnemyCategory
import com.example.game.engine.EnemyTarget
import com.example.game.engine.GameEngine
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `verify Phase 1 controls and nose orientation preserved`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        val initialX = engine.player.pos.x

        // 1. Move Left: Sideways only, roll bank tilt, zero yaw rotation
        engine.isLeftPressed = true
        engine.update(0.1f)
        assertTrue("Helicopter must move sideways left", engine.player.pos.x < initialX)
        assertTrue("Helicopter banks into roll without yaw rotation", engine.player.rollAngle > 0f)

        // 2. Strict boundary clamp check
        for (i in 0..150) {
            engine.update(0.1f)
        }
        assertTrue("Helicopter must not exceed min boundary", engine.player.pos.x >= engine.minX)

        engine.isLeftPressed = false
        engine.isRightPressed = true
        for (i in 0..200) {
            engine.update(0.1f)
        }
        assertTrue("Helicopter must not exceed max boundary", engine.player.pos.x <= engine.maxX)

        // 3. Diagonal flight test
        engine.resetBattle()
        val prevX = engine.player.pos.x
        val prevY = engine.player.pos.y
        engine.isUpPressed = true
        engine.isRightPressed = true
        engine.update(0.1f)
        assertTrue("Y advances forward", engine.player.pos.y > prevY)
        assertTrue("X moves right simultaneously", engine.player.pos.x > prevX)
    }

    @Test
    fun `verify automatic machine gun firing`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        engine.player.isAutoFiring = true
        engine.bullets.clear()

        // Update past fire rate interval
        engine.update(0.15f)
        assertTrue("Twin autocannons should produce tracer bullets", engine.bullets.size >= 2)
        assertTrue("Player bullets must be flagged as player", engine.bullets.all { it.isPlayer })
    }

    @Test
    fun `verify multiple rocket launcher barrage`() {
        val engine = GameEngine(selectedEmpire = EmpireType.ORC)
        val initialAmmo = engine.player.rocketAmmo
        assertEquals(engine.player.maxRocketAmmo, initialAmmo)
        assertEquals(0, engine.rockets.size)

        // Launch 4-rocket salvo
        engine.launchRocketBarrage()
        assertEquals("Should consume 4 rockets", initialAmmo - 4, engine.player.rocketAmmo)
        assertEquals("Should spawn 4 fan-spread rockets", 4, engine.rockets.size)
        assertTrue("Rocket cooldown must be triggered", engine.player.rocketCooldownTimer > 0f)

        // Subsequent launch while on cooldown should be ignored
        engine.launchRocketBarrage()
        assertEquals("Should not launch on cooldown", 4, engine.rockets.size)
    }

    @Test
    fun `verify target-lock guided missiles`() {
        val engine = GameEngine(selectedEmpire = EmpireType.ALIEN)
        val initialMissiles = engine.missiles.size
        val initialAmmo = engine.player.missileAmmo

        // Launch guided missiles
        engine.launchMissiles()
        assertEquals("Should launch twin guided missiles", initialMissiles + 2, engine.missiles.size)
        assertTrue("Missile timer must be active", engine.player.missileTimer > 0f)
        assertEquals(initialAmmo - 2, engine.player.missileAmmo)
    }

    @Test
    fun `verify 5 enemy AI types and distinct behaviors`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        engine.enemies.clear()

        // 1. Attack Helicopter: has spinning rotor and circle-strafes
        val helo = EnemyTarget("H1", EmpireType.HUMAN, EnemyCategory.ATTACK_HELICOPTER, Vector3D(0f, 20f, 9.5f), Vector3D(0f, -5f, 0f), 85f, 85f, 450, "AH Gunship")
        // 2. Fighter Aircraft: high speed flyby
        val jet = EnemyTarget("J1", EmpireType.HUMAN, EnemyCategory.FIGHTER_AIRCRAFT, Vector3D(2f, 25f, 9.5f), Vector3D(0f, -26f, 0f), 65f, 65f, 550, "Fighter Jet")
        // 3. Ground Tank: at Z=0.1 with rotating turret tracking player
        val tank = EnemyTarget("T1", EmpireType.HUMAN, EnemyCategory.GROUND_TANK, Vector3D(-3f, 18f, 0.1f), Vector3D(0f, -2.5f, 0f), 120f, 120f, 600, "Tank", isGroundUnit = true)
        // 4. AA Turret: ground bunker firing flak bursts
        val turret = EnemyTarget("A1", EmpireType.HUMAN, EnemyCategory.AA_TURRET, Vector3D(4f, 16f, 0.1f), Vector3D(0f, -1.5f, 0f), 100f, 100f, 500, "AA Turret", isGroundUnit = true)
        // 5. Missile Launcher: ground vehicle firing homing missiles
        val sam = EnemyTarget("S1", EmpireType.HUMAN, EnemyCategory.MISSILE_LAUNCHER, Vector3D(0f, 22f, 0.1f), Vector3D(0f, -2f, 0f), 90f, 90f, 650, "SAM Launcher", isGroundUnit = true)

        engine.enemies.addAll(listOf(helo, jet, tank, turret, sam))
        assertEquals(5, engine.enemies.size)

        // Process AI cycle
        engine.update(0.2f)

        assertTrue("Ground units must be positioned at ground level", tank.pos.z < 1.0f && turret.pos.z < 1.0f)
        assertTrue("Attack helo rotor angle should advance", helo.rotorAngle > 0f)
    }

    @Test
    fun `verify boss multi-phase system and destruction`() {
        val boss = BossCombatEntity(
            id = "B1",
            empire = EmpireType.DRAGON,
            name = "Ignis Rex: The World Burner",
            pos = Vector3D(0f, 30f, 10.5f),
            health = 600f,
            maxHealth = 600f,
            phase = 1
        )

        assertEquals(1, boss.phase)
        assertEquals(120f, boss.shield, 0.01f)

        // Take damage: 120 shield absorbed + 100 health damage -> 500 health (83% HP)
        boss.takeDamage(220f)
        assertEquals(0f, boss.shield, 0.01f)
        assertEquals(500f, boss.health, 0.01f)
        assertEquals("Should still be Phase 1", 1, boss.phase)

        // Deal damage down to < 66% (e.g. 350 health) -> Transitions to Phase 2
        boss.takeDamage(150f)
        assertEquals(2, boss.phase)
        assertTrue("Enrage factor should increase in phase 2", boss.enrageFactor > 1.0f)

        // Deal damage down to < 33%: absorbs 84 phase-2 shield + 166 health damage -> 184 health (30% HP)
        boss.takeDamage(250f)
        assertEquals(3, boss.phase)
        assertEquals(1.5f, boss.enrageFactor, 0.01f)

        // Final lethal damage
        boss.takeDamage(400f)
        assertEquals(0f, boss.health, 0.01f)
    }

    @Test
    fun `verify projectile collision and shooter damage immunity`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        val initialHp = engine.player.health

        // Verify player is not damaged by player-fired bullets
        engine.update(0.1f)
        assertEquals("Player bullets must never damage the player helicopter", initialHp, engine.player.health, 0.01f)
    }

    @Test
    fun `verify 4 empire battlefield environment generation and chunk culling`() {
        // Test Dragon Empire
        val dragonEngine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        assertTrue("Dragon battlefield should spawn volcanic structures", dragonEngine.structures.any { it.type == com.example.game.engine.StructureType.VOLCANIC_SPIRE })
        assertTrue("Dragon battlefield should spawn lava geysers", dragonEngine.hazards.any { it.type == com.example.game.engine.HazardType.LAVA_GEYSER })

        // Test Alien Empire
        val alienEngine = GameEngine(selectedEmpire = EmpireType.ALIEN)
        assertTrue("Alien battlefield should spawn alien pylons", alienEngine.structures.any { it.type == com.example.game.engine.StructureType.ALIEN_PYLON })
        assertTrue("Alien battlefield should spawn plasma lightning", alienEngine.hazards.any { it.type == com.example.game.engine.HazardType.PLASMA_LIGHTNING })

        // Test Orc Empire
        val orcEngine = GameEngine(selectedEmpire = EmpireType.ORC)
        assertTrue("Orc battlefield should spawn siege fortresses", orcEngine.structures.any { it.type == com.example.game.engine.StructureType.SIEGE_FORTRESS })
        assertTrue("Orc battlefield should spawn smog firepits", orcEngine.hazards.any { it.type == com.example.game.engine.HazardType.SMOG_FIREPIT })

        // Test Human Empire
        val humanEngine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        assertTrue("Human battlefield should spawn military hangars", humanEngine.structures.any { it.type == com.example.game.engine.StructureType.MILITARY_HANGAR })
        assertTrue("Human battlefield should spawn EMP defense grids", humanEngine.hazards.any { it.type == com.example.game.engine.HazardType.EMP_GRID })

        // Test progressive endless generation and culling
        val initStructCount = humanEngine.structures.size
        for (i in 0..100) {
            humanEngine.player.pos.y += 1.0f
            humanEngine.update(0.1f)
        }
        assertTrue("New chunks must be generated as player advances", humanEngine.furthestGeneratedY > 80f)
        assertTrue("Decorative structures behind camera should be culled", humanEngine.structures.filter { it.type != com.example.game.engine.StructureType.BARRACKS_FORT && it.type != com.example.game.engine.StructureType.HEADQUARTERS_FORT }.none { it.pos.y < humanEngine.camera.y - 80f })
    }

    @Test
    fun `verify environmental hazard warning and damage trigger`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        val initialHp = engine.player.health

        // Place a lava geyser directly on player X,Y coordinates
        val geyser = com.example.game.engine.EnvironmentHazard(
            id = "G1",
            type = com.example.game.engine.HazardType.LAVA_GEYSER,
            pos = Vector3D(engine.player.pos.x, engine.player.pos.y, 0.1f),
            radius = 5.0f,
            damage = 30f,
            cycleTimer = 0f,
            state = com.example.game.engine.HazardState.DORMANT
        )
        engine.hazards.clear()
        engine.hazards.add(geyser)

        // 1. While DORMANT: no damage
        engine.update(0.1f)
        assertEquals("Dormant hazard must not cause damage", initialHp, engine.player.health, 0.01f)

        // 2. Advance cycle to WARNING state
        geyser.cycleTimer = 4.25f
        engine.update(0.1f)
        assertEquals("Hazard enters WARNING state", com.example.game.engine.HazardState.WARNING, geyser.state)

        // 3. Advance cycle to ACTIVE state -> deals damage (after clearing spawn invulnerability)
        engine.player.invulnerableTimer = 0f
        geyser.cycleTimer = 1.3f
        engine.update(0.1f)
        assertEquals("Hazard enters ACTIVE state", com.example.game.engine.HazardState.ACTIVE, geyser.state)
        assertTrue(
            "Player must take hit/damage when standing in active hazard radius",
            engine.player.hitsRemainingInHeart < 10 || engine.player.health < initialHp || engine.player.shield < engine.player.maxShield
        )
    }

    @Test
    fun `verify seamless transition between empire battlefields`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        assertEquals(EmpireType.DRAGON, engine.selectedEmpire)

        // Transition to Alien Empire
        engine.transitionToEmpire(EmpireType.ALIEN)
        assertEquals(EmpireType.ALIEN, engine.selectedEmpire)
        assertTrue("Structures must be cleared and regenerated for new theater", engine.structures.any { it.empire == EmpireType.ALIEN })
        assertTrue("Alien hazards must be active", engine.hazards.any { it.type == com.example.game.engine.HazardType.PLASMA_LIGHTNING })

        // Transition to Orc Empire
        engine.transitionToEmpire(EmpireType.ORC)
        assertEquals(EmpireType.ORC, engine.selectedEmpire)
        assertTrue("Orc theater structures generated", engine.structures.any { it.empire == EmpireType.ORC })

        // Transition to Human Empire
        engine.transitionToEmpire(EmpireType.HUMAN)
        assertEquals(EmpireType.HUMAN, engine.selectedEmpire)
        assertTrue("Human theater structures generated", engine.structures.any { it.empire == EmpireType.HUMAN })
    }

    @Test
    fun `verify Dragon Emperor Ignis Rex weak point and phase transitions`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        engine.spawnEmpireBoss()
        val boss = engine.activeBoss
        assertNotNull("Dragon Emperor must be spawned", boss)
        assertEquals(EmpireType.DRAGON, boss!!.empire)
        assertEquals("Ignis Rex: Dragon Emperor", boss.name)
        assertNotNull("Dragon must have Dragon Heart Core weak point", boss.weakPoint)
        assertEquals("Dragon Heart Core", boss.weakPoint?.name)
        assertEquals(2.2f, boss.weakPoint?.damageMultiplier ?: 0f, 0.01f)

        // Test normal hull damage vs weak point critical damage
        val initialHp = boss.health
        boss.takeDamage(100f, isWeakPointHit = false)
        // Shield absorbed 100
        assertEquals(initialHp, boss.health, 0.01f)
        assertEquals(100f, boss.shield, 0.01f)

        // Break remaining shield and deal damage
        boss.takeDamage(100f, isWeakPointHit = false)
        assertEquals(0f, boss.shield, 0.01f)

        // Test weak point critical hit: 100 * 2.2 = 220 damage
        val preCritHp = boss.health
        boss.takeDamage(100f, isWeakPointHit = true)
        assertEquals(preCritHp - 220f, boss.health, 0.01f)

        // Force transition to Phase 2 (HP <= 66%)
        boss.health = boss.maxHealth * 0.60f
        engine.update(0.1f)
        assertEquals("Should advance to Phase 2", 2, boss.phase)
        assertTrue("Should gain enrage multiplier in Phase 2", boss.enrageFactor > 1.0f)

        // Force transition to Phase 3 (HP <= 33%)
        boss.health = boss.maxHealth * 0.25f
        engine.update(0.1f)
        assertEquals("Should advance to Phase 3 ENRAGED", 3, boss.phase)
        assertEquals(1.5f, boss.enrageFactor, 0.01f)
    }

    @Test
    fun `verify Alien Overlord Overseer Prime plasma cannons and energy shield`() {
        val engine = GameEngine(selectedEmpire = EmpireType.ALIEN)
        engine.spawnEmpireBoss()
        val boss = engine.activeBoss
        assertNotNull("Alien Overlord must be spawned", boss)
        assertEquals(EmpireType.ALIEN, boss!!.empire)
        assertEquals("Overseer Prime: Alien Mothership", boss.name)
        assertEquals("Alien mothership has 650 energy shield", 650f, boss.maxShield, 0.01f)
        assertEquals(2, boss.subsystems.size)
        assertTrue("Has Port Plasma Cannon", boss.subsystems.any { it.name == "PORT PLASMA CANNON" })
        assertTrue("Has Starboard Plasma Cannon", boss.subsystems.any { it.name == "STARBOARD PLASMA CANNON" })

        // Target Port subsystem directly
        val portPod = boss.subsystems.first { it.name == "PORT PLASMA CANNON" }
        assertFalse(portPod.isDestroyed)
        val destroyed = portPod.takeDamage(400f)
        assertTrue("Port plasma cannon should be destroyed after 400 damage", destroyed)
        assertTrue(portPod.isDestroyed)

        // Verify routing via engine applyDamageToBoss
        val starPod = boss.subsystems.first { it.name == "STARBOARD PLASMA CANNON" }
        val worldStarPos = Vector3D(boss.pos.x + starPod.offset.x, boss.pos.y + starPod.offset.y, boss.pos.z + starPod.offset.z)
        engine.applyDamageToBoss(400f, worldStarPos)
        assertTrue("Starboard plasma cannon should be destroyed via world pos hit", starPod.isDestroyed)
    }

    @Test
    fun `verify Orc Warlord Gashrak sky fortress and rear boiler exhaust`() {
        val engine = GameEngine(selectedEmpire = EmpireType.ORC)
        engine.spawnEmpireBoss()
        val boss = engine.activeBoss
        assertNotNull("Orc Warlord must be spawned", boss)
        assertEquals(EmpireType.ORC, boss!!.empire)
        assertEquals("Warlord Gashrak: Ironclad Sky Fortress", boss.name)
        assertNotNull("Orc zeppelin has rear boiler weak point", boss.weakPoint)
        assertEquals("Boiler Exhaust Vent", boss.weakPoint?.name)
        assertEquals(2.5f, boss.weakPoint?.damageMultiplier ?: 0f, 0.01f)

        // Damage rear boiler via applyDamageToBoss
        val worldBoilerPos = Vector3D(boss.pos.x + boss.weakPoint!!.offset.x, boss.pos.y + boss.weakPoint!!.offset.y, boss.pos.z + boss.weakPoint!!.offset.z)
        val initialHp = boss.health
        engine.applyDamageToBoss(100f, worldBoilerPos)
        // 100 damage * 2.5 multiplier = 250 damage dealt (shield is 0 on orc zeppelin)
        assertEquals(initialHp - 250f, boss.health, 0.01f)
    }

    @Test
    fun `verify Human Supreme Commander Project Titan destructible pods and victory progression`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        engine.spawnEmpireBoss()
        val boss = engine.activeBoss
        assertNotNull("Human Supreme Commander must be spawned", boss)
        assertEquals(EmpireType.HUMAN, boss!!.empire)
        assertEquals("Project Titan: Supreme Gunship", boss.name)
        assertEquals(3, boss.subsystems.size)
        assertTrue("Has 30mm Rotary Vulcan", boss.subsystems.any { it.name == "30MM ROTARY VULCAN" })
        assertTrue("Has Port Stinger Pod", boss.subsystems.any { it.name == "PORT STINGER POD" })
        assertTrue("Has Starboard Stinger Pod", boss.subsystems.any { it.name == "STARBOARD STINGER POD" })

        val initialScore = engine.score
        val initialCredits = engine.creditsEarned
        assertFalse(engine.isVictory)

        // Destroy boss
        boss.health = 50f
        boss.shield = 0f
        boss.bossHitsRemaining = 1
        engine.applyDamageToBoss(100f, boss.pos)

        // Verify boss defeat unlocks final 1000-hit Main HQ siege, then HQ destruction awards Victory
        assertTrue("Boss health reaches 0", boss.health <= 0f)
        assertTrue("HQ Boss-defeated gate must open", engine.enemyHeadquarters?.isBossDefeated == true)
        assertEquals("Boss score awarded +25,000 pts", initialScore + 25000, engine.score)
        assertEquals("Boss credits awarded +5,000 CR", initialCredits + 5000, engine.creditsEarned)
        assertFalse("Stage victory awaits Main HQ destruction", engine.isVictory)

        engine.destroyHeadquarters()
        assertTrue("Mission victory awarded once Main HQ is destroyed", engine.isVictory)
    }

    @Test
    fun `verify four playable helicopter classes have unique specifications and roles`() {
        // 1. Scout Helicopter: High speed (30 m/s), high agility, light armor (35)
        val scout = com.example.game.model.HelicopterClass.SCOUT
        assertEquals(30f, scout.baseSpeed, 0.01f)
        assertEquals(75f, scout.baseHealth, 0.01f)
        assertEquals(35f, scout.baseArmor, 0.01f)
        assertEquals(12, scout.baseRocketCapacity)

        // 2. Heavy Gunship: Heavy armor (100), heavy HP (150), large payload (24 rockets)
        val heavy = com.example.game.model.HelicopterClass.HEAVY_GUNSHIP
        assertEquals(19f, heavy.baseSpeed, 0.01f)
        assertEquals(150f, heavy.baseHealth, 0.01f)
        assertEquals(100f, heavy.baseArmor, 0.01f)
        assertEquals(24, heavy.baseRocketCapacity)
        assertEquals(12, heavy.baseMissileCapacity)

        // 3. Stealth Helicopter: Moderate speed (26 m/s), low radar profile
        val stealth = com.example.game.model.HelicopterClass.STEALTH
        assertEquals(26f, stealth.baseSpeed, 0.01f)
        assertEquals(90f, stealth.baseHealth, 0.01f)
        assertEquals(50f, stealth.baseArmor, 0.01f)
        assertEquals(16, stealth.baseRocketCapacity)

        // 4. Ultimate War Helicopter: Apex predator with plasma weapon capabilities and 120 shield
        val ultimate = com.example.game.model.HelicopterClass.ULTIMATE_WAR
        assertEquals(28f, ultimate.baseSpeed, 0.01f)
        assertEquals(130f, ultimate.baseHealth, 0.01f)
        assertEquals(85f, ultimate.baseArmor, 0.01f)
        assertEquals(120f, ultimate.baseShield, 0.01f)
        assertEquals(22f, ultimate.baseMgDamage, 0.01f)
    }

    @Test
    fun `verify all five upgrade categories tangibly modify in-flight stats`() {
        val baseScout = com.example.game.model.HelicopterClass.SCOUT
        val upgrades = com.example.game.model.HelicopterUpgrades()

        // 1. Engine upgrade increases speed
        val baseSpeed = upgrades.calculateEffectiveSpeed(baseScout.baseSpeed)
        upgrades.engineLevel = 4 // +24% boost
        val upgradedSpeed = upgrades.calculateEffectiveSpeed(baseScout.baseSpeed)
        assertTrue("Upgraded engine must yield faster velocity", upgradedSpeed > baseSpeed)
        assertEquals(baseScout.baseSpeed * 1.24f, upgradedSpeed, 0.01f)

        // 2. Armor upgrade increases health and armor
        upgrades.armorLevel = 3 // +24% boost
        val upgradedHp = upgrades.calculateEffectiveHealth(baseScout.baseHealth)
        val upgradedArmor = upgrades.calculateEffectiveArmor(baseScout.baseArmor)
        assertEquals(baseScout.baseHealth * 1.24f, upgradedHp, 0.01f)
        assertEquals(baseScout.baseArmor * 1.24f, upgradedArmor, 0.01f)

        // 3. Weapons upgrade increases MG damage, missile damage, and rocket payload
        upgrades.weaponLevel = 5 // +40% damage, +16 rockets
        val upgradedMg = upgrades.calculateEffectiveMgDamage(baseScout.baseMgDamage)
        val upgradedRockets = upgrades.calculateEffectiveRocketAmmo(baseScout.baseRocketCapacity)
        assertEquals(baseScout.baseMgDamage * 1.40f, upgradedMg, 0.01f)
        assertEquals(baseScout.baseRocketCapacity + 16, upgradedRockets)

        // 4. Defense upgrade increases shield capacity
        upgrades.defenseLevel = 2 // +15% shield
        val upgradedShield = upgrades.calculateEffectiveShield(baseScout.baseShield)
        assertEquals(baseScout.baseShield * 1.15f, upgradedShield, 0.01f)

        // 5. Apply into GameEngine and verify live combat entities
        val engine = GameEngine(
            selectedEmpire = EmpireType.DRAGON,
            initialHeloClass = baseScout,
            initialUpgrades = upgrades
        )
        assertEquals(upgradedHp, engine.player.maxHealth, 0.01f)
        assertEquals(upgradedArmor, engine.player.maxArmor, 0.01f)
        assertEquals(upgradedShield, engine.player.maxShield, 0.01f)
        assertEquals(upgradedSpeed, engine.player.flightSpeed, 0.01f)
        assertEquals(upgradedMg, engine.player.mgDamage, 0.01f)
        assertEquals(upgradedRockets, engine.player.maxRocketAmmo)
    }

    @Test
    fun `verify garage profile unlock progression and skin equipping`() {
        val profile = com.example.game.model.PlayerProfile(credits = 10000)
        assertEquals(1, profile.unlockedHelicopters.size)
        assertTrue(profile.unlockedHelicopters.contains(com.example.game.model.HelicopterClass.SCOUT.id))

        // Unlock Heavy Gunship for 8000 credits
        val heavy = com.example.game.model.HelicopterClass.HEAVY_GUNSHIP
        assertTrue("Player has sufficient credits", profile.credits >= heavy.unlockCost)
        profile.credits -= heavy.unlockCost
        profile.unlockedHelicopters.add(heavy.id)
        profile.selectedHelicopterClass = heavy

        assertEquals(2000, profile.credits)
        assertTrue("Heavy Gunship must be unlocked", profile.unlockedHelicopters.contains(heavy.id))
        assertEquals(heavy, profile.selectedHelicopterClass)

        // Equip Desert Mirage camo skin
        val skin = com.example.game.model.HelicopterSkin.MILITARY_CAMO
        profile.selectedSkin = skin
        assertEquals(skin, profile.selectedSkin)
    }

    @Test
    fun `verify cross-shaped D-pad movement controls and boundary safety`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        val startX = engine.player.pos.x
        val startY = engine.player.pos.y

        // Test UP moves forward
        engine.isUpPressed = true
        engine.update(0.1f)
        assertTrue("UP control must advance helicopter forward (+Y)", engine.player.pos.y > startY)
        engine.isUpPressed = false

        // Test DOWN moves backward
        val midY = engine.player.pos.y
        engine.isDownPressed = true
        engine.update(0.1f)
        assertTrue("DOWN control must move helicopter backward (-Y)", engine.player.pos.y < midY)
        engine.isDownPressed = false

        // Test LEFT moves sideways left without yaw rotation
        engine.resetBattle()
        val origX = engine.player.pos.x
        engine.isLeftPressed = true
        engine.update(0.1f)
        assertTrue("LEFT control must move helicopter sideways left (-X)", engine.player.pos.x < origX)
        assertTrue("Nose strictly faces forward, banking roll applied", engine.player.rollAngle > 0f)
        engine.isLeftPressed = false

        // Test RIGHT moves sideways right
        engine.resetBattle()
        val origXRight = engine.player.pos.x
        engine.isRightPressed = true
        engine.update(0.1f)
        assertTrue("RIGHT control must move helicopter sideways right (+X)", engine.player.pos.x > origXRight)
        engine.isRightPressed = false

        // Test Simultaneous Diagonal Control (UP + RIGHT held together)
        engine.resetBattle()
        val diagStartX = engine.player.pos.x
        val diagStartY = engine.player.pos.y
        engine.isUpPressed = true
        engine.isRightPressed = true
        engine.update(0.1f)
        assertTrue("Simultaneous UP advances forward", engine.player.pos.y > diagStartY)
        assertTrue("Simultaneous RIGHT moves sideways right", engine.player.pos.x > diagStartX)
    }

    @Test
    fun `verify open world theater 50 enemy barracks generation and distribution`() {
        for (empire in EmpireType.values()) {
            val engine = GameEngine(selectedEmpire = empire)
            assertEquals("Must generate exactly 50 barracks", 50, engine.allBarracks.size)

            val indices = engine.allBarracks.map { it.index }.toSet()
            assertEquals("Barracks indices must be 1 to 50 unique", 50, indices.size)
            assertTrue("Indices contain 1", indices.contains(1))
            assertTrue("Indices contain 50", indices.contains(50))

            // Verify barracks physical structures registered in terrain
            val barracksStructs = engine.structures.filter { it.type == com.example.game.engine.StructureType.BARRACKS_FORT }
            assertEquals("All 50 barracks have registered 3D fort structures", 50, barracksStructs.size)

            // Verify barracks are distributed widely across open-world coordinates
            val minBarracksX = engine.allBarracks.minOf { it.pos.x }
            val maxBarracksX = engine.allBarracks.maxOf { it.pos.x }
            assertTrue("Barracks must span wide lateral open-world territory (X span > 200)", (maxBarracksX - minBarracksX) > 200f)

            // Verify Headquarters exists and is initially locked
            assertNotNull("Empire headquarters must exist", engine.enemyHeadquarters)
            assertFalse("Headquarters must be locked at mission start", engine.enemyHeadquarters!!.isUnlocked)
            assertEquals("Barracks destroyed count starts at 0", 0, engine.barracksDestroyedCount)
        }
    }

    @Test
    fun `verify barracks destruction progression credits reward and HQ unlock at 50 barracks`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        val initialCredits = engine.creditsEarned
        val initialScore = engine.score

        // Destroy barracks 1
        val firstBarracks = engine.allBarracks[0]
        assertFalse(firstBarracks.isDestroyed)
        engine.damageBarracks(firstBarracks, 350f)

        assertTrue("First barracks must be marked destroyed", firstBarracks.isDestroyed)
        assertEquals(1, engine.barracksDestroyedCount)
        assertTrue("Credits must be awarded for destroying barracks", engine.creditsEarned > initialCredits)
        assertTrue("Score must increase", engine.score > initialScore)
        assertFalse("HQ must remain locked while barracks < 50", engine.enemyHeadquarters!!.isUnlocked)

        // Destroy all remaining barracks up to 49
        for (i in 1..48) {
            engine.damageBarracks(engine.allBarracks[i], 350f)
        }
        assertEquals(49, engine.barracksDestroyedCount)
        assertFalse("HQ must still be locked at 49 barracks", engine.enemyHeadquarters!!.isUnlocked)

        // Destroy the 50th barracks
        val fiftiethBarracks = engine.allBarracks[49]
        engine.damageBarracks(fiftiethBarracks, 350f)
        assertEquals(50, engine.barracksDestroyedCount)

        // Verify HQ is unlocked while Boss remains inside until Danger Zone entry
        assertTrue("Enemy headquarters forcefield must be unlocked when all 50 barracks destroyed", engine.enemyHeadquarters!!.isUnlocked)
        assertEquals("Boss waits inside Main HQ while player is far from Danger Zone", null, engine.activeBoss)

        val hq = engine.enemyHeadquarters!!
        engine.player.pos = Vector3D(hq.pos.x, hq.pos.y - 60f, 9.5f)
        engine.update(0.1f)

        assertNotNull("Supreme Empire Boss must emerge upon entering HQ Danger Zone", engine.activeBoss)
        assertTrue("Boss spawned flag must be true", engine.bossSpawned)
    }

    @Test
    fun `verify headquarters annihilation triggers supreme victory`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        val hq = engine.enemyHeadquarters!!
        hq.isUnlocked = true

        assertFalse("Game must not be victory at start", engine.isVictory)
        engine.destroyHeadquarters()

        assertTrue("Headquarters must be destroyed", hq.isDestroyed)
        assertTrue("Victory state must be achieved upon HQ destruction", engine.isVictory)
        assertTrue("Victory announcement must be displayed", engine.threatAlert?.message?.contains("SUPREME VICTORY") == true)
    }

    @Test
    fun `verify open world active enemy troop deployment`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        assertTrue("Must deploy infantry soldiers", engine.enemies.any { it.category == EnemyCategory.INFANTRY_SOLDIER })
        assertTrue("Must deploy rocket soldiers", engine.enemies.any { it.category == EnemyCategory.ROCKET_SOLDIER })
        assertTrue("Must deploy ground tanks", engine.enemies.any { it.category == EnemyCategory.GROUND_TANK })
    }

    @Test
    fun `verify player helicopter hovers cleanly without forced drift when controls released`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        engine.isUpPressed = true
        for (i in 1..5) engine.update(0.1f)
        assertTrue("Helicopter accelerates forward on UP", engine.player.velocity.y > 5f)

        // Release all movement controls -> helicopter decelerates smoothly and stops in hover
        engine.isUpPressed = false
        for (i in 1..20) engine.update(0.1f)
        assertEquals("Helicopter stops forward drift cleanly on release", 0f, engine.player.velocity.y, 0.01f)
        assertEquals("Helicopter stops lateral drift cleanly on release", 0f, engine.player.velocity.x, 0.01f)
    }

    @Test
    fun `verify enemy soldiers patrol actively and aim toward player`() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        engine.enemies.clear()
        val soldier = EnemyTarget(
            id = "S_PATROL",
            empire = EmpireType.HUMAN,
            category = EnemyCategory.INFANTRY_SOLDIER,
            pos = Vector3D(6f, 20f, 0.1f),
            velocity = Vector3D(0f, 0f, 0f),
            health = 50f,
            maxHealth = 50f,
            scoreValue = 100,
            unitName = "Infantry",
            isGroundUnit = true
        )
        engine.enemies.add(soldier)
        val startX = soldier.pos.x

        engine.update(0.25f)
        assertTrue("Infantry soldier patrols laterally rather than staying frozen", kotlin.math.abs(soldier.pos.x - startX) > 0.05f)
        assertTrue("Infantry soldier actively aims turretAngle toward player", kotlin.math.abs(soldier.turretAngle) > 0.1f)
    }

    @Test
    fun `verify smooth camera follow tracks player without tearing or stalling`() {
        val engine = GameEngine(selectedEmpire = EmpireType.ORC)
        val initialCamY = engine.camera.y
        engine.isUpPressed = true
        engine.isRightPressed = true
        for (i in 1..15) engine.update(0.05f)

        assertTrue("Camera follows player forward", engine.camera.y > initialCamY + 3f)
        assertTrue("Camera tracks player laterally", engine.camera.x > 1f)
    }

    @Test
    fun `verify AdMob debug test IDs and preserved production console constants`() {
        // In debug unit tests (BuildConfig.DEBUG == true), runtime AdMob IDs must resolve to Google sample test IDs
        assertTrue(BuildConfig.DEBUG)
        assertEquals(
            com.example.game.ads.AdMobManager.TEST_ADMOB_APP_ID,
            com.example.game.ads.AdMobManager.ADMOB_APP_ID
        )
        assertEquals(
            com.example.game.ads.AdMobManager.TEST_BANNER_AD_UNIT_ID,
            com.example.game.ads.AdMobManager.BANNER_AD_UNIT_ID
        )
        assertEquals(
            com.example.game.ads.AdMobManager.TEST_INTERSTITIAL_AD_UNIT_ID,
            com.example.game.ads.AdMobManager.INTERSTITIAL_AD_UNIT_ID
        )
        assertEquals(
            com.example.game.ads.AdMobManager.TEST_REWARDED_AD_UNIT_ID,
            com.example.game.ads.AdMobManager.REWARDED_AD_UNIT_ID
        )

        // Preserved production console constants (wired via BuildConfig in release buildType)
        assertEquals(
            "ca-app-pub-9786472984542330~8764530303",
            com.example.game.ads.AdMobManager.PROD_ADMOB_APP_ID
        )
        assertEquals(
            "ca-app-pub-9786472984542330/7032646237",
            com.example.game.ads.AdMobManager.PROD_BANNER_AD_UNIT_ID
        )
        assertEquals(
            "ca-app-pub-9786472984542330/7625553507",
            com.example.game.ads.AdMobManager.PROD_INTERSTITIAL_AD_UNIT_ID
        )
        assertEquals(
            "ca-app-pub-9786472984542330/9371034967",
            com.example.game.ads.AdMobManager.PROD_REWARDED_AD_UNIT_ID
        )
        assertEquals(
            "google.com, pub-9786472984542330, DIRECT, f08c47fec0942fa0",
            com.example.game.ads.AdMobManager.APP_ADS_TXT_RECORD
        )
    }

    @Test
    fun `verify long session combat prevents entity and projectile overload`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        engine.player.isAutoFiring = true
        engine.spawnEmpireBoss()

        // Simulate 3,600 frames (~60s at 60 FPS) of intense sustained combat
        for (frame in 0 until 3600) {
            engine.player.health = engine.player.maxHealth
            engine.player.armor = engine.player.maxArmor
            engine.player.shield = engine.player.maxShield
            engine.isUpPressed = frame % 120 < 90
            engine.isLeftPressed = frame % 180 in 0..45
            engine.isRightPressed = frame % 180 in 46..90

            if (frame % 90 == 0) {
                engine.player.rocketAmmo = engine.player.maxRocketAmmo
                engine.launchRocketBarrage()
            }
            if (frame % 120 == 0) {
                engine.player.missileAmmo = engine.player.maxMissileAmmo
                engine.launchMissiles()
            }

            engine.update(1f / 60f)

            assertTrue("Bullets must stay bounded", engine.bullets.size <= GameEngine.MAX_BULLETS)
            assertTrue("Rockets must stay bounded", engine.rockets.size <= GameEngine.MAX_ROCKETS)
            assertTrue("Missiles must stay bounded", engine.missiles.size <= GameEngine.MAX_MISSILES)
            assertTrue("Particles must stay bounded", engine.particles.size <= GameEngine.MAX_PARTICLES)
            assertTrue("Active enemies must stay bounded", engine.enemies.size <= GameEngine.MAX_ACTIVE_ENEMIES)
            assertTrue("Hazards must stay bounded", engine.hazards.size <= GameEngine.MAX_HAZARDS)
            assertTrue("Structures must stay bounded", engine.structures.size <= GameEngine.MAX_STRUCTURES)
        }

        assertTrue("Player X must remain finite", engine.player.pos.x.isFinite())
        assertTrue("Player Y must remain finite", engine.player.pos.y.isFinite())
        assertTrue("Player Z must remain finite", engine.player.pos.z.isFinite())
        assertTrue("Camera Y must remain finite", engine.camera.y.isFinite())
    }

    @Test
    fun `verify 5 main hearts x 10 hits player durability and rewarded ad continue restoration`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        assertEquals(5, engine.player.hearts)
        assertEquals(10, engine.player.hitsRemainingInHeart)

        // 49 hits leave 1 Heart with 1 Hit remaining (alive)
        for (hit in 1..49) {
            engine.player.invulnerableTimer = 0f
            engine.applyPlayerHit(1)
        }
        assertFalse("Player stays alive at 1 heart / 1 hit", engine.isGameOver)
        assertEquals(1, engine.player.hearts)
        assertEquals(1, engine.player.hitsRemainingInHeart)

        // 50th hit depletes final heart -> Game Over (Continue? YES / NO)
        engine.player.invulnerableTimer = 0f
        engine.applyPlayerHit(1)
        assertTrue("Player must stop at 0/5 hearts", engine.isGameOver)
        assertEquals(0, engine.player.hearts)

        // Watching Rewarded Ad restores full 5 Main Hearts x 10 Hits
        engine.continueAfterAdReward()
        assertFalse("Continue resumes battle", engine.isGameOver)
        assertEquals(5, engine.player.hearts)
        assertEquals(10, engine.player.hitsRemainingInHeart)
    }

    @Test
    fun `verify 24h cap of 10 rewarded ad continues and automatic reset after 24 hours`() {
        var fakeNow = 1_700_000_000_000L
        val mgr = com.example.game.network.CampaignProgressionManager(prefs = null, nowProvider = { fakeNow })

        assertEquals(10, mgr.getAdContinueQuota("pilot_1").remaining)
        repeat(10) { idx ->
            val updated = mgr.consumeAdContinueWatch("pilot_1")
            assertNotNull("Watch #${idx + 1} must succeed", updated)
        }
        val capped = mgr.getAdContinueQuota("pilot_1")
        assertEquals(0, capped.remaining)
        assertFalse("11th continue ad in 24h must be blocked", capped.canWatchAdToContinue)
        assertEquals(null, mgr.consumeAdContinueWatch("pilot_1"))

        // Advance time by 24 hours + 1 second -> auto resets to 10/10 available
        fakeNow += com.example.game.network.CampaignProgressionManager.RESET_WINDOW_MS + 1000L
        val resetQuota = mgr.getAdContinueQuota("pilot_1")
        assertEquals(10, resetQuota.remaining)
        assertTrue(resetQuota.canWatchAdToContinue)
    }

    @Test
    fun `verify strict sequential stage unlocking without advance stage skip`() {
        val mgr = com.example.game.network.CampaignProgressionManager(prefs = null)
        val uid = "pilot_seq"

        assertTrue("Stage 1 DRAGON unlocked initially", mgr.isStageUnlocked(EmpireType.DRAGON, null, uid))
        assertFalse("Stage 2 ALIEN locked initially", mgr.isStageUnlocked(EmpireType.ALIEN, null, uid))
        assertFalse("Stage 3 ORC locked initially", mgr.isStageUnlocked(EmpireType.ORC, null, uid))
        assertFalse("Stage 4 HUMAN locked initially", mgr.isStageUnlocked(EmpireType.HUMAN, null, uid))

        mgr.markStageCompleted(uid, EmpireType.DRAGON)
        assertTrue("Stage 2 ALIEN unlocked after clearing Stage 1", mgr.isStageUnlocked(EmpireType.ALIEN, null, uid))
        assertFalse("Stage 3 ORC remains locked", mgr.isStageUnlocked(EmpireType.ORC, null, uid))
    }

    @Test
    fun `verify sortie checkpoint export restore and 100-100-1000 hit targets with flare and overdrive`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)

        // Verify Barracks = 100 hits max, HQ = 1000 hits max
        val b0 = engine.allBarracks[0]
        assertEquals(100, b0.hitsRemaining)
        assertEquals(1000, engine.enemyHeadquarters!!.hitsRemaining)

        // Destroy 3 barracks cleanly
        engine.damageBarracks(engine.allBarracks[0], 100f)
        engine.damageBarracks(engine.allBarracks[1], 100f)
        engine.damageBarracks(engine.allBarracks[2], 100f)
        engine.player.pos = Vector3D(14f, 180f, 9.5f)
        repeat(15) {
            engine.player.invulnerableTimer = 0f
            engine.applyPlayerHit(1)
        } // drops from 5/10 to 4/5 hearts, 5 hits left
        val cp = engine.exportCheckpoint()

        // Restore into a fresh engine instance (simulating Dashboard -> CONTINUE STAGE)
        val resumedEngine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        resumedEngine.restoreFromCheckpoint(cp)
        assertEquals(3, resumedEngine.barracksDestroyedCount)
        assertEquals(14f, resumedEngine.player.pos.x, 0.01f)
        assertEquals(180f, resumedEngine.player.pos.y, 0.01f)
        assertEquals(4, resumedEngine.player.hearts)
        assertEquals(5, resumedEngine.player.hitsRemainingInHeart)

        // Flare countermeasure & Overdrive Plasma abilities
        resumedEngine.activateFlareCountermeasure()
        assertTrue("Flare sets invulnerable defensive window", resumedEngine.player.flareActiveTimer > 0f)
        assertTrue("Flare goes on cooldown", resumedEngine.player.flareCooldownTimer > 0f)

        resumedEngine.player.overdriveCharge = 100f
        resumedEngine.bullets.clear()
        resumedEngine.activateOverdriveBeam()
        assertTrue("Overdrive spawns plasma volley", resumedEngine.bullets.any { it.isPlayerPlasma })
        assertEquals(0f, resumedEngine.player.overdriveCharge, 0.01f)
    }

    @Test
    fun `verify tactical settings manager and engine camera shake toggle`() {
        val settingsMgr = com.example.game.network.TacticalSettingsManager(prefs = null)
        assertTrue(settingsMgr.settings.value.sfxEnabled)
        assertTrue(settingsMgr.settings.value.screenShakeEnabled)
        assertTrue(settingsMgr.settings.value.hapticsEnabled)
        assertTrue(settingsMgr.settings.value.autoFireByDefault)

        settingsMgr.setSfxEnabled(false)
        settingsMgr.setScreenShakeEnabled(false)
        settingsMgr.setHapticsEnabled(false)
        settingsMgr.setAutoFireByDefault(false)

        assertFalse(settingsMgr.settings.value.sfxEnabled)
        assertFalse(settingsMgr.settings.value.screenShakeEnabled)
        assertFalse(settingsMgr.settings.value.hapticsEnabled)
        assertFalse(settingsMgr.settings.value.autoFireByDefault)

        val engine = GameEngine(
            selectedEmpire = EmpireType.DRAGON,
            initialScreenShakeEnabled = false,
            initialAutoFire = false
        )
        assertFalse("Initial auto-fire respects setting", engine.player.isAutoFiring)
        engine.camera.screenShake = 5.0f
        engine.update(0.016f)
        assertEquals("Camera shake stays suppressed when disabled", 0f, engine.camera.screenShake, 0.001f)
    }

    @Test
    fun `verify hangar persistence spend delta tracks cumulative creditsSpent`() {
        val hangarMgr = com.example.game.network.HangarPersistenceManager(prefs = null)
        val initial = hangarMgr.loadState("uid_alpha")
        assertEquals(0, initial.creditsSpent)

        val purchased = com.example.game.model.PlayerProfile(
            credits = 10500,
            selectedHelicopterClass = com.example.game.model.HelicopterClass.HEAVY_GUNSHIP
        ).apply {
            unlockedHelicopters.add(com.example.game.model.HelicopterClass.HEAVY_GUNSHIP.id)
        }
        val next = hangarMgr.applyTransaction(
            uid = "uid_alpha",
            previousCredits = 18500,
            updatedProfile = purchased
        )
        assertEquals(8000, next.creditsSpent)
        assertEquals(com.example.game.model.HelicopterClass.HEAVY_GUNSHIP, next.selectedHelicopterClass)
        assertTrue(next.unlockedHelicopters.contains(com.example.game.model.HelicopterClass.HEAVY_GUNSHIP.id))
    }

    @Test
    fun `verify locked headquarters cannot be falsely destroyed before unlocking`() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        val hq = engine.enemyHeadquarters!!
        assertFalse("HQ starts locked", hq.isUnlocked)

        engine.destroyHeadquarters()

        assertFalse("Locked HQ must remain intact", hq.isDestroyed)
        assertFalse("Victory must remain false when HQ is locked", engine.isVictory)
    }

    @Test
    fun `verify live server stage status strictly overrides local completion cache`() {
        val mgr = com.example.game.network.CampaignProgressionManager(prefs = null)
        val uid = "pilot_live_strict"
        mgr.markStageCompleted(uid, EmpireType.DRAGON)

        val liveFalseState = com.example.game.network.GlobalCampaignState(
            dragonProgress = com.example.game.network.CampaignEmpireProgress(
                empire = EmpireType.DRAGON,
                destroyedCount = 12,
                isHeadquartersUnlocked = false,
                isHeadquartersDestroyed = false
            ),
            isServerConnected = true
        )
        assertFalse(
            "Connected server false must remain false even if local cache had true",
            mgr.isStageCompleted(EmpireType.DRAGON, liveFalseState, uid)
        )
        assertFalse(
            "Stage 2 must remain locked when live server reports Stage 1 HQ intact",
            mgr.isStageUnlocked(EmpireType.ALIEN, liveFalseState, uid)
        )

        val liveTrueState = liveFalseState.copy(
            dragonProgress = liveFalseState.dragonProgress.copy(
                destroyedCount = 50,
                isHeadquartersUnlocked = true,
                isHeadquartersDestroyed = true
            )
        )
        assertTrue(
            "Connected server true must unlock next stage",
            mgr.isStageUnlocked(EmpireType.ALIEN, liveTrueState, uid)
        )
    }

    @Test
    fun `verify shield and armor mitigation protects hearts under incoming damage`() {
        val upgrades = com.example.game.model.HelicopterUpgrades(
            armorLevel = 4,
            defenseLevel = 5
        )
        val armoredEngine = GameEngine(
            selectedEmpire = EmpireType.DRAGON,
            initialHeloClass = com.example.game.model.HelicopterClass.HEAVY_GUNSHIP,
            initialUpgrades = upgrades
        )
        armoredEngine.player.invulnerableTimer = 0f
        val startShield = armoredEngine.player.shield
        val startHits = armoredEngine.player.hitsRemainingInHeart

        // Incoming 35 damage is absorbed entirely by the 128 energy shield
        armoredEngine.applyPlayerIncomingDamage(rawDamage = 35f, hitWeight = 1)
        assertTrue("Shield must absorb incoming damage first", armoredEngine.player.shield < startShield)
        assertEquals("Hearts must stay untouched while shield absorbs hit", startHits, armoredEngine.player.hitsRemainingInHeart)

        // Drain shield and verify titanium armor absorbs light post-shield impact
        armoredEngine.player.shield = 0f
        armoredEngine.player.invulnerableTimer = 0f
        val startArmor = armoredEngine.player.armor
        armoredEngine.applyPlayerIncomingDamage(rawDamage = 5f, hitWeight = 1)
        assertTrue("Armor must degrade when absorbing impact", armoredEngine.player.armor < startArmor)
        assertEquals("Minor impact absorbed by armor leaves heart hits intact", startHits, armoredEngine.player.hitsRemainingInHeart)

        // Verify heavy unmitigated impact after armor exhaustion deducts heart hit
        armoredEngine.player.shield = 0f
        armoredEngine.player.armor = 0f
        armoredEngine.player.invulnerableTimer = 0f
        armoredEngine.applyPlayerIncomingDamage(rawDamage = 25f, hitWeight = 1)
        assertEquals(startHits - 1, armoredEngine.player.hitsRemainingInHeart)
    }

    @Test
    fun `verify per class fire rate rocket damage missile capacity and shield recharge scaling`() {
        val scout = GameEngine(
            selectedEmpire = EmpireType.HUMAN,
            initialHeloClass = com.example.game.model.HelicopterClass.SCOUT
        )
        val ultimate = GameEngine(
            selectedEmpire = EmpireType.HUMAN,
            initialHeloClass = com.example.game.model.HelicopterClass.ULTIMATE_WAR,
            initialUpgrades = com.example.game.model.HelicopterUpgrades(weaponLevel = 5, defenseLevel = 5)
        )

        assertTrue("Apex Predator fires autocannons faster than Viper Scout", ultimate.player.mgFireRate < scout.player.mgFireRate)
        assertTrue("Upgraded Apex rocket damage exceeds base Scout", ultimate.player.rocketDamage > scout.player.rocketDamage)
        assertEquals(12, ultimate.player.maxMissileAmmo)

        // Verify defenseLevel increases shield recharge rate
        val lowDef = GameEngine(
            selectedEmpire = EmpireType.DRAGON,
            initialUpgrades = com.example.game.model.HelicopterUpgrades(defenseLevel = 1)
        )
        val highDef = GameEngine(
            selectedEmpire = EmpireType.DRAGON,
            initialUpgrades = com.example.game.model.HelicopterUpgrades(defenseLevel = 5)
        )
        lowDef.player.shield = 0f
        highDef.player.shield = 0f
        lowDef.update(1.0f)
        highDef.update(1.0f)
        assertTrue("Higher defense upgrade recharges energy shield faster", highDef.player.shield > lowDef.player.shield)
    }
}
