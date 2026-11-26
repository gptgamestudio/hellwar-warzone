package com.example.game.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.BuildConfig
import com.example.game.network.SafeLog
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object AdMobManager {
    private const val TAG = "AdMobManager"

    // Google Official Sample Test IDs (used exclusively in debug builds)
    const val TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // Production AdMob IDs configured for hellwar-warzone (com.hellwar.game) — used exclusively in release builds
    const val PROD_ADMOB_APP_ID = "ca-app-pub-9786472984542330~8764530303"
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-9786472984542330/7032646237"
    const val PROD_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-9786472984542330/7625553507"
    const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-9786472984542330/9371034967"
    const val APP_ADS_TXT_RECORD = "google.com, pub-9786472984542330, DIRECT, f08c47fec0942fa0"

    // Build-variant resolved IDs from BuildConfig (test IDs in debug, production IDs in release)
    val ADMOB_APP_ID: String get() = BuildConfig.ADMOB_APP_ID
    val BANNER_AD_UNIT_ID: String get() = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
    val INTERSTITIAL_AD_UNIT_ID: String get() = BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID
    val REWARDED_AD_UNIT_ID: String get() = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID

    const val REWARDED_CREDITS_BONUS = 1000
    private const val INTERSTITIAL_COOLDOWN_MS = 60_000L
    private const val MAX_RETRY_ATTEMPTS = 4
    private const val BASE_RETRY_DELAY_MS = 4_000L

    private val initialized = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var sdkInitializerForTest: ((Context, () -> Unit) -> Unit)? = null

    @Volatile
    private var adRequestLoaderOverrideForTest: ((String) -> Unit)? = null

    @Volatile
    private var interstitialAd: InterstitialAd? = null
    private val isInterstitialLoading = AtomicBoolean(false)
    @Volatile
    private var interstitialRetryAttempt: Int = 0

    @Volatile
    private var rewardedAd: RewardedAd? = null
    private val isRewardedLoading = AtomicBoolean(false)
    @Volatile
    private var rewardedRetryAttempt: Int = 0

    @Volatile
    private var lastInterstitialShownMs: Long = 0L

    private val _isSdkInitialized = MutableStateFlow(false)
    val isSdkInitialized: StateFlow<Boolean> = _isSdkInitialized.asStateFlow()

    private val _isInterstitialReady = MutableStateFlow(false)
    val isInterstitialReady: StateFlow<Boolean> = _isInterstitialReady.asStateFlow()

    private val _isRewardedReady = MutableStateFlow(false)
    val isRewardedReady: StateFlow<Boolean> = _isRewardedReady.asStateFlow()

    private val _isRewardedFetching = MutableStateFlow(false)
    val isRewardedFetching: StateFlow<Boolean> = _isRewardedFetching.asStateFlow()

    private val _isFullScreenAdShowing = MutableStateFlow(false)
    val isFullScreenAdShowing: StateFlow<Boolean> = _isFullScreenAdShowing.asStateFlow()

    private val _lastRewardedError = MutableStateFlow<String?>(null)
    val lastRewardedError: StateFlow<String?> = _lastRewardedError.asStateFlow()

    fun initialize(context: Context, testDeviceIds: List<String> = emptyList()) {
        if (!AdConsentManager.canRequestAds.value) {
            SafeLog.d(TAG, "MobileAds initialization deferred until user ad consent permits requests")
            return
        }
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        try {
            if (BuildConfig.DEBUG) {
                val mergedTestIds = (listOf(AdRequest.DEVICE_ID_EMULATOR) + testDeviceIds)
                    .filter { it.isNotBlank() }
                    .distinct()
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(mergedTestIds)
                        .build()
                )
            }

            val testInit = sdkInitializerForTest
            if (testInit != null) {
                testInit(appContext) {
                    _isSdkInitialized.value = true
                    loadInterstitial(appContext, resetRetry = true)
                    loadRewarded(appContext, resetRetry = true)
                }
            } else {
                MobileAds.initialize(appContext) {
                    _isSdkInitialized.value = true
                    mainHandler.post {
                        loadInterstitial(appContext, resetRetry = true)
                        loadRewarded(appContext, resetRetry = true)
                    }
                }
            }
        } catch (t: Throwable) {
            initialized.set(false)
            SafeLog.w(TAG, "MobileAds SDK initialization skipped or unavailable (${SafeLog.safeThrowableSummary(t)})")
        }
    }

    /**
     * Re-triggers ad preloading after network recovery, consent approval, or UI resume.
     */
    fun ensureAdsLoaded(context: Context) {
        if (!AdConsentManager.canRequestAds.value) {
            SafeLog.d(TAG, "Ad preload blocked: consent not yet granted")
            return
        }
        val appContext = context.applicationContext
        if (!initialized.get()) {
            initialize(appContext)
            return
        }
        if (interstitialAd == null && !isInterstitialLoading.get()) {
            loadInterstitial(appContext, resetRetry = true)
        }
        if (rewardedAd == null && !isRewardedLoading.get()) {
            loadRewarded(appContext, resetRetry = true)
        }
    }

    fun buildAdRequest(): AdRequest = AdRequest.Builder().build()

    fun loadInterstitial(context: Context, resetRetry: Boolean = false) {
        if (!AdConsentManager.canRequestAds.value) return
        if (resetRetry) interstitialRetryAttempt = 0
        if (interstitialAd != null || !isInterstitialLoading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val testLoader = adRequestLoaderOverrideForTest
        if (testLoader != null) {
            testLoader(INTERSTITIAL_AD_UNIT_ID)
            isInterstitialLoading.set(false)
            return
        }
        mainHandler.post {
            if (!AdConsentManager.canRequestAds.value) {
                isInterstitialLoading.set(false)
                return@post
            }
            try {
                InterstitialAd.load(
                    appContext,
                    INTERSTITIAL_AD_UNIT_ID,
                    buildAdRequest(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            interstitialAd = ad
                            interstitialRetryAttempt = 0
                            isInterstitialLoading.set(false)
                            _isInterstitialReady.value = true
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            interstitialAd = null
                            isInterstitialLoading.set(false)
                            _isInterstitialReady.value = false
                            SafeLog.w(TAG, "Interstitial failed to load (code=${error.code}): ${error.message}")
                            scheduleInterstitialRetry(appContext)
                        }
                    }
                )
            } catch (t: Throwable) {
                isInterstitialLoading.set(false)
                _isInterstitialReady.value = false
                SafeLog.w(TAG, "Interstitial load threw (${SafeLog.safeThrowableSummary(t)})")
            }
        }
    }

    private fun scheduleInterstitialRetry(appContext: Context) {
        if (!AdConsentManager.canRequestAds.value) return
        val attempt = interstitialRetryAttempt
        if (attempt >= MAX_RETRY_ATTEMPTS) return
        interstitialRetryAttempt = attempt + 1
        val delayMs = BASE_RETRY_DELAY_MS * (1L shl attempt)
        mainHandler.postDelayed({
            if (interstitialAd == null && AdConsentManager.canRequestAds.value) {
                loadInterstitial(appContext, resetRetry = false)
            }
        }, delayMs)
    }

    fun showInterstitialIfReady(
        activity: Activity?,
        onContinue: () -> Unit
    ) {
        if (!AdConsentManager.canRequestAds.value) {
            onContinue()
            return
        }
        val currentAd = interstitialAd
        val now = SystemClock.elapsedRealtime()
        val cooldownPassed = lastInterstitialShownMs == 0L || (now - lastInterstitialShownMs) >= INTERSTITIAL_COOLDOWN_MS

        if (activity == null || activity.isFinishing || activity.isDestroyed || currentAd == null || !cooldownPassed || _isFullScreenAdShowing.value) {
            if (activity != null && currentAd == null) {
                loadInterstitial(activity.applicationContext, resetRetry = true)
            }
            onContinue()
            return
        }

        val appContext = activity.applicationContext
        currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                _isFullScreenAdShowing.value = true
                lastInterstitialShownMs = SystemClock.elapsedRealtime()
            }

            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                _isInterstitialReady.value = false
                _isFullScreenAdShowing.value = false
                loadInterstitial(appContext, resetRetry = true)
                onContinue()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitialAd = null
                _isInterstitialReady.value = false
                _isFullScreenAdShowing.value = false
                loadInterstitial(appContext, resetRetry = true)
                onContinue()
            }
        }

        try {
            _isFullScreenAdShowing.value = true
            currentAd.show(activity)
        } catch (t: Throwable) {
            interstitialAd = null
            _isInterstitialReady.value = false
            _isFullScreenAdShowing.value = false
            loadInterstitial(appContext, resetRetry = true)
            onContinue()
        }
    }

    fun loadRewarded(context: Context, resetRetry: Boolean = false) {
        if (!AdConsentManager.canRequestAds.value) return
        if (resetRetry) rewardedRetryAttempt = 0
        if (rewardedAd != null || !isRewardedLoading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val testLoader = adRequestLoaderOverrideForTest
        if (testLoader != null) {
            testLoader(REWARDED_AD_UNIT_ID)
            isRewardedLoading.set(false)
            return
        }
        _isRewardedFetching.value = true
        _lastRewardedError.value = null
        mainHandler.post {
            if (!AdConsentManager.canRequestAds.value) {
                isRewardedLoading.set(false)
                _isRewardedFetching.value = false
                return@post
            }
            try {
                RewardedAd.load(
                    appContext,
                    REWARDED_AD_UNIT_ID,
                    buildAdRequest(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            rewardedAd = ad
                            rewardedRetryAttempt = 0
                            isRewardedLoading.set(false)
                            _isRewardedFetching.value = false
                            _isRewardedReady.value = true
                            _lastRewardedError.value = null
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            rewardedAd = null
                            isRewardedLoading.set(false)
                            _isRewardedFetching.value = false
                            _isRewardedReady.value = false
                            _lastRewardedError.value = SafeLog.redactText(error.message)
                            SafeLog.w(TAG, "Rewarded failed to load (code=${error.code}): ${error.message}")
                            scheduleRewardedRetry(appContext)
                        }
                    }
                )
            } catch (t: Throwable) {
                isRewardedLoading.set(false)
                _isRewardedFetching.value = false
                _isRewardedReady.value = false
                _lastRewardedError.value = SafeLog.safeThrowableSummary(t)
                SafeLog.w(TAG, "Rewarded load threw (${SafeLog.safeThrowableSummary(t)})")
            }
        }
    }

    private fun scheduleRewardedRetry(appContext: Context) {
        if (!AdConsentManager.canRequestAds.value) return
        val attempt = rewardedRetryAttempt
        if (attempt >= MAX_RETRY_ATTEMPTS) return
        rewardedRetryAttempt = attempt + 1
        val delayMs = BASE_RETRY_DELAY_MS * (1L shl attempt)
        mainHandler.postDelayed({
            if (rewardedAd == null && AdConsentManager.canRequestAds.value) {
                loadRewarded(appContext, resetRetry = false)
            }
        }, delayMs)
    }

    fun buildServerSideVerificationOptions(
        userId: String? = null,
        customData: String? = null
    ): ServerSideVerificationOptions? {
        val cleanUid = userId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanCustom = customData?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUid == null && cleanCustom == null) return null
        return ServerSideVerificationOptions.Builder().apply {
            if (cleanUid != null) setUserId(cleanUid)
            if (cleanCustom != null) setCustomData(cleanCustom)
        }.build()
    }

    fun showRewardedAd(
        activity: Activity?,
        ssvUserId: String? = null,
        ssvCustomData: String? = null,
        onRewardEarned: (Int) -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onUnavailable: () -> Unit = {}
    ) {
        if (!AdConsentManager.canRequestAds.value) {
            onUnavailable()
            return
        }
        val currentAd = rewardedAd
        if (activity == null || activity.isFinishing || activity.isDestroyed || currentAd == null || _isFullScreenAdShowing.value) {
            if (activity != null && currentAd == null) {
                loadRewarded(activity.applicationContext, resetRetry = true)
            }
            onUnavailable()
            return
        }

        val appContext = activity.applicationContext
        var rewardGranted = false

        buildServerSideVerificationOptions(ssvUserId, ssvCustomData)?.let { ssvOptions ->
            currentAd.setServerSideVerificationOptions(ssvOptions)
        }

        currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                _isFullScreenAdShowing.value = true
            }

            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                _isRewardedReady.value = false
                _isFullScreenAdShowing.value = false
                loadRewarded(appContext, resetRetry = true)
                if (!rewardGranted) {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                _isRewardedReady.value = false
                _isFullScreenAdShowing.value = false
                loadRewarded(appContext, resetRetry = true)
                onUnavailable()
            }
        }

        try {
            _isFullScreenAdShowing.value = true
            currentAd.show(activity) { _ ->
                rewardGranted = true
                onRewardEarned(REWARDED_CREDITS_BONUS)
            }
        } catch (t: Throwable) {
            rewardedAd = null
            _isRewardedReady.value = false
            _isFullScreenAdShowing.value = false
            loadRewarded(appContext, resetRetry = true)
            onUnavailable()
        }
    }

    internal fun setTestHooksForTest(
        sdkInitializer: ((Context, () -> Unit) -> Unit)? = null,
        adRequestLoader: ((String) -> Unit)? = null
    ) {
        sdkInitializerForTest = sdkInitializer
        adRequestLoaderOverrideForTest = adRequestLoader
    }

    internal fun resetForTest() {
        sdkInitializerForTest = null
        adRequestLoaderOverrideForTest = null
        initialized.set(false)
        interstitialAd = null
        rewardedAd = null
        isInterstitialLoading.set(false)
        isRewardedLoading.set(false)
        interstitialRetryAttempt = 0
        rewardedRetryAttempt = 0
        lastInterstitialShownMs = 0L
        _isSdkInitialized.value = false
        _isInterstitialReady.value = false
        _isRewardedReady.value = false
        _isRewardedFetching.value = false
        _isFullScreenAdShowing.value = false
        _lastRewardedError.value = null
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
