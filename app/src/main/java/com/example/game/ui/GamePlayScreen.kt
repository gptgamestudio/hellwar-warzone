package com.example.game.ui

import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
    onUpdateEndlessRecord: (highestWave: Int, bestScore: Int, wavesCompleted: Int, totalRegularKills: Int, totalBossesDefeated: Int) -> Unit = { _, _, _, _, _ -> },
    onUpdateEmpireEndlessRecord: ((empire: EmpireType, highestWave: Int, bestScore: Int, wavesCompleted: Int, empireRegularKills: Int, cycleKills: Int, empireBossesDefeated: Int) -> Unit)? = null,
    onStartEndlessSession: (suspend (EmpireType) -> Result<String>)? = null,
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

    LaunchedEffect(
        tacticalSettings.isSfxActive,
        tacticalSettings.isMusicActive,
        isFullScreenAdShowing
    ) {
        audioSystem.setAudioEnabled(
            sfxEnabled = tacticalSettings.isSfxActive && !isFullScreenAdShowing,
            musicEnabled = tacticalSettings.isMusicActive && !isFullScreenAdShowing
        )
    }

    DisposableEffect(audioSystem) {
        onDispose {
            audioSystem.release()
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val activeUpgradesSnapshot = profile.getUpgrades(profile.selectedHelicopterClass).copy()

    val initialEmpireProgress = remember(initialEndlessRecord, selectedEmpire) {
        initialEndlessRecord.progressFor(selectedEmpire)
    }
    val otherEmpiresCappedKills = remember(initialEndlessRecord, selectedEmpire) {
        EmpireType.entries
            .filter { it != selectedEmpire }
            .sumOf { initialEndlessRecord.progressFor(it).cappedChallengeKills }
    }

    var endlessSessionId by remember(gameMode, selectedEmpire) { mutableStateOf(resumeEndlessCheckpoint?.endlessSessionId) }
    val settledWaves = remember(gameMode, selectedEmpire) {
        mutableSetOf<Int>().apply {
            resumeEndlessCheckpoint?.settledWaves?.let { addAll(it) }
        }
    }
    val pendingClaimWaves = remember(gameMode, selectedEmpire) {
        mutableMapOf<Int, Int>().apply {
            resumeEndlessCheckpoint?.pendingClaimWaves?.let { putAll(it) }
        }
    }
    val inFlightWaves = remember(gameMode, selectedEmpire) { mutableSetOf<Int>() }
    var bestEndlessWave by remember(initialEmpireProgress, initialEndlessRecord) {
        mutableStateOf(maxOf(initialEmpireProgress.highestWave, if (initialEndlessRecord.empireProgress.isEmpty()) initialEndlessRecord.highestWave else 0))
    }
    var bestEndlessScore by remember(initialEmpireProgress, initialEndlessRecord) {
        mutableStateOf(maxOf(initialEmpireProgress.bestSurvivalScore, if (initialEndlessRecord.empireProgress.isEmpty()) initialEndlessRecord.bestSurvivalScore else 0))
    }
    var bestTotalRegularKills by remember(initialEmpireProgress) { mutableStateOf(initialEmpireProgress.regularKills) }
    var bestBossesDefeated by remember(initialEmpireProgress) { mutableStateOf(initialEmpireProgress.bossesDefeated) }
    var endlessSyncStatus by remember(gameMode, selectedEmpire) {
        mutableStateOf(
            if (pendingClaimWaves.isNotEmpty()) EndlessSyncStatus.PENDING_OFFLINE else EndlessSyncStatus.IDLE
        )
    }

    val engineRef = remember { mutableStateOf<GameEngine?>(null) }

    fun publishEndlessProgress(targetEngine: GameEngine, wavesDone: Int = targetEngine.wavesCompleted) {
        bestEndlessWave = maxOf(bestEndlessWave, targetEngine.currentWave)
        bestEndlessScore = maxOf(bestEndlessScore, targetEngine.score)
        bestTotalRegularKills = maxOf(bestTotalRegularKills, targetEngine.endlessTotalRegularKills)
        bestBossesDefeated = maxOf(bestBossesDefeated, targetEngine.endlessBossesDefeated)
        onUpdateEndlessRecord(
            bestEndlessWave,
            bestEndlessScore,
            wavesDone,
            bestTotalRegularKills,
            bestBossesDefeated
        )
        onUpdateEmpireEndlessRecord?.invoke(
            selectedEmpire,
            bestEndlessWave,
            bestEndlessScore,
            wavesDone,
            bestTotalRegularKills,
            targetEngine.endlessCycleKills,
            bestBossesDefeated
        )
    }

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
            val sessionResult = onStartEndlessSession(selectedEmpire)
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
                    publishEndlessProgress(targetEngine, targetEngine.wavesCompleted)
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
                resumeEndlessCheckpoint?.activeEmpire ?: selectedEmpire
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
            initialEndlessRegularKills = if (gameMode == GameMode.ENDLESS_SURVIVAL) initialEmpireProgress.regularKills else 0,
            initialEndlessCycleKills = if (gameMode == GameMode.ENDLESS_SURVIVAL) initialEmpireProgress.cycleKills else 0,
            initialEndlessBossesDefeated = if (gameMode == GameMode.ENDLESS_SURVIVAL) initialEmpireProgress.bossesDefeated else 0,
            otherEmpiresCappedKills = if (gameMode == GameMode.ENDLESS_SURVIVAL) otherEmpiresCappedKills else 0,
            onEndlessWaveCompleted = { event ->
                if (!settledWaves.contains(event.waveNumber) && !pendingClaimWaves.containsKey(event.waveNumber)) {
                    pendingClaimWaves[event.waveNumber] = event.waveKills
                }
                bestEndlessWave = maxOf(bestEndlessWave, event.waveNumber + 1)
                bestEndlessScore = maxOf(bestEndlessScore, event.survivalScore)
                val activeEngine = engineRef.value
                if (activeEngine != null) {
                    publishEndlessProgress(activeEngine, event.waveNumber)
                    activeEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                    if (!activeEngine.isGameOver) {
                        onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(activeEngine))
                    }
                    coroutineScope.launch {
                        settlePendingWaves(activeEngine)
                    }
                }
            },
            onEndlessBossDefeated = { event ->
                bestEndlessScore = maxOf(bestEndlessScore, event.survivalScore)
                val activeEngine = engineRef.value
                if (activeEngine != null) {
                    publishEndlessProgress(activeEngine, activeEngine.wavesCompleted)
                    activeEngine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                    if (!activeEngine.isGameOver) {
                        onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(activeEngine))
                    }
                } else {
                    bestTotalRegularKills = maxOf(bestTotalRegularKills, event.totalRegularKills)
                    bestBossesDefeated = maxOf(bestBossesDefeated, event.bossNumber)
                    onUpdateEndlessRecord(bestEndlessWave, bestEndlessScore, 0, bestTotalRegularKills, bestBossesDefeated)
                    onUpdateEmpireEndlessRecord?.invoke(
                        selectedEmpire,
                        bestEndlessWave,
                        bestEndlessScore,
                        0,
                        bestTotalRegularKills,
                        0,
                        bestBossesDefeated
                    )
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
    var isExitingBattle by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var isLandscapeViewportStable by remember { mutableStateOf(false) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var isBannerVisible by remember { mutableStateOf(true) }

    LaunchedEffect(
        isPaused,
        isFullScreenAdShowing,
        isExitingBattle,
        hudState.isGameOver,
        hudState.isVictory,
        tacticalSettings.isMusicActive
    ) {
        if (tacticalSettings.isMusicActive &&
            !isPaused &&
            !isFullScreenAdShowing &&
            !isExitingBattle &&
            !hudState.isGameOver &&
            !hudState.isVictory
        ) {
            audioSystem.startMusic()
        } else {
            audioSystem.pauseMusic()
        }
    }

    LaunchedEffect(viewportSize) {
        val isLandscape = viewportSize.width > viewportSize.height && viewportSize.width > 0 && viewportSize.height > 0
        if (!isLandscape) {
            isLandscapeViewportStable = false
        } else {
            withFrameNanos { }
            isLandscapeViewportStable = true
        }
    }

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
            bestTotalRegularKills = maxOf(bestTotalRegularKills, engine.endlessTotalRegularKills)
            bestBossesDefeated = maxOf(bestBossesDefeated, engine.endlessBossesDefeated)
            engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
            if (endlessSessionId.isNullOrBlank() && onStartEndlessSession != null) {
                val res = onStartEndlessSession(selectedEmpire)
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
    LaunchedEffect(
        engine,
        gameMode,
        hudState.barracksDestroyedCount,
        hudState.isHqBossDefeated,
        hudState.currentWave,
        hudState.wavesCompleted,
        hudState.endlessBossesDefeated,
        hudState.bossEncounterState,
        hudState.endlessTotalRegularKills / 10
    ) {
        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
            if (!engine.isGameOver && (hudState.currentWave > 1 || hudState.wavesCompleted > 0 || hudState.score > 0 || hudState.endlessTotalRegularKills > 0 || hudState.endlessBossesDefeated > 0)) {
                publishEndlessProgress(engine, hudState.wavesCompleted)
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
                publishEndlessProgress(engine, engine.wavesCompleted)
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
                publishEndlessProgress(engine, engine.wavesCompleted)
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
    DisposableEffect(lifecycleOwner, context, canRequestAds, activity, isExitingBattle) {
        if (!isExitingBattle) {
            activity?.let { act ->
                if (act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        }
        if (canRequestAds) {
            AdMobManager.ensureAdsLoaded(context)
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (!isExitingBattle) {
                    activity?.let { act ->
                        if (act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }
                }
                if (AdConsentManager.canRequestAds.value) {
                    AdMobManager.ensureAdsLoaded(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val saveAndReturnToDashboard: () -> Unit = {
        if (!isExitingBattle) {
            isExitingBattle = true
            isPaused = true
            engine.clearMovementInputs()
            if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                publishEndlessProgress(engine, engine.wavesCompleted)
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
    }

    // System back button saves checkpoint and returns to Dashboard
    BackHandler {
        saveAndReturnToDashboard()
    }

    // High performance 60 FPS game loop gated on stable landscape viewport & active battle state
    LaunchedEffect(engine, isPaused, isFullScreenAdShowing, isLandscapeViewportStable, isExitingBattle) {
        if (isPaused || isFullScreenAdShowing || !isLandscapeViewportStable || isExitingBattle) {
            return@LaunchedEffect
        }
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
        // Dedicated Upper Horizontal AdMob Banner Strip (separate from battlefield and HUD controls)
        com.example.game.ads.UpperGameplayBannerDock(
            isBannerVisible = isBannerVisible,
            onSetBannerVisible = { isBannerVisible = it }
        )

        // Weighted Landscape Battlefield Zone
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0E1621))
                .onSizeChanged { viewportSize = it }
        ) {
            if (isLandscapeViewportStable) {
                // 3D Canvas Rendering (reads frameTick so every simulation step triggers a crisp redraw)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE")
                    val currentTick = frameTick
                    if (size.width > size.height && size.width > 0f && size.height > 0f) {
                        renderer.render(this, engine, size.width, size.height)
                    }
                }

            // Full Military HUD Overlay
            HudOverlay(
                hudState = hudState,
                engine = engine,
                onPauseClick = { isPaused = true },
                onPauseStateChange = { paused ->
                    isPaused = paused
                    if (paused && gameMode == GameMode.ENDLESS_SURVIVAL && !engine.isGameOver) {
                        publishEndlessProgress(engine, engine.wavesCompleted)
                        onSaveEndlessCheckpoint(snapshotEndlessCheckpoint(engine))
                    }
                },
                onSaveAndReturnToDashboard = saveAndReturnToDashboard,
                onReturnToMenu = {
                    if (!isExitingBattle) {
                        isExitingBattle = true
                        isPaused = true
                        engine.clearMovementInputs()
                        if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                            publishEndlessProgress(engine, engine.wavesCompleted)
                            onClearEndlessCheckpoint()
                        } else {
                            onClearCheckpoint()
                        }
                        AdMobManager.showInterstitialIfReady(activity) {
                            onReturnToMenu()
                        }
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
                            engine.resetBattle(preserveEndlessCumulative = true)
                            engine.updateEndlessMetadata(bestEndlessWave, bestEndlessScore, endlessSyncStatus)
                            if (onStartEndlessSession != null) {
                                coroutineScope.launch {
                                    val res = onStartEndlessSession(selectedEmpire)
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
                hapticsEnabled = tacticalSettings.hapticsEnabled,
                soundAndMusicEnabled = tacticalSettings.soundAndMusicEnabled,
                onToggleSoundAndMusic = { enabled ->
                    settingsManager.setSoundAndMusicEnabled(enabled)
                }
            )
            }
        }
    }
}
