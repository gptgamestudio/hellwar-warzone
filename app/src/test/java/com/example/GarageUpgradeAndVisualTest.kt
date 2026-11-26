package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.engine.GameEngine
import com.example.game.engine.Renderer3D
import com.example.game.model.EmpireType
import com.example.game.model.HelicopterClass
import com.example.game.model.HelicopterSkin
import com.example.game.model.PlayerProfile
import com.example.game.network.GlobalCampaignState
import com.example.game.network.LeaderboardRecord
import com.example.game.ui.GarageScreen
import com.example.game.ui.LeaderboardScreen
import com.example.game.ui.UpgradeType
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GarageUpgradeAndVisualTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
        AdConsentManager.setConsentStateForTest(
            canRequestAds = false,
            isPrivacyOptionsRequired = false,
            isConsentGatheringComplete = true
        )
    }

    @After
    fun tearDown() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @Test
    fun garageScreen_opensUpgradesTabShowsLockedBannerAndJumpsToFleet() {
        val profile = PlayerProfile(
            credits = 20_000,
            selectedHelicopterClass = HelicopterClass.SCOUT
        )

        composeRule.setContent {
            MyApplicationTheme {
                GarageScreen(
                    profile = profile,
                    onProfileUpdated = {},
                    onBack = {},
                    initialTab = 1,
                    enablePreviewAnimation = false
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("upgrade_engine").performScrollTo().assertIsDisplayed()

        // Switch to Fleet tab, inspect locked HEAVY_GUNSHIP, then switch back to Upgrades
        composeRule.onNodeWithTag("tab_fleet").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("helo_card_${HelicopterClass.HEAVY_GUNSHIP.id}")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_upgrades").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("locked_upgrade_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AIRFRAME LOCKED — UNLOCK IN FLEET TAB")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("go_to_fleet_button")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // Back on Fleet tab: unlock button for Heavy Gunship should be visible
        composeRule.onNodeWithTag("unlock_btn_${HelicopterClass.HEAVY_GUNSHIP.id}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun garageScreen_invokesCloudHangarCallbacksForUnlockUpgradeAndSkin() {
        var unlockedClass: HelicopterClass? = null
        var upgradedPair: Pair<HelicopterClass, UpgradeType>? = null
        var equippedPair: Pair<HelicopterClass, HelicopterSkin>? = null

        val profile = PlayerProfile(
            credits = 30_000,
            selectedHelicopterClass = HelicopterClass.SCOUT
        )

        composeRule.setContent {
            MyApplicationTheme {
                GarageScreen(
                    profile = profile,
                    onProfileUpdated = {},
                    onBack = {},
                    quarantineNotice = "Legacy records preserved.",
                    onUnlockHelicopterAsync = { cls, onDone ->
                        unlockedClass = cls
                        onDone(true, "UNLOCKED")
                    },
                    onUpgradeComponentAsync = { cls, cat, onDone ->
                        upgradedPair = cls to cat
                        onDone(true, "UPGRADED")
                    },
                    onEquipLoadoutAsync = { cls, skin, onDone ->
                        equippedPair = cls to skin
                        onDone(true, "EQUIPPED")
                    },
                    enablePreviewAnimation = false
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("hangar_quarantine_notice").performScrollTo().assertIsDisplayed()

        // 1. Unlock Heavy Gunship via cloud callback
        composeRule.onNodeWithTag("unlock_btn_${HelicopterClass.HEAVY_GUNSHIP.id}")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(HelicopterClass.HEAVY_GUNSHIP, unlockedClass)

        // 2. Upgrade Engine on unlocked Scout via cloud callback
        composeRule.onNodeWithTag("tab_upgrades").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("upgrade_engine").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertNotNull(upgradedPair)
        assertEquals(UpgradeType.ENGINE, upgradedPair!!.second)

        // 3. Equip Desert Mirage skin via cloud callback
        composeRule.onNodeWithTag("tab_paints").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("skin_${HelicopterSkin.MILITARY_CAMO.id}")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertNotNull(equippedPair)
        assertEquals(HelicopterSkin.MILITARY_CAMO, equippedPair!!.second)
    }

    @Test
    fun leaderboardScreen_rendersGlobalTop5PilotsYouBadgeAndRefreshAction() {
        var refreshCount = 0
        val top5 = listOf(
            LeaderboardRecord(id = "u1", callsign = "ACE-ONE", highScore = 950_000, totalBarracksDestroyed = 200, hqClearedCount = 4),
            LeaderboardRecord(id = "u_me", callsign = "VIPER-7", highScore = 710_000, totalBarracksDestroyed = 152, hqClearedCount = 3),
            LeaderboardRecord(id = "u3", callsign = "GHOST-3", highScore = 540_000, totalBarracksDestroyed = 110, hqClearedCount = 2),
            LeaderboardRecord(id = "u4", callsign = "TITAN-4", highScore = 320_000, totalBarracksDestroyed = 64, hqClearedCount = 1),
            LeaderboardRecord(id = "u5", callsign = "ROOKIE-5", highScore = 120_000, totalBarracksDestroyed = 20, hqClearedCount = 0)
        )

        composeRule.setContent {
            MyApplicationTheme {
                LeaderboardScreen(
                    profile = PlayerProfile(callsign = "VIPER-7"),
                    campaignState = GlobalCampaignState(
                        authenticatedUserId = "u_me",
                        highScore = 710_000,
                        isServerConnected = true
                    ),
                    onBack = {},
                    onFetchTopLeaderboard = {
                        refreshCount++
                        Result.success(top5)
                    },
                    initialTopLeaderboard = top5
                )
            }
        }
        composeRule.waitForIdle()

        val listNode = composeRule.onNodeWithTag("leaderboard_scroll_list")
        listNode.performScrollToNode(androidx.compose.ui.test.hasText("GLOBAL TOP 5 PILOTS"))
        composeRule.onNodeWithText("GLOBAL TOP 5 PILOTS").assertIsDisplayed()

        listNode.performScrollToNode(androidx.compose.ui.test.hasTestTag("leaderboard_rank_row_1"))
        composeRule.onNodeWithTag("leaderboard_rank_row_1").assertIsDisplayed()
        composeRule.onNodeWithText("ACE-ONE").assertIsDisplayed()

        listNode.performScrollToNode(androidx.compose.ui.test.hasTestTag("leaderboard_rank_row_2"))
        composeRule.onNodeWithText("YOU").assertIsDisplayed()

        listNode.performScrollToNode(androidx.compose.ui.test.hasTestTag("leaderboard_rank_row_5"))
        composeRule.onNodeWithTag("leaderboard_rank_row_5").assertIsDisplayed()

        composeRule.onNodeWithTag("leaderboard_refresh_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, refreshCount)
    }

    @Test
    fun renderer3D_smokeRendersAllEmpiresHelicopterClassesAndSkins() {
        val renderer = Renderer3D()
        val nativeBitmap = android.graphics.Bitmap.createBitmap(
            320,
            240,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val imageBitmap = nativeBitmap.asImageBitmap()
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        val density = Density(1f)

        var renderCount = 0
        for (empire in EmpireType.entries) {
            for (heloClass in HelicopterClass.entries) {
                for (skin in HelicopterSkin.entries) {
                    val engine = GameEngine(
                        selectedEmpire = empire,
                        initialHeloClass = heloClass,
                        initialSkin = skin
                    )
                    drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(320f, 240f)) {
                        renderer.render(this, engine, size.width, size.height)
                    }
                    renderCount++
                }
            }
        }
        assertEquals(80, renderCount)
    }
}
