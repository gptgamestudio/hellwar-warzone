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

data class EndlessCheckpoint(
    val currentWave: Int,
    val wavesCompleted: Int,
    val activeEmpire: EmpireType,
    val playerX: Float,
    val playerY: Float,
    val hearts: Int,
    val hitsRemainingInHeart: Int,
    val score: Int,
    val kills: Int,
    val confirmedCreditsEarned: Int,
    val waveEnemiesDefeated: Int,
    val waveTotalEnemies: Int,
    val overdriveCharge: Float = 0f,
    val endlessSessionId: String? = null,
    val settledWaves: Set<Int> = emptySet(),
    val pendingClaimWaves: Map<Int, Int> = emptyMap(),
    val savedAtMs: Long = System.currentTimeMillis(),
    val totalRegularKills: Int = 0,
    val cycleKills: Int = 0,
    val bossesDefeated: Int = 0,
    val bossRotationIndex: Int = 0,
    val bossEncounterState: String = "NORMAL_WAVE",
    val activeBossHealth: Float = 0f,
    val activeBossMaxHealth: Float = 0f,
    val activeBossHitsRemaining: Int = 0,
    val bossRewardClaimed: Boolean = false
)

data class EmpireEndlessProgress(
    val empire: EmpireType,
    val regularKills: Int = 0,
    val cycleKills: Int = 0,
    val bossesDefeated: Int = 0,
    val highestWave: Int = 0,
    val bestSurvivalScore: Int = 0,
    val totalWavesCompleted: Int = 0
) {
    val cappedChallengeKills: Int
        get() = regularKills.coerceIn(0, CampaignProgressionManager.EMPIRE_ENDLESS_KILL_TARGET)

    val isTargetReached: Boolean
        get() = regularKills >= CampaignProgressionManager.EMPIRE_ENDLESS_KILL_TARGET

    val progressFraction: Float
        get() = (cappedChallengeKills.toFloat() / CampaignProgressionManager.EMPIRE_ENDLESS_KILL_TARGET.toFloat())
            .coerceIn(0f, 1f)
}

data class EndlessSurvivalRecord(
    val highestWave: Int = 0,
    val bestSurvivalScore: Int = 0,
    val totalWavesCompleted: Int = 0,
    val totalRegularKills: Int = 0,
    val totalBossesDefeated: Int = 0,
    val empireProgress: Map<EmpireType, EmpireEndlessProgress> = emptyMap()
) {
    fun progressFor(empire: EmpireType): EmpireEndlessProgress =
        empireProgress[empire] ?: EmpireEndlessProgress(empire)

    val cappedFourEmpireKills: Int
        get() = if (empireProgress.isNotEmpty()) {
            EmpireType.entries.sumOf { progressFor(it).cappedChallengeKills }
        } else {
            totalRegularKills.coerceIn(0, CampaignProgressionManager.TOTAL_ENDLESS_KILL_TARGET)
        }

    val uncappedFourEmpireKills: Int
        get() = if (empireProgress.isNotEmpty()) {
            EmpireType.entries.sumOf { progressFor(it).regularKills.coerceAtLeast(0) }
        } else {
            totalRegularKills.coerceAtLeast(0)
        }

    val totalBossesAcrossEmpires: Int
        get() = if (empireProgress.isNotEmpty()) {
            EmpireType.entries.sumOf { progressFor(it).bossesDefeated.coerceAtLeast(0) }
        } else {
            totalBossesDefeated.coerceAtLeast(0)
        }

    val conqueredEmpireCount: Int
        get() = EmpireType.entries.count { progressFor(it).isTargetReached }

    val bestWaveAcrossEmpires: Int
        get() = maxOf(highestWave, empireProgress.values.maxOfOrNull { it.highestWave } ?: 0)

    val bestScoreAcrossEmpires: Int
        get() = maxOf(bestSurvivalScore, empireProgress.values.maxOfOrNull { it.bestSurvivalScore } ?: 0)

    val totalWavesAcrossEmpires: Int
        get() = maxOf(totalWavesCompleted, empireProgress.values.sumOf { it.totalWavesCompleted })
}

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
        const val EMPIRE_ENDLESS_KILL_TARGET = 250_000
        const val TOTAL_ENDLESS_KILL_TARGET = 1_000_000
        const val ENDLESS_BOSS_CYCLE_SIZE = 100

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
        private const val KEY_ENDLESS_CHECKPOINT_JSON = "endless_checkpoint_json"
        private const val KEY_ENDLESS_CHECKPOINT_EMPIRE_PREFIX = "endless_checkpoint_emp"
        private const val KEY_LAST_ENDLESS_EMPIRE = "last_endless_empire"
        private const val KEY_ENDLESS_V2_MIGRATED = "endless_v2_migrated"
        private const val KEY_ENDLESS_HIGHEST_WAVE = "endless_highest_wave"
        private const val KEY_ENDLESS_BEST_SCORE = "endless_best_score"
        private const val KEY_ENDLESS_TOTAL_WAVES = "endless_total_waves"
        private const val KEY_ENDLESS_TOTAL_REGULAR_KILLS = "endless_total_regular_kills"
        private const val KEY_ENDLESS_TOTAL_BOSSES_DEFEATED = "endless_total_bosses_defeated"
        private const val KEY_ENDLESS_EMPIRE_PROGRESS_PREFIX = "endless_emp_progress"

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
    private val memoryEndlessCheckpoints = mutableMapOf<String, String>()
    private val memoryLastEndlessEmpire = mutableMapOf<String, EmpireType>()
    private val memoryEndlessMigratedUids = mutableSetOf<String>()
    private val memoryEndlessRecords = mutableMapOf<String, EndlessSurvivalRecord>()
    private val memoryEmpireProgress = mutableMapOf<String, MutableMap<EmpireType, EmpireEndlessProgress>>()

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

    private fun endlessCheckpointKey(uid: String, empire: EmpireType): String =
        "${KEY_ENDLESS_CHECKPOINT_EMPIRE_PREFIX}_${empire.name}_$uid"

    private fun empireProgressKey(uid: String, empire: EmpireType): String =
        "${KEY_ENDLESS_EMPIRE_PROGRESS_PREFIX}_${empire.name}_$uid"

    private fun serializeEndlessCheckpoint(checkpoint: EndlessCheckpoint): String {
        val pendingObj = JSONObject()
        checkpoint.pendingClaimWaves.forEach { (wave, kills) ->
            pendingObj.put(wave.toString(), kills)
        }
        return JSONObject().apply {
            put("currentWave", checkpoint.currentWave)
            put("wavesCompleted", checkpoint.wavesCompleted)
            put("activeEmpire", checkpoint.activeEmpire.name)
            put("playerX", checkpoint.playerX.toDouble())
            put("playerY", checkpoint.playerY.toDouble())
            put("hearts", checkpoint.hearts)
            put("hitsRemainingInHeart", checkpoint.hitsRemainingInHeart)
            put("score", checkpoint.score)
            put("kills", checkpoint.kills)
            put("confirmedCreditsEarned", checkpoint.confirmedCreditsEarned)
            put("waveEnemiesDefeated", checkpoint.waveEnemiesDefeated)
            put("waveTotalEnemies", checkpoint.waveTotalEnemies)
            put("overdriveCharge", checkpoint.overdriveCharge.toDouble())
            put("endlessSessionId", checkpoint.endlessSessionId ?: "")
            put("settledWaves", JSONArray(checkpoint.settledWaves.toList()))
            put("pendingClaimWaves", pendingObj)
            put("savedAtMs", checkpoint.savedAtMs)
            put("totalRegularKills", checkpoint.totalRegularKills)
            put("cycleKills", checkpoint.cycleKills)
            put("bossesDefeated", checkpoint.bossesDefeated)
            put("bossRotationIndex", checkpoint.bossRotationIndex)
            put("bossEncounterState", checkpoint.bossEncounterState)
            put("activeBossHealth", checkpoint.activeBossHealth.toDouble())
            put("activeBossMaxHealth", checkpoint.activeBossMaxHealth.toDouble())
            put("activeBossHitsRemaining", checkpoint.activeBossHitsRemaining)
            put("bossRewardClaimed", checkpoint.bossRewardClaimed)
        }.toString()
    }

    private fun parseEndlessCheckpoint(raw: String?): EndlessCheckpoint? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val empire = runCatching { EmpireType.valueOf(obj.getString("activeEmpire")) }.getOrNull()
                ?: EmpireType.DRAGON
            val settledArr = obj.optJSONArray("settledWaves") ?: JSONArray()
            val settled = buildSet {
                for (i in 0 until settledArr.length()) {
                    val w = settledArr.optInt(i, -1)
                    if (w > 0) add(w)
                }
            }
            val pendingObj = obj.optJSONObject("pendingClaimWaves")
            val pending = buildMap {
                if (pendingObj != null) {
                    val keys = pendingObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val w = k.toIntOrNull()
                        val v = pendingObj.optInt(k, 0)
                        if (w != null && w > 0 && v > 0) put(w, v)
                    }
                }
            }
            EndlessCheckpoint(
                currentWave = obj.optInt("currentWave", 1).coerceAtLeast(1),
                wavesCompleted = obj.optInt("wavesCompleted", 0).coerceAtLeast(0),
                activeEmpire = empire,
                playerX = obj.optDouble("playerX", 0.0).toFloat(),
                playerY = obj.optDouble("playerY", 0.0).toFloat(),
                hearts = obj.optInt("hearts", 5).coerceIn(1, 5),
                hitsRemainingInHeart = obj.optInt("hitsRemainingInHeart", 10).coerceIn(1, 10),
                score = obj.optInt("score", 0).coerceAtLeast(0),
                kills = obj.optInt("kills", 0).coerceAtLeast(0),
                confirmedCreditsEarned = obj.optInt("confirmedCreditsEarned", 0).coerceAtLeast(0),
                waveEnemiesDefeated = obj.optInt("waveEnemiesDefeated", 0).coerceAtLeast(0),
                waveTotalEnemies = obj.optInt("waveTotalEnemies", 4).coerceAtLeast(1),
                overdriveCharge = obj.optDouble("overdriveCharge", 0.0).toFloat(),
                endlessSessionId = obj.optString("endlessSessionId", "").takeIf { it.isNotBlank() },
                settledWaves = settled,
                pendingClaimWaves = pending,
                savedAtMs = obj.optLong("savedAtMs", 0L),
                totalRegularKills = obj.optInt("totalRegularKills", 0).coerceAtLeast(0),
                cycleKills = obj.optInt("cycleKills", 0).coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE),
                bossesDefeated = obj.optInt("bossesDefeated", 0).coerceAtLeast(0),
                bossRotationIndex = obj.optInt("bossRotationIndex", 0).coerceAtLeast(0) % 4,
                bossEncounterState = obj.optString("bossEncounterState", "NORMAL_WAVE").ifBlank { "NORMAL_WAVE" },
                activeBossHealth = obj.optDouble("activeBossHealth", 0.0).toFloat().coerceAtLeast(0f),
                activeBossMaxHealth = obj.optDouble("activeBossMaxHealth", 0.0).toFloat().coerceAtLeast(0f),
                activeBossHitsRemaining = obj.optInt("activeBossHitsRemaining", 0).coerceAtLeast(0),
                bossRewardClaimed = obj.optBoolean("bossRewardClaimed", false)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readEmpireProgressRaw(uid: String, empire: EmpireType): EmpireEndlessProgress {
        if (prefs == null) {
            return memoryEmpireProgress[uid]?.get(empire) ?: EmpireEndlessProgress(empire)
        }
        val raw = prefs.getString(empireProgressKey(uid, empire), null)
            ?: return EmpireEndlessProgress(empire)
        return try {
            val obj = JSONObject(raw)
            EmpireEndlessProgress(
                empire = empire,
                regularKills = obj.optInt("regularKills", 0).coerceAtLeast(0),
                cycleKills = obj.optInt("cycleKills", 0).coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE),
                bossesDefeated = obj.optInt("bossesDefeated", 0).coerceAtLeast(0),
                highestWave = obj.optInt("highestWave", 0).coerceAtLeast(0),
                bestSurvivalScore = obj.optInt("bestSurvivalScore", 0).coerceAtLeast(0),
                totalWavesCompleted = obj.optInt("totalWavesCompleted", 0).coerceAtLeast(0)
            )
        } catch (_: Throwable) {
            EmpireEndlessProgress(empire)
        }
    }

    private fun writeEmpireProgressRaw(uid: String, progress: EmpireEndlessProgress) {
        if (prefs != null) {
            val json = JSONObject().apply {
                put("empire", progress.empire.name)
                put("regularKills", progress.regularKills.coerceAtLeast(0))
                put("cycleKills", progress.cycleKills.coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE))
                put("bossesDefeated", progress.bossesDefeated.coerceAtLeast(0))
                put("highestWave", progress.highestWave.coerceAtLeast(0))
                put("bestSurvivalScore", progress.bestSurvivalScore.coerceAtLeast(0))
                put("totalWavesCompleted", progress.totalWavesCompleted.coerceAtLeast(0))
            }.toString()
            prefs.edit().putString(empireProgressKey(uid, progress.empire), json).apply()
        } else {
            val map = memoryEmpireProgress.getOrPut(uid) { mutableMapOf() }
            map[progress.empire] = progress
        }
    }

    /**
     * Idempotent non-destructive legacy migration:
     * Migrates legacy single-pool Endless kills, boss records, and checkpoint into the
     * last saved checkpoint's `activeEmpire` (or `DRAGON` if no valid legacy checkpoint exists).
     */
    @Synchronized
    fun ensureLegacyEndlessMigrated(uid: String) {
        val alreadyMigrated = if (prefs != null) {
            prefs.getBoolean("${KEY_ENDLESS_V2_MIGRATED}_$uid", false)
        } else {
            memoryEndlessMigratedUids.contains(uid)
        }
        if (alreadyMigrated) return

        val legacyRawCp = if (prefs != null) {
            prefs.getString("${KEY_ENDLESS_CHECKPOINT_JSON}_$uid", null)
        } else {
            memoryEndlessCheckpoints[uid]
        }
        val legacyCp = parseEndlessCheckpoint(legacyRawCp)

        val legacyRecord = if (prefs != null) {
            EndlessSurvivalRecord(
                highestWave = prefs.getInt("${KEY_ENDLESS_HIGHEST_WAVE}_$uid", 0).coerceAtLeast(0),
                bestSurvivalScore = prefs.getInt("${KEY_ENDLESS_BEST_SCORE}_$uid", 0).coerceAtLeast(0),
                totalWavesCompleted = prefs.getInt("${KEY_ENDLESS_TOTAL_WAVES}_$uid", 0).coerceAtLeast(0),
                totalRegularKills = prefs.getInt("${KEY_ENDLESS_TOTAL_REGULAR_KILLS}_$uid", 0).coerceAtLeast(0),
                totalBossesDefeated = prefs.getInt("${KEY_ENDLESS_TOTAL_BOSSES_DEFEATED}_$uid", 0).coerceAtLeast(0)
            )
        } else {
            memoryEndlessRecords[uid] ?: EndlessSurvivalRecord()
        }

        val hasLegacyProgress = legacyCp != null ||
            legacyRecord.highestWave > 0 ||
            legacyRecord.bestSurvivalScore > 0 ||
            legacyRecord.totalWavesCompleted > 0 ||
            legacyRecord.totalRegularKills > 0 ||
            legacyRecord.totalBossesDefeated > 0

        if (hasLegacyProgress) {
            val targetEmpire = legacyCp?.activeEmpire ?: EmpireType.DRAGON
            val existingEmp = readEmpireProgressRaw(uid, targetEmpire)
            val migratedKills = maxOf(
                existingEmp.regularKills,
                legacyRecord.totalRegularKills,
                legacyCp?.totalRegularKills ?: 0,
                legacyCp?.kills ?: 0
            )
            val migratedBosses = maxOf(
                existingEmp.bossesDefeated,
                legacyRecord.totalBossesDefeated,
                legacyCp?.bossesDefeated ?: 0
            )
            val migratedWave = maxOf(
                existingEmp.highestWave,
                legacyRecord.highestWave,
                legacyCp?.currentWave ?: 0
            )
            val migratedScore = maxOf(
                existingEmp.bestSurvivalScore,
                legacyRecord.bestSurvivalScore,
                legacyCp?.score ?: 0
            )
            val migratedWavesCompleted = maxOf(
                existingEmp.totalWavesCompleted,
                legacyRecord.totalWavesCompleted,
                legacyCp?.wavesCompleted ?: 0
            )
            val migratedCycle = when {
                legacyCp != null -> legacyCp.cycleKills.coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE)
                existingEmp.cycleKills > 0 -> existingEmp.cycleKills
                else -> (migratedKills % ENDLESS_BOSS_CYCLE_SIZE).coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE)
            }

            writeEmpireProgressRaw(
                uid,
                EmpireEndlessProgress(
                    empire = targetEmpire,
                    regularKills = migratedKills,
                    cycleKills = migratedCycle,
                    bossesDefeated = migratedBosses,
                    highestWave = migratedWave,
                    bestSurvivalScore = migratedScore,
                    totalWavesCompleted = migratedWavesCompleted
                )
            )

            if (legacyCp != null && !legacyRawCp.isNullOrBlank()) {
                val empCpKey = endlessCheckpointKey(uid, targetEmpire)
                if (prefs != null) {
                    val editor = prefs.edit()
                    if (prefs.getString(empCpKey, null).isNullOrBlank()) {
                        editor.putString(empCpKey, legacyRawCp)
                    }
                    if (prefs.getString("${KEY_LAST_ENDLESS_EMPIRE}_$uid", null).isNullOrBlank()) {
                        editor.putString("${KEY_LAST_ENDLESS_EMPIRE}_$uid", targetEmpire.name)
                    }
                    editor.apply()
                } else {
                    memoryEndlessCheckpoints.putIfAbsent(empCpKey, legacyRawCp)
                    memoryLastEndlessEmpire.putIfAbsent(uid, targetEmpire)
                }
            }
        }

        if (prefs != null) {
            prefs.edit().putBoolean("${KEY_ENDLESS_V2_MIGRATED}_$uid", true).apply()
        } else {
            memoryEndlessMigratedUids.add(uid)
        }
    }

    @Synchronized
    fun saveEmpireEndlessRecord(
        uid: String,
        empire: EmpireType,
        highestWaveReached: Int,
        survivalScore: Int,
        completedWavesCount: Int = 0,
        totalRegularKills: Int = 0,
        cycleKills: Int? = null,
        totalBossesDefeated: Int = 0
    ): EndlessSurvivalRecord {
        ensureLegacyEndlessMigrated(uid)
        val currentEmpire = readEmpireProgressRaw(uid, empire)
        val incomingKills = totalRegularKills.coerceAtLeast(0)
        val nextEmpKills = maxOf(currentEmpire.regularKills, incomingKills)
        val nextEmpCycle = when {
            cycleKills == null -> currentEmpire.cycleKills
            incomingKills >= currentEmpire.regularKills -> cycleKills.coerceIn(0, ENDLESS_BOSS_CYCLE_SIZE)
            else -> currentEmpire.cycleKills
        }
        val updatedEmp = EmpireEndlessProgress(
            empire = empire,
            regularKills = nextEmpKills,
            cycleKills = nextEmpCycle,
            bossesDefeated = maxOf(currentEmpire.bossesDefeated, totalBossesDefeated.coerceAtLeast(0)),
            highestWave = maxOf(currentEmpire.highestWave, highestWaveReached.coerceAtLeast(0)),
            bestSurvivalScore = maxOf(currentEmpire.bestSurvivalScore, survivalScore.coerceAtLeast(0)),
            totalWavesCompleted = maxOf(currentEmpire.totalWavesCompleted, completedWavesCount.coerceAtLeast(0))
        )
        writeEmpireProgressRaw(uid, updatedEmp)

        val currentAgg = loadEndlessRecord(uid)
        val empMap = EmpireType.entries.associateWith {
            if (it == empire) updatedEmp else currentAgg.progressFor(it)
        }
        val nextHighestWave = maxOf(currentAgg.highestWave, empMap.values.maxOfOrNull { it.highestWave } ?: 0)
        val nextBestScore = maxOf(currentAgg.bestSurvivalScore, empMap.values.maxOfOrNull { it.bestSurvivalScore } ?: 0)
        val nextTotalWaves = maxOf(currentAgg.totalWavesCompleted, empMap.values.sumOf { it.totalWavesCompleted })
        val nextCappedTotalKills = EmpireType.entries.sumOf { empMap.getValue(it).cappedChallengeKills }
        val nextTotalBosses = EmpireType.entries.sumOf { empMap.getValue(it).bossesDefeated }

        val updated = EndlessSurvivalRecord(
            highestWave = nextHighestWave,
            bestSurvivalScore = nextBestScore,
            totalWavesCompleted = nextTotalWaves,
            totalRegularKills = nextCappedTotalKills,
            totalBossesDefeated = nextTotalBosses,
            empireProgress = empMap
        )
        if (prefs != null) {
            prefs.edit()
                .putInt("${KEY_ENDLESS_HIGHEST_WAVE}_$uid", updated.highestWave)
                .putInt("${KEY_ENDLESS_BEST_SCORE}_$uid", updated.bestSurvivalScore)
                .putInt("${KEY_ENDLESS_TOTAL_WAVES}_$uid", updated.totalWavesCompleted)
                .putInt("${KEY_ENDLESS_TOTAL_REGULAR_KILLS}_$uid", updated.totalRegularKills)
                .putInt("${KEY_ENDLESS_TOTAL_BOSSES_DEFEATED}_$uid", updated.totalBossesDefeated)
                .apply()
        } else {
            memoryEndlessRecords[uid] = updated
        }
        return updated
    }

    @Synchronized
    fun saveEndlessRecord(
        uid: String,
        highestWaveReached: Int,
        survivalScore: Int,
        completedWavesCount: Int = 0,
        totalRegularKills: Int = 0,
        totalBossesDefeated: Int = 0,
        empire: EmpireType = EmpireType.DRAGON,
        cycleKills: Int? = null
    ): EndlessSurvivalRecord {
        return saveEmpireEndlessRecord(
            uid = uid,
            empire = empire,
            highestWaveReached = highestWaveReached,
            survivalScore = survivalScore,
            completedWavesCount = completedWavesCount,
            totalRegularKills = totalRegularKills,
            cycleKills = cycleKills,
            totalBossesDefeated = totalBossesDefeated
        )
    }

    @Synchronized
    fun loadEndlessRecord(uid: String): EndlessSurvivalRecord {
        ensureLegacyEndlessMigrated(uid)
        val empMap = EmpireType.entries.associateWith { readEmpireProgressRaw(uid, it) }
        val anyEmpireData = empMap.values.any {
            it.regularKills > 0 || it.bossesDefeated > 0 || it.highestWave > 0 ||
                it.bestSurvivalScore > 0 || it.totalWavesCompleted > 0 || it.cycleKills > 0
        }
        return if (prefs != null) {
            val flatWave = prefs.getInt("${KEY_ENDLESS_HIGHEST_WAVE}_$uid", 0).coerceAtLeast(0)
            val flatScore = prefs.getInt("${KEY_ENDLESS_BEST_SCORE}_$uid", 0).coerceAtLeast(0)
            val flatWaves = prefs.getInt("${KEY_ENDLESS_TOTAL_WAVES}_$uid", 0).coerceAtLeast(0)
            val flatKills = prefs.getInt("${KEY_ENDLESS_TOTAL_REGULAR_KILLS}_$uid", 0).coerceAtLeast(0)
            val flatBosses = prefs.getInt("${KEY_ENDLESS_TOTAL_BOSSES_DEFEATED}_$uid", 0).coerceAtLeast(0)
            EndlessSurvivalRecord(
                highestWave = if (anyEmpireData) maxOf(flatWave, empMap.values.maxOf { it.highestWave }) else flatWave,
                bestSurvivalScore = if (anyEmpireData) maxOf(flatScore, empMap.values.maxOf { it.bestSurvivalScore }) else flatScore,
                totalWavesCompleted = if (anyEmpireData) maxOf(flatWaves, empMap.values.sumOf { it.totalWavesCompleted }) else flatWaves,
                totalRegularKills = if (anyEmpireData) empMap.values.sumOf { it.cappedChallengeKills } else flatKills,
                totalBossesDefeated = if (anyEmpireData) empMap.values.sumOf { it.bossesDefeated } else flatBosses,
                empireProgress = empMap
            )
        } else {
            val base = memoryEndlessRecords[uid] ?: EndlessSurvivalRecord()
            base.copy(
                highestWave = maxOf(base.highestWave, empMap.values.maxOfOrNull { it.highestWave } ?: 0),
                bestSurvivalScore = maxOf(base.bestSurvivalScore, empMap.values.maxOfOrNull { it.bestSurvivalScore } ?: 0),
                totalWavesCompleted = maxOf(base.totalWavesCompleted, empMap.values.sumOf { it.totalWavesCompleted }),
                totalRegularKills = if (anyEmpireData) empMap.values.sumOf { it.cappedChallengeKills } else base.totalRegularKills,
                totalBossesDefeated = if (anyEmpireData) empMap.values.sumOf { it.bossesDefeated } else base.totalBossesDefeated,
                empireProgress = empMap
            )
        }
    }

    @Synchronized
    fun saveEndlessCheckpoint(uid: String, checkpoint: EndlessCheckpoint) {
        ensureLegacyEndlessMigrated(uid)
        val json = serializeEndlessCheckpoint(checkpoint)
        val empKey = endlessCheckpointKey(uid, checkpoint.activeEmpire)
        if (prefs != null) {
            prefs.edit()
                .putString(empKey, json)
                .putString("${KEY_LAST_ENDLESS_EMPIRE}_$uid", checkpoint.activeEmpire.name)
                .apply()
        } else {
            memoryEndlessCheckpoints[empKey] = json
            memoryLastEndlessEmpire[uid] = checkpoint.activeEmpire
        }
    }

    @Synchronized
    fun loadEndlessCheckpoint(uid: String, empire: EmpireType? = null): EndlessCheckpoint? {
        ensureLegacyEndlessMigrated(uid)
        if (empire != null) {
            val raw = if (prefs != null) {
                prefs.getString(endlessCheckpointKey(uid, empire), null)
            } else {
                memoryEndlessCheckpoints[endlessCheckpointKey(uid, empire)]
            }
            return parseEndlessCheckpoint(raw)
        }

        val lastEmpName = if (prefs != null) {
            prefs.getString("${KEY_LAST_ENDLESS_EMPIRE}_$uid", null)
        } else {
            memoryLastEndlessEmpire[uid]?.name
        }
        val lastEmp = lastEmpName?.let { runCatching { EmpireType.valueOf(it) }.getOrNull() }
        if (lastEmp != null) {
            loadEndlessCheckpointForExactEmpire(uid, lastEmp)?.let { return it }
        }

        return EmpireType.entries
            .mapNotNull { loadEndlessCheckpointForExactEmpire(uid, it) }
            .maxByOrNull { it.savedAtMs }
    }

    private fun loadEndlessCheckpointForExactEmpire(uid: String, empire: EmpireType): EndlessCheckpoint? {
        val raw = if (prefs != null) {
            prefs.getString(endlessCheckpointKey(uid, empire), null)
        } else {
            memoryEndlessCheckpoints[endlessCheckpointKey(uid, empire)]
        }
        return parseEndlessCheckpoint(raw)
    }

    @Synchronized
    fun loadAllEndlessCheckpoints(uid: String): Map<EmpireType, EndlessCheckpoint> {
        ensureLegacyEndlessMigrated(uid)
        return buildMap {
            for (emp in EmpireType.entries) {
                loadEndlessCheckpointForExactEmpire(uid, emp)?.let { put(emp, it) }
            }
        }
    }

    @Synchronized
    fun clearEndlessCheckpoint(uid: String, empire: EmpireType? = null) {
        ensureLegacyEndlessMigrated(uid)
        if (empire != null) {
            val empKey = endlessCheckpointKey(uid, empire)
            if (prefs != null) {
                val editor = prefs.edit().remove(empKey)
                val lastEmpName = prefs.getString("${KEY_LAST_ENDLESS_EMPIRE}_$uid", null)
                if (lastEmpName == empire.name) {
                    editor.remove("${KEY_LAST_ENDLESS_EMPIRE}_$uid")
                }
                editor.apply()
            } else {
                memoryEndlessCheckpoints.remove(empKey)
                if (memoryLastEndlessEmpire[uid] == empire) {
                    memoryLastEndlessEmpire.remove(uid)
                }
            }
            return
        }

        if (prefs != null) {
            val editor = prefs.edit()
                .remove("${KEY_ENDLESS_CHECKPOINT_JSON}_$uid")
                .remove("${KEY_LAST_ENDLESS_EMPIRE}_$uid")
            for (emp in EmpireType.entries) {
                editor.remove(endlessCheckpointKey(uid, emp))
            }
            editor.apply()
        } else {
            memoryEndlessCheckpoints.remove(uid)
            memoryLastEndlessEmpire.remove(uid)
            for (emp in EmpireType.entries) {
                memoryEndlessCheckpoints.remove(endlessCheckpointKey(uid, emp))
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
