package com.example.game.network

import android.content.Context
import android.content.SharedPreferences
import com.example.game.model.HelicopterClass
import com.example.game.model.HelicopterSkin
import com.example.game.model.HelicopterUpgrades
import com.example.game.model.PlayerProfile
import org.json.JSONArray
import org.json.JSONObject

data class HangarLocalState(
    val creditsSpent: Int = 0,
    val selectedHelicopterClass: HelicopterClass = HelicopterClass.SCOUT,
    val selectedSkin: HelicopterSkin = HelicopterSkin.TACTICAL_BLACK,
    val unlockedHelicopters: Set<String> = setOf(HelicopterClass.SCOUT.id),
    val upgradesByClass: Map<String, HelicopterUpgrades> = HelicopterClass.entries.associate {
        it.id to HelicopterUpgrades()
    }
)

/**
 * Persists player hangar purchases, selected gunship/skin, upgrade tiers, and cumulative credits spent
 * per authenticated Firebase UID so available War Credits = max(0, firestoreCredits - creditsSpent).
 */
class HangarPersistenceManager(
    private val prefs: SharedPreferences?
) {
    fun loadState(uid: String): HangarLocalState {
        if (prefs == null) return HangarLocalState()
        val raw = prefs.getString(keyFor(uid), null) ?: return HangarLocalState()
        return try {
            val json = JSONObject(raw)
            val spent = json.optInt("creditsSpent", 0).coerceAtLeast(0)
            val className = json.optString("selectedHelicopterClass", HelicopterClass.SCOUT.name)
            val selectedClass = HelicopterClass.entries.find { it.name == className } ?: HelicopterClass.SCOUT
            val skinName = json.optString("selectedSkin", HelicopterSkin.TACTICAL_BLACK.name)
            val selectedSkin = HelicopterSkin.entries.find { it.name == skinName } ?: HelicopterSkin.TACTICAL_BLACK

            val unlockedSet = mutableSetOf(HelicopterClass.SCOUT.id)
            val unlockedArr = json.optJSONArray("unlockedHelicopters")
            if (unlockedArr != null) {
                for (i in 0 until unlockedArr.length()) {
                    unlockedArr.optString(i)?.takeIf { it.isNotBlank() }?.let { unlockedSet.add(it) }
                }
            }

            val upgradesJson = json.optJSONObject("upgrades")
            val upgradesMap = HelicopterClass.entries.associate { helo ->
                val obj = upgradesJson?.optJSONObject(helo.id)
                helo.id to HelicopterUpgrades(
                    engineLevel = obj?.optInt("engineLevel", 1)?.coerceIn(1, 5) ?: 1,
                    armorLevel = obj?.optInt("armorLevel", 1)?.coerceIn(1, 5) ?: 1,
                    weaponLevel = obj?.optInt("weaponLevel", 1)?.coerceIn(1, 5) ?: 1,
                    defenseLevel = obj?.optInt("defenseLevel", 1)?.coerceIn(1, 5) ?: 1,
                    radarLevel = obj?.optInt("radarLevel", 1)?.coerceIn(1, 5) ?: 1
                )
            }

            HangarLocalState(
                creditsSpent = spent,
                selectedHelicopterClass = selectedClass,
                selectedSkin = selectedSkin,
                unlockedHelicopters = unlockedSet,
                upgradesByClass = upgradesMap
            )
        } catch (_: Exception) {
            HangarLocalState()
        }
    }

    fun applyTransaction(
        uid: String,
        previousCredits: Int,
        updatedProfile: PlayerProfile
    ): HangarLocalState {
        val current = loadState(uid)
        val spentDelta = (previousCredits - updatedProfile.credits).coerceAtLeast(0)
        val updatedState = HangarLocalState(
            creditsSpent = (current.creditsSpent + spentDelta).coerceAtLeast(0),
            selectedHelicopterClass = updatedProfile.selectedHelicopterClass,
            selectedSkin = updatedProfile.selectedSkin,
            unlockedHelicopters = updatedProfile.unlockedHelicopters.toSet(),
            upgradesByClass = HelicopterClass.entries.associate { helo ->
                helo.id to updatedProfile.getUpgrades(helo).copy()
            }
        )
        saveState(uid, updatedState)
        return updatedState
    }

    fun saveState(uid: String, state: HangarLocalState) {
        if (prefs == null) return
        val upgradesJson = JSONObject()
        state.upgradesByClass.forEach { (id, up) ->
            upgradesJson.put(
                id,
                JSONObject().apply {
                    put("engineLevel", up.engineLevel.coerceIn(1, 5))
                    put("armorLevel", up.armorLevel.coerceIn(1, 5))
                    put("weaponLevel", up.weaponLevel.coerceIn(1, 5))
                    put("defenseLevel", up.defenseLevel.coerceIn(1, 5))
                    put("radarLevel", up.radarLevel.coerceIn(1, 5))
                }
            )
        }
        val payload = JSONObject().apply {
            put("creditsSpent", state.creditsSpent.coerceAtLeast(0))
            put("selectedHelicopterClass", state.selectedHelicopterClass.name)
            put("selectedSkin", state.selectedSkin.name)
            put("unlockedHelicopters", JSONArray(state.unlockedHelicopters.toList()))
            put("upgrades", upgradesJson)
        }
        prefs.edit().putString(keyFor(uid), payload.toString()).apply()
    }

    private fun keyFor(uid: String): String = "hangar_$uid"

    companion object {
        private const val PREFS_NAME = "hellwar_hangar_prefs"

        @Volatile
        private var instance: HangarPersistenceManager? = null

        fun getInstance(context: Context): HangarPersistenceManager {
            return instance ?: synchronized(this) {
                instance ?: HangarPersistenceManager(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
