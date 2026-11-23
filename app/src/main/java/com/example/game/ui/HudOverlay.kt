package com.example.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.game.engine.BossCombatEntity
import com.example.game.engine.GameEngine
import com.example.game.engine.GameHudState
import com.example.game.engine.HazardState
import com.example.game.model.EmpireType
import com.example.game.network.AdContinueQuota
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ShieldBlue
import com.example.ui.theme.ThreatRed
import kotlinx.coroutines.delay

@Composable
fun HudOverlay(
    hudState: GameHudState,
    engine: GameEngine,
    onPauseClick: () -> Unit,
    onReturnToMenu: () -> Unit,
    onRestartSortie: () -> Unit,
    modifier: Modifier = Modifier,
    onPauseStateChange: (Boolean) -> Unit = {},
    onClaimDebriefAdBonus: ((onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    onSaveAndReturnToDashboard: () -> Unit = onReturnToMenu,
    adContinueQuota: AdContinueQuota = AdContinueQuota(0),
    onWatchAdContinue: ((onContinued: () -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    nextStageEmpire: EmpireType? = null,
    onAutoDeployNextStage: ((EmpireType) -> Unit)? = null,
    hapticsEnabled: Boolean = true
) {
    var showPauseDialog by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pulseHaptic = remember(hapticsEnabled, haptic) {
        {
            if (hapticsEnabled) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // TOP HUD: Health, Armor, Score, Radar, Mission Progress & BOSS HEALTH
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 94.dp)
                .align(Alignment.TopStart)
        ) {
            TopStatusRow(
                hudState = hudState,
                onBackToDashboard = {
                    engine.isUpPressed = false
                    engine.isDownPressed = false
                    engine.isLeftPressed = false
                    engine.isRightPressed = false
                    onSaveAndReturnToDashboard()
                },
                onPauseClick = {
                    engine.isUpPressed = false
                    engine.isDownPressed = false
                    engine.isLeftPressed = false
                    engine.isRightPressed = false
                    showPauseDialog = true
                    onPauseClick()
                    onPauseStateChange(true)
                }
            )

            // Boss Health Bar if Boss is Active
            hudState.bossEntity?.let { boss ->
                Spacer(modifier = Modifier.height(4.dp))
                BossHealthPanel(boss = boss)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Dynamic Mission Objective Strip (Barracks -> HQ Danger Zone Boss -> 1,000-Hit Main HQ)
            val objectiveLabel: String
            val objectiveText: String
            val objectiveProgress: Float
            val objectiveColor: Color
            when {
                hudState.isHqBossDefeated -> {
                    objectiveLabel = "SIEGE HQ"
                    objectiveText = "${hudState.hqHitsRemaining}/${hudState.hqMaxHits}"
                    objectiveProgress = (hudState.hqHitsRemaining.toFloat() / hudState.hqMaxHits.toFloat()).coerceIn(0f, 1f)
                    objectiveColor = ThreatRed
                }
                hudState.isHeadquartersUnlocked -> {
                    objectiveLabel = if (hudState.bossEntity != null) "BOSS" else "GO TO HQ"
                    objectiveText = if (hudState.bossEntity != null) {
                        "${hudState.bossEntity.bossHitsRemaining}/${hudState.bossEntity.maxBossHits}"
                    } else {
                        "DANGER ZONE"
                    }
                    objectiveProgress = if (hudState.bossEntity != null) {
                        (hudState.bossEntity.bossHitsRemaining.toFloat() / hudState.bossEntity.maxBossHits.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    objectiveColor = Color(0xFF00E676)
                }
                else -> {
                    objectiveLabel = "BARRACKS"
                    objectiveText = "${hudState.barracksDestroyedCount}/${hudState.totalBarracks}"
                    objectiveProgress = (hudState.barracksDestroyedCount.toFloat() / hudState.totalBarracks.toFloat()).coerceIn(0f, 1f)
                    objectiveColor = Color(0xFFFF9100)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xD916221C), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF334A3C), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = objectiveLabel,
                    color = Color(0xFFFFD600),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                LinearProgressIndicator(
                    progress = { objectiveProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = objectiveColor,
                    trackColor = Color(0x33FF9100)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = objectiveText,
                    color = objectiveColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Threat warning banner
            AnimatedVisibility(
                visible = hudState.activeThreat != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                hudState.activeThreat?.let { threat ->
                    ThreatWarningBanner(threat.message, threat.color)
                }
            }
        }

        // PERMANENTLY ANCHORED UPPER-RIGHT RADAR (Completely independent 2D HUD element)
        TacticalRadarMinimap(
            engine = engine,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 10.dp)
        )

        // BOTTOM HUD: Center-Bottom Cross D-Pad with Side Weapon & Tactical Controls
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .align(Alignment.BottomCenter)
        ) {
            // Left Side Tactical: Auto-Fire Toggle, Flare Countermeasure & MRL Rocket Barrage
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Button(
                    onClick = { engine.player.isAutoFiring = !engine.player.isAutoFiring },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hudState.isAutoFiring) Color(0xFF1E2836) else Color(0xFF263238),
                        contentColor = if (hudState.isAutoFiring) AmberWarning else Color(0xFF78909C)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .testTag("auto_fire_toggle")
                ) {
                    Text(
                        text = if (hudState.isAutoFiring) "AUTO:ON" else "AUTO:OFF",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                FlareCountermeasureButton(
                    hudState = hudState,
                    onActivate = {
                        pulseHaptic()
                        engine.activateFlareCountermeasure()
                    }
                )

                MultipleRocketButton(
                    hudState = hudState,
                    onLaunch = {
                        pulseHaptic()
                        engine.launchRocketBarrage()
                    }
                )
            }

            // CENTER-BOTTOM: Cross-Shaped 4-Directional Movement D-Pad
            CrossDirectionalTouchControls(
                engine = engine,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Right Side Weapon Controls: Overdrive Plasma & Target-Lock Guided Missile
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                OverdriveWeaponButton(
                    hudState = hudState,
                    onActivate = {
                        pulseHaptic()
                        engine.activateOverdriveBeam()
                    }
                )

                DedicatedMissileButton(
                    hudState = hudState,
                    onLaunch = {
                        pulseHaptic()
                        engine.launchMissiles()
                    }
                )
            }
        }

        // Pause Modal Dialog
        if (showPauseDialog) {
            TacticalPauseDialog(
                onResume = {
                    showPauseDialog = false
                    onPauseStateChange(false)
                },
                onRestart = {
                    showPauseDialog = false
                    onPauseStateChange(false)
                    onRestartSortie()
                },
                onQuit = {
                    showPauseDialog = false
                    onPauseStateChange(false)
                    onSaveAndReturnToDashboard()
                }
            )
        }

        // Game Over (5-Heart Exhausted -> Watch Ad Continue? YES/NO) or Victory (Auto-Deploy Next Stage)
        if (hudState.isGameOver) {
            DefeatContinueDialog(
                score = hudState.score,
                kills = hudState.kills,
                credits = hudState.creditsEarned,
                empire = hudState.activeEmpire,
                stageNumber = hudState.stageNumber,
                adContinueQuota = adContinueQuota,
                onWatchAdContinue = onWatchAdContinue,
                onRestart = onRestartSortie,
                onReturnToHangar = onReturnToMenu
            )
        } else if (hudState.isVictory) {
            MissionDebriefingDialog(
                isVictory = true,
                score = hudState.score,
                kills = hudState.kills,
                credits = hudState.creditsEarned,
                empire = hudState.activeEmpire,
                stageNumber = hudState.stageNumber,
                nextStageEmpire = nextStageEmpire,
                onAutoDeployNextStage = onAutoDeployNextStage,
                onClaimSupplyBonus = onClaimDebriefAdBonus,
                onRestart = onRestartSortie,
                onReturnToHangar = onReturnToMenu
            )
        }
    }
}

@Composable
private fun BossHealthPanel(boss: BossCombatEntity) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xEE1A0A0A),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, ThreatRed),
        modifier = Modifier.fillMaxWidth().testTag("boss_health_panel")
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dangerous, contentDescription = null, tint = ThreatRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = boss.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ThreatRed
                    )
                }

                // Hit Counter & Phase Badge
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF263238), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${boss.bossHitsRemaining}/${boss.maxBossHits} HITS",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                when {
                                    boss.isEvading -> CyberCyan
                                    boss.phase == 3 -> ThreatRed
                                    else -> AmberWarning
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = when {
                                boss.isEvading -> "EVADING"
                                boss.phase == 3 -> "P3 ENRAGED"
                                else -> "PHASE ${boss.phase}/3"
                            },
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Shield Bar if shield > 0
            if (boss.shield > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "SHIELD",
                        fontSize = 9.sp,
                        color = ShieldBlue,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(44.dp)
                    )
                    LinearProgressIndicator(
                        progress = { (boss.shield / boss.maxShield).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = ShieldBlue,
                        trackColor = Color(0x3300E5FF)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Boss Health Bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "HULL",
                    fontSize = 9.sp,
                    color = ThreatRed,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(44.dp)
                )
                LinearProgressIndicator(
                    progress = { (boss.health / boss.maxHealth).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(3.dp)),
                    color = ThreatRed,
                    trackColor = Color(0x33FF1744)
                )
            }

            // Subsystems and Weak Point Status Row
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                boss.weakPoint?.let { wp ->
                    Text(
                        text = "TARGET: ${wp.name} [EXPOSED]",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = AmberWarning,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (boss.subsystems.isNotEmpty()) {
                    val destroyedCount = boss.subsystems.count { it.isDestroyed }
                    Text(
                        text = "SUBSYSTEMS: ${boss.subsystems.size - destroyedCount}/${boss.subsystems.size} OPERATIONAL",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (destroyedCount > 0) Color(0xFF00E676) else Color(0xFF90A4AE),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun TopStatusRow(
    hudState: GameHudState,
    onBackToDashboard: () -> Unit,
    onPauseClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC0D141E), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back to Dashboard Arrow (saves mid-stage checkpoint cleanly)
        IconButton(
            onClick = onBackToDashboard,
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF16202C), RoundedCornerShape(8.dp))
                .testTag("back_to_dashboard_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Save & Return to Dashboard",
                tint = CyberCyan,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 5 Hearts + 10 Hits per Heart + Armor/Shield Gauges
        Column(modifier = Modifier.weight(1.45f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 1..hudState.maxHearts) {
                    val filled = i <= hudState.hearts
                    Icon(
                        imageVector = if (filled) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (filled) ThreatRed else Color(0xFF455A64),
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${hudState.hitsRemainingInHeart}/${hudState.hitsPerHeart}",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Heart Integrity Progress Bar (10 hits per heart)
            LinearProgressIndicator(
                progress = { (hudState.hitsRemainingInHeart.toFloat() / hudState.hitsPerHeart.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = if (hudState.hearts <= 1) ThreatRed else HealthGreen,
                trackColor = Color(0x33FF1744)
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Compact Shield Bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SHD",
                    color = CyberCyan,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(24.dp)
                )
                LinearProgressIndicator(
                    progress = { (hudState.shield / hudState.maxShield.coerceAtLeast(1f)).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = CyberCyan,
                    trackColor = Color(0x3300E5FF)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Stage, Score & Kills
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "STG ${hudState.stageNumber} // ${hudState.score}",
                color = AmberWarning,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "KILLS: ${hudState.kills}",
                color = Color(0xFFCFD8DC),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = onPauseClick,
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF1E2836), RoundedCornerShape(8.dp))
                .testTag("pause_button")
        ) {
            Icon(Icons.Default.Pause, contentDescription = "Pause Game", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TacticalRadarMinimap(
    engine: GameEngine,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_sweep_angle"
    )

    Box(
        modifier = modifier
            .size(78.dp)
            .background(Color(0xF00D1620), CircleShape)
            .border(2.dp, Color(0xFF334654), CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // Subdued military tactical range rings (500m, 1000m)
            drawCircle(Color(0x284E7D63), radius * 0.48f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            drawCircle(Color(0x384E7D63), radius * 0.90f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))

            // Crosshair guidelines (subtle)
            drawLine(Color(0x224E7D63), Offset(center.x, 3f), Offset(center.x, size.height - 3f), 1f)
            drawLine(Color(0x224E7D63), Offset(3f, center.y), Offset(size.width - 3f, center.y), 1f)

            // Cardinal "N" North indicator at top
            drawCircle(Color(0xFFFFB300), 2.5f, Offset(center.x, 5f))

            // Realistic rotating radar sweep line
            val sweepRad = Math.toRadians(sweepAngle.toDouble())
            val sweepEnd = Offset(
                center.x + (radius * 0.88f * cos(sweepRad)).toFloat(),
                center.y + (radius * 0.88f * sin(sweepRad)).toFloat()
            )
            drawLine(
                Color(0x664E7D63),
                center,
                sweepEnd,
                1.5f,
                StrokeCap.Round
            )

            // Player Helicopter icon in center: Distinct green helicopter facing UP
            drawLine(Color(0xFF00E676), Offset(center.x, center.y + 4f), Offset(center.x, center.y - 5f), 2.5f, StrokeCap.Round)
            drawLine(Color(0xFF00E676), Offset(center.x - 4f, center.y - 1f), Offset(center.x + 4f, center.y - 1f), 2f, StrokeCap.Round)
            drawCircle(Color.White, 1.5f, center)

            val camY = engine.camera.y
            val camX = engine.camera.x

            // Discovered Barracks on radar (Gold Diamond blips)
            for (barracks in engine.allBarracks) {
                if (barracks.isDiscovered && !barracks.isDestroyed) {
                    val relX = ((barracks.pos.x - camX) / 45f) * (radius * 0.8f)
                    val relY = -((barracks.pos.y - (camY + 12f)) / 55f) * (radius * 0.8f)
                    val blipPos = Offset(
                        (center.x + relX).coerceIn(4f, size.width - 4f),
                        (center.y + relY).coerceIn(4f, size.height - 4f)
                    )
                    drawRect(Color(0xFFFFD600), Offset(blipPos.x - 2.5f, blipPos.y - 2.5f), Size(5f, 5f))
                }
            }

            // Unlocked Headquarters on radar (High priority objective)
            engine.enemyHeadquarters?.let { hq ->
                if (hq.isUnlocked && !hq.isDestroyed) {
                    val relX = ((hq.pos.x - camX) / 60f) * (radius * 0.8f)
                    val relY = -((hq.pos.y - (camY + 12f)) / 70f) * (radius * 0.8f)
                    val blipPos = Offset(
                        (center.x + relX).coerceIn(5f, size.width - 5f),
                        (center.y + relY).coerceIn(5f, size.height - 5f)
                    )
                    drawCircle(Color(0xFFFF1744), 5f, blipPos)
                    drawCircle(Color.White, 2f, blipPos)
                }
            }

            // Enemies on radar
            for (enemy in engine.enemies) {
                val relX = ((enemy.pos.x - camX) / 28f) * (radius * 0.8f)
                val relY = -((enemy.pos.y - (camY + 16f)) / 40f) * (radius * 0.8f)
                val blipPos = Offset(
                    (center.x + relX).coerceIn(4f, size.width - 4f),
                    (center.y + relY).coerceIn(4f, size.height - 4f)
                )
                if (enemy.isGroundUnit) {
                    // Ground tank / artillery: Amber square
                    drawRect(Color(0xFFFF9100), Offset(blipPos.x - 2f, blipPos.y - 2f), Size(4f, 4f))
                } else {
                    // Aerial combatant: Crimson red dot
                    drawCircle(Color(0xFFFF1744), 2.8f, blipPos)
                }
            }

            // Active Boss entity: Flashing alert marker
            engine.activeBoss?.let { boss ->
                val relX = (boss.pos.x / 14f) * (radius * 0.8f)
                val relY = -((boss.pos.y - (camY + 16f)) / 32f) * (radius * 0.8f)
                val blipPos = Offset((center.x + relX).coerceIn(4f, size.width - 4f), (center.y + relY).coerceIn(4f, size.height - 4f))
                drawCircle(Color(0xFFFF1744), 5.5f, blipPos)
                drawCircle(Color(0xFFFFD600), 2.5f, blipPos)
            }
        }
    }
}

@Composable
private fun ThreatWarningBanner(message: String, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "warning_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "alpha"
    )

    Row(
        modifier = Modifier
            .background(Color(0xDE141E19), RoundedCornerShape(6.dp))
            .background(color.copy(alpha = alpha * 0.28f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = alpha), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = "Threat Alert", tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = message, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Countermeasure Flare Button (Auto-Recharges every 7.5s, deflects incoming fire)
 */
@Composable
private fun FlareCountermeasureButton(
    hudState: GameHudState,
    onActivate: () -> Unit
) {
    val isReady = hudState.isFlareReady

    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (isReady) Color(0xFF0E7490) else Color(0xFF1E293B))
            .border(2.dp, if (isReady) CyberCyan else Color(0xFF455A64), CircleShape)
            .pointerInput(isReady) {
                if (isReady) {
                    detectTapGestures(onTap = { onActivate() })
                }
            }
            .testTag("flare_button"),
        contentAlignment = Alignment.Center
    ) {
        if (!isReady) {
            CircularProgressIndicator(
                progress = { hudState.flareCooldownPercent },
                modifier = Modifier.fillMaxSize(),
                color = CyberCyan,
                trackColor = Color(0x22FFFFFF),
                strokeWidth = 3.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, contentDescription = "Countermeasure Flare", tint = if (isReady) Color.White else Color(0xFF90A4AE), modifier = Modifier.size(18.dp))
            Text(text = "FLARE", fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = if (isReady) Color.White else Color(0xFF90A4AE))
        }
    }
}

/**
 * Overdrive Plasma Beam Button (0-100% auto-recharging super-weapon)
 */
@Composable
private fun OverdriveWeaponButton(
    hudState: GameHudState,
    onActivate: () -> Unit
) {
    val isReady = hudState.isOverdriveReady

    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (isReady) Color(0xFF0284C7) else Color(0xFF1E293B))
            .border(2.dp, if (isReady) Color.White else Color(0xFF455A64), CircleShape)
            .pointerInput(isReady) {
                if (isReady) {
                    detectTapGestures(onTap = { onActivate() })
                }
            }
            .testTag("overdrive_button"),
        contentAlignment = Alignment.Center
    ) {
        if (!isReady) {
            CircularProgressIndicator(
                progress = { hudState.overdriveChargePercent },
                modifier = Modifier.fillMaxSize(),
                color = CyberCyan,
                trackColor = Color(0x22FFFFFF),
                strokeWidth = 3.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Bolt, contentDescription = "Overdrive Plasma", tint = if (isReady) Color.White else Color(0xFF90A4AE), modifier = Modifier.size(18.dp))
            Text(
                text = if (isReady) "OVER" else "${(hudState.overdriveChargePercent * 100).toInt()}%",
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = if (isReady) Color.White else Color(0xFF90A4AE)
            )
        }
    }
}

/**
 * Multiple Rocket Launcher (MRL) Button
 */
@Composable
private fun MultipleRocketButton(
    hudState: GameHudState,
    onLaunch: () -> Unit
) {
    val isReady = hudState.isRocketReady

    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (isReady) Color(0xFFC2410C) else Color(0xFF263238))
            .border(2.dp, if (isReady) MilitaryGold else Color(0xFF455A64), CircleShape)
            .pointerInput(isReady) {
                if (isReady) {
                    detectTapGestures(onTap = { onLaunch() })
                }
            }
            .testTag("rocket_barrage_button"),
        contentAlignment = Alignment.Center
    ) {
        if (!isReady) {
            CircularProgressIndicator(
                progress = { hudState.rocketCooldownPercent },
                modifier = Modifier.fillMaxSize(),
                color = MilitaryGold,
                trackColor = Color(0x22FFFFFF),
                strokeWidth = 3.5.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "MRL", fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = if (isReady) Color.White else Color(0xFF90A4AE))
            Text(text = "${hudState.rocketAmmo}", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MilitaryGold)
        }
    }
}

/**
 * Dedicated Guided Missile Button with Target-Lock Feedback
 */
@Composable
private fun DedicatedMissileButton(
    hudState: GameHudState,
    onLaunch: () -> Unit
) {
    val isReady = hudState.isMissileReady
    val hasLock = hudState.lockedTargetName != null

    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(if (isReady) (if (hasLock) ThreatRed else Color(0xFF991B1B)) else Color(0xFF263238))
            .border(2.dp, if (hasLock) Color.White else (if (isReady) AmberWarning else Color(0xFF455A64)), CircleShape)
            .pointerInput(isReady) {
                if (isReady) {
                    detectTapGestures(onTap = { onLaunch() })
                }
            }
            .testTag("missile_attack_button"),
        contentAlignment = Alignment.Center
    ) {
        if (!isReady) {
            CircularProgressIndicator(
                progress = { hudState.missileReadyPercent },
                modifier = Modifier.fillMaxSize(),
                color = AmberWarning,
                trackColor = Color(0x22FFFFFF),
                strokeWidth = 4.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.RocketLaunch, contentDescription = "Missile Attack", tint = if (isReady) Color.White else Color(0xFF78909C), modifier = Modifier.size(22.dp))
            Text(
                text = if (hasLock) "LOCKED" else if (isReady) "${hudState.missileAmmo} MIS" else "ARMING",
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = if (hasLock) Color.White else AmberWarning
            )
        }
    }
}

/**
 * Cross-Shaped 4-Directional Movement Control Pad
 */
@Composable
private fun CrossDirectionalTouchControls(
    engine: GameEngine,
    modifier: Modifier = Modifier
) {
    val btnWidth = 48.dp
    val btnHeight = 48.dp

    androidx.compose.runtime.DisposableEffect(engine) {
        onDispose {
            engine.isUpPressed = false
            engine.isDownPressed = false
            engine.isLeftPressed = false
            engine.isRightPressed = false
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.testTag("cross_dpad_controller")
    ) {
        // TOP: UP Button (Forward)
        DirectionButton(
            icon = Icons.Default.KeyboardArrowUp,
            label = "UP",
            onPressChange = { engine.isUpPressed = it },
            modifier = Modifier.size(width = btnWidth, height = btnHeight).testTag("control_up")
        )

        // MIDDLE ROW: LEFT, DOWN (Backwards), RIGHT
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT Button (Sideways Left)
            DirectionButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                label = "LEFT",
                onPressChange = { engine.isLeftPressed = it },
                modifier = Modifier.size(width = btnWidth, height = btnHeight).testTag("control_left")
            )

            // CENTER-BOTTOM: DOWN Button (Move Backward)
            DirectionButton(
                icon = Icons.Default.KeyboardArrowDown,
                label = "DOWN",
                onPressChange = { engine.isDownPressed = it },
                modifier = Modifier.size(width = btnWidth, height = btnHeight).testTag("control_down")
            )

            // RIGHT Button (Sideways Right)
            DirectionButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                label = "RIGHT",
                onPressChange = { engine.isRightPressed = it },
                modifier = Modifier.size(width = btnWidth, height = btnHeight).testTag("control_right")
            )
        }
    }
}

@Composable
private fun DirectionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var localPressed by remember { mutableStateOf(false) }
    val bgColor = if (localPressed) AmberWarning else Color(0xEE1E293B)
    val borderColor = if (localPressed) Color.White else Color(0xFF475569)
    val tintColor = if (localPressed) Color.Black else CyberCyan

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(onPressChange) {
                awaitPointerEventScope {
                    try {
                        while (true) {
                            val downEvent = awaitPointerEvent()
                            val hasDown = downEvent.changes.any { it.pressed }
                            if (hasDown) {
                                localPressed = true
                                onPressChange(true)
                                while (true) {
                                    val currentEvent = awaitPointerEvent()
                                    val anyStillPressed = currentEvent.changes.any { it.pressed }
                                    if (!anyStillPressed) {
                                        localPressed = false
                                        onPressChange(false)
                                        break
                                    }
                                }
                            }
                        }
                    } finally {
                        localPressed = false
                        onPressChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tintColor,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = if (localPressed) Color.Black else Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun TacticalPauseDialog(onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit) {
    Dialog(onDismissRequest = onResume) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F1722),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "SORTIE PAUSED", fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = CyberCyan)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "WAR-NET BATTLE LINK ON STANDBY", fontSize = 11.sp, color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(20.dp))

                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RESUME BATTLE", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AmberWarning)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RESTART SORTIE", color = AmberWarning, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onQuit, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
                    Text("ABORT TO HANGAR", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DefeatContinueDialog(
    score: Int,
    kills: Int,
    credits: Int,
    empire: EmpireType,
    stageNumber: Int,
    adContinueQuota: AdContinueQuota,
    onWatchAdContinue: ((onContinued: () -> Unit, onStatus: (String) -> Unit) -> Unit)?,
    onRestart: () -> Unit,
    onReturnToHangar: () -> Unit
) {
    var statusText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C131D),
            border = androidx.compose.foundation.BorderStroke(2.dp, ThreatRed),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "0/5 HEARTS — CONTINUE?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = ThreatRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "STAGE $stageNumber // ${empire.title}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = empire.primaryColor,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF151F2C), RoundedCornerShape(10.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SCORE", fontSize = 9.sp, color = Color(0xFF90A4AE))
                        Text("$score", fontSize = 16.sp, fontWeight = FontWeight.Black, color = AmberWarning)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("KILLS", fontSize = 9.sp, color = Color(0xFF90A4AE))
                        Text("$kills", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CREDITS", fontSize = 9.sp, color = Color(0xFF90A4AE))
                        Text("+$credits", fontSize = 15.sp, fontWeight = FontWeight.Black, color = MilitaryGold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "24H REWARDED CONTINUES LEFT: ${adContinueQuota.remaining}/${adContinueQuota.maxPer24Hours}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (adContinueQuota.canWatchAdToContinue) CyberCyan else ThreatRed
                )
                statusText?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // YES — WATCH AD TO RESTORE +5 MAIN HEARTS
                Button(
                    onClick = {
                        if (adContinueQuota.canWatchAdToContinue && onWatchAdContinue != null) {
                            onWatchAdContinue({}, { msg -> statusText = msg })
                        }
                    },
                    enabled = adContinueQuota.canWatchAdToContinue && onWatchAdContinue != null,
                    colors = ButtonDefaults.buttonColors(containerColor = HealthGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("defeat_continue_yes_button")
                ) {
                    Text(
                        text = if (adContinueQuota.canWatchAdToContinue) {
                            "YES — WATCH AD (+5 HEARTS)"
                        } else {
                            "DAILY 10/10 LIMIT REACHED (24H)"
                        },
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // NO — RETURN TO DASHBOARD
                Button(
                    onClick = onReturnToHangar,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("defeat_continue_no_button")
                ) {
                    Text("NO — RETURN TO DASHBOARD", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AmberWarning)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RESTART STAGE", color = AmberWarning, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MissionDebriefingDialog(
    isVictory: Boolean,
    score: Int,
    kills: Int,
    credits: Int,
    empire: EmpireType,
    stageNumber: Int = 1,
    nextStageEmpire: EmpireType? = null,
    onAutoDeployNextStage: ((EmpireType) -> Unit)? = null,
    onClaimSupplyBonus: ((onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit)? = null,
    onRestart: () -> Unit,
    onReturnToHangar: () -> Unit
) {
    var bonusClaimed by remember { mutableStateOf(false) }
    var bonusStatus by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(3) }
    var autoDeployCancelled by remember { mutableStateOf(false) }

    if (isVictory && nextStageEmpire != null && onAutoDeployNextStage != null && !autoDeployCancelled) {
        LaunchedEffect(nextStageEmpire) {
            while (countdown > 0) {
                delay(1000L)
                countdown -= 1
            }
            onAutoDeployNextStage(nextStageEmpire)
        }
    }

    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C131D),
            border = androidx.compose.foundation.BorderStroke(2.dp, if (isVictory) HealthGreen else ThreatRed),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (nextStageEmpire == null) "ALL 4 EMPIRES CONQUERED!" else "STAGE $stageNumber CLEARED!",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = HealthGreen
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${empire.title} HQ ANNIHILATED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = empire.primaryColor, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF151F2C), RoundedCornerShape(10.dp)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FINAL SCORE", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text("$score", fontSize = 18.sp, fontWeight = FontWeight.Black, color = AmberWarning)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HOSTILES", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text("$kills", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WAR CREDITS", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text("+$credits CR", fontSize = 16.sp, fontWeight = FontWeight.Black, color = MilitaryGold)
                    }
                }

                if (nextStageEmpire != null && onAutoDeployNextStage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (autoDeployCancelled) {
                            "NEXT STAGE UNLOCKED: ${nextStageEmpire.title}"
                        } else {
                            "AUTO-DEPLOYING STAGE ${stageNumber + 1} IN ${countdown}s..."
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            autoDeployCancelled = true
                            onAutoDeployNextStage(nextStageEmpire)
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("auto_deploy_next_stage_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = HealthGreen)
                    ) {
                        Text("DEPLOY STAGE ${stageNumber + 1}: ${nextStageEmpire.name}", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                }

                if (onClaimSupplyBonus != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (!bonusClaimed) {
                                autoDeployCancelled = true
                                onClaimSupplyBonus(
                                    { bonus ->
                                        bonusClaimed = true
                                        bonusStatus = "+$bonus CR CREDITED TO ACCOUNT"
                                    },
                                    { msg -> bonusStatus = msg }
                                )
                            }
                        },
                        enabled = !bonusClaimed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (bonusClaimed) Color(0xFF1B5E20) else Color(0xFF162436)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MilitaryGold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("debrief_rewarded_ad_button")
                    ) {
                        Text(
                            text = bonusStatus
                                ?: if (bonusClaimed) "SUPPLY BONUS CREDITED"
                                else "WATCH AD: CLAIM +${com.example.game.ads.AdMobManager.REWARDED_CREDITS_BONUS} CR",
                            color = MilitaryGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        autoDeployCancelled = true
                        onRestart()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("REPLAY STAGE", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        autoDeployCancelled = true
                        onReturnToHangar()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))
                ) {
                    Text("RETURN TO DASHBOARD", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
