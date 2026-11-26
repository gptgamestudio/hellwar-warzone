package com.example.ads

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobBannerBar
import com.example.game.ads.AdMobManager
import com.example.game.ads.ConsentGateway
import com.example.game.ads.DebugConsentConfig
import com.example.game.network.SafeLog
import com.example.game.ui.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdMobConsentComplianceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
        SafeLog.setSinkForTest(null)
    }

    @After
    fun tearDown() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
        SafeLog.setSinkForTest(null)
    }

    @Test
    fun buildConsentRequestParameters_appliesDebugGeographyOnlyInDebug() {
        val eeaDebugConfig = DebugConsentConfig(
            debugGeography = ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA,
            testDeviceHashedIds = listOf("TEST_HASH_DEVICE_001"),
            forceTesting = true
        )

        val debugParams = AdConsentManager.buildConsentRequestParameters(
            context = context,
            debugConfig = eeaDebugConfig,
            isDebug = true
        )
        assertNotNull(
            "Debug build must attach ConsentDebugSettings when debug overrides are provided",
            debugParams.consentDebugSettings
        )
        assertEquals(
            ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA,
            debugParams.consentDebugSettings!!.debugGeography
        )

        val releaseParams = AdConsentManager.buildConsentRequestParameters(
            context = context,
            debugConfig = eeaDebugConfig,
            isDebug = false
        )
        assertNull(
            "Release build must strip ConsentDebugSettings completely",
            releaseParams.consentDebugSettings
        )
    }

    @Test
    fun adRequestsAndSdkInit_areBlockedUntilConsentPermitsAds_thenLoadGoogleTestIds() {
        var sdkInitCount = 0
        val loadedAdUnits = mutableListOf<String>()
        var consentAllowed = false
        var privacyRequired = false
        var infoRequested = false
        var formPresented = false
        var capturedParams: ConsentRequestParameters? = null

        val fakeGateway = object : ConsentGateway {
            override fun canRequestAds(context: Context): Boolean = consentAllowed
            override fun isPrivacyOptionsRequired(context: Context): Boolean = privacyRequired
            override fun requestConsentInfoUpdate(
                activity: Activity,
                params: ConsentRequestParameters,
                onSuccess: () -> Unit,
                onFailure: (Int?, String) -> Unit
            ) {
                infoRequested = true
                capturedParams = params
                privacyRequired = true
                onSuccess()
            }

            override fun loadAndShowConsentFormIfRequired(
                activity: Activity,
                onDismissed: (Int?, String?) -> Unit
            ) {
                formPresented = true
                consentAllowed = true
                privacyRequired = true
                onDismissed(null, null)
            }

            override fun showPrivacyOptionsForm(
                activity: Activity,
                onDismissed: (Int?, String?) -> Unit
            ) {
                onDismissed(null, null)
            }
        }

        AdConsentManager.setGatewayForTest(fakeGateway)
        AdMobManager.setTestHooksForTest(
            sdkInitializer = { _, onDone ->
                sdkInitCount++
                onDone()
            },
            adRequestLoader = { adUnitId ->
                loadedAdUnits += adUnitId
            }
        )

        // Step 1: Before consent is gathered -> all init and ad loads must be strictly blocked
        AdMobManager.initialize(context)
        AdMobManager.ensureAdsLoaded(context)
        AdMobManager.loadInterstitial(context, resetRetry = true)
        AdMobManager.loadRewarded(context, resetRetry = true)
        var unavailableCalled = false
        AdMobManager.showRewardedAd(
            activity = composeRule.activity,
            onRewardEarned = {},
            onUnavailable = { unavailableCalled = true }
        )

        assertEquals("SDK must not initialize without UMP consent", 0, sdkInitCount)
        assertFalse(AdMobManager.isSdkInitialized.value)
        assertTrue("No ad requests may run before consent", loadedAdUnits.isEmpty())
        assertTrue("Rewarded ad must report unavailable while consent blocked", unavailableCalled)

        // Step 2: Gather consent with EEA debug geography
        var gatheredResult = false
        AdConsentManager.gatherConsent(
            activity = composeRule.activity,
            debugConfig = DebugConsentConfig(
                debugGeography = ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA,
                testDeviceHashedIds = listOf("EMU_TEST_HASH"),
                forceTesting = true
            )
        ) { allowed ->
            gatheredResult = allowed
            if (allowed) {
                AdMobManager.initialize(context)
            }
        }

        assertTrue(infoRequested)
        assertTrue(formPresented)
        assertTrue(gatheredResult)
        assertNotNull(capturedParams?.consentDebugSettings)
        assertTrue(AdConsentManager.canRequestAds.value)
        assertTrue(AdConsentManager.isPrivacyOptionsRequired.value)
        assertEquals(1, sdkInitCount)
        assertTrue(AdMobManager.isSdkInitialized.value)
        assertEquals(
            listOf(
                AdMobManager.TEST_INTERSTITIAL_AD_UNIT_ID,
                AdMobManager.TEST_REWARDED_AD_UNIT_ID
            ),
            loadedAdUnits
        )
        assertFalse(loadedAdUnits.contains(AdMobManager.PROD_INTERSTITIAL_AD_UNIT_ID))
        assertFalse(loadedAdUnits.contains(AdMobManager.PROD_REWARDED_AD_UNIT_ID))
    }

    @Test
    fun adMobBannerBar_doesNotInitializeOrLoadAdWhenConsentIsDenied() {
        var sdkInitCount = 0
        AdMobManager.setTestHooksForTest(
            sdkInitializer = { _, onDone ->
                sdkInitCount++
                onDone()
            }
        )
        AdConsentManager.setConsentStateForTest(
            canRequestAds = false,
            isPrivacyOptionsRequired = true,
            isConsentGatheringComplete = true
        )

        composeRule.setContent {
            MyApplicationTheme {
                AdMobBannerBar()
            }
        }

        composeRule.onNodeWithTag("admob_banner_bar").assertIsDisplayed()
        assertEquals("Banner must not initialize MobileAds without consent", 0, sdkInitCount)
        assertFalse(AdMobManager.isSdkInitialized.value)
    }

    @Test
    fun settingsScreen_showsAndReopensPrivacyOptionsFormWhenRequired() {
        var privacyFormOpened = 0
        var consentAllowed = false
        var sdkInitCount = 0

        val fakeGateway = object : ConsentGateway {
            override fun canRequestAds(context: Context): Boolean = consentAllowed
            override fun isPrivacyOptionsRequired(context: Context): Boolean = true
            override fun requestConsentInfoUpdate(
                activity: Activity,
                params: ConsentRequestParameters,
                onSuccess: () -> Unit,
                onFailure: (Int?, String) -> Unit
            ) = onSuccess()

            override fun loadAndShowConsentFormIfRequired(
                activity: Activity,
                onDismissed: (Int?, String?) -> Unit
            ) = onDismissed(null, null)

            override fun showPrivacyOptionsForm(
                activity: Activity,
                onDismissed: (Int?, String?) -> Unit
            ) {
                privacyFormOpened++
                consentAllowed = true
                onDismissed(null, null)
            }
        }

        AdConsentManager.setGatewayForTest(fakeGateway)
        AdConsentManager.setConsentStateForTest(
            canRequestAds = false,
            isPrivacyOptionsRequired = true,
            isConsentGatheringComplete = true
        )
        AdMobManager.setTestHooksForTest(
            sdkInitializer = { _, onDone ->
                sdkInitCount++
                onDone()
            },
            adRequestLoader = { _ -> }
        )

        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    pilotEmail = "pilot@example.com",
                    pilotUid = "uid_123",
                    onSignOut = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("AD CONSENT STATUS: PENDING / RESTRICTED")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_options_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, privacyFormOpened)
        assertTrue(AdConsentManager.canRequestAds.value)
        assertEquals(1, sdkInitCount)
        composeRule.onNodeWithText("AD CONSENT STATUS: PERMITTED")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
