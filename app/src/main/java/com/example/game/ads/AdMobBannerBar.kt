package com.example.game.ads

import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

@Composable
fun AdMobBannerBar(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.BANNER_AD_UNIT_ID
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var adViewRef by remember { mutableStateOf<AdView?>(null) }
    var needsReload by remember { mutableStateOf(false) }
    var lastRetryAtMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycleOwner, adViewRef) {
        val view = adViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view?.resume()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (view != null && needsReload && now - lastRetryAtMs >= 15_000L) {
                        lastRetryAtMs = now
                        needsReload = false
                        try {
                            view.loadAd(AdMobManager.buildAdRequest())
                        } catch (_: Throwable) {
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.destroy()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(Color(0xFF090D14))
            .border(width = 1.dp, color = Color(0xFF1E293B))
            .padding(vertical = 2.dp)
            .testTag("admob_banner_bar"),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.wrapContentSize(),
            factory = { ctx ->
                AdMobManager.initialize(ctx)
                AdView(ctx).apply {
                    // Smallest official standard AdMob banner (320x50)
                    setAdSize(AdSize.BANNER)
                    this.adUnitId = adUnitId
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            needsReload = false
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            needsReload = true
                        }
                    }
                    try {
                        loadAd(AdMobManager.buildAdRequest())
                    } catch (_: Throwable) {
                        needsReload = true
                    }
                    adViewRef = this
                }
            }
        )
    }
}

/**
 * Compact, movable up-and-down AdMob Banner dock for active combat gameplay.
 * - Uses the smallest official AdMob banner size (AdSize.BANNER, 320x50).
 * - Anchored cleanly at the very bottom BELOW the weighted Game Zone so it never overlaps controls.
 * - Dragging or tapping moves the banner smoothly down out of the way (pausing & hiding AdView)
 *   or lifts the Game Zone higher for comfortable thumb clearance.
 */
@Composable
fun MovableBottomBannerDock(
    isBannerVisible: Boolean,
    extraZoneLiftDp: Float,
    onSetBannerVisible: (Boolean) -> Unit,
    onExtraZoneLiftChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.BANNER_AD_UNIT_ID
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var adViewRef by remember { mutableStateOf<AdView?>(null) }
    var needsReload by remember { mutableStateOf(false) }
    var lastRetryAtMs by remember { mutableLongStateOf(0L) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }

    val clampedLift = extraZoneLiftDp.coerceIn(0f, 56f)
    val animatedBannerHeight by animateDpAsState(
        targetValue = if (isBannerVisible) 52.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "banner_dock_height"
    )
    val animatedLiftHeight by animateDpAsState(
        targetValue = if (isBannerVisible) clampedLift.dp else 0.dp,
        animationSpec = tween(durationMillis = 120),
        label = "game_zone_lift"
    )

    // Keep AdView visibility and pause/resume strictly synced so hidden mode never records obscured impressions
    LaunchedEffect(isBannerVisible, adViewRef) {
        val view = adViewRef ?: return@LaunchedEffect
        if (isBannerVisible) {
            view.visibility = View.VISIBLE
            view.resume()
            val now = android.os.SystemClock.elapsedRealtime()
            if (needsReload && now - lastRetryAtMs >= 10_000L) {
                lastRetryAtMs = now
                needsReload = false
                try {
                    view.loadAd(AdMobManager.buildAdRequest())
                } catch (_: Throwable) {
                }
            }
        } else {
            view.pause()
            view.visibility = View.GONE
        }
    }

    DisposableEffect(lifecycleOwner, adViewRef) {
        val view = adViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (isBannerVisible) {
                        view?.resume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.destroy()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF090D14))
            .testTag("movable_banner_dock")
    ) {
        // Adjustable Game-Zone Lift Cushion (when dragged upward while banner is docked)
        if (animatedLiftHeight > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedLiftHeight)
                    .background(Color(0xFF0B111A))
            )
        }

        // Interactive Up/Down Drag & Tap Handle Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color(0xFF0F1722))
                .border(width = 1.dp, color = Color(0xFF233247))
                .pointerInput(isBannerVisible, clampedLift) {
                    val thresholdPx = with(density) { 18.dp.toPx() }
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumPx = 0f },
                        onDragEnd = { dragAccumPx = 0f },
                        onDragCancel = { dragAccumPx = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            dragAccumPx += dragAmount
                            if (dragAmount < 0f) {
                                // Dragging UP: bring banner up or lift Game Zone upward above banner
                                if (!isBannerVisible && dragAccumPx <= -thresholdPx) {
                                    onSetBannerVisible(true)
                                    dragAccumPx = 0f
                                } else if (isBannerVisible) {
                                    val deltaDp = with(density) { (-dragAmount).toDp().value }
                                    onExtraZoneLiftChange((clampedLift + deltaDp).coerceIn(0f, 56f))
                                }
                            } else if (dragAmount > 0f) {
                                // Dragging DOWN: lower Game Zone lift first, then tuck banner ad down out of the way
                                if (clampedLift > 0f) {
                                    val deltaDp = with(density) { dragAmount.toDp().value }
                                    onExtraZoneLiftChange((clampedLift - deltaDp).coerceIn(0f, 56f))
                                    dragAccumPx = 0f
                                } else if (isBannerVisible && dragAccumPx >= thresholdPx) {
                                    onSetBannerVisible(false)
                                    dragAccumPx = 0f
                                }
                            }
                        }
                    )
                }
                .clickable {
                    if (isBannerVisible) {
                        onExtraZoneLiftChange(0f)
                        onSetBannerVisible(false)
                    } else {
                        onSetBannerVisible(true)
                    }
                }
                .padding(horizontal = 12.dp)
                .testTag("banner_dock_handle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Move Game Zone",
                    tint = CyberCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBannerVisible) "GAME ZONE ▲▼" else "FULL COMBAT MODE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isBannerVisible) CyberCyan else Color(0xFF94A3B8)
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF172334))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isBannerVisible) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (isBannerVisible) "Drop Banner Ad" else "Show Banner Ad",
                    tint = AmberWarning,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (isBannerVisible) "HIDE AD" else "SHOW AD",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = AmberWarning
                )
            }
        }

        // Smallest AdMob Banner Slot (AdSize.BANNER = 320x50) anchored at very bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedBannerHeight)
                .clipToBounds()
                .background(Color(0xFF090D14))
                .testTag("gameplay_admob_banner_slot"),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.wrapContentSize(),
                factory = { ctx ->
                    AdMobManager.initialize(ctx)
                    AdView(ctx).apply {
                        setAdSize(AdSize.BANNER)
                        this.adUnitId = adUnitId
                        visibility = if (isBannerVisible) View.VISIBLE else View.GONE
                        adListener = object : AdListener() {
                            override fun onAdLoaded() {
                                needsReload = false
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                needsReload = true
                            }
                        }
                        try {
                            loadAd(AdMobManager.buildAdRequest())
                        } catch (_: Throwable) {
                            needsReload = true
                        }
                        adViewRef = this
                    }
                }
            )
        }
    }
}
