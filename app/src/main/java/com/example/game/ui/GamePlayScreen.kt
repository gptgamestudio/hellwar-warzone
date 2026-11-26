package com.example.game.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.findActivity
import com.example.game.engine.EndlessSyncStatus
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.Renderer3D
import com.example.game.model.EmpireType
import com.example.game.network.AdContinueQuota
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.EndlessSurvivalRecord
import com.example.game.network.EndlessWaveSettlementOutcome
import com.example.game.network.SortieCheckpoint
import kotlinx.coroutines.launch

@Composable
fun GamePlayScreen(
    selectedEmpire: EmpireType,
    profile: com.example.game.model.PlayerProfile = com.example.game.model.PlayerProfile(),
    resumeCheckpoint: SortieCheckpoint? = null,
    adContinueQuota: AdContinueQuota = AdContinueQuota(0),
    onSaveCheckpoint: (SortieCheckpoint) -> Unit = {},
    onClearCheckpoint: () -> Unit = {},
    onWatchAdContinue: ((onContinued: () -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    onStageCompleted: (EmpireType) -> Unit = {},
    onAutoDeployNextStage: ((EmpireType) -> Unit)? = null,
    onReturnToMenu: () -> Unit,
    onClaimDebriefAdBonus: ((onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    gameMode: GameMode = GameMode.CAMPAIGN,
    resumeEndlessCheckpoint: EndlessCheckpoint? = null,
    initialEndlessRecord: EndlessSurvivalRecord = EndlessSurvivalRecord(),
    onSaveEndlessCheckpoint: (EndlessCheckpoint) -> Unit = {},
    onClearEndlessCheckpoint: () -> Unit = {},
    onUpdateEndlessRecord: (highestWave: Int, bestScore: Int, wavesCompleted: Int) -> Unit = { _, _, _ -> },
    onStartEndlessSession: (suspend () -> Result<String>)? = null,
    onClaimEndlessWave: (suspend (sessionId: String, waveNumber: Int, waveKills: Int) -> Result<EndlessWaveSettlementOutcome>)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val campaignService = remember(gameMode, context) {
        if (gameMode == GameMode.CAMPAIGN && com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()) {
            com.example.game.network.WarNetCampaignService.getInstance(context)
        } else {
            null
        }
    }
    val settingsManager = remember(context) { com.example.game.network.TacticalSettingsManager.getInstance(context) }
    val tacticalSettings by settingsManager.settings.collectAsState()
    val isFullScreenAdShowing by AdMobManager.isFullScreenAdShowing.collectAsState()
    val audioSystem = remember(context) { com.example.game.audio.CombatAudioSystem(context.applicationContext) }

    LaunchedEffect(tacticalSettings.sfxEnabled, isFullScreenAdShowing) {
        audioSystem.isMuted = !tacticalSettings.sfxEnabled || isFullScreenAdShowing
    }

    DisposableEffect(audioSystem) {
        onDispose {
            audioSystem.release()
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val activeUpgradesSnapshot = profile.getUpgrades(profile.selectedHelicopterClass).copy()

    var endlessSessionId by remember(gameMode) { mutableStateOf(resumeEndlessCheckpoint?.endlessSessionId) }
    val settledWaves = remember(gameMode) {
        mutableSetOf<Int>().apply {
            resumeEndlessCheckpoint?.settledWaves?.let { addAll(it) }
        }
    }
    val pendingClaimWaves = remember(gameMode) {
        mutableMapOf<Int, Int>().apply {
            resumeEndlessCheckpoint?.pendingClaimWaves?.let { putAll(it) }
        }
    }
    val inFlightWaves = remember(gameMode) { mutableSetOf<Int>() }
    var bestEndlessWave by remember(initialEndlessRecord) { mutableStateOf(initialEndlessRecord.highestWave) }
    var bestEndlessScore by remember(initialEndlessRecord) { mutableStateOf(initialEndlessRecord.bestSurvivalScore) }
    var endlessSyncStatus by remember(gameMode) {
        mutableStateOf(
            if (pendingClaimWaves.isNotEmpty()) EndlessSyncStatus.PENDING_OFFLINE else EndlessSyncStatus.IDLE
        )
    }

    val engineRef = remember { mutableStateOf<GameEngine?>(null) }

    fun snapshotEndlessCheckpoint(targetEngine: GameEngine): EndlessCheckpoint {
        return targetEngine.exportEndlessCheckpoint(
            sessionId = endlessSessionId,
            settledWaves = settledWaves.toSet(),
            pendingClaimWaves = pendingClaimWaves.toMap()
        )
    }

    suspend fun settlePendingWaves(targetEngine: GameEngine) {
        if (gameMode != GameMode.ENDLESS_SURVIVAL) return
        if (onStartEndlessSession == null || onClaimEndlessWave == null) {
            if (pendingClaimWaves.isNotEmpty()) {
                endlessSyncStatus = EndlessSyncStatus.PENDING_OFFLINE
                targetEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
            }
            return
        }
        var sid = endlessSessionId
        if (sid.isNullOrBlank()) {
            val sessionResult = onStartEndlessSession()
            sid = sessionResult.getOrNull()
            if (!sid.isNullOrBlank()) {
                endlessSessionId = sid
            } else {
                endlessSyncStatus = EndlessSyncStatus.PENDING_OFFLINE
                targetEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                if (!targetEngine.isGameOver) {
                    onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(targetEngine))
                }
                return
            }
        }

        val sortedWaves = pendingClaimWaves.keys.sorted()
        var encounteredFailure = false
        for (waveNum in sortedWaves) {
            if (settledWaves.contains(waveNum)) {
                pendingClaimWaves.remove(waveNum)
                continue
            }
            val waveKills = pendingClaimWaves[waveNum] ?: continue
            if (!inFlightWaves.add(waveNum)) continue
            try {
                val claimRes = onClaimEndlessWave(sid, waveNum, waveKills)
                claimRes.onSuccess { outcome ->
                    settledWaves.add(waveNum)
                    pendingClaimWaves.remove(waveNum)
                    if (!outcome.alreadyClaimed && outcome.creditsAwarded > 0) {
                        targetEngine.applyEndlessWaveCredits(waveNum, outcome.creditsAwarded)
                    }
                    bestEndlessWave = maxOf(bestEndlessWave, outcome.highestWave)
                    bestEndlessScore = maxOf(bestEndlessScore, outcome.bestSurvivalScore)
                    onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, targetEngine.wavesCompleted)
                }.onFailure {
                    encounteredFailure = true
                }
            } finally {
                inFlightWaves.remove(waveNum)
            }
            if (encounteredFailure) break
        }

        endlessSyncStatus = if (encounteredFailure || pendingClaimWaves.isNotEmpty()) {
            EndlessSyncStatus.PENDING_OFFLINE
        } else {
            EndlessSyncStatus.SYNCED
        }
        targetEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
        if (!targetEngine.isGameOver) {
            onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(targetEngine))
        }
    }

    val engine = remember(
        gameMode,
        selectedEmpire,
        profile.selectedHelicopterClass,
        profile.selectedSkin,
        activeUpgradesSnapshot
    ) {
        GameEngine(
            selectedEmpire = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                GameEngine.endlessEmpireForWave(resumeEndlessCheckpoint?.currentWave ?: 1)
            } else {
                selectedEmpire
            },
            audioSystem = audioSystem,
            initialHeloClass = profile.selectedHelicopterClass,
            initialSkin = profile.selectedSkin,
            initialUpgrades = activeUpgradesSnapshot,
            campaignService = if (gameMode == GameMode.CAMPAIGN) campaignService else null,
            initialScreenShakeEnabled = tacticalSettings.screenShakeEnabled,
            initialAutoFire = tacticalSettings.autoFireByDefault,
            gameMode = gameMode,
            onEndlessWaveCompleted = { event ->
                if (!settledWaves.contains(event.waveNumber) && !pendingClaimWaves.containsKey(event.waveNumber)) {
                    pendingClaimWaves[event.waveNumber] = event.waveKills
                }
                bestEndlessWave = maxOf(bestEndlessWave, event.waveNumber + 1)
                bestEndlessScore = maxOf(bestEndlessScore, event.survivalScore)
                onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, event.waveNumber)
                val activeEngine = engineRef.value
                if (activeEngine != null) {
                    activeEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                    if (!activeEngine.isGameOver) {
                        onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(activeEngine))
                    }
                    coroutineScope.launch {
                        settlePendingWaves(activeEngine)
                    }
                }
            }
        ).also { engineRef.value = it }
    }
    LaunchedEffect(engine, tacticalSettings.screenShakeEnabled) {
        engine.isScreenShakeEnabled = tacticalSettings.screenShakeEnabled
    }

    val renderer = remember { Renderer3D() }
    val hudState by engine.hudState.collectAsState()
    var isPaused by remember { mutableStateOf(false) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var isBannerVisible by remember { mutableStateOf(true) }
    var extraZoneLiftDp by remember { mutableFloatStateOf(0f) }

    val nextStageEmpire = remember(selectedEmpire, gameMode) {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            null
        } else {
            val order = CampaignProgressionManager.STAGE_ORDER
            val idx = order.indexOf(selectedEmpire)
            if (idx >= 0 && idx + 1 < order.size) order[idx + 1] else null
        }
    }

    // Restore exact mid-stage or Endless checkpoint + start/sync Endless Cloud Session
    LaunchedEffect(engine, gameMode, resumeCheckpoint, resumeEndlessCheckpoint) {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            if (resumeEndlessCheckpoint != null) {
                engine.restoreFromEndlessCheckpoint(resumeEndlessCheckpoint)
            }
            bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
            bestEndlessScore = maxOf(bestEndlessScore, engine.score)
            engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
            if (endlessSessionId.isNullOrBlank() && onStartEndlessSession != null) {
                val res = onStartEndlessSession()
                res.onSuccess { sid ->
                    endlessSessionId = sid
                    if (pendingClaimWaves.isNotEmpty()) {
                        settlePendingWaves(engine)
                    } else {
                        endlessSyncStatus = EndlessSyncStatus.SYNCED
                        engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                    }
                }.onFailure {
                    endlessSyncStatus = EndlessSyncStatus.PENDING_OFFLINE
                    engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                }
            } else if (pendingClaimWaves.isNotEmpty()) {
                settlePendingWaves(engine)
            }
        } else if (resumeCheckpoint != null && resumeCheckpoint.empire == selectedEmpire) {
            engine.restoreFromCheckpoint(resumeCheckpoint)
        }
    }

    // Periodic milestone checkpoint auto-save while combat is active
    LaunchedEffect(engine, gameMode, hudState.barracksDestroyedCount, hudState.isHqBossDefeated, hudState.currentWave, hudState.wavesCompleted) {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            if (!engine.isGameOver && (hudState.currentWave > 1 || hudState.wavesCompleted > 0 || hudState.score > 0)) {
                bestEndlessWave = maxOf(bestEndlessWave, hudState.currentWave)
                bestEndlessScore = maxOf(bestEndlessScore, hudState.score)
                onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, hudState.wavesCompleted)
                onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
            }
        } else {
            if (!engine.isVictory && !engine.isGameOver &&
                (hudState.barracksDestroyedCount > 0 || hudState.isHqBossDefeated)
            ) {
                onSaveCheckpoint(engine.exportCheckpoint())
            }
        }
    }

    // Handle Campaign Victory milestone: unlock next stage cleanly and clear mid-stage checkpoint
    LaunchedEffect(hudState.isVictory, gameMode) {
        if (gameMode == GameMode.CAMPAIGN && hudState.isVictory) {
            onStageCompleted(selectedEmpire)
            onClearCheckpoint()
        }
    }

    // Trigger Interstitial Ad upon 0/5 Hearts defeat and update Endless records
    LaunchedEffect(hudState.isGameOver) {
        if (hudState.isGameOver) {
            if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
                bestEndlessScore = maxOf(bestEndlessScore, engine.score)
                onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, engine.wavesCompleted)
                engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                onClearEndlessCheckpoint()
            }
            AdMobManager.showInterstitialIfReady(activity) {}
        }
    }

    // Auto-save active mid-stage / Endless checkpoint if composition exits unexpectedly
    DisposableEffect(engine, gameMode) {
        onDispose {
            if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
                bestEndlessScore = maxOf(bestEndlessScore, engine.score)
                onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, engine.wavesCompleted)
                if (!engine.isGameOver) {
                    onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
                } else {
                    onClearEndlessCheckpoint()
                }
            } else {
                if (!engine.isVictory && !engine.isGameOver) {
                    onSaveCheckpoint(engine.exportCheckpoint())
                }
            }
        }
    }

    // Preload ads on sortie launch and resume ONLY after consent permits ads
    val canRequestAds by AdConsentManager.canRequestAds.collectAsState()
    LaunchedEffect(canRequestAds, context) {
        if (canRequestAds) {
            AdMobManager.ensureAdsLoaded(context)
        }
    }
    DisposableEffect(lifecycleOwner, context, canRequestAds) {
        if (canRequestAds) {
            AdMobManager.ensureAdsLoaded(context)
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && AdConsentManager.canRequestAds.value) {
                AdMobManager.ensureAdsLoaded(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val saveAndReturnToDashboard: () -> Unit = {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
            bestEndlessScore = maxOf(bestEndlessScore, engine.score)
            onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, engine.wavesCompleted)
            if (!engine.isGameOver) {
                onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
            } else {
                onClearEndlessCheckpoint()
            }
        } else {
            if (!engine.isVictory && !engine.isGameOver) {
                onSaveCheckpoint(engine.exportCheckpoint())
            } else {
                onClearCheckpoint()
            }
        }
        AdMobManager.showInterstitialIfReady(activity) {
            onReturnToMenu()
        }
    }

    // System back button saves checkpoint and returns to Dashboard
    BackHandler {
        saveAndReturnToDashboard()
    }

    // High performance 60 FPS game loop with delta timing and true pause / ad freeze
    LaunchedEffect(engine, isPaused, isFullScreenAdShowing) {
        if (isPaused || isFullScreenAdShowing) return@LaunchedEffect
        var lastFrameTime = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTime != 0L) {
                    val delta = ((frameTimeNanos - lastFrameTime) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    engine.update(delta)
                }
                lastFrameTime = frameTimeNanos
                frameTick++
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D14))
    ) {
        // Movable Game Zone: shifts up/down smoothly as the bottom banner dock adjusts
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFF6FAF6))
        ) {
            // 3D Canvas Rendering (reads frameTick so every simulation step triggers a crisp redraw)
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE")
                val currentTick = frameTick
                renderer.render(this, engine, size.width, size.height)
            }

            // Full Military HUD Overlay
            HudOverlay(
                hudState = hudState,
                engine = engine,
                onPauseClick = { isPaused = true },
                onPauseStateChange = { paused ->
                    isPaused = paused
                    if (paused && gameMode == GameMode.ENDLESS_SURVIVAL && !engine.isGameOver) {
                        bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
                        bestEndlessScore = maxOf(bestEndlessScore, engine.score)
                        onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, engine.wavesCompleted)
                        onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
                    }
                },
                onSaveAndReturnToDashboard = saveAndReturnToDashboard,
                onReturnToMenu = {
                    if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                        bestEndlessWave = maxOf(bestEndlessWave, engine.currentWave)
                        bestEndlessScore = maxOf(bestEndlessScore, engine.score)
                        onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, engine.wavesCompleted)
                        onClearEndlessCheckpoint()
                    } else {
                        onClearCheckpoint()
                    }
                    AdMobManager.showInterstitialIfReady(activity) {
                        onReturnToMenu()
                    }
                },
                onRestartSortie = {
                    if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                        onClearEndlessCheckpoint()
                        settledWaves.clear()
                        pendingClaimWaves.clear()
                        inFlightWaves.clear()
                        endlessSessionId = null
                        endlessSyncStatus = EndlessSyncStatus.IDLE
                        AdMobManager.showInterstitialIfReady(activity) {
                            isPaused = false
                            engine.resetBattle()
                            engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                            if (onStartEndlessSession != null) {
                                coroutineScope.launch {
                                    val res = onStartEndlessSession()
                                    res.onSuccess { sid ->
                                        endlessSessionId = sid
                                        endlessSyncStatus = EndlessSyncStatus.SYNCED
                                    }.onFailure {
                                        endlessSyncStatus = EndlessSyncStatus.PENDING_OFFLINE
                                    }
                                    engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                                }
                            }
                        }
                    } else {
                        onClearCheckpoint()
                        AdMobManager.showInterstitialIfReady(activity) {
                            isPaused = false
                            engine.resetBattle()
                        }
                    }
                },
                adContinueQuota = adContinueQuota,
                onWatchAdContinue = if (onWatchAdContinue != null) {
                    { onContinued, onStatus ->
                        onWatchAdContinue(
                            {
                                engine.continueAfterAdReward()
                                if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                                    onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
                                } else {
                                    onSaveCheckpoint(engine.exportCheckpoint())
                                }
                                onContinued()
                            },
                            onStatus
                        )
                    }
                } else null,
                nextStageEmpire = nextStageEmpire,
                onAutoDeployNextStage = if (gameMode == GameMode.CAMPAIGN && onAutoDeployNextStage != null) {
                    { nextEmpire ->
                        onClearCheckpoint()
                        AdMobManager.showInterstitialIfReady(activity) {
                            onAutoDeployNextStage(nextEmpire)
                        }
                    }
                } else null,
                onClaimDebriefAdBonus = onClaimDebriefAdBonus,
                hapticsEnabled = tacticalSettings.hapticsEnabled
            )
        }

        // Smallest AdMob Banner (320x50) at the very bottom with movable up/down Game Zone handle
        com.example.game.ads.MovableBottomBannerDock(
            isBannerVisible = isBannerVisible,
            extraZoneLiftDp = extraZoneLiftDp,
            onSetBannerVisible = { isBannerVisible = it },
            onExtraZoneLiftChange = { extraZoneLiftDp = it }
        )
    }
}
