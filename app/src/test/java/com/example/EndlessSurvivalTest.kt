package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.engine.EndlessSyncStatus
import com.example.game.engine.EndlessWaveCompletionEvent
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import com.example.game.model.HelicopterClass
import com.example.game.model.HelicopterSkin
import com.example.game.model.HelicopterUpgrades
import com.example.game.model.PlayerProfile
import com.example.game.network.AdContinueQuota
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.EndlessSurvivalRecord
import com.example.game.network.EndlessWaveSettlementOutcome
import com.example.game.network.SortieCheckpoint
import com.example.game.ui.GamePlayScreen
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
@Config(sdk = [36])
class EndlessSurvivalTest {

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
        composeRule.mainClock.autoAdvance = true
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @Test
    fun endlessMode_hasNoBarracksOrHqAndNeverTriggersCampaignVictory() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)

        assertEquals(GameMode.ENDLESS_SURVIVAL, engine.gameMode)
        assertTrue("Endless mode must NOT spawn campaign barracks", engine.allBarracks.isEmpty())
        assertNull("Endless mode must NOT spawn campaign HQ", engine.enemyHeadquarters)
        assertFalse("Endless mode must never set campaign victory", engine.isVictory)
        assertEquals(1, engine.currentWave)
        assertEquals(EmpireType.DRAGON, engine.selectedEmpire)

        val hud = engine.hudState.value
        assertEquals(GameMode.ENDLESS_SURVIVAL, hud.gameMode)
        assertEquals(0, hud.totalBarracks)
        assertEquals(1, hud.currentWave)
        assertFalse(hud.isVictory)
    }

    @Test
    fun endlessMode_fiveWaveTheaterCycleAndProgressiveDifficultyScaling() {
        // Waves 1..5 DRAGON -> 6..10 ALIEN -> 11..15 ORC -> 16..20 HUMAN -> 21..25 DRAGON
        assertEquals(EmpireType.DRAGON, GameEngine.endlessEmpireForWave(1))
        assertEquals(EmpireType.DRAGON, GameEngine.endlessEmpireForWave(5))
        assertEquals(EmpireType.ALIEN, GameEngine.endlessEmpireForWave(6))
        assertEquals(EmpireType.ALIEN, GameEngine.endlessEmpireForWave(10))
        assertEquals(EmpireType.ORC, GameEngine.endlessEmpireForWave(11))
        assertEquals(EmpireType.ORC, GameEngine.endlessEmpireForWave(15))
        assertEquals(EmpireType.HUMAN, GameEngine.endlessEmpireForWave(16))
        assertEquals(EmpireType.HUMAN, GameEngine.endlessEmpireForWave(20))
        assertEquals(EmpireType.DRAGON, GameEngine.endlessEmpireForWave(21))

        val w1Count = GameEngine.endlessEnemyCountForWave(1)
        val w5Count = GameEngine.endlessEnemyCountForWave(5)
        val w10Count = GameEngine.endlessEnemyCountForWave(10)
        assertTrue("Wave 5 enemy count must exceed Wave 1", w5Count > w1Count)
        assertTrue("Wave 10 enemy count must exceed Wave 5", w10Count > w5Count)

        assertTrue(
            "Enemy HP multiplier must scale upward across waves",
            GameEngine.endlessHpMultiplierForWave(6) > GameEngine.endlessHpMultiplierForWave(1)
        )
        assertTrue(
            "Enemy damage multiplier must scale upward across waves",
            GameEngine.endlessDamageMultiplierForWave(6) > GameEngine.endlessDamageMultiplierForWave(1)
        )
        assertTrue(
            "Server reference wave credits must increase with wave",
            GameEngine.endlessWaveRewardCredits(6) > GameEngine.endlessWaveRewardCredits(1)
        )
    }

    @Test
    fun endlessMode_clearingFiveWavesCyclesDragonToAlienAndAppliesNoUnverifiedCredits() {
        val completedEvents = mutableListOf<EndlessWaveCompletionEvent>()
        val engine = GameEngine(
            gameMode = GameMode.ENDLESS_SURVIVAL,
            onEndlessWaveCompleted = { completedEvents.add(it) }
        )

        for (expectedWave in 1..6) {
            assertEquals(expectedWave, engine.currentWave)
            val expectedEmpire = if (expectedWave <= 5) EmpireType.DRAGON else EmpireType.ALIEN
            assertEquals(expectedEmpire, engine.selectedEmpire)

            // Wait through transition timer if active, spawn all wave enemies, and eliminate them
            var guardSteps = 0
            while (engine.currentWave == expectedWave && guardSteps < 400) {
                if (engine.enemies.isNotEmpty()) {
                    engine.enemies.forEach { it.health = 0f }
                }
                engine.update(0.15f)
                guardSteps++
            }
            assertEquals("Wave $expectedWave must advance to ${expectedWave + 1}", expectedWave + 1, engine.currentWave)
        }

        assertEquals(6, completedEvents.size)
        assertEquals(EmpireType.DRAGON, completedEvents[4].empire)
        assertEquals(EmpireType.ALIEN, completedEvents[4].nextEmpire)
        assertEquals(EmpireType.ALIEN, completedEvents[5].empire)
        assertEquals(EmpireType.ALIEN, engine.selectedEmpire)
        assertFalse(engine.isVictory)

        // Client must NEVER grant unverified spendable credits until Cloud Functions confirms
        assertEquals(0, engine.creditsEarned)
        engine.applyEndlessWaveCredits(1, 150)
        engine.applyEndlessWaveCredits(1, 150) // duplicate ignored
        assertEquals(150, engine.creditsEarned)
    }

    @Test
    fun endlessMode_unboundedForwardFlight_andNoDeadlockWhenEnemiesCullBehindCamera() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        engine.player.pos = Vector3D(0f, 1500f, 9.5f)
        engine.isUpPressed = true
        engine.update(0.2f)
        assertTrue("Endless forward flight must allow Y > 1080", engine.player.pos.y > 1500f)

        // Simulate enemy behind camera getting culled; waveEnemiesSpawned must re-queue
        engine.enemies.forEach { it.pos = Vector3D(0f, engine.camera.y - 100f, 9.5f) }
        engine.update(0.1f)
        assertTrue(
            "Culled behind-camera enemies must decrement spawned count so wave can respawn",
            engine.waveEnemiesSpawned < engine.waveTotalEnemies
        )
    }

    @Test
    fun endlessMode_defeatContinueAndRestartLifecycle() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        engine.player.invulnerableTimer = 0f
        repeat(50) {
            engine.player.invulnerableTimer = 0f
            engine.applyPlayerHit(1)
        }
        assertTrue(engine.isGameOver)
        assertFalse(engine.isVictory)
        assertEquals(0, engine.player.hearts)

        engine.continueAfterAdReward()
        assertFalse(engine.isGameOver)
        assertEquals(5, engine.player.hearts)

        engine.resetBattle()
        assertEquals(1, engine.currentWave)
        assertEquals(EmpireType.DRAGON, engine.selectedEmpire)
        assertFalse(engine.isGameOver)
    }

    @Test
    fun uidScopedPersistence_endlessRecordsAndCheckpointsStayIsolatedFromCampaignAndOtherUsers() {
        val prefs = context.getSharedPreferences(CampaignProgressionManager.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = CampaignProgressionManager(prefs = prefs)

        // Save Campaign checkpoint for pilotA
        val campaignCp = SortieCheckpoint(
            empire = EmpireType.DRAGON,
            stageNumber = 1,
            playerX = 5f,
            playerY = 120f,
            hearts = 4,
            hitsRemainingInHeart = 8,
            score = 4200,
            kills = 12,
            creditsEarned = 600,
            destroyedBarracksIds = setOf("BARRACKS_DRAGON_1", "BARRACKS_DRAGON_2"),
            bossSpawned = false,
            bossDefeated = false,
            bossHitsRemaining = 100,
            hqUnlocked = false,
            hqHitsRemaining = 1000
        )
        manager.saveCheckpoint("pilotA", campaignCp)

        // Save Endless checkpoint + monotonic records for pilotA
        val endlessCp = EndlessCheckpoint(
            currentWave = 7,
            wavesCompleted = 6,
            activeEmpire = EmpireType.ALIEN,
            playerX = -8f,
            playerY = 340f,
            hearts = 3,
            hitsRemainingInHeart = 7,
            score = 18900,
            kills = 48,
            confirmedCreditsEarned = 1425,
            waveEnemiesDefeated = 4,
            waveTotalEnemies = 16,
            overdriveCharge = 55f,
            endlessSessionId = "endless_sess_123",
            settledWaves = setOf(1, 2, 3, 4, 5, 6),
            pendingClaimWaves = emptyMap()
        )
        manager.saveEndlessCheckpoint("pilotA", endlessCp)
        manager.saveEndlessRecord("pilotA", highestWaveReached = 7, survivalScore = 18900, completedWavesCount = 6)
        // Lower subsequent run must NOT overwrite monotonic max record
        val recordA = manager.saveEndlessRecord("pilotA", highestWaveReached = 2, survivalScore = 1500, completedWavesCount = 1)
        assertEquals(7, recordA.highestWave)
        assertEquals(18900, recordA.bestSurvivalScore)
        assertEquals(6, recordA.totalWavesCompleted)

        // PilotB must have completely isolated state
        assertNull(manager.loadEndlessCheckpoint("pilotB"))
        assertEquals(0, manager.loadEndlessRecord("pilotB").highestWave)

        // Verify Campaign checkpoint untouched
        val loadedCampaign = manager.loadCheckpoint("pilotA")
        assertNotNull(loadedCampaign)
        assertEquals(2, loadedCampaign!!.destroyedBarracksIds.size)

        // Restore into GameEngine and verify exact wave/empire/hearts/settled credits
        val restoredEngine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        restoredEngine.restoreFromEndlessCheckpoint(manager.loadEndlessCheckpoint("pilotA")!!)
        assertEquals(7, restoredEngine.currentWave)
        assertEquals(EmpireType.ALIEN, restoredEngine.selectedEmpire)
        assertEquals(3, restoredEngine.player.hearts)
        assertEquals(18900, restoredEngine.score)
        assertEquals(1425, restoredEngine.creditsEarned)
        assertEquals(16, restoredEngine.waveTotalEnemies)
        assertEquals(4, restoredEngine.waveEnemiesDefeated)

        // Clearing Endless checkpoint must preserve Campaign checkpoint & Endless record
        manager.clearEndlessCheckpoint("pilotA")
        assertNull(manager.loadEndlessCheckpoint("pilotA"))
        assertNotNull(manager.loadCheckpoint("pilotA"))
        assertEquals(7, manager.loadEndlessRecord("pilotA").highestWave)
    }

    @Test
    fun hudOverlay_rendersEndlessWaveHud_andOfflinePendingSyncDefeatNotice() {
        val engine = GameEngine(
            initialHeloClass = HelicopterClass.STEALTH,
            initialSkin = HelicopterSkin.PLASMA_NEON,
            initialUpgrades = HelicopterUpgrades(weaponLevel = 3),
            gameMode = GameMode.ENDLESS_SURVIVAL
        )
        engine.updateEndlessMetadata(bestWave = 8, bestScore = 24000, syncStatus = EndlessSyncStatus.PENDING_OFFLINE)

        // Force defeat (0/5 hearts)
        repeat(50) {
            engine.player.invulnerableTimer = 0f
            engine.applyPlayerHit(1)
        }
        // Trigger HUD refresh
        engine.continueAfterAdReward()
        repeat(50) {
            engine.player.invulnerableTimer = 0f
            engine.applyPlayerHit(1)
        }

        // Note: applyPlayerHit directly checks isGameOver without calling private updateHud(),
        // so trigger updateEndlessMetadata to refresh hudState flow for compose test
        engine.updateEndlessMetadata(bestWave = 8, bestScore = 24000, syncStatus = EndlessSyncStatus.PENDING_OFFLINE)
        val hudState = engine.hudState.value

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = hudState,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {},
                    adContinueQuota = AdContinueQuota(0),
                    onWatchAdContinue = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed()
        composeRule.onNodeWithTag("hud_health_counter").assertIsDisplayed()
        composeRule.onNodeWithTag("endless_pending_sync_notice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("YES — WATCH AD (+5 HEARTS)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("RESTART ENDLESS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun mainMenuScreen_showsContinueEndlessAndBestRecord_andTriggersCallbacks() {
        var startedEndless = false
        var continuedEndless = false
        val cp = EndlessCheckpoint(
            currentWave = 9,
            wavesCompleted = 8,
            activeEmpire = EmpireType.ALIEN,
            playerX = 0f,
            playerY = 200f,
            hearts = 4,
            hitsRemainingInHeart = 10,
            score = 14500,
            kills = 40,
            confirmedCreditsEarned = 1800,
            waveEnemiesDefeated = 2,
            waveTotalEnemies = 20
        )

        composeRule.setContent {
            MyApplicationTheme {
                MainMenuScreen(
                    profile = PlayerProfile(callsign = "ACE-01", credits = 12000),
                    onPlayWar = {},
                    onEmpireCampaign = {},
                    onEndlessBattle = { startedEndless = true },
                    onGarage = {},
                    onWeaponsUpgrades = {},
                    onWarCommand = {},
                    onLeaderboard = {},
                    onSettings = {},
                    endlessResumeCheckpoint = cp,
                    onContinueEndless = { continuedEndless = true },
                    endlessRecord = EndlessSurvivalRecord(highestWave = 9, bestSurvivalScore = 14500, totalWavesCompleted = 8)
                )
            }
        }

        composeRule.onNodeWithTag("menu_continue_endless").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(continuedEndless)

        composeRule.onNodeWithTag("menu_endless_battle").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(startedEndless)
    }

    @Test
    fun gamePlayScreen_pendingOfflineWaveSettlesOnceSessionRecovers_withoutDuplicateCredits() {
        val claimedWaves = mutableListOf<Int>()
        var failNetwork = true
        val pendingCp = EndlessCheckpoint(
            currentWave = 3,
            wavesCompleted = 2,
            activeEmpire = EmpireType.DRAGON,
            playerX = 0f,
            playerY = 80f,
            hearts = 5,
            hitsRemainingInHeart = 10,
            score = 2200,
            kills = 10,
            confirmedCreditsEarned = 150,
            waveEnemiesDefeated = 1,
            waveTotalEnemies = 8,
            settledWaves = setOf(1),
            pendingClaimWaves = mapOf(1 to 4, 2 to 6) // Wave 1 already settled -> must be skipped!
        )

        var latestSavedCp: EndlessCheckpoint? = null
        val attemptState = androidx.compose.runtime.mutableIntStateOf(0)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val attempt = attemptState.intValue
            MyApplicationTheme {
                androidx.compose.runtime.key(attempt) {
                    GamePlayScreen(
                        selectedEmpire = EmpireType.DRAGON,
                        gameMode = GameMode.ENDLESS_SURVIVAL,
                        resumeEndlessCheckpoint = if (attempt == 0) pendingCp else (latestSavedCp ?: pendingCp),
                        initialEndlessRecord = EndlessSurvivalRecord(highestWave = 3, bestSurvivalScore = 2200, totalWavesCompleted = 2),
                        onSaveEndlessCheckpoint = { latestSavedCp = it },
                        onReturnToMenu = {},
                        onStartEndlessSession = {
                            if (failNetwork) Result.failure(IllegalStateException("offline"))
                            else Result.success("sess_recovered")
                        },
                        onClaimEndlessWave = { _, waveNumber, _ ->
                            claimedWaves.add(waveNumber)
                            Result.success(
                                EndlessWaveSettlementOutcome(
                                    alreadyClaimed = false,
                                    waveNumber = waveNumber,
                                    empire = "DRAGON",
                                    nextEmpire = "DRAGON",
                                    creditsAwarded = 185,
                                    scoreAwarded = 1200,
                                    newTotalCredits = 20000,
                                    sessionScore = 3400,
                                    highestWave = 3,
                                    bestSurvivalScore = 3400
                                )
                            )
                        }
                    )
                }
            }
        }

        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        // While offline: pending Wave 2 remains queued in checkpoint and NOT claimed unverified
        assertTrue(claimedWaves.isEmpty())
        assertEquals(mapOf(1 to 4, 2 to 6), latestSavedCp?.pendingClaimWaves)

        // Now flip failNetwork = false and re-enter with pending checkpoint -> settles Wave 2 only (skips already-settled Wave 1)
        failNetwork = false
        attemptState.intValue = 1
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertEquals(listOf(2), claimedWaves)
    }
}
