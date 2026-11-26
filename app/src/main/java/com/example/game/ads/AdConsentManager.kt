package com.example.game.ads

import android.app.Activity
import android.content.Context
import com.example.BuildConfig
import com.example.game.network.SafeLog
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only configuration for verifying UMP EEA/US state consent flows on test devices.
 * Ignored completely in release builds (!BuildConfig.DEBUG).
 */
data class DebugConsentConfig(
    val debugGeography: Int = ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED,
    val testDeviceHashedIds: List<String> = emptyList(),
    val forceTesting: Boolean = false
)

/**
 * Abstraction over Google User Messaging Platform (UMP) SDK for deterministic unit/Robolectric testing.
 */
interface ConsentGateway {
    fun canRequestAds(context: Context): Boolean
    fun isPrivacyOptionsRequired(context: Context): Boolean
    fun requestConsentInfoUpdate(
        activity: Activity,
        params: ConsentRequestParameters,
        onSuccess: () -> Unit,
        onFailure: (errorCode: Int?, message: String) -> Unit
    )
    fun loadAndShowConsentFormIfRequired(
        activity: Activity,
        onDismissed: (errorCode: Int?, message: String?) -> Unit
    )
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (errorCode: Int?, message: String?) -> Unit
    )
}

internal object UmpConsentGateway : ConsentGateway {
    override fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    override fun isPrivacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun requestConsentInfoUpdate(
        activity: Activity,
        params: ConsentRequestParameters,
        onSuccess: () -> Unit,
        onFailure: (errorCode: Int?, message: String) -> Unit
    ) {
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            { onSuccess() },
            { requestError ->
                onFailure(requestError.errorCode, requestError.message)
            }
        )
    }

    override fun loadAndShowConsentFormIfRequired(
        activity: Activity,
        onDismissed: (errorCode: Int?, message: String?) -> Unit
    ) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
            onDismissed(formError?.errorCode, formError?.message)
        }
    }

    override fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (errorCode: Int?, message: String?) -> Unit
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            onDismissed(formError?.errorCode, formError?.message)
        }
    }
}

/**
 * Manages Google User Messaging Platform (UMP) consent before MobileAds initialization or ad requests.
 * No Banner, Interstitial, or Rewarded ad request is permitted until [canRequestAds] is true.
 */
object AdConsentManager {
    private const val TAG = "AdConsentManager"

    @Volatile
    private var gateway: ConsentGateway = UmpConsentGateway

    private val isGatheringInFlight = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _isPrivacyOptionsRequired = MutableStateFlow(false)
    val isPrivacyOptionsRequired: StateFlow<Boolean> = _isPrivacyOptionsRequired.asStateFlow()

    private val _isConsentGatheringComplete = MutableStateFlow(false)
    val isConsentGatheringComplete: StateFlow<Boolean> = _isConsentGatheringComplete.asStateFlow()

    private val _lastConsentError = MutableStateFlow<String?>(null)
    val lastConsentError: StateFlow<String?> = _lastConsentError.asStateFlow()

    /**
     * Builds UMP ConsentRequestParameters. Debug geography and hashed test device IDs are
     * attached strictly when [isDebug] (defaulting to BuildConfig.DEBUG) is true.
     */
    fun buildConsentRequestParameters(
        context: Context,
        debugConfig: DebugConsentConfig = DebugConsentConfig(),
        tagForUnderAgeOfConsent: Boolean = false,
        isDebug: Boolean = BuildConfig.DEBUG
    ): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(tagForUnderAgeOfConsent)

        if (isDebug) {
            val hasDebugOverrides =
                debugConfig.debugGeography != ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED ||
                    debugConfig.testDeviceHashedIds.isNotEmpty() ||
                    debugConfig.forceTesting
            if (hasDebugOverrides) {
                val debugBuilder = ConsentDebugSettings.Builder(context.applicationContext)
                    .setDebugGeography(debugConfig.debugGeography)
                    .setForceTesting(debugConfig.forceTesting)
                debugConfig.testDeviceHashedIds.forEach { deviceId ->
                    if (deviceId.isNotBlank()) {
                        debugBuilder.addTestDeviceHashedId(deviceId.trim())
                    }
                }
                builder.setConsentDebugSettings(debugBuilder.build())
            }
        }

        return builder.build()
    }

    /**
     * Requests updated consent info from UMP, presents a consent form if required, and invokes
     * [onConsentGathered] with the resulting `canRequestAds` decision.
     */
    fun gatherConsent(
        activity: Activity,
        debugConfig: DebugConsentConfig = DebugConsentConfig(),
        onConsentGathered: (canRequestAds: Boolean) -> Unit = {}
    ) {
        val appContext = activity.applicationContext

        // Synchronize previously persisted UMP consent decision immediately if already valid.
        val cachedCanRequest = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
        val cachedPrivacyRequired = runCatching { gateway.isPrivacyOptionsRequired(appContext) }.getOrDefault(false)
        _canRequestAds.value = cachedCanRequest
        _isPrivacyOptionsRequired.value = cachedPrivacyRequired
        if (cachedCanRequest) {
            onConsentGathered(true)
        }

        if (!isGatheringInFlight.compareAndSet(false, true)) {
            return
        }

        val params = buildConsentRequestParameters(appContext, debugConfig)
        try {
            gateway.requestConsentInfoUpdate(
                activity = activity,
                params = params,
                onSuccess = {
                    val privacyRequiredNow = runCatching { gateway.isPrivacyOptionsRequired(appContext) }.getOrDefault(false)
                    _isPrivacyOptionsRequired.value = privacyRequiredNow

                    if (activity.isFinishing || activity.isDestroyed) {
                        isGatheringInFlight.set(false)
                        val allowed = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
                        _canRequestAds.value = allowed
                        _isConsentGatheringComplete.value = true
                        onConsentGathered(allowed)
                        return@requestConsentInfoUpdate
                    }

                    gateway.loadAndShowConsentFormIfRequired(activity) { errorCode, message ->
                        isGatheringInFlight.set(false)
                        if (errorCode != null || !message.isNullOrBlank()) {
                            val safeMsg = SafeLog.redactText(message ?: "UMP form error code=$errorCode")
                            _lastConsentError.value = "code=$errorCode: $safeMsg"
                            SafeLog.w(TAG, "Consent form dismissed with error (code=$errorCode): $safeMsg")
                        } else {
                            _lastConsentError.value = null
                        }
                        val allowed = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
                        val privacyRequired = runCatching { gateway.isPrivacyOptionsRequired(appContext) }.getOrDefault(false)
                        _canRequestAds.value = allowed
                        _isPrivacyOptionsRequired.value = privacyRequired
                        _isConsentGatheringComplete.value = true
                        onConsentGathered(allowed)
                    }
                },
                onFailure = { errorCode, message ->
                    isGatheringInFlight.set(false)
                    val safeMsg = SafeLog.redactText(message)
                    _lastConsentError.value = "code=$errorCode: $safeMsg"
                    SafeLog.w(TAG, "Consent info update failed (code=$errorCode): $safeMsg")
                    val allowed = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
                    val privacyRequired = runCatching { gateway.isPrivacyOptionsRequired(appContext) }.getOrDefault(false)
                    _canRequestAds.value = allowed
                    _isPrivacyOptionsRequired.value = privacyRequired
                    _isConsentGatheringComplete.value = true
                    onConsentGathered(allowed)
                }
            )
        } catch (t: Throwable) {
            isGatheringInFlight.set(false)
            val summary = SafeLog.safeThrowableSummary(t)
            _lastConsentError.value = summary
            SafeLog.w(TAG, "Consent gathering threw ($summary)")
            val allowed = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
            _canRequestAds.value = allowed
            _isConsentGatheringComplete.value = true
            onConsentGathered(allowed)
        }
    }

    /**
     * Re-opens Google's UMP privacy options form from Settings when [isPrivacyOptionsRequired] is true.
     */
    fun showPrivacyOptionsForm(
        activity: Activity?,
        onComplete: (canRequestAds: Boolean) -> Unit = {}
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            onComplete(_canRequestAds.value)
            return
        }
        val appContext = activity.applicationContext
        try {
            gateway.showPrivacyOptionsForm(activity) { errorCode, message ->
                if (errorCode != null || !message.isNullOrBlank()) {
                    val safeMsg = SafeLog.redactText(message ?: "code=$errorCode")
                    _lastConsentError.value = "code=$errorCode: $safeMsg"
                    SafeLog.w(TAG, "Privacy options form dismissed with error (code=$errorCode): $safeMsg")
                } else {
                    _lastConsentError.value = null
                }
                val allowed = runCatching { gateway.canRequestAds(appContext) }.getOrDefault(false)
                val privacyRequired = runCatching { gateway.isPrivacyOptionsRequired(appContext) }.getOrDefault(false)
                _canRequestAds.value = allowed
                _isPrivacyOptionsRequired.value = privacyRequired
                onComplete(allowed)
            }
        } catch (t: Throwable) {
            SafeLog.w(TAG, "Privacy options form threw (${SafeLog.safeThrowableSummary(t)})")
            onComplete(_canRequestAds.value)
        }
    }

    internal fun setGatewayForTest(testGateway: ConsentGateway?) {
        gateway = testGateway ?: UmpConsentGateway
    }

    internal fun setConsentStateForTest(
        canRequestAds: Boolean,
        isPrivacyOptionsRequired: Boolean = false,
        isConsentGatheringComplete: Boolean = true
    ) {
        _canRequestAds.value = canRequestAds
        _isPrivacyOptionsRequired.value = isPrivacyOptionsRequired
        _isConsentGatheringComplete.value = isConsentGatheringComplete
    }

    internal fun resetForTest() {
        gateway = UmpConsentGateway
        isGatheringInFlight.set(false)
        _canRequestAds.value = false
        _isPrivacyOptionsRequired.value = false
        _isConsentGatheringComplete.value = false
        _lastConsentError.value = null
    }
}
