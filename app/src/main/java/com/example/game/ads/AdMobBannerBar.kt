package com.example.game.ads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun AdMobBannerBar(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.BANNER_AD_UNIT_ID
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var adViewRef by remember { mutableStateOf<AdView?>(null) }

    DisposableEffect(lifecycleOwner, adViewRef) {
        val view = adViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resume()
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
            .background(Color(0xFF090D14))
            .border(width = 1.dp, color = Color(0xFF1E293B))
            .padding(vertical = 4.dp)
            .heightIn(min = 52.dp)
            .testTag("admob_banner_bar"),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                AdMobManager.initialize(ctx)
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    this.adUnitId = adUnitId
                    adListener = object : AdListener() {}
                    try {
                        loadAd(AdMobManager.buildAdRequest())
                    } catch (_: Throwable) {
                    }
                    adViewRef = this
                }
            },
            onRelease = { view ->
                view.destroy()
            }
        )
    }
}
