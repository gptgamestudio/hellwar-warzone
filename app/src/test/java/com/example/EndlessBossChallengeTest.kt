package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.UpperGameplayBannerDock
import com.example.game.engine.BossEncounterState
import com.example.game.engine.EndlessBossDefeatedEvent
import com.example.game.engine.EndlessBossType
import com.example.game.engine.EnemyCategory
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.Renderer3D
import com.example.game.model.EmpireType
import com.example.game.model.PlayerProfile
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.EndlessSurvivalRecord
import com.example.game.network.SortieCheckpoint
import com.example.game.ui.HudOverlay
import com.example.game.ui.MainMenuScreen
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
@Config(sdk = [36], qualifiers = "w844dp-h390dp-land")
class EndlessBossChallengeTest {

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
        AdConsentManager.setConsentStateForTest(
            canRequestAds = true,
            isPrivacyOptionsRequired = false,
            isConsentGatheringComplete = true
        )
    }

    @After
    fun tearDown() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    private fun advanceRegularKillsTo(
        engine: GameEngine,
        targetTotalRegularKills: Int,
        seenCategories: MutableSet<EnemyCategory>? = null
    ) {
        var guard = 0
        while (engine.endlessTotalRegularKills < targetTotalRegularKills && guard < 4000) {
            engine.player.invulnerableTimer = 1.0f
            engine.enemies.forEach { seenCategories?.add(it.category) }
            val remainingNeeded = targetTotalRegularKills - engine.endlessTotalRegularKills
            if (engine.bossEncounterState == BossEncounterState.NORMAL_WAVE && engine.enemies.isNotEmpty()) {
                engine.enemies.take(remainingNeeded).forEach { it.health = 0f }
            }
            engine.update(0.15f)
            guard++
        }
        assertEquals(
            "Expected $targetTotalRegularKills regular kills",
            targetTotalRegularKills,
            engine.endlessTotalRegularKills
        )
    }

    private fun stepUntilBossActive(engine: GameEngine) {
        var guard = 0
        while (engine.bossEncounterState != BossEncounterState.BOSS_ACTIVE && guard < 80) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.15f)
            guard++
        }
        assertEquals(BossEncounterState.BOSS_ACTIVE, engine.bossEncounterState)
        assertNotNull("Boss entity must be spawned in BOSS_ACTIVE", engine.activeBoss)
    }

    private fun defeatActiveBossAndResumeNextCycle(engine: GameEngine) {
        val boss = engine.activeBoss ?: error("Expected activeBoss to be non-null")
        engine.applyDamageToBoss(
            damage = boss.maxHealth * 10f,
            hitPos = boss.pos.copy(),
            discreteHits = boss.maxBossHits + 50
        )
        assertEquals(BossEncounterState.BOSS_DEFEATED, engine.bossEncounterState)
        var guard = 0
        while (engine.bossEncounterState != BossEncounterState.NORMAL_WAVE && guard < 80) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.15f)
            guard++
        }
        assertEquals(BossEncounterState.NORMAL_WAVE, engine.bossEncounterState)
    }

    @Test
    fun scenario01_at99RegularKills_noBossSpawnsAndCycleRemainsNormalWave() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        advanceRegularKillsTo(engine, 99)

        assertEquals(99, engine.endlessTotalRegularKills)
        assertEquals(99, engine.endlessCycleKills)
        assertEquals(0, engine.endlessBossesDefeated)
        assertEquals(BossEncounterState.NORMAL_WAVE, engine.bossEncounterState)
        assertNull("Boss must NOT spawn at 99 kills", engine.activeBoss)
        assertFalse(engine.bossSpawned)
    }

    @Test
    fun scenario02_at100RegularKills_stopsRegularSpawningAndSpawnsExactlyOneBoss() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        advanceRegularKillsTo(engine, 100)

        assertEquals(100, engine.endlessTotalRegularKills)
        assertEquals(100, engine.endlessCycleKills)
        assertEquals(BossEncounterState.BOSS_INTRO, engine.bossEncounterState)
        assertTrue("Regular enemies must be cleared cleanly for boss fight", engine.enemies.isEmpty())
        assertEquals("BIG BOSS APPROACHING", engine.hudState.value.bossAnnouncementTitle)

        stepUntilBossActive(engine)
        val boss = engine.activeBoss!!
        assertNull("Themed flagship boss uses Empire flagship visual model", boss.endlessBossType)
        assertNull(engine.activeEndlessBossType)
        assertEquals(EmpireType.DRAGON, boss.empire)
        assertEquals(GameEngine.endlessThemedBossName(EmpireType.DRAGON), boss.name)
        assertTrue(engine.enemies.isEmpty())

        // Regular enemy spawning must stay paused during active Big Boss encounter
        repeat(15) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.2f)
            assertTrue("Regular enemies must not spawn while Big Boss is active", engine.enemies.isEmpty())
            assertTrue("Exactly one Big Boss must be active", engine.activeBoss === boss)
        }
    }

    @Test
    fun scenario03_gradualSequentialSpawning_neverSpawnsAllEnemiesAtOnce() {
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            initialEndlessMaxActiveEnemies = 8,
            initialEndlessSpawnIntervalSec = 0.65f
        )

        val initialCount = engine.enemies.size
        assertTrue("Initial burst must stay small (was $initialCount)", initialCount in 1..2)
        assertTrue("Must never spawn 100 enemies at once", initialCount < 10)

        val counts = mutableListOf(initialCount)
        repeat(8) {
            engine.update(0.35f)
            counts.add(engine.enemies.size)
            assertTrue(
                "Active enemies (${engine.enemies.size}) must stay within wave & active limit",
                engine.enemies.size <= engine.waveTotalEnemies &&
                    engine.enemies.size <= engine.endlessMaxActiveEnemies
            )
        }
        assertTrue("Sequential timer should spawn additional enemies over time", counts.max() > initialCount)
    }

    @Test
    fun scenario04_mixedEnemyComposition_coversAllEightTypesAcross100KillCycle() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        val seenCategories = mutableSetOf<EnemyCategory>()

        advanceRegularKillsTo(engine, 100, seenCategories = seenCategories)

        assertEquals(
            "A 100-enemy cycle must mix all 8 regular enemy categories",
            EnemyCategory.entries.toSet(),
            seenCategories
        )
    }

    @Test
    fun scenario05_activeEnemyLimit_neverExceededEvenUnderHighSpawnPressure() {
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            initialEndlessMaxActiveEnemies = 5,
            initialEndlessSpawnIntervalSec = 0.25f
        )
        assertEquals(5, engine.endlessMaxActiveEnemies)

        // Advance past Wave 1 into Wave 2+ where waveTotalEnemies > 5
        advanceRegularKillsTo(engine, 12)
        repeat(25) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.25f)
            assertTrue(
                "Enemies (${engine.enemies.size}) must never exceed endlessMaxActiveEnemies (5)",
                engine.enemies.size <= 5
            )
        }

        engine.endlessMaxActiveEnemies = 999
        assertEquals(
            "Configured limit must clamp safely to MAX_ACTIVE_ENEMIES",
            GameEngine.MAX_ACTIVE_ENEMIES,
            engine.endlessMaxActiveEnemies
        )
    }

    @Test
    fun scenario06_and_07_bossDefeatDoesNotCountAsRegularKill_resetsCycleAwardsScoreAndResumes() {
        val bossEvents = mutableListOf<EndlessBossDefeatedEvent>()
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            onEndlessBossDefeated = { bossEvents.add(it) }
        )
        advanceRegularKillsTo(engine, 100)
        stepUntilBossActive(engine)

        val scoreBeforeBoss = engine.score
        val regularKillsBeforeBoss = engine.endlessTotalRegularKills
        val boss = engine.activeBoss!!

        engine.applyDamageToBoss(
            damage = boss.maxHealth * 5f,
            hitPos = boss.pos.copy(),
            discreteHits = boss.maxBossHits
        )

        // Boss kill must NOT increment regular or cycle kills, and must NOT mint unverified credits
        assertEquals(regularKillsBeforeBoss, engine.endlessTotalRegularKills)
        assertEquals(1, engine.endlessBossesDefeated)
        assertEquals(0, engine.creditsEarned)
        assertEquals(1, bossEvents.size)
        assertEquals(EmpireType.DRAGON, bossEvents.first().empire)
        assertNull(bossEvents.first().bossType)
        assertTrue(bossEvents.first().bossName.contains("Ignis Rex"))
        assertEquals(scoreBeforeBoss + GameEngine.endlessBossScoreReward(0), engine.score)
        assertNull(engine.activeBoss)
        assertEquals(BossEncounterState.BOSS_DEFEATED, engine.bossEncounterState)
        assertEquals("BIG BOSS DEFEATED!", engine.hudState.value.bossAnnouncementTitle)

        // Step through BOSS_DEFEATED -> NEXT_WAVE -> NORMAL_WAVE
        var sawNextWaveBanner = false
        var guard = 0
        while (engine.bossEncounterState != BossEncounterState.NORMAL_WAVE && guard < 80) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.15f)
            if (engine.bossEncounterState == BossEncounterState.NEXT_WAVE) {
                sawNextWaveBanner = true
            }
            guard++
        }
        assertTrue("Should show NEXT_WAVE announcement before resuming", sawNextWaveBanner)
        assertEquals(BossEncounterState.NORMAL_WAVE, engine.bossEncounterState)
        assertEquals(0, engine.endlessCycleKills)

        // Verify regular enemies resume spawning cleanly
        repeat(8) {
            engine.player.invulnerableTimer = 1.0f
            engine.update(0.3f)
        }
        assertTrue("Regular enemy spawning must resume after boss defeat", engine.enemies.isNotEmpty())
    }

    @Test
    fun scenario08_and_09_themedFlagshipBossEscalatesAcrossTiersWithinSameEmpire() {
        val engine = GameEngine(selectedEmpire = EmpireType.ALIEN, gameMode = GameMode.ENDLESS_SURVIVAL)
        var boss1Hp = 0f
        var boss1Hits = 0

        for (bossIdx in 1..2) {
            advanceRegularKillsTo(engine, bossIdx * 100)
            stepUntilBossActive(engine)
            val boss = engine.activeBoss!!
            assertEquals(EmpireType.ALIEN, boss.empire)
            assertNull(boss.endlessBossType)
            assertTrue(boss.name.contains("Overseer Prime"))
            if (bossIdx == 1) {
                boss1Hp = boss.maxHealth
                boss1Hits = boss.maxBossHits
                defeatActiveBossAndResumeNextCycle(engine)
            }
        }

        val tier2Boss = engine.activeBoss!!
        assertEquals(2, tier2Boss.bossTier)
        assertTrue("2nd boss (Tier 2 escalation) should carry [T2] marker", tier2Boss.name.contains("[T2]"))
        assertTrue("Scaled Tier 2 boss must have higher max HP", tier2Boss.maxHealth > boss1Hp)
        assertTrue("Scaled Tier 2 boss must have more hits required", tier2Boss.maxBossHits > boss1Hits)
    }

    @Test
    fun scenario10_noDuplicateBossSpawnOrDuplicateRewardAcrossRepeatedHitsOrPauseResume() {
        var callbackCount = 0
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            onEndlessBossDefeated = { callbackCount++ }
        )
        advanceRegularKillsTo(engine, 100)
        stepUntilBossActive(engine)
        val boss = engine.activeBoss!!

        // Calling spawnEndlessBigBoss again while active must be ignored
        engine.spawnEndlessBigBoss()
        assertTrue(engine.activeBoss === boss)

        // Calling applyDamageToBoss multiple times after lethal damage must reward once
        val scoreBefore = engine.score
        assertTrue(engine.applyDamageToBoss(boss.maxHealth * 3f, boss.pos.copy(), boss.maxBossHits))
        assertFalse(engine.applyDamageToBoss(boss.maxHealth * 3f, boss.pos.copy(), boss.maxBossHits))

        assertEquals(1, callbackCount)
        assertEquals(1, engine.endlessBossesDefeated)
        assertEquals(scoreBefore + GameEngine.endlessBossScoreReward(0), engine.score)

        // Export checkpoint in BOSS_DEFEATED with bossRewardClaimed=true and restore into fresh engine
        val cp = engine.exportEndlessCheckpoint(sessionId = "sess_dup_guard")
        assertTrue(cp.bossRewardClaimed)
        assertEquals(1, cp.bossesDefeated)

        val restored = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        restored.restoreFromEndlessCheckpoint(cp)
        assertEquals(1, restored.endlessBossesDefeated)
        assertEquals(EmpireType.DRAGON.ordinal, restored.endlessBossRotationIndex)
        assertEquals(0, restored.endlessCycleKills)
        assertNull(restored.activeBoss)
    }

    @Test
    fun scenario11_saveAndLoad_preservesEndlessChallengeAndBossStateAndCampaignIsolation() {
        val prefs = context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = CampaignProgressionManager(prefs = prefs)

        // Save Campaign checkpoint first
        manager.saveCheckpoint(
            "pilotZ",
            SortieCheckpoint(
                empire = EmpireType.ORC,
                stageNumber = 3,
                playerX = 12f,
                playerY = 220f,
                hearts = 4,
                hitsRemainingInHeart = 8,
                score = 9900,
                kills = 31,
                creditsEarned = 800,
                destroyedBarracksIds = setOf("BARRACKS_ORC_1"),
                bossSpawned = false,
                bossDefeated = false,
                bossHitsRemaining = 100,
                hqUnlocked = false,
                hqHitsRemaining = 1000
            )
        )

        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        advanceRegularKillsTo(engine, 100)
        stepUntilBossActive(engine)
        val boss = engine.activeBoss!!
        // Damage boss partially without defeating it
        engine.applyDamageToBoss(400f, boss.pos.copy(), discreteHits = 20)
        val remainingHpBeforeSave = boss.health
        val remainingHitsBeforeSave = boss.bossHitsRemaining

        val savedCp = engine.exportEndlessCheckpoint(sessionId = "sess_boss_mid")
        manager.saveEndlessCheckpoint("pilotZ", savedCp)
        manager.saveEndlessRecord(
            uid = "pilotZ",
            highestWaveReached = engine.currentWave,
            survivalScore = engine.score,
            completedWavesCount = engine.wavesCompleted,
            totalRegularKills = engine.endlessTotalRegularKills,
            totalBossesDefeated = engine.endlessBossesDefeated
        )

        val loadedCp = manager.loadEndlessCheckpoint("pilotZ")
        assertNotNull(loadedCp)
        assertEquals(100, loadedCp!!.totalRegularKills)
        assertEquals(100, loadedCp.cycleKills)
        assertEquals(BossEncounterState.BOSS_ACTIVE.name, loadedCp.bossEncounterState)
        assertEquals(remainingHpBeforeSave, loadedCp.activeBossHealth, 0.1f)
        assertEquals(remainingHitsBeforeSave, loadedCp.activeBossHitsRemaining)

        // Restore into brand-new engine
        val resumedEngine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        resumedEngine.restoreFromEndlessCheckpoint(loadedCp)
        assertEquals(100, resumedEngine.endlessTotalRegularKills)
        assertEquals(100, resumedEngine.endlessCycleKills)
        assertEquals(BossEncounterState.BOSS_ACTIVE, resumedEngine.bossEncounterState)
        assertNotNull(resumedEngine.activeBoss)
        assertEquals(EmpireType.DRAGON, resumedEngine.activeBoss!!.empire)
        assertNull(resumedEngine.activeBoss!!.endlessBossType)
        assertEquals(remainingHpBeforeSave, resumedEngine.activeBoss!!.health, 0.5f)
        assertEquals(remainingHitsBeforeSave, resumedEngine.activeBoss!!.bossHitsRemaining)

        // Campaign checkpoint must remain completely intact
        val campaignCp = manager.loadCheckpoint("pilotZ")
        assertNotNull(campaignCp)
        assertEquals(EmpireType.ORC, campaignCp!!.empire)

        val loadedRecord = manager.loadEndlessRecord("pilotZ")
        assertEquals(100, loadedRecord.totalRegularKills)

        composeRule.setContent {
            MyApplicationTheme {
                MainMenuScreen(
                    profile = PlayerProfile(callsign = "ACE-Z"),
                    onPlayWar = {},
                    onEmpireCampaign = {},
                    onEndlessBattle = {},
                    onGarage = {},
                    onWeaponsUpgrades = {},
                    onWarCommand = {},
                    onLeaderboard = {},
                    onSettings = {},
                    endlessResumeCheckpoint = loadedCp,
                    endlessRecord = loadedRecord
                )
            }
        }
        composeRule.onNodeWithTag("menu_continue_endless").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("menu_endless_battle").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun scenario12_millionKillMilestone_announcesAndContinuesPast250kAndOneMillionWithoutCap() {
        val engine = GameEngine(
            selectedEmpire = EmpireType.HUMAN,
            gameMode = GameMode.ENDLESS_SURVIVAL,
            otherEmpiresCappedKills = 750_000
        )
        engine.restoreFromEndlessCheckpoint(
            EndlessCheckpoint(
                currentWave = 140,
                wavesCompleted = 139,
                activeEmpire = EmpireType.HUMAN,
                playerX = 0f,
                playerY = 900f,
                hearts = 5,
                hitsRemainingInHeart = 10,
                score = 50_000_000,
                kills = 249_998,
                confirmedCreditsEarned = 100_000,
                waveEnemiesDefeated = 0,
                waveTotalEnemies = 36,
                totalRegularKills = 249_998,
                cycleKills = 98,
                bossesDefeated = 2_499,
                bossRotationIndex = EmpireType.HUMAN.ordinal,
                bossEncounterState = BossEncounterState.NORMAL_WAVE.name
            )
        )

        // Advance 2 kills -> HUMAN reaches 250,000 and 4-Empire aggregate reaches 1,000,000
        advanceRegularKillsTo(engine, 250_000)
        assertEquals(250_000, engine.endlessTotalRegularKills)
        assertEquals(1_000_000, engine.hudState.value.endlessAggregateKills)
        assertFalse(engine.isVictory)
        assertFalse(engine.isGameOver)

        // Complete HUMAN flagship boss #2,500 (Project Titan)
        stepUntilBossActive(engine)
        assertEquals(EmpireType.HUMAN, engine.activeBoss!!.empire)
        assertTrue(engine.activeBoss!!.name.contains("Project Titan"))
        defeatActiveBossAndResumeNextCycle(engine)

        // Continue past 250,000 in HUMAN while 4-Empire aggregate caps cleanly at 1,000,000
        advanceRegularKillsTo(engine, 250_003)
        assertEquals(250_003, engine.endlessTotalRegularKills)
        assertEquals(1_000_000, engine.hudState.value.endlessAggregateKills)
        assertEquals(3, engine.endlessCycleKills)
        assertEquals(2_500, engine.endlessBossesDefeated)
    }

    @Test
    fun scenario13_longSessionMemorySafety_andObjectPoolingBoundsLists() {
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            initialEndlessMaxActiveEnemies = 8,
            initialEndlessSpawnIntervalSec = 0.25f
        )
        engine.player.isAutoFiring = true

        // Run 2 full 100-kill + boss cycles (200 regular kills + 2 boss fights)
        for (cycle in 1..2) {
            advanceRegularKillsTo(engine, cycle * 100)
            stepUntilBossActive(engine)
            defeatActiveBossAndResumeNextCycle(engine)
        }

        assertTrue("Bullets must stay bounded (${engine.bullets.size})", engine.bullets.size <= GameEngine.MAX_BULLETS)
        assertTrue("Particles must stay bounded (${engine.particles.size})", engine.particles.size <= GameEngine.MAX_PARTICLES)
        assertTrue("Active enemies must stay bounded (${engine.enemies.size})", engine.enemies.size <= engine.endlessMaxActiveEnemies)
        assertTrue("Enemy pool should recycle defeated enemies", engine.enemyPoolSize > 0)
        assertTrue("Particle pool should recycle expired explosion particles", engine.particlePoolSize > 0)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w640dp-h360dp-land")
    fun hudLayout_endlessChallengeAndBossHudFitCleanlyIn16By9Landscape() {
        assertEndlessAndBossHudLayout()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w844dp-h390dp-land")
    fun hudLayout_endlessChallengeAndBossHudFitCleanlyIn19Point5By9Landscape() {
        assertEndlessAndBossHudLayout()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w915dp-h412dp-land")
    fun hudLayout_endlessChallengeAndBossHudFitCleanlyIn20By9Landscape() {
        assertEndlessAndBossHudLayout()
    }

    private fun assertEndlessAndBossHudLayout() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        advanceRegularKillsTo(engine, 100)
        stepUntilBossActive(engine)

        composeRule.setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    UpperGameplayBannerDock(
                        isBannerVisible = true,
                        onSetBannerVisible = {}
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        HudOverlay(
                            hudState = engine.hudState.value,
                            engine = engine,
                            onPauseClick = {},
                            onReturnToMenu = {},
                            onRestartSortie = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val dockBounds = composeRule.onNodeWithTag("movable_banner_dock").assertIsDisplayed().getBoundsInRoot()
        val heartsBounds = composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed().getBoundsInRoot()
        val challengeBounds = composeRule.onNodeWithTag("endless_challenge_hud").assertIsDisplayed().getBoundsInRoot()
        val bossPanelBounds = composeRule.onNodeWithTag("boss_health_panel").assertIsDisplayed().getBoundsInRoot()
        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()
        val joystickBounds = composeRule.onNodeWithTag("virtual_joystick").assertIsDisplayed().getBoundsInRoot()
        val lockedBounds = composeRule.onNodeWithTag("missile_attack_button").assertIsDisplayed().getBoundsInRoot()

        composeRule.onNodeWithTag("endless_total_kills_text")
            .assertIsDisplayed()
            .assertTextContains("DRAGON: 100 / 250,000")
        composeRule.onNodeWithTag("endless_next_boss_text")
            .assertIsDisplayed()
            .assertTextContains("Next Boss: 100 / 100")
        composeRule.onNodeWithTag("endless_aggregate_kills_text")
            .assertIsDisplayed()
            .assertTextContains("4-Empire: 100 / 1,000,000")
        composeRule.onNodeWithTag("boss_name_text")
            .assertIsDisplayed()
            .assertTextContains(GameEngine.endlessThemedBossName(EmpireType.DRAGON))
        composeRule.onNodeWithTag("boss_health_bar").assertIsDisplayed()

        // Radar and OVER must stay removed
        composeRule.onNodeWithTag("tactical_radar_minimap").assertDoesNotExist()
        composeRule.onNodeWithTag("overdrive_button").assertDoesNotExist()
        composeRule.onNodeWithText("OVER").assertDoesNotExist()

        // No clipping outside screen bounds
        assertTrue(challengeBounds.left >= rootBounds.left)
        assertTrue(bossPanelBounds.right <= pauseBounds.left)
        assertTrue(challengeBounds.right <= bossPanelBounds.left)
        assertTrue(dockBounds.bottom <= heartsBounds.top)
        assertTrue(dockBounds.bottom <= bossPanelBounds.top)
        assertTrue(challengeBounds.bottom < joystickBounds.top)
        assertTrue(lockedBounds.bottom <= rootBounds.bottom)
    }

    @Test
    fun renderer3D_rendersAllFourEndlessBigBossModelsCleanly() {
        val renderer = Renderer3D()
        val drawScope = CanvasDrawScope()
        val density = Density(1f)
        val viewports = listOf(1280 to 720, 2340 to 1080, 2400 to 1080)

        EmpireType.entries.forEachIndexed { idx, emp ->
            val engine = GameEngine(selectedEmpire = emp, gameMode = GameMode.ENDLESS_SURVIVAL)
            engine.restoreFromEndlessCheckpoint(
                EndlessCheckpoint(
                    currentWave = 10 + idx * 5,
                    wavesCompleted = 9 + idx * 5,
                    activeEmpire = emp,
                    playerX = 0f,
                    playerY = 200f,
                    hearts = 5,
                    hitsRemainingInHeart = 10,
                    score = 10_000,
                    kills = (idx + 1) * 100,
                    confirmedCreditsEarned = 0,
                    waveEnemiesDefeated = 0,
                    waveTotalEnemies = 20,
                    totalRegularKills = (idx + 1) * 100,
                    cycleKills = 100,
                    bossesDefeated = idx,
                    bossRotationIndex = emp.ordinal,
                    bossEncounterState = BossEncounterState.BOSS_ACTIVE.name
                )
            )
            val boss = engine.activeBoss
            assertNotNull(boss)
            assertEquals(emp, boss!!.empire)
            assertNull(boss.endlessBossType)

            val (w, h) = viewports[idx % viewports.size]
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap.asImageBitmap())
            drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                renderer.render(this, engine, size.width, size.height)
            }
            bitmap.recycle()
        }
    }
}
