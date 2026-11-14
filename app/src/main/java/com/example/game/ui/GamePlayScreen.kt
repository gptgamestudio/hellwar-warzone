package com.example.game.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun GamePlayScreen(
    selectedEmpire: EmpireType,
    profile: com.example.game.model.PlayerProfile = com.example.game.model.PlayerProfile(),
    onReturnToMenu: () -> Unit,
    onClaimDebriefAdBonus: ((onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val campaignService = remember { com.example.game.network.WarNetCampaignService.getInstance(context) }
    val audioSystem = remember { com.example.game.audio.CombatAudioSystem() }
    val engine = remember(selectedEmpire, profile.selectedHelicopterClass, profile.selectedSkin) {
        val upgrades = profile.getUpgrades(profile.selectedHelicopterClass)
        GameEngine(
            selectedEmpire = selectedEmpire,
            audioSystem = audioSystem,
            initialHeloClass = profile.selectedHelicopterClass,
            initialSkin = profile.selectedSkin,
            initialUpgrades = upgrades,
            campaignService = campaignService
        )
    }
    val renderer = remember { Renderer3D() }
    val hudState by engine.hudState.collectAsState()
    var isPaused by remember { mutableStateOf(false) }
    var frameTick by remember { mutableLongStateOf(0L) }

    // Preload ads on sortie launch for smooth post-mission transitions
    LaunchedEffect(Unit) {
        AdMobManager.loadInterstitial(context)
        AdMobManager.loadRewarded(context)
    }

    // Handle back button: pause or return to menu
    BackHandler {
        onReturnToMenu()
    }

    // High performance 60 FPS game loop with delta timing and true pause freeze
    LaunchedEffect(engine, isPaused) {
        if (isPaused) return@LaunchedEffect
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

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFF6FAF6))) {
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
            onReturnToMenu = {
                AdMobManager.showInterstitialIfReady(activity) {
                    onReturnToMenu()
                }
            },
            onRestartSortie = {
                AdMobManager.showInterstitialIfReady(activity) {
                    isPaused = false
                    engine.resetBattle()
                }
            },
            onClaimDebriefAdBonus = onClaimDebriefAdBonus
        )
    }
}
