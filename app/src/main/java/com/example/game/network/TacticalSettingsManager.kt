package com.example.game.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TacticalSettings(
    val sfxEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val screenShakeEnabled: Boolean = true,
    val autoFireByDefault: Boolean = true
)

/**
 * Device-local persistence for tactical audio, haptics, camera recoil, and weapons preferences.
 */
class TacticalSettingsManager(
    private val prefs: SharedPreferences?
) {
    private val _settings = MutableStateFlow(readFromPrefs())
    val settings: StateFlow<TacticalSettings> = _settings.asStateFlow()

    private fun readFromPrefs(): TacticalSettings {
        if (prefs == null) return TacticalSettings()
        return TacticalSettings(
            sfxEnabled = prefs.getBoolean(KEY_SFX_ENABLED, true),
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS_ENABLED, true),
            screenShakeEnabled = prefs.getBoolean(KEY_SCREEN_SHAKE, true),
            autoFireByDefault = prefs.getBoolean(KEY_AUTO_FIRE_DEFAULT, true)
        )
    }

    private fun save(updated: TacticalSettings) {
        _settings.value = updated
        prefs?.edit()
            ?.putBoolean(KEY_SFX_ENABLED, updated.sfxEnabled)
            ?.putBoolean(KEY_HAPTICS_ENABLED, updated.hapticsEnabled)
            ?.putBoolean(KEY_SCREEN_SHAKE, updated.screenShakeEnabled)
            ?.putBoolean(KEY_AUTO_FIRE_DEFAULT, updated.autoFireByDefault)
            ?.apply()
    }

    fun setSfxEnabled(enabled: Boolean) {
        save(_settings.value.copy(sfxEnabled = enabled))
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

    companion object {
        private const val PREFS_NAME = "hellwar_tactical_settings"
        private const val KEY_SFX_ENABLED = "sfx_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_SCREEN_SHAKE = "screen_shake_enabled"
        private const val KEY_AUTO_FIRE_DEFAULT = "auto_fire_by_default"

        @Volatile
        private var instance: TacticalSettingsManager? = null

        fun getInstance(context: Context): TacticalSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: TacticalSettingsManager(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
