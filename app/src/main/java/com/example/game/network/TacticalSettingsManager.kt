package com.example.game.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.tacticalSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TacticalSettingsManager.PREFS_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, TacticalSettingsManager.PREFS_NAME))
    }
)

data class TacticalSettings(
    val soundAndMusicEnabled: Boolean = true,
    val sfxEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val screenShakeEnabled: Boolean = true,
    val autoFireByDefault: Boolean = true
) {
    val isSfxActive: Boolean get() = soundAndMusicEnabled && sfxEnabled
    val isMusicActive: Boolean get() = soundAndMusicEnabled && musicEnabled
}

/**
 * Device-local persistence backed by Jetpack Preferences DataStore for global game sound/music,
 * haptics, camera recoil, and weapons preferences.
 */
class TacticalSettingsManager private constructor(
    private val dataStore: DataStore<Preferences>?,
    private val prefs: SharedPreferences?,
    private val scope: CoroutineScope
) {
    constructor(prefs: SharedPreferences? = null) : this(
        dataStore = null,
        prefs = prefs,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    constructor(
        dataStore: DataStore<Preferences>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) : this(
        dataStore = dataStore,
        prefs = null,
        scope = scope
    )

    private val _settings = MutableStateFlow(readFromPrefs())
    val settings: StateFlow<TacticalSettings> = _settings.asStateFlow()

    @Volatile
    private var lastWriteJob: Job? = null

    init {
        if (dataStore != null) {
            scope.launch {
                dataStore.data
                    .catch { exception ->
                        if (exception is IOException) {
                            emit(emptyPreferences())
                        } else {
                            throw exception
                        }
                    }
                    .collect { preferences ->
                        _settings.value = mapPreferences(preferences)
                    }
            }
        }
    }

    private fun readFromPrefs(): TacticalSettings {
        if (prefs == null) return TacticalSettings()
        val sfx = prefs.getBoolean(KEY_SFX_ENABLED, true)
        val music = prefs.getBoolean(KEY_MUSIC_ENABLED, true)
        val master = prefs.getBoolean(KEY_SOUND_AND_MUSIC_ENABLED, sfx || music)
        return TacticalSettings(
            soundAndMusicEnabled = master,
            sfxEnabled = sfx,
            musicEnabled = music,
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS_ENABLED, true),
            screenShakeEnabled = prefs.getBoolean(KEY_SCREEN_SHAKE, true),
            autoFireByDefault = prefs.getBoolean(KEY_AUTO_FIRE_DEFAULT, true)
        )
    }

    private fun mapPreferences(preferences: Preferences): TacticalSettings {
        val sfx = preferences[DS_KEY_SFX_ENABLED] ?: true
        val music = preferences[DS_KEY_MUSIC_ENABLED] ?: true
        val master = preferences[DS_KEY_SOUND_AND_MUSIC_ENABLED] ?: (sfx || music)
        return TacticalSettings(
            soundAndMusicEnabled = master,
            sfxEnabled = sfx,
            musicEnabled = music,
            hapticsEnabled = preferences[DS_KEY_HAPTICS_ENABLED] ?: true,
            screenShakeEnabled = preferences[DS_KEY_SCREEN_SHAKE] ?: true,
            autoFireByDefault = preferences[DS_KEY_AUTO_FIRE_DEFAULT] ?: true
        )
    }

    private fun save(updated: TacticalSettings) {
        _settings.value = updated
        if (dataStore != null) {
            lastWriteJob = scope.launch {
                dataStore.edit { prefs ->
                    prefs[DS_KEY_SOUND_AND_MUSIC_ENABLED] = updated.soundAndMusicEnabled
                    prefs[DS_KEY_SFX_ENABLED] = updated.sfxEnabled
                    prefs[DS_KEY_MUSIC_ENABLED] = updated.musicEnabled
                    prefs[DS_KEY_HAPTICS_ENABLED] = updated.hapticsEnabled
                    prefs[DS_KEY_SCREEN_SHAKE] = updated.screenShakeEnabled
                    prefs[DS_KEY_AUTO_FIRE_DEFAULT] = updated.autoFireByDefault
                }
            }
        } else {
            prefs?.edit {
                putBoolean(KEY_SOUND_AND_MUSIC_ENABLED, updated.soundAndMusicEnabled)
                putBoolean(KEY_SFX_ENABLED, updated.sfxEnabled)
                putBoolean(KEY_MUSIC_ENABLED, updated.musicEnabled)
                putBoolean(KEY_HAPTICS_ENABLED, updated.hapticsEnabled)
                putBoolean(KEY_SCREEN_SHAKE, updated.screenShakeEnabled)
                putBoolean(KEY_AUTO_FIRE_DEFAULT, updated.autoFireByDefault)
            }
        }
    }

    /**
     * Master global toggle that enables or disables both game sound effects and music together.
     */
    fun setSoundAndMusicEnabled(enabled: Boolean) {
        save(
            _settings.value.copy(
                soundAndMusicEnabled = enabled,
                sfxEnabled = enabled,
                musicEnabled = enabled
            )
        )
    }

    fun setSfxEnabled(enabled: Boolean) {
        save(_settings.value.copy(sfxEnabled = enabled))
    }

    fun setMusicEnabled(enabled: Boolean) {
        save(_settings.value.copy(musicEnabled = enabled))
    }

    fun setHapticsEnabled(enabled: Boolean) {
        save(_settings.value.copy(hapticsEnabled = enabled))
    }

    fun setScreenShakeEnabled(enabled: Boolean) {
        save(_settings.value.copy(screenShakeEnabled = enabled))
    }

    fun setAutoFireByDefault(enabled: Boolean) {
        save(_settings.value.copy(autoFireByDefault = enabled))
    }

    suspend fun awaitPendingWrites(): TacticalSettings {
        lastWriteJob?.join()
        val ds = dataStore ?: return _settings.value
        val current = mapPreferences(ds.data.first())
        _settings.value = current
        return current
    }

    companion object {
        const val PREFS_NAME = "hellwar_tactical_settings"
        private const val KEY_SOUND_AND_MUSIC_ENABLED = "sound_and_music_enabled"
        private const val KEY_SFX_ENABLED = "sfx_enabled"
        private const val KEY_MUSIC_ENABLED = "music_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_SCREEN_SHAKE = "screen_shake_enabled"
        private const val KEY_AUTO_FIRE_DEFAULT = "auto_fire_by_default"

        private val DS_KEY_SOUND_AND_MUSIC_ENABLED = booleanPreferencesKey(KEY_SOUND_AND_MUSIC_ENABLED)
        private val DS_KEY_SFX_ENABLED = booleanPreferencesKey(KEY_SFX_ENABLED)
        private val DS_KEY_MUSIC_ENABLED = booleanPreferencesKey(KEY_MUSIC_ENABLED)
        private val DS_KEY_HAPTICS_ENABLED = booleanPreferencesKey(KEY_HAPTICS_ENABLED)
        private val DS_KEY_SCREEN_SHAKE = booleanPreferencesKey(KEY_SCREEN_SHAKE)
        private val DS_KEY_AUTO_FIRE_DEFAULT = booleanPreferencesKey(KEY_AUTO_FIRE_DEFAULT)

        @Volatile
        private var instance: TacticalSettingsManager? = null

        fun getInstance(context: Context): TacticalSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: TacticalSettingsManager(
                    dataStore = context.applicationContext.tacticalSettingsDataStore
                ).also { instance = it }
            }
        }

        internal fun resetForTest() {
            instance = null
        }
    }
}
