package com.example.game.network

import android.content.Context
import android.content.SharedPreferences
import com.example.game.model.EmpireType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact mid-stage continuation snapshot so the player can pause/exit via the Back arrow
 * and resume seamlessly from the Dashboard "CONTINUE" button without losing barracks,
 * boss, HQ, or heart/hit progress.
 */
data class SortieCheckpoint(
    val empire: EmpireType,
    val stageNumber: Int,
    val playerX: Float,
    val playerY: Float,
    val hearts: Int,
    val hitsRemainingInHeart: Int,
    val score: Int,
    val kills: Int,
    val creditsEarned: Int,
    val destroyedBarracksIds: Set<String>,
    val bossSpawned: Boolean,
    val bossDefeated: Boolean,
    val bossHitsRemaining: Int,
    val hqUnlocked: Boolean,
    val hqHitsRemaining: Int,
    val overdriveCharge: Float = 0f,
    val savedAtMs: Long = System.currentTimeMillis()
)

data class AdContinueQuota(
    val usedToday: Int,
    val maxPer24Hours: Int = CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES,
    val remaining: Int = (CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES - usedToday).coerceAtLeast(0),
    val msUntilReset: Long = 0L
) {
    val canWatchAdToContinue: Boolean get() = remaining > 0
}

/**
 * Pure Kotlin progression, sequential stage-locking, checkpoint persistence, and 24-hour
 * rewarded-ad continue quota manager. Supports both Android SharedPreferences and in-memory
 * storage for fast deterministic unit testing.
 */
class CampaignProgressionManager(
    private val prefs: SharedPreferences? = null,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val PREFS_NAME = "hellwar_progression_prefs"
        const val MAX_DAILY_REWARDED_CONTINUES = 10
        const val RESET_WINDOW_MS = 24L * 60L * 60L * 1000L // 24 hours

        /** Strict campaign order: Stage 1 -> 2 -> 3 -> 4 (no advance stage skipping). */
        val STAGE_ORDER: List<EmpireType> = listOf(
            EmpireType.DRAGON,
            EmpireType.ALIEN,
            EmpireType.ORC,
            EmpireType.HUMAN
        )

        private const val KEY_CHECKPOINT_JSON = "checkpoint_json"
        private const val KEY_LOCAL_COMPLETED_STAGES = "local_completed_stages"
        private const val KEY_QUOTA_WINDOW_START_MS = "ad_quota_window_start_ms"
        private const val KEY_QUOTA_USED_COUNT = "ad_quota_used_count"

        @Volatile
        private var instance: CampaignProgressionManager? = null

        fun getInstance(context: Context): CampaignProgressionManager {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                instance ?: CampaignProgressionManager(prefs = sp).also { instance = it }
            }
        }
    }

    // In-memory mirrors used when prefs == null (JVM tests) and as fast cache at runtime
    private var memoryCheckpointJson: String? = null
    private val memoryCompletedStages = mutableSetOf<String>()
    private var memoryQuotaWindowStartMs: Long = 0L
    private var memoryQuotaUsedCount: Int = 0

    fun getStageNumber(empire: EmpireType): Int {
        val idx = STAGE_ORDER.indexOf(empire)
        return if (idx >= 0) idx + 1 else 1
    }

    fun getEmpireForStage(stageNumber: Int): EmpireType {
        return STAGE_ORDER.getOrElse(stageNumber - 1) { STAGE_ORDER.first() }
    }

    fun getNextStage(empire: EmpireType): EmpireType? {
        val idx = STAGE_ORDER.indexOf(empire)
        return if (idx >= 0 && idx + 1 < STAGE_ORDER.size) STAGE_ORDER[idx + 1] else null
    }

    fun markStageCompleted(uid: String, empire: EmpireType) {
        val key = "${KEY_LOCAL_COMPLETED_STAGES}_$uid"
        val current = getLocallyCompletedStages(uid).toMutableSet()
        current.add(empire.name)
        if (prefs != null) {
            prefs.edit().putStringSet(key, current).apply()
        } else {
            memoryCompletedStages.add("${uid}_${empire.name}")
        }
    }

    private fun getLocallyCompletedStages(uid: String): Set<String> {
        return if (prefs != null) {
            prefs.getStringSet("${KEY_LOCAL_COMPLETED_STAGES}_$uid", emptySet()) ?: emptySet()
        } else {
            val prefix = "${uid}_"
            memoryCompletedStages.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }
    }

    fun isStageCompleted(empire: EmpireType, campaignState: GlobalCampaignState?, uid: String = "default"): Boolean {
        if (campaignState != null) {
            return when (empire) {
                EmpireType.DRAGON -> campaignState.dragonProgress.isHeadquartersDestroyed
                EmpireType.ALIEN -> campaignState.alienProgress.isHeadquartersDestroyed
                EmpireType.ORC -> campaignState.orcProgress.isHeadquartersDestroyed
                EmpireType.HUMAN -> campaignState.humanProgress.isHeadquartersDestroyed
            }
        }
        return getLocallyCompletedStages(uid).contains(empire.name)
    }

    /**
     * Sequential stage gate: Stage 1 is always unlocked. Stage N (2..4) unlocks ONLY
     * once Stage N-1 Headquarters has been destroyed (no advance skipping allowed).
     */
    fun isStageUnlocked(empire: EmpireType, campaignState: GlobalCampaignState?, uid: String = "default"): Boolean {
        val idx = STAGE_ORDER.indexOf(empire)
        if (idx <= 0) return true
        val previousEmpire = STAGE_ORDER[idx - 1]
        return isStageCompleted(previousEmpire, campaignState, uid)
    }

    fun getHighestUnlockedEmpire(campaignState: GlobalCampaignState?, uid: String = "default"): EmpireType {
        var highest = STAGE_ORDER.first()
        for (empire in STAGE_ORDER) {
            if (isStageUnlocked(empire, campaignState, uid)) {
                highest = empire
            } else {
                break
            }
        }
        return highest
    }

    fun saveCheckpoint(uid: String, checkpoint: SortieCheckpoint) {
        val json = JSONObject().apply {
            put("empire", checkpoint.empire.name)
            put("stageNumber", checkpoint.stageNumber)
            put("playerX", checkpoint.playerX.toDouble())
            put("playerY", checkpoint.playerY.toDouble())
            put("hearts", checkpoint.hearts)
            put("hitsRemainingInHeart", checkpoint.hitsRemainingInHeart)
            put("score", checkpoint.score)
            put("kills", checkpoint.kills)
            put("creditsEarned", checkpoint.creditsEarned)
            put("destroyedBarracksIds", JSONArray(checkpoint.destroyedBarracksIds.toList()))
            put("bossSpawned", checkpoint.bossSpawned)
            put("bossDefeated", checkpoint.bossDefeated)
            put("bossHitsRemaining", checkpoint.bossHitsRemaining)
            put("hqUnlocked", checkpoint.hqUnlocked)
            put("hqHitsRemaining", checkpoint.hqHitsRemaining)
            put("overdriveCharge", checkpoint.overdriveCharge.toDouble())
            put("savedAtMs", checkpoint.savedAtMs)
        }.toString()

        if (prefs != null) {
            prefs.edit().putString("${KEY_CHECKPOINT_JSON}_$uid", json).apply()
        } else {
            memoryCheckpointJson = "${uid}::$json"
        }
    }

    fun loadCheckpoint(uid: String): SortieCheckpoint? {
        val raw = if (prefs != null) {
            prefs.getString("${KEY_CHECKPOINT_JSON}_$uid", null)
        } else {
            memoryCheckpointJson?.takeIf { it.startsWith("${uid}::") }?.removePrefix("${uid}::")
        } ?: return null

        return try {
            val obj = JSONObject(raw)
            val empire = runCatching { EmpireType.valueOf(obj.getString("empire")) }.getOrNull() ?: return null
            val arr = obj.optJSONArray("destroyedBarracksIds") ?: JSONArray()
            val destroyedIds = buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank()) add(id)
                }
            }
            SortieCheckpoint(
                empire = empire,
                stageNumber = obj.optInt("stageNumber", getStageNumber(empire)),
                playerX = obj.optDouble("playerX", 0.0).toFloat(),
                playerY = obj.optDouble("playerY", 0.0).toFloat(),
                hearts = obj.optInt("hearts", 5).coerceIn(1, 5),
                hitsRemainingInHeart = obj.optInt("hitsRemainingInHeart", 10).coerceIn(1, 10),
                score = obj.optInt("score", 0),
                kills = obj.optInt("kills", 0),
                creditsEarned = obj.optInt("creditsEarned", 0),
                destroyedBarracksIds = destroyedIds,
                bossSpawned = obj.optBoolean("bossSpawned", false),
                bossDefeated = obj.optBoolean("bossDefeated", false),
                bossHitsRemaining = obj.optInt("bossHitsRemaining", 100),
                hqUnlocked = obj.optBoolean("hqUnlocked", false),
                hqHitsRemaining = obj.optInt("hqHitsRemaining", 1000),
                overdriveCharge = obj.optDouble("overdriveCharge", 0.0).toFloat(),
                savedAtMs = obj.optLong("savedAtMs", 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCheckpoint(uid: String) {
        if (prefs != null) {
            prefs.edit().remove("${KEY_CHECKPOINT_JSON}_$uid").apply()
        } else {
            if (memoryCheckpointJson?.startsWith("${uid}::") == true) {
                memoryCheckpointJson = null
            }
        }
    }

    /**
     * Checks 24-hour rewarded ad continue limit (max 10 watches per 24h window).
     * Automatically resets count once 24 hours have elapsed since window start.
     */
    @Synchronized
    fun getAdContinueQuota(uid: String = "default"): AdContinueQuota {
        val now = nowProvider()
        var windowStart = if (prefs != null) {
            prefs.getLong("${KEY_QUOTA_WINDOW_START_MS}_$uid", 0L)
        } else {
            memoryQuotaWindowStartMs
        }
        var used = if (prefs != null) {
            prefs.getInt("${KEY_QUOTA_USED_COUNT}_$uid", 0)
        } else {
            memoryQuotaUsedCount
        }

        if (windowStart > 0L && (now - windowStart) >= RESET_WINDOW_MS) {
            windowStart = 0L
            used = 0
            saveQuotaState(uid, windowStart, used)
        }

        val msUntilReset = if (windowStart > 0L && used > 0) {
            (RESET_WINDOW_MS - (now - windowStart)).coerceAtLeast(0L)
        } else {
            0L
        }

        return AdContinueQuota(
            usedToday = used.coerceIn(0, MAX_DAILY_REWARDED_CONTINUES),
            maxPer24Hours = MAX_DAILY_REWARDED_CONTINUES,
            remaining = (MAX_DAILY_REWARDED_CONTINUES - used).coerceIn(0, MAX_DAILY_REWARDED_CONTINUES),
            msUntilReset = msUntilReset
        )
    }

    /**
     * Consumes one of the 10 daily rewarded-ad continues. Returns updated quota or null if capped.
     */
    @Synchronized
    fun consumeAdContinueWatch(uid: String = "default"): AdContinueQuota? {
        val current = getAdContinueQuota(uid)
        if (!current.canWatchAdToContinue) return null

        val now = nowProvider()
        val existingStart = if (prefs != null) {
            prefs.getLong("${KEY_QUOTA_WINDOW_START_MS}_$uid", 0L)
        } else {
            memoryQuotaWindowStartMs
        }
        val windowStart = if (existingStart <= 0L) now else existingStart
        val newUsed = current.usedToday + 1
        saveQuotaState(uid, windowStart, newUsed)
        return getAdContinueQuota(uid)
    }

    private fun saveQuotaState(uid: String, windowStartMs: Long, usedCount: Int) {
        if (prefs != null) {
            prefs.edit()
                .putLong("${KEY_QUOTA_WINDOW_START_MS}_$uid", windowStartMs)
                .putInt("${KEY_QUOTA_USED_COUNT}_$uid", usedCount)
                .apply()
        } else {
            memoryQuotaWindowStartMs = windowStartMs
            memoryQuotaUsedCount = usedCount
        }
    }
}
