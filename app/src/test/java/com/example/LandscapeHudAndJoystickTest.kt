package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.ThreatAlert
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import com.example.game.ui.HudOverlay
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w800dp-h400dp-land")
class LandscapeHudAndJoystickTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun manifestDeclaresSensorLandscapeAndPreservesGameAcrossOrientationConfigChanges() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val activityInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )

        assertEquals(
            "MainActivity must launch in sensorLandscape",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            activityInfo.screenOrientation
        )
        assertTrue(
            "MainActivity must handle orientation configChanges without resetting gameplay",
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

        // New circular virtual joystick must be visible in lower-left quadrant
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
    fun rightSideDisplaysOnlyVerticalOverAndLockedCircularButtons() {
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

        // Old rectangular ammo panels must be completely removed
        composeRule.onNodeWithTag("auto_fire_toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("flare_button").assertDoesNotExist()
        composeRule.onNodeWithTag("rocket_barrage_button").assertDoesNotExist()
        composeRule.onNodeWithTag("missile_ammo_indicator").assertDoesNotExist()
        composeRule.onNodeWithTag("right_ammo_indicators").assertDoesNotExist()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val rootHeight = rootBounds.bottom - rootBounds.top

        val overBounds = composeRule.onNodeWithTag("overdrive_button").assertIsDisplayed().getBoundsInRoot()
        val lockedBounds = composeRule.onNodeWithTag("missile_attack_button").assertIsDisplayed().getBoundsInRoot()

        // Both circular buttons sit on the lower-right side, OVER above LOCKED
        assertTrue(overBounds.left > rootWidth * 0.5f)
        assertTrue(lockedBounds.left > rootWidth * 0.5f)
        assertTrue(overBounds.top > rootHeight * 0.35f)
        assertTrue(overBounds.top < lockedBounds.top)

        composeRule.onNodeWithText("OVER").assertIsDisplayed()
        composeRule.onNodeWithText("LOCKED").assertIsDisplayed()
    }

    @Test
    fun upperHudShowsOnlyCompactHeartsAndHealthCountWithRadarAndPause() {
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

        // Compact upper-left hearts + health counter + upper-right radar + pause
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val rootHeight = rootBounds.bottom - rootBounds.top

        val heartsBounds = composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed().getBoundsInRoot()
        composeRule.onNodeWithTag("hud_health_counter").assertIsDisplayed()
        val radarBounds = composeRule.onNodeWithTag("tactical_radar_minimap").assertIsDisplayed().getBoundsInRoot()
        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()

        assertTrue(heartsBounds.left < rootWidth * 0.35f)
        assertTrue(heartsBounds.top < rootHeight * 0.25f)
        assertTrue(radarBounds.left > rootWidth * 0.5f)
        assertTrue(pauseBounds.left > radarBounds.left)

        // All removed upper-left clutter & warning banners must not exist
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
}
