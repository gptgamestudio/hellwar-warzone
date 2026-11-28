package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.engine.BossEncounterState
import com.example.game.engine.EnemyCategory
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.model.EmpireType
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.GlobalCampaignState
import com.example.game.network.SortieCheckpoint
import com.example.game.ui.EndlessTheaterScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FourEmpiresEndlessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @After
    fun tearDown() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    private fun advanceKills(
        engine: GameEngine,
        targetKills: Int,
        seenCategories: MutableSet<EnemyCategory>? = null
    ) {
        var guard = 0
        while (engine.endlessTotalRegularKills < targetKills && guard < 4000) {
            engine.player.invulnerableTimer = 1.0f
            engine.enemies.forEach { seenCategories?.add(it.category) }
            val needed = targetKills - engine.endlessTotalRegularKills
            if (engine.bossEncounterState == BossEncounterState.NORMAL_WAVE && engine.enemies.isNotEmpty()) {
                engine.enemies.take(needed).forEach { it.health = 0f }
            }
            engine.update(0.15f)
            guard++
        }
        assertEquals(targetKills, engine.endlessTotalRegularKills)
    }

    private fun stepToBossActive(engine: GameEngine) {
        var guard = 0
        while (engine.bossEncounterState != BossEncounterState.BOSS_ACTIVE && guard < 80) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.15f)
            guard++
        }
        assertEquals(BossEncounterState.BOSS_ACTIVE, engine.bossEncounterState)
        assertNotNull(engine.activeBoss)
    }

    @Test
    fun allFourEmpires_immediatelySelectableInEndlessScreenEvenWhenCampaignIsLocked() {
        val prefs = context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = CampaignProgressionManager(prefs = prefs)
        val freshCampaignState = GlobalCampaignState()

        // Campaign has only Stage 1 (DRAGON) unlocked and Stages 2..4 locked
        assertTrue(manager.isStageUnlocked(EmpireType.DRAGON, freshCampaignState, "fresh_pilot"))
        assertFalse(manager.isStageUnlocked(EmpireType.ALIEN, freshCampaignState, "fresh_pilot"))
        assertFalse(manager.isStageUnlocked(EmpireType.ORC, freshCampaignState, "fresh_pilot"))
        assertFalse(manager.isStageUnlocked(EmpireType.HUMAN, freshCampaignState, "fresh_pilot"))

        val deployedEmpires = mutableListOf<EmpireType>()
        composeRule.setContent {
            MyApplicationTheme {
                EndlessTheaterScreen(
                    endlessRecord = manager.loadEndlessRecord("fresh_pilot"),
                    onDeployEmpire = { deployedEmpires.add(it) },
                    onBack = {}
                )
            }
        }

        for (emp in EmpireType.entries) {
            composeRule.onNodeWithTag("endless_deploy_${emp.name}")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
        }

        assertEquals(4, deployedEmpires.size)
        assertEquals(EmpireType.entries.toSet(), deployedEmpires.toSet())
    }

    @Test
    fun endlessTheaterScreen_displaysConqueredBadgePerEmpireCheckpointsAndCappedAggregate() {
        val prefs = context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = CampaignProgressionManager(prefs = prefs)

        // Exceed 250,000 in DRAGON -> must cap DRAGON contribution at 250,000 toward 1,000,000
        manager.saveEmpireEndlessRecord("pilot_ui", EmpireType.DRAGON, 45, 5_000_000, 44, 300_000, 12, 3_000)
        manager.saveEmpireEndlessRecord("pilot_ui", EmpireType.ALIEN, 12, 180_000, 11, 50_000, 40, 500)
        val alienCp = EndlessCheckpoint(
            currentWave = 12,
            wavesCompleted = 11,
            activeEmpire = EmpireType.ALIEN,
            playerX = 0f,
            playerY = 120f,
            hearts = 4,
            hitsRemainingInHeart = 10,
            score = 180_000,
            kills = 50_000,
            confirmedCreditsEarned = 1200,
            waveEnemiesDefeated = 2,
            waveTotalEnemies = 22,
            totalRegularKills = 50_000,
            cycleKills = 40,
            bossesDefeated = 500
        )
        manager.saveEndlessCheckpoint("pilot_ui", alienCp)

        val record = manager.loadEndlessRecord("pilot_ui")
        assertEquals(300_000, record.cappedFourEmpireKills) // 250k + 50k
        assertEquals(350_000, record.uncappedFourEmpireKills)
        assertEquals(1, record.conqueredEmpireCount)

        val resumed = mutableListOf<EmpireType>()
        composeRule.setContent {
            MyApplicationTheme {
                EndlessTheaterScreen(
                    endlessRecord = record,
                    checkpointsByEmpire = manager.loadAllEndlessCheckpoints("pilot_ui"),
                    onDeployEmpire = {},
                    onResumeEmpire = { resumed.add(it) },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("endless_overall_progress_text")
            .assertIsDisplayed()
            .assertTextContains("300,000 / 1,000,000", substring = true)
        composeRule.onNodeWithTag("endless_empire_conquered_DRAGON")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("endless_empire_kills_DRAGON")
            .performScrollTo()
            .assertTextContains("Kills: 300,000 / 250,000")
        composeRule.onNodeWithTag("endless_resume_ALIEN")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(EmpireType.ALIEN), resumed)
    }

    @Test
    fun eachEmpire_spawnsItsThemedFlagshipBossEvery100Kills_scalesTierAndNeverSpawnsAddsInPhase2() {
        val expectedBossKeywords = mapOf(
            EmpireType.DRAGON to "Ignis Rex",
            EmpireType.ALIEN to "Overseer Prime",
            EmpireType.ORC to "Warlord Gashrak",
            EmpireType.HUMAN to "Project Titan"
        )

        for (emp in EmpireType.entries) {
            val seenCategories = mutableSetOf<EnemyCategory>()
            val engine = GameEngine(selectedEmpire = emp, gameMode = GameMode.ENDLESS_SURVIVAL)
            advanceKills(engine, 100, seenCategories = seenCategories)
            assertEquals("All 8 mixed categories should spawn in $emp", EnemyCategory.entries.toSet(), seenCategories)

            stepToBossActive(engine)
            val boss = engine.activeBoss!!
            assertEquals(emp, boss.empire)
            assertNull("Endless themed boss must use flagship model", boss.endlessBossType)
            assertTrue(
                "Boss name '${boss.name}' must match $emp flagship",
                boss.name.contains(expectedBossKeywords.getValue(emp))
            )

            // Force boss into Phase 2 (< 50% HP) and update repeatedly to trigger Phase 2 special attacks
            boss.health = boss.maxHealth * 0.35f
            boss.phase = 2
            boss.attackTimer = 0f
            boss.modeTimer = 0f
            repeat(12) {
                engine.player.invulnerableTimer = 1.0f
                engine.update(0.25f)
            }
            assertTrue(
                "Phase 2 Endless boss in $emp must NOT summon regular enemy adds",
                engine.enemies.isEmpty()
            )
            assertEquals("Regular kill count must stay 100 during boss fight", 100, engine.endlessTotalRegularKills)

            // Defeat boss -> count remains 100 regular kills, bossesDefeated = 1
            engine.applyDamageToBoss(boss.maxHealth * 5f, boss.pos.copy(), discreteHits = boss.maxBossHits + 10)
            assertEquals(100, engine.endlessTotalRegularKills)
            assertEquals(1, engine.endlessBossesDefeated)
        }
    }

    @Test
    fun independentEmpires_250kTargetsPerEmpireCheckpointsAndLegacyMigration() {
        val prefs = context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)

        // Scenario A: Legacy user with active legacy checkpoint in ALIEN
        val legacyAlienCpJson = org.json.JSONObject().apply {
            put("currentWave", 9)
            put("wavesCompleted", 8)
            put("activeEmpire", EmpireType.ALIEN.name)
            put("playerX", 0.0)
            put("playerY", 400.0)
            put("hearts", 4)
            put("hitsRemainingInHeart", 10)
            put("score", 42_000)
            put("kills", 185)
            put("confirmedCreditsEarned", 1500)
            put("waveEnemiesDefeated", 3)
            put("waveTotalEnemies", 20)
            put("totalRegularKills", 185)
            put("cycleKills", 85)
            put("bossesDefeated", 1)
            put("savedAtMs", 123456L)
        }.toString()
        prefs.edit()
            .putInt("endless_highest_wave_legacyA", 9)
            .putInt("endless_best_score_legacyA", 42_000)
            .putInt("endless_total_waves_legacyA", 8)
            .putInt("endless_total_regular_kills_legacyA", 185)
            .putInt("endless_total_bosses_defeated_legacyA", 1)
            .putString("endless_checkpoint_json_legacyA", legacyAlienCpJson)
            .commit()

        val managerA = CampaignProgressionManager(prefs = prefs)
        val recA = managerA.loadEndlessRecord("legacyA")
        assertEquals(185, recA.progressFor(EmpireType.ALIEN).regularKills)
        assertEquals(85, recA.progressFor(EmpireType.ALIEN).cycleKills)
        assertEquals(1, recA.progressFor(EmpireType.ALIEN).bossesDefeated)
        assertEquals(0, recA.progressFor(EmpireType.DRAGON).regularKills)
        assertNotNull(managerA.loadEndlessCheckpoint("legacyA", EmpireType.ALIEN))

        // Idempotent re-load must NOT double-count
        val recA2 = managerA.loadEndlessRecord("legacyA")
        assertEquals(185, recA2.cappedFourEmpireKills)
        assertEquals(1, recA2.totalBossesAcrossEmpires)

        // Scenario B: Legacy user with NO legacy checkpoint -> migrates safely into DRAGON
        prefs.edit()
            .putInt("endless_highest_wave_legacyB", 4)
            .putInt("endless_best_score_legacyB", 9_800)
            .putInt("endless_total_waves_legacyB", 3)
            .putInt("endless_total_regular_kills_legacyB", 74)
            .putInt("endless_total_bosses_defeated_legacyB", 0)
            .commit()

        val recB = managerA.loadEndlessRecord("legacyB")
        assertEquals(74, recB.progressFor(EmpireType.DRAGON).regularKills)
        assertEquals(74, recB.progressFor(EmpireType.DRAGON).cycleKills)
        assertEquals(4, recB.progressFor(EmpireType.DRAGON).highestWave)
        assertEquals(0, recB.progressFor(EmpireType.ALIEN).regularKills)

        // Independent per-Empire checkpoints & Campaign checkpoint preservation
        managerA.saveCheckpoint(
            "legacyA",
            SortieCheckpoint(
                empire = EmpireType.HUMAN,
                stageNumber = 4,
                playerX = 0f,
                playerY = 0f,
                hearts = 5,
                hitsRemainingInHeart = 10,
                score = 1000,
                kills = 5,
                creditsEarned = 200,
                destroyedBarracksIds = setOf("B1"),
                bossSpawned = false,
                bossDefeated = false,
                bossHitsRemaining = 100,
                hqUnlocked = false,
                hqHitsRemaining = 1000
            )
        )
        managerA.saveEndlessCheckpoint(
            "legacyA",
            EndlessCheckpoint(
                currentWave = 3,
                wavesCompleted = 2,
                activeEmpire = EmpireType.ORC,
                playerX = 1f,
                playerY = 90f,
                hearts = 5,
                hitsRemainingInHeart = 10,
                score = 3300,
                kills = 24,
                confirmedCreditsEarned = 400,
                waveEnemiesDefeated = 2,
                waveTotalEnemies = 8,
                totalRegularKills = 24,
                cycleKills = 24,
                bossesDefeated = 0
            )
        )

        // Clearing ORC checkpoint must keep ALIEN Endless checkpoint and HUMAN Campaign checkpoint intact
        managerA.clearEndlessCheckpoint("legacyA", EmpireType.ORC)
        assertNull(managerA.loadEndlessCheckpoint("legacyA", EmpireType.ORC))
        assertNotNull(managerA.loadEndlessCheckpoint("legacyA", EmpireType.ALIEN))
        assertNotNull(managerA.loadCheckpoint("legacyA"))
    }
}
