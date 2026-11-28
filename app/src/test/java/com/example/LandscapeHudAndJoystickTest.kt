package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.UpperGameplayBannerDock
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.ThreatAlert
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import com.example.game.ui.HudOverlay
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w800dp-h400dp-land")
class LandscapeHudAndJoystickTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @After
    fun tearDown() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @Test
    fun manifestDeclaresPortraitLaunchAndPreservesAcrossOrientationConfigChanges() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val activityInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )

        assertEquals(
            "MainActivity must default to portrait orientation in manifest",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activityInfo.screenOrientation
        )
        assertTrue(
            "MainActivity must handle orientation configChanges cleanly",
            (activityInfo.configChanges and ActivityInfo.CONFIG_ORIENTATION) != 0
        )
        assertTrue(
            "MainActivity must handle screenSize configChanges",
            (activityInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE) != 0
        )
        assertTrue(
            "MainActivity must handle screenLayout configChanges",
            (activityInfo.configChanges and ActivityInfo.CONFIG_SCREEN_LAYOUT) != 0
        )
        assertTrue(
            "MainActivity must handle smallestScreenSize configChanges",
            (activityInfo.configChanges and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) != 0
        )
    }

    @Test
    fun hudReplacesDPadWithLowerLeftCircularJoystickAndTracks360DragAndCenterReturn() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.threatAlert = null
        engine.update(0.05f)

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = engine.hudState.value,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Old D-pad buttons must be completely removed
        composeRule.onNodeWithTag("cross_dpad_controller").assertDoesNotExist()
        composeRule.onNodeWithTag("control_up").assertDoesNotExist()
        composeRule.onNodeWithTag("control_down").assertDoesNotExist()
        composeRule.onNodeWithTag("control_left").assertDoesNotExist()
        composeRule.onNodeWithTag("control_right").assertDoesNotExist()

        // Compact circular virtual joystick must be visible in lower-left quadrant
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val joystickNode = composeRule.onNodeWithTag("virtual_joystick")
        joystickNode.assertIsDisplayed()
        val joystickBounds = joystickNode.getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val rootHeight = rootBounds.bottom - rootBounds.top
        assertTrue(
            "Joystick must be positioned on the left side",
            joystickBounds.right < rootWidth * 0.45f
        )
        assertTrue(
            "Joystick must be positioned in the lower area",
            joystickBounds.top > rootHeight * 0.45f
        )

        // Touch-and-drag up-right (360-degree diagonal) and verify analog input + center snap-back
        joystickNode.performTouchInput {
            down(center)
            moveBy(Offset(35f, -35f))
        }
        composeRule.waitForIdle()
        assertTrue("Dragging right should produce positive joystickInputX", engine.joystickInputX > 0.2f)
        assertTrue("Dragging up should produce positive joystickInputY", engine.joystickInputY > 0.2f)

        // Drag down-left
        joystickNode.performTouchInput {
            moveBy(Offset(-70f, 70f))
        }
        composeRule.waitForIdle()
        assertTrue("Dragging left should produce negative joystickInputX", engine.joystickInputX < -0.2f)
        assertTrue("Dragging down should produce negative joystickInputY", engine.joystickInputY < -0.2f)

        // Release returns joystick cleanly to center (0, 0)
        joystickNode.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(0f, engine.joystickInputX, 0.001f)
        assertEquals(0f, engine.joystickInputY, 0.001f)
    }

    @Test
    fun rightSideDisplaysOnlyUpperRightPauseAndLowerRightLockedCircularButton() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.player.overdriveCharge = 100f
        engine.player.lockedTargetId = "boss_or_target"
        engine.player.lockProgress = 1.0f
        engine.enemies.clear()
        engine.enemies.add(
            com.example.game.engine.EnemyTarget(
                id = "boss_or_target",
                empire = EmpireType.DRAGON,
                category = com.example.game.engine.EnemyCategory.ATTACK_HELICOPTER,
                pos = Vector3D(0f, 20f, 9.5f),
                velocity = Vector3D(0f, 0f, 0f),
                health = 80f,
                maxHealth = 80f,
                scoreValue = 450,
                unitName = "DRAGON WYVERN"
            )
        )
        engine.update(0.05f)

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = engine.hudState.value,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Removed controls (Radar, OVER, and old rectangular ammo panels) must not exist
        composeRule.onNodeWithTag("tactical_radar_minimap").assertDoesNotExist()
        composeRule.onNodeWithTag("overdrive_button").assertDoesNotExist()
        composeRule.onNodeWithText("OVER").assertDoesNotExist()
        composeRule.onNodeWithTag("auto_fire_toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("flare_button").assertDoesNotExist()
        composeRule.onNodeWithTag("rocket_barrage_button").assertDoesNotExist()
        composeRule.onNodeWithTag("missile_ammo_indicator").assertDoesNotExist()
        composeRule.onNodeWithTag("right_ammo_indicators").assertDoesNotExist()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val rootHeight = rootBounds.bottom - rootBounds.top

        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()
        val lockedBounds = composeRule.onNodeWithTag("missile_attack_button").assertIsDisplayed().getBoundsInRoot()

        // Pause at upper-right, LOCKED Bomb circular button at lower-right, aligned cleanly along right edge
        assertTrue(pauseBounds.left > rootWidth * 0.5f)
        assertTrue(pauseBounds.top < rootHeight * 0.35f)
        assertTrue(lockedBounds.left > rootWidth * 0.5f)
        assertTrue(lockedBounds.top > rootHeight * 0.45f)
        assertTrue(
            "Pause and LOCKED right-side buttons must align cleanly",
            abs((pauseBounds.right - lockedBounds.right).value) <= 12f
        )

        composeRule.onNodeWithText("LOCKED").assertIsDisplayed()

        // LOCKED button preserves guided missile launch callback
        val missilesBefore = engine.missiles.size
        composeRule.onNodeWithTag("missile_attack_button").performClick()
        composeRule.waitForIdle()
        assertTrue("Tapping LOCKED button must launch guided missile(s)", engine.missiles.size > missilesBefore)
    }

    @Test
    fun upperHudShowsOnlyCompactHeartsAndHealthCountWithPauseAndNoRadar() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.threatAlert = ThreatAlert("WARNING: MAGMA GEYSER SURGE DETECTED", Color(0xFFFF3D00), 1.8f)
        engine.update(0.05f)

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = engine.hudState.value,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {}
                )
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val rootHeight = rootBounds.bottom - rootBounds.top

        val heartsBounds = composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed().getBoundsInRoot()
        composeRule.onNodeWithTag("hud_health_counter").assertIsDisplayed()
        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()

        assertTrue(heartsBounds.left < rootWidth * 0.35f)
        assertTrue(heartsBounds.top < rootHeight * 0.25f)
        assertTrue(pauseBounds.left > rootWidth * 0.5f)

        // Radar, OVER, and all removed upper-left clutter & warning banners must not exist
        composeRule.onNodeWithTag("tactical_radar_minimap").assertDoesNotExist()
        composeRule.onNodeWithTag("overdrive_button").assertDoesNotExist()
        composeRule.onNodeWithTag("top_status_row").assertDoesNotExist()
        composeRule.onNodeWithTag("hud_secondary_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("hud_wave_score_text").assertDoesNotExist()
        composeRule.onNodeWithTag("campaign_objective_hud").assertDoesNotExist()
        composeRule.onNodeWithTag("endless_wave_hud").assertDoesNotExist()
        composeRule.onNodeWithTag("boss_health_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("hud_auto_hide_toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("endless_sync_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("threat_warning_banner").assertDoesNotExist()
    }

    @Test
    fun upperGameplayBannerDockRendersAboveBattlefieldWithoutOverlappingHeartsOrPause() {
        AdConsentManager.setConsentStateForTest(
            canRequestAds = true,
            isPrivacyOptionsRequired = false,
            isConsentGatheringComplete = true
        )
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.update(0.05f)

        composeRule.setContent {
            MyApplicationTheme {
                var isBannerVisible by remember { mutableStateOf(true) }
                Column(modifier = Modifier.fillMaxSize()) {
                    UpperGameplayBannerDock(
                        isBannerVisible = isBannerVisible,
                        onSetBannerVisible = { isBannerVisible = it }
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        HudOverlay(
                            hudState = engine.hudState.value,
                            engine = engine,
                            onPauseClick = {},
                            onReturnToMenu = {},
                            onRestartSortie = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val dockBounds = composeRule.onNodeWithTag("movable_banner_dock").assertIsDisplayed().getBoundsInRoot()
        val heartsBounds = composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed().getBoundsInRoot()
        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()

        assertTrue(
            "Upper banner dock must sit strictly above health hearts without overlap",
            dockBounds.bottom <= heartsBounds.top
        )
        assertTrue(
            "Upper banner dock must sit strictly above Pause button without overlap",
            dockBounds.bottom <= pauseBounds.top
        )

        composeRule.onNodeWithText("TACTICAL COMMS BANNER").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_dock_handle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FULL COMBAT MODE").assertIsDisplayed()
        composeRule.onNodeWithText("SHOW AD").assertIsDisplayed()
    }
}
