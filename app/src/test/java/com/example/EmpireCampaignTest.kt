package com.example

import com.example.game.engine.EnemyCategory
import com.example.game.engine.GameEngine
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gameplay unit tests for the 4-Empire, 200-Barracks campaign, combat controls,
 * boss battles, and damage absorption.
 *
 * Firebase Auth, Cloud Firestore security rules, and two-device cloud synchronization
 * are verified against the live local emulators in WarNetCampaignRuleTest.
 */
class EmpireCampaignTest {

    @Test
    fun testFourIndependentEmpiresHaveExactly50BarracksEach_Totaling200UniqueBarracks() {
        val allBarracksIds = mutableSetOf<String>()
        val empires = listOf(EmpireType.HUMAN, EmpireType.ORC, EmpireType.DRAGON, EmpireType.ALIEN)

        assertEquals("Exactly 4 empire territories must exist", 4, empires.size)

        for (empire in empires) {
            val engine = GameEngine(selectedEmpire = empire)
            assertEquals("Each empire must have 50 barracks", 50, engine.allBarracks.size)

            val empireIds = engine.allBarracks.map { it.id }.toSet()
            assertEquals("All 50 barracks in ${empire.name} must have unique IDs", 50, empireIds.size)

            for (b in engine.allBarracks) {
                assertTrue("ID must follow naming convention BARRACKS_${empire.name}_#", b.id.startsWith("BARRACKS_${empire.name}_"))
                allBarracksIds.add(b.id)
            }
        }

        assertEquals("Total campaign must contain exactly 200 unique enemy barracks", 200, allBarracksIds.size)
    }

    @Test
    fun testBarracksPhysicalDistributionNonClusteredAcrossTerritory() {
        for (empire in EmpireType.values()) {
            val engine = GameEngine(selectedEmpire = empire)
            val barracks = engine.allBarracks

            val minY = barracks.minOf { it.pos.y }
            val maxY = barracks.maxOf { it.pos.y }
            val minX = barracks.minOf { it.pos.x }
            val maxX = barracks.maxOf { it.pos.x }

            assertTrue("Barracks Y must stretch across deep territory (> 700 units)", (maxY - minY) > 700f)
            assertTrue("Barracks X must span across theater (> 150 units wide)", (maxX - minX) > 150f)

            for (i in 0 until barracks.size) {
                for (j in i + 1 until barracks.size) {
                    val dist = (barracks[i].pos - barracks[j].pos).length()
                    assertTrue("Barracks #${barracks[i].index} and #${barracks[j].index} must not overlap", dist > 5.0f)
                }
            }
        }
    }

    @Test
    fun testHeadquartersUnlockStrictlyAt50BarracksDestroyed() {
        val engine = GameEngine(selectedEmpire = EmpireType.ALIEN)
        assertFalse("HQ must be locked initially", engine.enemyHeadquarters?.isUnlocked ?: true)

        for (i in 0 until 49) {
            engine.damageBarracks(engine.allBarracks[i], 1000f)
            assertFalse("HQ must remain locked at $i/50 destroyed", engine.enemyHeadquarters?.isUnlocked ?: true)
        }

        engine.damageBarracks(engine.allBarracks[49], 1000f)
        assertTrue("HQ must unlock when all 50 barracks in empire are destroyed", engine.enemyHeadquarters?.isUnlocked ?: false)

        engine.destroyHeadquarters()
        assertTrue(engine.enemyHeadquarters?.isDestroyed ?: false)
        assertTrue(engine.isVictory)
    }

    @Test
    fun testCombatFlightControlsAndDiagonalMovement() {
        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN)
        val initialY = engine.player.pos.y

        engine.isUpPressed = true
        engine.update(0.1f)
        assertTrue("UP must increase Y (flight forward)", engine.player.pos.y > initialY)
        engine.isUpPressed = false

        val currentX = engine.player.pos.x
        engine.isLeftPressed = true
        engine.update(0.1f)
        assertTrue("LEFT must decrease X (strafe left)", engine.player.pos.x < currentX)
        engine.isLeftPressed = false

        engine.player.velocity = Vector3D(0f, 0f, 0f)
        val diagStartX = engine.player.pos.x
        val diagStartY = engine.player.pos.y
        engine.isUpPressed = true
        engine.isRightPressed = true
        engine.update(0.15f)
        assertTrue("UP+RIGHT must move forward", engine.player.pos.y > diagStartY)
        assertTrue("UP+RIGHT must move right", engine.player.pos.x > diagStartX)
        engine.isUpPressed = false
        engine.isRightPressed = false

        val initBullets = engine.bullets.size
        engine.player.isAutoFiring = true
        engine.player.mgTimer = 0.15f
        engine.update(0.1f)
        assertTrue("Machine gun must spawn tracer bullets", engine.bullets.size > initBullets)

        engine.player.rocketAmmo = 12
        engine.player.rocketCooldownTimer = 0f
        engine.launchRocketBarrage()
        assertEquals("Rocket salvo must launch 4 cluster rockets", 4, engine.rockets.size)

        engine.player.missileAmmo = 6
        engine.player.missileTimer = 0f
        engine.launchMissiles()
        assertEquals("Guided missile salvo must launch 2 missiles", 2, engine.missiles.size)
    }

    @Test
    fun testAllFourEmpiresHeadquartersAndBossUnlockIndividually() {
        for (empire in EmpireType.values()) {
            val engine = GameEngine(selectedEmpire = empire)
            assertFalse("HQ for ${empire.name} must start locked", engine.enemyHeadquarters?.isUnlocked ?: true)

            for (i in 0 until 49) {
                engine.damageBarracks(engine.allBarracks[i], 1000f)
                assertFalse("HQ for ${empire.name} must remain locked at $i/50", engine.enemyHeadquarters?.isUnlocked ?: true)
            }

            engine.damageBarracks(engine.allBarracks[49], 1000f)
            assertTrue("HQ for ${empire.name} must unlock at 50/50", engine.enemyHeadquarters?.isUnlocked ?: false)
            assertEquals("Boss stays inside Main HQ until player enters Danger Zone", null, engine.activeBoss)

            val hq = engine.enemyHeadquarters!!
            engine.player.pos = Vector3D(hq.pos.x, hq.pos.y - 60f, 9.5f)
            engine.update(0.1f)

            assertNotNull("Boss must deploy from HQ when player enters Danger Zone for ${empire.name}", engine.activeBoss)
            val boss = engine.activeBoss!!
            val keyWord = empire.bossName.split(" ")[0].trimEnd('\'', 's', ':')
            assertTrue("Boss name must identify empire flagship ($keyWord in ${boss.name})", boss.name.contains(keyWord, ignoreCase = true))

            val initialHp = boss.health
            boss.takeDamage(1000f)
            assertTrue("Boss health must deplete", boss.health < initialHp)

            engine.destroyHeadquarters()
            assertTrue("Victory must be achieved for ${empire.name}", engine.isVictory)
        }
    }

    @Test
    fun testPlayerDamageAbsorptionChain() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON)
        engine.player.shield = 40f
        engine.player.armor = 35f
        engine.player.health = 75f

        engine.player.takeDamage(30f)
        assertEquals(10f, engine.player.shield, 0.01f)
        assertEquals(35f, engine.player.armor, 0.01f)
        assertEquals(75f, engine.player.health, 0.01f)

        engine.player.takeDamage(20f)
        assertEquals(0f, engine.player.shield, 0.01f)
        assertEquals(25f, engine.player.armor, 0.01f)
        assertEquals(75f, engine.player.health, 0.01f)

        engine.player.takeDamage(45f)
        assertEquals(0f, engine.player.shield, 0.01f)
        assertEquals(0f, engine.player.armor, 0.01f)
        assertEquals(55f, engine.player.health, 0.01f)
    }

    @Test
    fun testActiveDefendersSpawnedPerEmpire() {
        val human = GameEngine(selectedEmpire = EmpireType.HUMAN)
        assertTrue(human.enemies.any { it.category == EnemyCategory.INFANTRY_SOLDIER })
        val orc = GameEngine(selectedEmpire = EmpireType.ORC)
        assertTrue(orc.enemies.any { it.category == EnemyCategory.GROUND_TANK })
    }
}
