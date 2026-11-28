package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.UpperGameplayBannerDock
import com.example.game.engine.EnemyCategory
import com.example.game.engine.EnemyTarget
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.engine.Renderer3D
import com.example.game.engine.Vector3D
import com.example.game.model.EmpireType
import com.example.game.model.HelicopterClass
import com.example.game.ui.GamePlayScreen
import com.example.game.ui.HudOverlay
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w844dp-h390dp-land")
class OrientationAndScaleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
        AdConsentManager.setConsentStateForTest(
            canRequestAds = true,
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
    fun mainActivity_switchesPortraitAndStrictLandscapeAndLocksAcrossConfigChanges() {
        val context: Context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.hellwar.game")
                    .setProjectId("demo-heliwar-test")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            // Default menu/auth state is strict Portrait
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // Config change outside gameplay keeps Portrait locked
            activity.onConfigurationChanged(Configuration().apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            })
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // Entering gameplay switches strictly to Landscape (no sensorLandscape)
            activity.updateScreenOrientation(isGameplay = true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

            // Sensor/device orientation change during battle must keep strict Landscape
            activity.onConfigurationChanged(Configuration().apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
            })
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

            // Leaving battle restores Portrait
            activity.updateScreenOrientation(isGameplay = false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h844dp-port")
    fun gamePlayScreen_waitsForStableLandscapeViewportBeforeRenderingHud() {
        composeRule.setContent {
            MyApplicationTheme {
                GamePlayScreen(
                    selectedEmpire = EmpireType.DRAGON,
                    gameMode = GameMode.CAMPAIGN,
                    onReturnToMenu = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Upper banner dock mounts immediately while battlefield HUD waits for landscape viewport (width > height)
        composeRule.onNodeWithTag("movable_banner_dock").assertIsDisplayed()
        composeRule.onNodeWithTag("hud_health_hearts").assertDoesNotExist()
        composeRule.onNodeWithTag("virtual_joystick").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w640dp-h360dp-land")
    fun responsiveHudFitsCleanlyIn16By9Landscape() {
        assertResponsiveLandscapeHud()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w844dp-h390dp-land")
    fun responsiveHudFitsCleanlyIn19Point5By9Landscape() {
        assertResponsiveLandscapeHud()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w915dp-h412dp-land")
    fun responsiveHudFitsCleanlyIn20By9Landscape() {
        assertResponsiveLandscapeHud()
    }

    private fun assertResponsiveLandscapeHud() {
        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
        engine.player.lockedTargetId = "target_1"
        engine.player.lockProgress = 1.0f
        engine.update(0.05f)

        composeRule.setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    UpperGameplayBannerDock(
                        isBannerVisible = true,
                        onSetBannerVisible = {}
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

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val dockBounds = composeRule.onNodeWithTag("movable_banner_dock").assertIsDisplayed().getBoundsInRoot()
        val heartsBounds = composeRule.onNodeWithTag("hud_health_hearts").assertIsDisplayed().getBoundsInRoot()
        val pauseBounds = composeRule.onNodeWithTag("pause_button").assertIsDisplayed().getBoundsInRoot()
        val joystickBounds = composeRule.onNodeWithTag("virtual_joystick").assertIsDisplayed().getBoundsInRoot()
        val lockedBounds = composeRule.onNodeWithTag("missile_attack_button").assertIsDisplayed().getBoundsInRoot()

        composeRule.onNodeWithTag("tactical_radar_minimap").assertDoesNotExist()
        composeRule.onNodeWithTag("overdrive_button").assertDoesNotExist()

        // Zero clipping outside viewport bounds
        assertTrue(heartsBounds.left >= rootBounds.left)
        assertTrue(joystickBounds.left >= rootBounds.left)
        assertTrue(joystickBounds.bottom <= rootBounds.bottom)
        assertTrue(pauseBounds.right <= rootBounds.right)
        assertTrue(lockedBounds.right <= rootBounds.right)
        assertTrue(lockedBounds.bottom <= rootBounds.bottom)

        // Banner dock never overlaps HUD controls
        assertTrue(dockBounds.bottom <= heartsBounds.top)
        assertTrue(dockBounds.bottom <= pauseBounds.top)

        // Center battlefield remains completely clear between left and right HUD controls
        assertTrue(heartsBounds.right < pauseBounds.left)
        assertTrue(joystickBounds.right < lockedBounds.left)

        // Pause and LOCKED align cleanly on the right edge
        assertTrue(abs((pauseBounds.right - lockedBounds.right).value) <= 12f)
    }

    @Test
    fun renderer3D_usesEnlargedCharacterScalesAndRendersAcrossAspectRatios() {
        val renderer = Renderer3D()

        assertEquals(1.78f, renderer.playerModelScale(HelicopterClass.SCOUT), 0.001f)
        assertEquals(1.64f, renderer.playerModelScale(HelicopterClass.HEAVY_GUNSHIP), 0.001f)
        assertEquals(1.72f, renderer.playerModelScale(HelicopterClass.STEALTH), 0.001f)
        assertEquals(1.68f, renderer.playerModelScale(HelicopterClass.ULTIMATE_WAR), 0.001f)

        assertEquals(2.45f, renderer.enemyVisualScale(EnemyCategory.INFANTRY_SOLDIER), 0.001f)
        assertEquals(2.35f, renderer.enemyVisualScale(EnemyCategory.ROCKET_SOLDIER), 0.001f)
        assertEquals(2.05f, renderer.enemyVisualScale(EnemyCategory.WATCHTOWER_SNIPER), 0.001f)
        assertEquals(1.95f, renderer.enemyVisualScale(EnemyCategory.AA_TURRET), 0.001f)
        assertEquals(1.85f, renderer.enemyVisualScale(EnemyCategory.GROUND_TANK), 0.001f)
        assertEquals(1.85f, renderer.enemyVisualScale(EnemyCategory.ATTACK_HELICOPTER), 0.001f)

        assertTrue(renderer.enemyHealthBarOffset(EnemyCategory.INFANTRY_SOLDIER) >= 58f)
        assertTrue(renderer.enemyHealthBarOffset(EnemyCategory.ATTACK_HELICOPTER) >= 78f)

        val viewports = listOf(1280 to 720, 2340 to 1080, 2400 to 1080)
        val drawScope = CanvasDrawScope()
        val density = Density(1f)

        for ((w, h) in viewports) {
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap.asImageBitmap())
            val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, gameMode = GameMode.CAMPAIGN)
            engine.enemies.clear()
            EnemyCategory.entries.forEachIndexed { idx, category ->
                val isGround = category != EnemyCategory.ATTACK_HELICOPTER &&
                    category != EnemyCategory.FIGHTER_AIRCRAFT
                engine.enemies.add(
                    EnemyTarget(
                        id = "scaled_$idx",
                        empire = EmpireType.DRAGON,
                        category = category,
                        pos = Vector3D((idx - 3) * 5f, 18f + idx * 3f, if (isGround) 0.1f else 9.5f),
                        velocity = Vector3D(0f, 0f, 0f),
                        health = 100f,
                        maxHealth = 100f,
                        scoreValue = 150,
                        unitName = category.label,
                        isGroundUnit = isGround,
                        isAlerted = true
                    )
                )
            }
            engine.player.lockedTargetId = "scaled_0"
            engine.player.lockProgress = 1.0f

            drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                renderer.render(this, engine, size.width, size.height)
            }
            bitmap.recycle()
        }
    }
}
