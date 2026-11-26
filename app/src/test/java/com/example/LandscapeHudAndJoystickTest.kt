package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertFalse
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
    fun rightSideDisplaysFourAmmoIndicatorsPlusOverAndLockedButtonsVertically() {
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

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left

        val autoBounds = composeRule.onNodeWithTag("auto_fire_toggle").assertIsDisplayed().getBoundsInRoot()
        val flareBounds = composeRule.onNodeWithTag("flare_button").assertIsDisplayed().getBoundsInRoot()
        val rocketBounds = composeRule.onNodeWithTag("rocket_barrage_button").assertIsDisplayed().getBoundsInRoot()
        val missileAmmoBounds = composeRule.onNodeWithTag("missile_ammo_indicator").assertIsDisplayed().getBoundsInRoot()
        val overBounds = composeRule.onNodeWithTag("overdrive_button").assertIsDisplayed().getBoundsInRoot()
        val lockedBounds = composeRule.onNodeWithTag("missile_attack_button").assertIsDisplayed().getBoundsInRoot()

        // All 4 ammo indicators and OVER/LOCKED buttons sit on the RIGHT side
        assertTrue(autoBounds.left > rootWidth * 0.5f)
        assertTrue(flareBounds.left > rootWidth * 0.5f)
        assertTrue(rocketBounds.left > rootWidth * 0.5f)
        assertTrue(missileAmmoBounds.left > rootWidth * 0.5f)
        assertTrue(overBounds.left > rootWidth * 0.5f)
        assertTrue(lockedBounds.left > rootWidth * 0.5f)

        // Vertically organized order
        assertTrue(autoBounds.top < flareBounds.top)
        assertTrue(flareBounds.top < rocketBounds.top)
        assertTrue(rocketBounds.top < missileAmmoBounds.top)
        assertTrue(overBounds.top < lockedBounds.top)

        composeRule.onNodeWithText("OVER").assertIsDisplayed()
        composeRule.onNodeWithText("LOCKED").assertIsDisplayed()

        // Interactive verification: toggle auto-fire & launch rockets
        val initialAutoFire = engine.player.isAutoFiring
        composeRule.onNodeWithTag("auto_fire_toggle").performClick()
        assertFalse(engine.player.isAutoFiring == initialAutoFire)

        val initialRocketAmmo = engine.player.rocketAmmo
        composeRule.onNodeWithTag("rocket_barrage_button").performClick()
        assertTrue("MRL launch must consume rocket ammo", engine.player.rocketAmmo < initialRocketAmmo)
    }

    @Test
    fun upperHudIsCompactCollapsibleAndShowsMagmaWarningOnlyWhenActive() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.threatAlert = null
        engine.update(0.05f)

        val stateWithThreat = androidx.compose.runtime.mutableStateOf(engine.hudState.value)

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = stateWithThreat.value,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Core HUD elements preserved: health/status, stage/score, kills, radar, BARRACKS progress
        composeRule.onNodeWithTag("top_status_row").assertIsDisplayed()
        composeRule.onNodeWithTag("hud_wave_score_text").assertTextContains("STG 1", substring = true)
        composeRule.onNodeWithTag("campaign_objective_hud").assertIsDisplayed()
        composeRule.onNodeWithTag("tactical_radar_minimap").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_button").assertIsDisplayed()

        // Magma warning must NOT appear when activeThreat == null
        composeRule.onNodeWithTag("threat_warning_banner").assertDoesNotExist()

        // Secondary HUD panel collapses and expands cleanly
        composeRule.onNodeWithTag("hud_collapse_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("campaign_objective_hud").assertDoesNotExist()

        composeRule.onNodeWithTag("hud_collapse_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("campaign_objective_hud").assertIsDisplayed()

        // Magma warning appears ONLY when an actual warning is active
        composeRule.runOnUiThread {
            stateWithThreat.value = stateWithThreat.value.copy(
                activeThreat = ThreatAlert("WARNING: MAGMA GEYSER SURGE DETECTED", Color(0xFFFF3D00), 1.8f)
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("threat_warning_banner").assertIsDisplayed()
        composeRule.onNodeWithText("WARNING: MAGMA GEYSER SURGE DETECTED").assertIsDisplayed()
    }
}
