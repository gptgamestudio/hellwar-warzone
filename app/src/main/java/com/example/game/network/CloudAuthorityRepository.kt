package com.example.game.network

import android.content.Context
import com.example.R
import com.example.game.model.HelicopterClass
import com.example.game.model.HelicopterSkin
import com.example.game.model.HelicopterUpgrades
import com.example.game.ui.UpgradeType
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

data class PreparedRewardSession(
    val nonceId: String,
    val rewardType: String,
    val creditsAmount: Int,
    val adContinueRemaining: Int
)

enum class RewardSettlementStatus {
    VERIFIED_SETTLED,
    PENDING_SSV,
    EXPIRED,
    REJECTED
}

data class RewardedSettlementOutcome(
    val nonceId: String,
    val status: RewardSettlementStatus,
    val rewardType: String,
    val settledCredits: Int,
    val continueGranted: Boolean,
    val availableCredits: Int,
    val adContinueUsedToday: Int,
    val adContinueRemaining: Int
)

data class EndlessCloudStats(
    val highestWave: Int = 0,
    val bestSurvivalScore: Int = 0,
    val totalWavesCompleted: Int = 0,
    val totalEndlessCreditsEarned: Int = 0
)

data class EndlessWaveSettlementOutcome(
    val alreadyClaimed: Boolean,
    val waveNumber: Int,
    val empire: String,
    val nextEmpire: String,
    val creditsAwarded: Int,
    val scoreAwarded: Int,
    val newTotalCredits: Int,
    val sessionScore: Int,
    val highestWave: Int,
    val bestSurvivalScore: Int
)

data class CloudHangarUiState(
    val isLoadedFromCloud: Boolean = false,
    val isSyncing: Boolean = false,
    val hangarState: HangarLocalState = HangarLocalState(),
    val adContinueUsedToday: Int = 0,
    val adContinueRemaining: Int = CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES,
    val adContinueWindowStartMs: Long = 0L,
    val quarantineNotice: String? = null,
    val endlessStats: EndlessCloudStats = EndlessCloudStats()
) {
    fun toAdContinueQuota(nowMs: Long = System.currentTimeMillis()): AdContinueQuota {
        val expired = adContinueWindowStartMs > 0L &&
            (nowMs - adContinueWindowStartMs) >= CampaignProgressionManager.RESET_WINDOW_MS
        val effectiveUsed = if (expired) 0 else adContinueUsedToday
        val effectiveRemaining = (CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES - effectiveUsed)
            .coerceIn(0, CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES)
        val msUntilReset = if (!expired && adContinueWindowStartMs > 0L && effectiveUsed > 0) {
            (CampaignProgressionManager.RESET_WINDOW_MS - (nowMs - adContinueWindowStartMs)).coerceAtLeast(0L)
        } else {
            0L
        }
        return AdContinueQuota(
            usedToday = effectiveUsed,
            maxPer24Hours = CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES,
            remaining = effectiveRemaining,
            msUntilReset = msUntilReset
        )
    }
}

/**
 * Server-authoritative cloud bridge for Hangar transactions, legacy migration, and AdMob SSV verification.
 * Targets the dedicated enterprise named database (`R.string.firestore_database_id`) and Cloud Functions.
 * Client never authorizes rewarded credits or continue quota independently.
 */
class CloudAuthorityRepository private constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val localCache: HangarPersistenceManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow(CloudHangarUiState())
    val state: StateFlow<CloudHangarUiState> = _state.asStateFlow()

    private var activeUid: String? = null
    private var hangarListener: ListenerRegistration? = null
    private var quarantineListener: ListenerRegistration? = null
    private var endlessStatsListener: ListenerRegistration? = null
    private val migrationAttemptedForUid = AtomicBoolean(false)

    fun startObserving(uid: String) {
        val signedIn = auth.currentUser?.uid
        if (signedIn == null || signedIn != uid) return
        if (activeUid == uid && hangarListener != null) return

        stopObserving()
        activeUid = uid
        migrationAttemptedForUid.set(false)
        val cached = localCache.loadState(uid)
        _state.value = CloudHangarUiState(
            isLoadedFromCloud = false,
            isSyncing = true,
            hangarState = cached
        )

        val hangarRef = db.collection("users").document(uid).collection("hangar").document("state")
        val quarantineRef = db.collection("migration_quarantine").document(uid)
        val endlessStatsRef = db.collection("users").document(uid).collection("endless").document("stats")

        hangarListener = hangarRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                SafeLog.w(
                    TAG,
                    "Cloud hangar snapshot error at ${SafeLog.sanitizeFirestorePath(hangarRef.path)}: ${SafeLog.safeThrowableSummary(error)}"
                )
                _state.value = _state.value.copy(isSyncing = false)
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                if (migrationAttemptedForUid.compareAndSet(false, true)) {
                    scope.launch {
                        triggerInitialLegacyMigration(uid, localCache.loadState(uid))
                    }
                } else {
                    _state.value = _state.value.copy(isSyncing = false)
                }
                return@addSnapshotListener
            }

            applyCloudSnapshot(uid, snapshot)
        }

        quarantineListener = quarantineRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                SafeLog.w(TAG, "Quarantine listener warning: ${SafeLog.safeThrowableSummary(error)}")
                return@addSnapshotListener
            }
            val notice = if (snapshot != null && snapshot.exists() && snapshot.getString("status") == "PENDING_REVIEW") {
                "Your legacy helicopters & upgrades were safely preserved while cloud balance reconciliation is under review."
            } else {
                null
            }
            _state.value = _state.value.copy(quarantineNotice = notice)
        }

        endlessStatsListener = endlessStatsRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                SafeLog.w(TAG, "Endless stats listener warning: ${SafeLog.safeThrowableSummary(error)}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                _state.value = _state.value.copy(
                    endlessStats = EndlessCloudStats(
                        highestWave = ((snapshot.get("highestWave") as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                        bestSurvivalScore = ((snapshot.get("bestSurvivalScore") as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                        totalWavesCompleted = ((snapshot.get("totalWavesCompleted") as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                        totalEndlessCreditsEarned = ((snapshot.get("totalEndlessCreditsEarned") as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    fun stopObserving() {
        hangarListener?.remove()
        hangarListener = null
        quarantineListener?.remove()
        quarantineListener = null
        endlessStatsListener?.remove()
        endlessStatsListener = null
        activeUid = null
        migrationAttemptedForUid.set(false)
        _state.value = CloudHangarUiState()
    }

    private suspend fun triggerInitialLegacyMigration(uid: String, legacyLocal: HangarLocalState) {
        try {
            val payload = hashMapOf(
                "creditsSpent" to legacyLocal.creditsSpent,
                "selectedHelicopterClass" to legacyLocal.selectedHelicopterClass.name,
                "selectedSkin" to legacyLocal.selectedSkin.name,
                "unlockedHelicopters" to legacyLocal.unlockedHelicopters.toList(),
                "upgradesByClass" to legacyLocal.upgradesByClass.mapValues { (_, up) ->
                    hashMapOf(
                        "engineLevel" to up.engineLevel,
                        "armorLevel" to up.armorLevel,
                        "weaponLevel" to up.weaponLevel,
                        "defenseLevel" to up.defenseLevel,
                        "radarLevel" to up.radarLevel
                    )
                }
            )
            val result = functions.getHttpsCallable("migrateLegacyHangar").call(payload).await()
            val data = result.data as? Map<*, *>
            val hangarMap = data?.get("hangar") as? Map<*, *>
            if (hangarMap != null) {
                applyCallableHangarMap(uid, hangarMap)
            } else {
                _state.value = _state.value.copy(isSyncing = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w(TAG, "Legacy hangar migration deferred (${SafeLog.safeThrowableSummary(e)})")
            _state.value = _state.value.copy(isSyncing = false)
        }
    }

    suspend fun unlockHelicopter(heloClass: HelicopterClass): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            _state.value = _state.value.copy(isSyncing = true)
            val result = functions.getHttpsCallable("unlockHelicopter")
                .call(hashMapOf("helicopterId" to heloClass.id))
                .await()
            val map = result.data as? Map<*, *>
            if (map != null) {
                applyCallableHangarMap(uid, map)
            }
            Result.success("PURCHASE VALIDATED: ${heloClass.title} UNLOCKED!")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isSyncing = false)
            SafeLog.w(TAG, "unlockHelicopter failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    suspend fun upgradeComponent(heloClass: HelicopterClass, category: UpgradeType): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            _state.value = _state.value.copy(isSyncing = true)
            val result = functions.getHttpsCallable("upgradeHelicopterComponent")
                .call(
                    hashMapOf(
                        "helicopterId" to heloClass.id,
                        "category" to category.name
                    )
                )
                .await()
            val map = result.data as? Map<*, *>
            if (map != null) {
                applyCallableHangarMap(uid, map)
            }
            Result.success("UPGRADE INSTALLED: ${category.name} SYNCED TO CLOUD")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isSyncing = false)
            SafeLog.w(TAG, "upgradeComponent failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    suspend fun equipLoadout(heloClass: HelicopterClass, skin: HelicopterSkin): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            _state.value = _state.value.copy(isSyncing = true)
            val result = functions.getHttpsCallable("equipLoadout")
                .call(
                    hashMapOf(
                        "helicopterId" to heloClass.id,
                        "skin" to skin.name
                    )
                )
                .await()
            val map = result.data as? Map<*, *>
            if (map != null) {
                applyCallableHangarMap(uid, map)
            }
            Result.success("LOADOUT CONFIRMED: ${heloClass.title} (${skin.label.uppercase()})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isSyncing = false)
            SafeLog.w(TAG, "equipLoadout failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    suspend fun startEndlessSession(): Result<String> {
        auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            val result = functions.getHttpsCallable("startEndlessSession")
                .call(emptyMap<String, Any>())
                .await()
            val map = result.data as? Map<*, *>
                ?: return Result.failure(IllegalStateException("Empty endless session response"))
            val sessionId = (map["sessionId"] as? String)?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Missing endless sessionId"))
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w(TAG, "startEndlessSession failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    suspend fun claimEndlessWaveCompletion(
        sessionId: String,
        waveNumber: Int,
        waveKills: Int
    ): Result<EndlessWaveSettlementOutcome> {
        auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            val result = functions.getHttpsCallable("claimEndlessWaveCompletion")
                .call(
                    hashMapOf(
                        "sessionId" to sessionId,
                        "waveNumber" to waveNumber,
                        "waveKills" to waveKills
                    )
                )
                .await()
            val map = result.data as? Map<*, *>
                ?: return Result.failure(IllegalStateException("Empty endless wave response"))
            val outcome = EndlessWaveSettlementOutcome(
                alreadyClaimed = (map["alreadyClaimed"] as? Boolean) ?: false,
                waveNumber = (map["waveNumber"] as? Number)?.toInt() ?: waveNumber,
                empire = (map["empire"] as? String) ?: "DRAGON",
                nextEmpire = (map["nextEmpire"] as? String) ?: "DRAGON",
                creditsAwarded = (map["creditsAwarded"] as? Number)?.toInt() ?: 0,
                scoreAwarded = (map["scoreAwarded"] as? Number)?.toInt() ?: 0,
                newTotalCredits = (map["newTotalCredits"] as? Number)?.toInt() ?: 0,
                sessionScore = (map["sessionScore"] as? Number)?.toInt() ?: 0,
                highestWave = (map["highestWave"] as? Number)?.toInt() ?: waveNumber,
                bestSurvivalScore = (map["bestSurvivalScore"] as? Number)?.toInt() ?: 0
            )
            val prevStats = _state.value.endlessStats
            _state.value = _state.value.copy(
                endlessStats = prevStats.copy(
                    highestWave = maxOf(prevStats.highestWave, outcome.highestWave),
                    bestSurvivalScore = maxOf(prevStats.bestSurvivalScore, outcome.bestSurvivalScore),
                    totalWavesCompleted = if (outcome.alreadyClaimed) prevStats.totalWavesCompleted else prevStats.totalWavesCompleted + 1,
                    totalEndlessCreditsEarned = prevStats.totalEndlessCreditsEarned + outcome.creditsAwarded
                )
            )
            Result.success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w(TAG, "claimEndlessWaveCompletion failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    suspend fun prepareRewardedSession(rewardType: String): Result<PreparedRewardSession> {
        auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        return try {
            val result = functions.getHttpsCallable("prepareRewardedSession")
                .call(hashMapOf("rewardType" to rewardType))
                .await()
            val map = result.data as? Map<*, *>
                ?: return Result.failure(IllegalStateException("Empty reward session response"))
            val nonceId = map["nonceId"] as? String
                ?: return Result.failure(IllegalStateException("Missing SSV nonce"))
            Result.success(
                PreparedRewardSession(
                    nonceId = nonceId,
                    rewardType = (map["rewardType"] as? String) ?: rewardType,
                    creditsAmount = (map["creditsAmount"] as? Number)?.toInt() ?: 0,
                    adContinueRemaining = (map["adContinueRemaining"] as? Number)?.toInt()
                        ?: CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w(TAG, "prepareRewardedSession failed (${SafeLog.safeThrowableSummary(e)})")
            Result.failure(e)
        }
    }

    /**
     * Polls backend SSV settlement ledger strictly without authorizing rewards client-side.
     */
    suspend fun awaitRewardedSettlement(
        nonceId: String,
        maxAttempts: Int = 8,
        pollIntervalMs: Long = 750L
    ): Result<RewardedSettlementOutcome> {
        auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        var lastOutcome: RewardedSettlementOutcome? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                val res = functions.getHttpsCallable("claimRewardedOutcome")
                    .call(hashMapOf("nonceId" to nonceId))
                    .await()
                val map = res.data as? Map<*, *>
                if (map != null) {
                    val outcome = parseOutcomeMap(nonceId, map)
                    lastOutcome = outcome
                    if (outcome.status == RewardSettlementStatus.VERIFIED_SETTLED) {
                        _state.value = _state.value.copy(
                            adContinueUsedToday = outcome.adContinueUsedToday,
                            adContinueRemaining = outcome.adContinueRemaining
                        )
                        return Result.success(outcome)
                    }
                    if (outcome.status == RewardSettlementStatus.EXPIRED ||
                        outcome.status == RewardSettlementStatus.REJECTED
                    ) {
                        return Result.success(outcome)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.w(TAG, "claimRewardedOutcome poll failed (${SafeLog.safeThrowableSummary(e)})")
                if (attempt == maxAttempts - 1) {
                    return Result.failure(e)
                }
            }
            if (attempt < maxAttempts - 1) {
                delay(pollIntervalMs)
            }
        }
        return lastOutcome?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Reward settlement still pending"))
    }

    private fun parseOutcomeMap(nonceId: String, map: Map<*, *>): RewardedSettlementOutcome {
        val rawStatus = (map["status"] as? String).orEmpty()
        val status = runCatching { RewardSettlementStatus.valueOf(rawStatus) }
            .getOrDefault(RewardSettlementStatus.PENDING_SSV)
        return RewardedSettlementOutcome(
            nonceId = nonceId,
            status = status,
            rewardType = (map["rewardType"] as? String).orEmpty(),
            settledCredits = (map["settledCredits"] as? Number)?.toInt() ?: 0,
            continueGranted = (map["continueGranted"] as? Boolean) ?: false,
            availableCredits = (map["availableCredits"] as? Number)?.toInt() ?: 0,
            adContinueUsedToday = (map["adContinueUsedToday"] as? Number)?.toInt() ?: 0,
            adContinueRemaining = (map["adContinueRemaining"] as? Number)?.toInt()
                ?: CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES
        )
    }

    private fun applyCloudSnapshot(uid: String, snapshot: DocumentSnapshot) {
        val rawMap = snapshot.data ?: return
        applyCallableHangarMap(uid, rawMap)
    }

    private fun applyCallableHangarMap(uid: String, raw: Map<*, *>) {
        val spent = ((raw["creditsSpentCloud"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        val className = raw["selectedHelicopterClass"] as? String
        val selectedClass = HelicopterClass.entries.find { it.name == className } ?: HelicopterClass.SCOUT
        val skinName = raw["selectedSkin"] as? String
        val selectedSkin = HelicopterSkin.entries.find { it.name == skinName } ?: HelicopterSkin.TACTICAL_BLACK

        val unlocked = mutableSetOf(HelicopterClass.SCOUT.id)
        val rawUnlocked = raw["unlockedHelicopters"] as? List<*>
        rawUnlocked?.forEach { item ->
            val id = item as? String
            if (!id.isNullOrBlank()) unlocked.add(id)
        }

        val rawUpgrades = raw["upgradesByClass"] as? Map<*, *>
        val upgradesMap = HelicopterClass.entries.associate { helo ->
            val obj = rawUpgrades?.get(helo.id) as? Map<*, *>
            helo.id to HelicopterUpgrades(
                engineLevel = ((obj?.get("engineLevel") as? Number)?.toInt() ?: 1).coerceIn(1, 5),
                armorLevel = ((obj?.get("armorLevel") as? Number)?.toInt() ?: 1).coerceIn(1, 5),
                weaponLevel = ((obj?.get("weaponLevel") as? Number)?.toInt() ?: 1).coerceIn(1, 5),
                defenseLevel = ((obj?.get("defenseLevel") as? Number)?.toInt() ?: 1).coerceIn(1, 5),
                radarLevel = ((obj?.get("radarLevel") as? Number)?.toInt() ?: 1).coerceIn(1, 5)
            )
        }

        val nextHangar = HangarLocalState(
            creditsSpent = spent,
            selectedHelicopterClass = selectedClass,
            selectedSkin = selectedSkin,
            unlockedHelicopters = unlocked,
            upgradesByClass = upgradesMap
        )
        localCache.saveState(uid, nextHangar)

        val usedToday = ((raw["adContinueUsedToday"] as? Number)?.toInt() ?: 0)
            .coerceIn(0, CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES)
        val windowStart = ((raw["adContinueWindowStartMs"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
        val remaining = ((raw["adContinueRemaining"] as? Number)?.toInt()
            ?: (CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES - usedToday))
            .coerceIn(0, CampaignProgressionManager.MAX_DAILY_REWARDED_CONTINUES)
        val qStatus = raw["quarantineStatus"] as? String

        _state.value = _state.value.copy(
            isLoadedFromCloud = true,
            isSyncing = false,
            hangarState = nextHangar,
            adContinueUsedToday = usedToday,
            adContinueRemaining = remaining,
            adContinueWindowStartMs = windowStart,
            quarantineNotice = if (qStatus == "PENDING_REVIEW") {
                "Your legacy helicopters & upgrades were safely preserved while cloud balance reconciliation is under review."
            } else {
                _state.value.quarantineNotice
            }
        )
    }

    companion object {
        private const val TAG = "CloudAuthorityRepo"
        private const val FUNCTIONS_REGION = "us-central1"

        @Volatile
        private var instance: CloudAuthorityRepository? = null

        fun getInstance(context: Context): CloudAuthorityRepository {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                val dbId = appContext.getString(R.string.firestore_database_id)
                instance ?: CloudAuthorityRepository(
                    db = FirebaseFirestore.getInstance(dbId),
                    auth = Firebase.auth,
                    functions = Firebase.functions(FUNCTIONS_REGION),
                    localCache = HangarPersistenceManager.getInstance(appContext)
                ).also { instance = it }
            }
        }
    }
}
