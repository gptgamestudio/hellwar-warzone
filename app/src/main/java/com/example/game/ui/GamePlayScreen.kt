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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.game.ads.AdMobManager
import com.example.game.ads.findActivity
import com.example.game.engine.GameEngine
import com.example.game.engine.Renderer3D
import com.example.game.model.EmpireType
import com.example.game.network.AdContinueQuota
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.SortieCheckpoint

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
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val campaignService = remember { com.example.game.network.WarNetCampaignService.getInstance(context) }
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
    val engine = remember(selectedEmpire, profile.selectedHelicopterClass, profile.selectedSkin) {
        val upgrades = profile.getUpgrades(profile.selectedHelicopterClass)
        GameEngine(
            selectedEmpire = selectedEmpire,
            audioSystem = audioSystem,
            initialHeloClass = profile.selectedHelicopterClass,
            initialSkin = profile.selectedSkin,
            initialUpgrades = upgrades,
            campaignService = campaignService,
            initialScreenShakeEnabled = tacticalSettings.screenShakeEnabled,
            initialAutoFire = tacticalSettings.autoFireByDefault
        )
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

    val nextStageEmpire = remember(selectedEmpire) {
        val order = CampaignProgressionManager.STAGE_ORDER
        val idx = order.indexOf(selectedEmpire)
        if (idx >= 0 && idx + 1 < order.size) order[idx + 1] else null
    }

    // Restore exact mid-stage checkpoint when continuing from Dashboard
    LaunchedEffect(engine, resumeCheckpoint) {
        if (resumeCheckpoint != null && resumeCheckpoint.empire == selectedEmpire) {
            engine.restoreFromCheckpoint(resumeCheckpoint)
        }
    }

    // Periodic milestone checkpoint auto-save while combat is active
    LaunchedEffect(engine, hudState.barracksDestroyedCount, hudState.isHqBossDefeated) {
        if (!engine.isVictory && !engine.isGameOver &&
            (hudState.barracksDestroyedCount > 0 || hudState.isHqBossDefeated)
        ) {
            onSaveCheckpoint(engine.exportCheckpoint())
        }
    }

    // Handle Victory milestone: unlock next stage cleanly and clear mid-stage checkpoint
    LaunchedEffect(hudState.isVictory) {
        if (hudState.isVictory) {
            onStageCompleted(selectedEmpire)
            onClearCheckpoint()
        }
    }

    // Trigger Interstitial Ad upon 0/5 Hearts defeat before Continue (YES/NO) prompt
    LaunchedEffect(hudState.isGameOver) {
        if (hudState.isGameOver) {
            AdMobManager.showInterstitialIfReady(activity) {}
        }
    }

    // Auto-save active mid-stage checkpoint if composition exits unexpectedly
    DisposableEffect(engine) {
        onDispose {
            if (!engine.isVictory && !engine.isGameOver) {
                onSaveCheckpoint(engine.exportCheckpoint())
            }
        }
    }

    // Preload ads on sortie launch and resume
    DisposableEffect(lifecycleOwner, context) {
        AdMobManager.ensureAdsLoaded(context)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                AdMobManager.ensureAdsLoaded(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val saveAndReturnToDashboard: () -> Unit = {
        if (!engine.isVictory && !engine.isGameOver) {
            onSaveCheckpoint(engine.exportCheckpoint())
        } else {
            onClearCheckpoint()
        }
        AdMobManager.showInterstitialIfReady(activity) {
            onReturnToMenu()
        }
    }

    // System back button saves mid-stage checkpoint and returns to Dashboard
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
                onPauseStateChange = { isPaused = it },
                onSaveAndReturnToDashboard = saveAndReturnToDashboard,
                onReturnToMenu = {
                    onClearCheckpoint()
                    AdMobManager.showInterstitialIfReady(activity) {
                        onReturnToMenu()
                    }
                },
                onRestartSortie = {
                    onClearCheckpoint()
                    AdMobManager.showInterstitialIfReady(activity) {
                        isPaused = false
                        engine.resetBattle()
                    }
                },
                adContinueQuota = adContinueQuota,
                onWatchAdContinue = if (onWatchAdContinue != null) {
                    { onContinued, onStatus ->
                        onWatchAdContinue(
                            {
                                engine.continueAfterAdReward()
                                onSaveCheckpoint(engine.exportCheckpoint())
                                onContinued()
                            },
                            onStatus
                        )
                    }
                } else null,
                nextStageEmpire = nextStageEmpire,
                onAutoDeployNextStage = if (onAutoDeployNextStage != null) {
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
