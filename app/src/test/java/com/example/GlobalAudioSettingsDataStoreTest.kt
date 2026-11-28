package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.audio.CombatAudioSystem
import com.example.game.engine.GameEngine
import com.example.game.engine.GameMode
import com.example.game.model.PlayerProfile
import com.example.game.network.TacticalSettingsManager
import com.example.game.ui.HudOverlay
import com.example.game.ui.MainMenuScreen
import com.example.game.ui.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w844dp-h390dp-land")
class GlobalAudioSettingsDataStoreTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TacticalSettingsManager.resetForTest()
        File(context.filesDir, "datastore/${TacticalSettingsManager.PREFS_NAME}.preferences_pb").delete()
        context.getSharedPreferences(TacticalSettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @After
    fun tearDown() {
        TacticalSettingsManager.resetForTest()
        AdConsentManager.resetForTest()
        AdMobManager.resetForTest()
    }

    @Test
    fun dataStore_persistsGlobalSoundAndMusicToggleAcrossManagerInstances() {
        runBlocking {
            val tempDir = File(context.cacheDir, "ds_test_${System.nanoTime()}").apply { mkdirs() }
            val pbFile = File(tempDir, "test_tactical_settings.preferences_pb")

            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { pbFile }
            val mgr1 = TacticalSettingsManager(dataStore = ds1, scope = scope1)

            // Default is enabled
            val initial = mgr1.awaitPendingWrites()
            assertTrue(initial.soundAndMusicEnabled)
            assertTrue(initial.isSfxActive)
            assertTrue(initial.isMusicActive)

            // Disable global sound & music and wait for DataStore write
            mgr1.setSoundAndMusicEnabled(false)
            val disabled = mgr1.awaitPendingWrites()
            assertFalse(disabled.soundAndMusicEnabled)
            assertFalse(disabled.isSfxActive)
            assertFalse(disabled.isMusicActive)
            scope1.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()

            // Create a fresh DataStore + manager instance reading the same file -> must remain disabled
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { pbFile }
            val mgr2 = TacticalSettingsManager(dataStore = ds2, scope = scope2)
            val reloaded = mgr2.awaitPendingWrites()
            assertFalse("Global Sound & Music OFF must persist in DataStore", reloaded.soundAndMusicEnabled)
            assertFalse(reloaded.isSfxActive)
            assertFalse(reloaded.isMusicActive)

            // Re-enable and verify persistence across restart
            mgr2.setSoundAndMusicEnabled(true)
            mgr2.awaitPendingWrites()
            scope2.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()

            val scope3 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ds3 = PreferenceDataStoreFactory.create(scope = scope3) { pbFile }
            val mgr3 = TacticalSettingsManager(dataStore = ds3, scope = scope3)
            val reenabled = mgr3.awaitPendingWrites()
            assertTrue("Global Sound & Music ON must persist in DataStore", reenabled.soundAndMusicEnabled)
            assertTrue(reenabled.isSfxActive)
            assertTrue(reenabled.isMusicActive)
            scope3.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun settingsScreen_globalSoundAndMusicToggleControlsSfxAndMusicAndPersistsToDataStore() {
        runBlocking {
            val manager = TacticalSettingsManager.getInstance(context)
            manager.setSoundAndMusicEnabled(true)
            manager.awaitPendingWrites()

            composeRule.setContent {
                MyApplicationTheme {
                    SettingsScreen(
                        pilotEmail = "ace@heliwar.com",
                        pilotUid = "uid_audio_test",
                        onSignOut = {},
                        onBack = {}
                    )
                }
            }
            composeRule.waitForIdle()

            val globalSwitch = composeRule.onNodeWithTag("setting_global_audio_toggle")
            val sfxSwitch = composeRule.onNodeWithTag("setting_sfx_toggle")
            val musicSwitch = composeRule.onNodeWithTag("setting_music_toggle")

            globalSwitch.performScrollTo().assertIsDisplayed().assertIsOn()
            sfxSwitch.performScrollTo().assertIsDisplayed().assertIsOn()
            musicSwitch.performScrollTo().assertIsDisplayed().assertIsOn()

            // Tap global toggle OFF -> disables both SFX & Music and writes to DataStore
            globalSwitch.performClick()
            composeRule.waitForIdle()

            globalSwitch.assertIsOff()
            sfxSwitch.assertIsOff()
            musicSwitch.assertIsOff()

            val persistedOff = manager.awaitPendingWrites()
            assertFalse(persistedOff.soundAndMusicEnabled)
            assertFalse(persistedOff.isSfxActive)
            assertFalse(persistedOff.isMusicActive)

            // Tap global toggle ON -> re-enables both SFX & Music and writes to DataStore
            globalSwitch.performClick()
            composeRule.waitForIdle()

            globalSwitch.assertIsOn()
            sfxSwitch.assertIsOn()
            musicSwitch.assertIsOn()

            val persistedOn = manager.awaitPendingWrites()
            assertTrue(persistedOn.soundAndMusicEnabled)
            assertTrue(persistedOn.isSfxActive)
            assertTrue(persistedOn.isMusicActive)
        }
    }

    @Test
    fun hudPauseDialog_exposesGlobalAudioQuickToggle() {
        val engine = GameEngine(gameMode = GameMode.ENDLESS_SURVIVAL)
        engine.update(0.05f)
        var hudAudioState = true

        composeRule.setContent {
            MyApplicationTheme {
                HudOverlay(
                    hudState = engine.hudState.value,
                    engine = engine,
                    onPauseClick = {},
                    onReturnToMenu = {},
                    onRestartSortie = {},
                    soundAndMusicEnabled = hudAudioState,
                    onToggleSoundAndMusic = { hudAudioState = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pause_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pause_global_audio_toggle")
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()
        composeRule.waitForIdle()
        assertFalse(hudAudioState)
    }

    @Test
    fun mainMenuHeader_exposesGlobalAudioQuickToggle() {
        var menuAudioState = true

        composeRule.setContent {
            MyApplicationTheme {
                MainMenuScreen(
                    profile = PlayerProfile(callsign = "ACE-AUDIO"),
                    onPlayWar = {},
                    onEmpireCampaign = {},
                    onEndlessBattle = {},
                    onGarage = {},
                    onWeaponsUpgrades = {},
                    onWarCommand = {},
                    onLeaderboard = {},
                    onSettings = {},
                    soundAndMusicEnabled = menuAudioState,
                    onToggleSoundAndMusic = { menuAudioState = it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("menu_global_audio_toggle").assertIsDisplayed().performClick()
        assertFalse(menuAudioState)
    }

    @Test
    fun combatAudioSystem_syncsSfxAndMusicMuteState() {
        val audio = CombatAudioSystem(context = null)
        assertEquals(false, audio.isMuted)
        assertEquals(false, audio.isMusicMuted)

        audio.setAudioEnabled(sfxEnabled = false, musicEnabled = false)
        assertTrue(audio.isMuted)
        assertTrue(audio.isMusicMuted)

        audio.setAudioEnabled(sfxEnabled = true, musicEnabled = true)
        assertFalse(audio.isMuted)
        assertFalse(audio.isMusicMuted)

        audio.release()
    }
}
