package com.example.game.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.game.engine.EndlessSyncStatus
import com.example.game.engine.GameEngine
import com.example.game.engine.GameHudState
import com.example.game.engine.GameMode
import com.example.game.model.EmpireType
import com.example.game.network.AdContinueQuota
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ThreatRed
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactVertical = maxHeight < 310.dp
        val joystickSize = if (compactVertical) 92.dp else 104.dp
        val overdriveButtonSize = if (compactVertical) 56.dp else 64.dp
        val missileButtonSize = if (compactVertical) 56.dp else 64.dp
        val radarSize = if (compactVertical) 56.dp else 64.dp

        // UPPER-LEFT COMPACT HUD: Hearts + Health Counter Only
        CompactHealthHud(
            hearts = hudState.hearts,
            maxHearts = hudState.maxHearts,
            hitsRemainingInHeart = hudState.hitsRemainingInHeart,
            hitsPerHeart = hudState.hitsPerHeart,
            compactVertical = compactVertical,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 12.dp)
        )

        // UPPER-RIGHT: Radar + Pause Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 14.dp)
        ) {
            TacticalRadarMinimap(
                engine = engine,
                sizeDp = radarSize,
                modifier = Modifier.testTag("tactical_radar_minimap")
            )

            IconButton(
                onClick = {
                    engine.clearMovementInputs()
                    showPauseDialog = true
                    onPauseClick()
                    onPauseStateChange(true)
                },
                modifier = Modifier
                    .size(if (compactVertical) 48.dp else 52.dp)
                    .background(Color(0xCC0D1B2A), RoundedCornerShape(14.dp))
                    .border(1.2.dp, Color(0xFF287D9C), RoundedCornerShape(14.dp))
                    .testTag("pause_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = "Pause Game",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // LOWER-LEFT: Single Compact Circular 360-Degree Virtual Joystick
        VirtualCircularJoystick(
            engine = engine,
            sizeDp = joystickSize,
            isEnabled = !showPauseDialog && !hudState.isGameOver && !hudState.isVictory,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 12.dp)
        )

        // LOWER-RIGHT: Only Two Circular Action Buttons (OVER above LOCKED)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp)
                .testTag("right_action_buttons")
        ) {
            OverdriveWeaponButton(
                hudState = hudState,
                sizeDp = overdriveButtonSize,
                onActivate = {
                    pulseHaptic()
                    engine.activateOverdriveBeam()
                }
            )

            DedicatedMissileButton(
                hudState = hudState,
                sizeDp = missileButtonSize,
                onLaunch = {
                    pulseHaptic()
                    engine.launchMissiles()
                }
            )
        }

        // Pause Modal Dialog
        if (showPauseDialog) {
            TacticalPauseDialog(
                gameMode = hudState.gameMode,
                currentWave = hudState.currentWave,
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

        // Game Over (5-Heart Exhausted -> Watch Ad Continue? YES/NO) or Victory (Campaign only)
        if (hudState.isGameOver) {
            DefeatContinueDialog(
                score = hudState.score,
                kills = hudState.kills,
                credits = hudState.creditsEarned,
                empire = hudState.activeEmpire,
                stageNumber = hudState.stageNumber,
                gameMode = hudState.gameMode,
                currentWave = hudState.currentWave,
                wavesCompleted = hudState.wavesCompleted,
                endlessBestWave = hudState.endlessBestWave,
                endlessBestScore = hudState.endlessBestScore,
                endlessSyncStatus = hudState.endlessSyncStatus,
                adContinueQuota = adContinueQuota,
                onWatchAdContinue = onWatchAdContinue,
                onRestart = onRestartSortie,
                onReturnToHangar = onReturnToMenu
            )
        } else if (hudState.isVictory && hudState.gameMode == GameMode.CAMPAIGN) {
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
private fun CompactHealthHud(
    hearts: Int,
    maxHearts: Int,
    hitsRemainingInHeart: Int,
    hitsPerHeart: Int,
    compactVertical: Boolean,
    modifier: Modifier = Modifier
) {
    val heartSize = if (compactVertical) 13.dp else 15.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .semantics {
                contentDescription = "Life: $hearts of $maxHearts hearts, $hitsRemainingInHeart of $hitsPerHeart"
            }
            .testTag("hud_health_hearts")
    ) {
        for (i in 1..maxHearts) {
            val filled = i <= hearts
            Icon(
                imageVector = if (filled) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = if (filled) Color(0xFFFF2442) else Color(0x66FFFFFF),
                modifier = Modifier.size(heartSize)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$hitsRemainingInHeart/$hitsPerHeart",
            color = Color.White,
            fontSize = if (compactVertical) 12.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xCC000000),
                    offset = Offset(1f, 1f),
                    blurRadius = 3f
                )
            ),
            modifier = Modifier.testTag("hud_health_counter")
        )
    }
}

@Composable
private fun TacticalRadarMinimap(
    engine: GameEngine,
    sizeDp: Dp = 66.dp,
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
            .size(sizeDp)
            .background(Color(0xD80A1522), CircleShape)
            .border(1.5.dp, Color(0xFF287D9C), CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // Subdued military tactical range rings
            drawCircle(Color(0x284E7D63), radius * 0.48f, center, style = Stroke(1f))
            drawCircle(Color(0x384E7D63), radius * 0.90f, center, style = Stroke(1.2f))

            // Crosshair guidelines (subtle)
            drawLine(Color(0x224E7D63), Offset(center.x, 3f), Offset(center.x, size.height - 3f), 1f)
            drawLine(Color(0x224E7D63), Offset(3f, center.y), Offset(size.width - 3f, center.y), 1f)

            // Cardinal North indicator at top
            drawCircle(Color(0xFFFFB300), 2.5f, Offset(center.x, 5f))

            // Rotating radar sweep line
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

            // Player Helicopter icon in center facing UP
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

            // Unlocked Headquarters on radar
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
                    drawRect(Color(0xFFFF9100), Offset(blipPos.x - 2f, blipPos.y - 2f), Size(4f, 4f))
                } else {
                    drawCircle(Color(0xFFFF1744), 2.8f, blipPos)
                }
            }

            // Active Boss entity
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

/**
 * Compact Lower-Left Circular 360-Degree Virtual Joystick
 */
@Composable
private fun VirtualCircularJoystick(
    engine: GameEngine,
    sizeDp: Dp = 104.dp,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var knobOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    DisposableEffect(engine) {
        onDispose {
            knobOffsetPx = Offset.Zero
            isDragging = false
            engine.clearMovementInputs()
        }
    }

    LaunchedEffect(isEnabled) {
        if (!isEnabled) {
            knobOffsetPx = Offset.Zero
            isDragging = false
            engine.setJoystickInput(0f, 0f)
        }
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .onSizeChanged { containerSize = it }
            .semantics { contentDescription = "Flight navigation joystick" }
            .testTag("virtual_joystick")
            .pointerInput(engine, isEnabled) {
                if (!isEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val activePointerId = down.id
                    isDragging = true

                    fun updateVector(pointerPos: Offset) {
                        val widthPx = containerSize.width.toFloat().coerceAtLeast(1f)
                        val heightPx = containerSize.height.toFloat().coerceAtLeast(1f)
                        val center = Offset(widthPx / 2f, heightPx / 2f)
                        val maxRadius = (minOf(widthPx, heightPx) * 0.34f).coerceAtLeast(1f)

                        val dx = pointerPos.x - center.x
                        val dy = pointerPos.y - center.y
                        val dist = sqrt(dx * dx + dy * dy)
                        val clampedDist = dist.coerceAtMost(maxRadius)
                        val scale = if (dist > 0.001f) clampedDist / dist else 0f

                        val clampedX = dx * scale
                        val clampedY = dy * scale
                        knobOffsetPx = Offset(clampedX, clampedY)

                        val normX = (clampedX / maxRadius).coerceIn(-1f, 1f)
                        // Screen Y grows downward; game +Y flight is upward
                        val normY = (-clampedY / maxRadius).coerceIn(-1f, 1f)
                        engine.setJoystickInput(normX, normY)
                    }

                    updateVector(down.position)
                    down.consume()

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == activePointerId }
                            if (change == null || !change.pressed) {
                                break
                            }
                            updateVector(change.position)
                            change.consume()
                        }
                    } finally {
                        knobOffsetPx = Offset.Zero
                        isDragging = false
                        engine.setJoystickInput(0f, 0f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = minOf(size.width, size.height) / 2f - 3f
            val innerRingRadius = outerRadius * 0.68f
            val knobRadius = outerRadius * 0.34f

            // Translucent dark base pad
            drawCircle(
                color = Color(0x780A1522),
                radius = outerRadius,
                center = center
            )

            // Outer glowing ring
            drawCircle(
                color = if (isDragging) CyberCyan else CyberCyan.copy(alpha = 0.62f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = if (isDragging) 3f else 2f)
            )

            // Inner reference guide ring
            drawCircle(
                color = CyberCyan.copy(alpha = 0.24f),
                radius = innerRingRadius,
                center = center,
                style = Stroke(width = 1.2f)
            )

            // Subtle 4-cardinal micro-ticks (visual direction markers inside ring)
            val tickOffset = outerRadius * 0.80f
            drawCircle(CyberCyan.copy(alpha = 0.48f), 2.5f, Offset(center.x, center.y - tickOffset))
            drawCircle(CyberCyan.copy(alpha = 0.48f), 2.5f, Offset(center.x, center.y + tickOffset))
            drawCircle(CyberCyan.copy(alpha = 0.48f), 2.5f, Offset(center.x - tickOffset, center.y))
            drawCircle(CyberCyan.copy(alpha = 0.48f), 2.5f, Offset(center.x + tickOffset, center.y))

            // Thumb knob (returns smoothly to center on release)
            val knobCenter = Offset(
                x = center.x + knobOffsetPx.x,
                y = center.y + knobOffsetPx.y
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7CE8FF),
                        if (isDragging) Color(0xFF00B8D4) else Color(0xFF0284C7),
                        Color(0xCC0C4A6E)
                    ),
                    center = knobCenter,
                    radius = knobRadius
                ),
                radius = knobRadius,
                center = knobCenter
            )
            drawCircle(
                color = Color.White.copy(alpha = if (isDragging) 0.9f else 0.6f),
                radius = knobRadius,
                center = knobCenter,
                style = Stroke(width = 2f)
            )
        }
    }
}

/**
 * Overdrive Plasma Beam / Bullet Overdrive Button (Blue Circular Button)
 */
@Composable
private fun OverdriveWeaponButton(
    hudState: GameHudState,
    sizeDp: Dp = 64.dp,
    onActivate: () -> Unit
) {
    val isReady = hudState.isOverdriveReady
    val fillBrush = if (isReady) {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF22CCFF), Color(0xFF0088F5))
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(Color(0xD90E7490), Color(0xD90B4F7A))
        )
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(fillBrush)
            .border(
                width = 2.dp,
                color = if (isReady) Color.White else Color.White.copy(alpha = 0.85f),
                shape = CircleShape
            )
            .semantics {
                contentDescription = if (isReady) {
                    "Overdrive ready"
                } else {
                    "Overdrive ${(hudState.overdriveChargePercent * 100).toInt()} percent"
                }
            }
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
                modifier = Modifier.fillMaxSize().padding(3.dp),
                color = CyberCyan,
                trackColor = Color(0x28FFFFFF),
                strokeWidth = 2.5.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "OVER",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * Dedicated Guided Missile / LOCKED Bomb-Missile Button (Red Circular Button)
 */
@Composable
private fun DedicatedMissileButton(
    hudState: GameHudState,
    sizeDp: Dp = 64.dp,
    onLaunch: () -> Unit
) {
    val isReady = hudState.isMissileReady
    val hasLock = hudState.lockedTargetName != null
    val fillBrush = when {
        hasLock && isReady -> Brush.verticalGradient(
            colors = listOf(Color(0xFFFF3D5E), Color(0xFFE0143C))
        )
        isReady -> Brush.verticalGradient(
            colors = listOf(Color(0xFFE11D48), Color(0xFFB91C1C))
        )
        else -> Brush.verticalGradient(
            colors = listOf(Color(0xD97F1D1D), Color(0xD9450A0A))
        )
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(fillBrush)
            .border(
                width = 2.dp,
                color = if (isReady) Color.White else Color.White.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .semantics {
                contentDescription = if (hasLock) "Missile locked" else "Missile attack"
            }
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
                modifier = Modifier.fillMaxSize().padding(3.dp),
                color = AmberWarning,
                trackColor = Color(0x28FFFFFF),
                strokeWidth = 2.5.dp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.RocketLaunch,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = if (hasLock) "LOCKED" else if (isReady) "${hudState.missileAmmo} MIS" else "ARMING",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun TacticalPauseDialog(
    gameMode: GameMode = GameMode.CAMPAIGN,
    currentWave: Int = 1,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit
) {
    Dialog(onDismissRequest = onResume) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F1722),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "ENDLESS WAVE $currentWave PAUSED" else "SORTIE PAUSED",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = CyberCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "CHECKPOINT & RECORDS SAVED LOCALLY" else "WAR-NET BATTLE LINK ON STANDBY",
                    fontSize = 11.sp,
                    color = Color(0xFF90A4AE),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RESUME BATTLE", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AmberWarning)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "RESTART ENDLESS" else "RESTART SORTIE",
                        color = AmberWarning,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onQuit, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
                    Text(
                        text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "SAVE & RETURN TO MENU" else "ABORT TO HANGAR",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
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
    gameMode: GameMode = GameMode.CAMPAIGN,
    currentWave: Int = 1,
    wavesCompleted: Int = 0,
    endlessBestWave: Int = 0,
    endlessBestScore: Int = 0,
    endlessSyncStatus: EndlessSyncStatus = EndlessSyncStatus.IDLE,
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "0/5 HEARTS — CONTINUE?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = ThreatRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                        "WAVE $currentWave ($wavesCompleted CLEARED) // ${empire.title}"
                    } else {
                        "STAGE $stageNumber // ${empire.title}"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = empire.primaryColor,
                    fontFamily = FontFamily.Monospace
                )

                if (gameMode == GameMode.ENDLESS_SURVIVAL) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BEST WAVE: $endlessBestWave • BEST SCORE: $endlessBestScore",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        fontFamily = FontFamily.Monospace
                    )
                }

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
                        Text(
                            text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "VERIFIED CR" else "CREDITS",
                            fontSize = 9.sp,
                            color = Color(0xFF90A4AE)
                        )
                        Text("+$credits", fontSize = 15.sp, fontWeight = FontWeight.Black, color = MilitaryGold)
                    }
                }

                if (gameMode == GameMode.ENDLESS_SURVIVAL && endlessSyncStatus == EndlessSyncStatus.PENDING_OFFLINE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "CLOUD SYNC PENDING (OFFLINE — CREDITS REQUIRE SERVER VALIDATION)",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AmberWarning,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("endless_pending_sync_notice")
                    )
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
                    Text(
                        text = if (gameMode == GameMode.ENDLESS_SURVIVAL) "RESTART ENDLESS" else "RESTART STAGE",
                        color = AmberWarning,
                        fontWeight = FontWeight.Bold
                    )
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
