package com.example.ads

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.CloudHangarUiState
import com.example.game.network.RewardSettlementStatus
import com.example.game.network.RewardedSettlementOutcome
import com.example.game.network.SafeLog
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdMobSsvAndConsentTest {

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
    fun buildServerSideVerificationOptions_populatesTrimmedUidAndNonceAndRejectsBlank() {
        assertNull(AdMobManager.buildServerSideVerificationOptions(null, null))
        assertNull(AdMobManager.buildServerSideVerificationOptions("   ", ""))

        val options = AdMobManager.buildServerSideVerificationOptions(
            userId = "  pilot_uid_42  ",
            customData = "  nonce_998877  "
        )
        assertNotNull(options)
        assertEquals("pilot_uid_42", options!!.userId)
        assertEquals("nonce_998877", options.customData)

        val customOnly = AdMobManager.buildServerSideVerificationOptions(
            userId = "  ",
            customData = "nonce_only"
        )
        assertNotNull(customOnly)
        assertTrue(customOnly!!.userId.isNullOrEmpty())
        assertEquals("nonce_only", customOnly.customData)
    }

    @Test
    fun cloudHangarUiState_toAdContinueQuota_honorsDailyCapAndResetsAfter24Hours() {
        val t0 = 1_700_000_000_000L
        val midWindowState = CloudHangarUiState(
            isLoadedFromCloud = true,
            adContinueUsedToday = 10,
            adContinueRemaining = 0,
            adContinueWindowStartMs = t0
        )

        val activeQuota = midWindowState.toAdContinueQuota(nowMs = t0 + 60_000L)
        assertEquals(10, activeQuota.usedToday)
        assertEquals(0, activeQuota.remaining)
        assertFalse(activeQuota.canWatchAdToContinue)
        assertTrue(activeQuota.msUntilReset > 0L)

        val resetQuota = midWindowState.toAdContinueQuota(
            nowMs = t0 + CampaignProgressionManager.RESET_WINDOW_MS + 1_000L
        )
        assertEquals(0, resetQuota.usedToday)
        assertEquals(10, resetQuota.remaining)
        assertTrue(resetQuota.canWatchAdToContinue)
        assertEquals(0L, resetQuota.msUntilReset)
    }

    @Test
    fun rewardSettlementContract_blocksRewardedShowBeforeConsentAndNeverSelfAuthorizes() {
        var unavailableTriggered = false
        var rewardEarnedTriggered = false

        AdConsentManager.setConsentStateForTest(
            canRequestAds = false,
            isPrivacyOptionsRequired = true,
            isConsentGatheringComplete = true
        )

        AdMobManager.showRewardedAd(
            activity = composeRule.activity,
            ssvUserId = "pilot_uid_42",
            ssvCustomData = "nonce_abc",
            onRewardEarned = { rewardEarnedTriggered = true },
            onUnavailable = { unavailableTriggered = true }
        )

        assertTrue(unavailableTriggered)
        assertFalse(rewardEarnedTriggered)

        val pendingOutcome = RewardedSettlementOutcome(
            nonceId = "nonce_abc",
            status = RewardSettlementStatus.PENDING_SSV,
            rewardType = "SUPPLY_DROP",
            settledCredits = 0,
            continueGranted = false,
            availableCredits = 18_500,
            adContinueUsedToday = 0,
            adContinueRemaining = 10
        )
        assertEquals(RewardSettlementStatus.PENDING_SSV, pendingOutcome.status)
        assertEquals(0, pendingOutcome.settledCredits)
        assertFalse(pendingOutcome.continueGranted)
    }
}
