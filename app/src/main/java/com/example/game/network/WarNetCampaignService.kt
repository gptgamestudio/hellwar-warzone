package com.example.game.network

import android.content.Context
import com.example.R
import com.example.game.engine.EnemyBarracks
import com.example.game.model.EmpireType
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Collections

sealed class BarracksDestructionResult {
    data class FirstTimeDestroyed(
        val creditsAwarded: Int,
        val scoreAwarded: Int,
        val newTotalInEmpire: Int
    ) : BarracksDestructionResult()

    object AlreadyDestroyed : BarracksDestructionResult()
}

data class CampaignEmpireProgress(
    val empire: EmpireType,
    val destroyedCount: Int,
    val totalCount: Int = 50,
    val isHeadquartersUnlocked: Boolean,
    val isHeadquartersDestroyed: Boolean
) {
    val progressPercent: Float get() = (destroyedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    val isComplete: Boolean get() = destroyedCount >= totalCount && isHeadquartersDestroyed
}

data class GlobalCampaignState(
    val humanProgress: CampaignEmpireProgress = CampaignEmpireProgress(EmpireType.HUMAN, 0, 50, false, false),
    val orcProgress: CampaignEmpireProgress = CampaignEmpireProgress(EmpireType.ORC, 0, 50, false, false),
    val dragonProgress: CampaignEmpireProgress = CampaignEmpireProgress(EmpireType.DRAGON, 0, 50, false, false),
    val alienProgress: CampaignEmpireProgress = CampaignEmpireProgress(EmpireType.ALIEN, 0, 50, false, false),
    val totalBarracksDestroyed: Int = 0,
    val totalBarracksAcrossAllEmpires: Int = 200,
    val authenticatedPilotCallsign: String = "VIPER-7",
    val authenticatedUserId: String = "",
    val credits: Long = 18500L,
    val highScore: Long = 0L,
    val isServerConnected: Boolean = false,
    val serverCluster: String = "CLOUD FIRESTORE",
    val syncErrorMessage: String? = null
)

/**
 * Production Cloud Campaign Service backed by Firebase Authentication (Google Sign-In)
 * and Google Cloud Firestore (named enterprise database from R.string.firestore_database_id).
 */
class WarNetCampaignService private constructor(
    val repository: FirestoreCampaignRepository,
    private val auth: FirebaseAuth = Firebase.auth,
    private val databaseId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    var currentCallsign: String = "VIPER-7"
        private set

    private val destroyedBarracksIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val destroyedHqEmpires: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private var profileCredits: Long = 18500L
    private var profileHighScore: Long = 0L
    private var activeObservedUid: String? = null
    private var observerJobs: MutableList<Job> = mutableListOf()

    private val _campaignState = MutableStateFlow(buildState(isConnected = false))
    val campaignState: StateFlow<GlobalCampaignState> = _campaignState.asStateFlow()

    companion object {
        const val BARRACKS_PER_EMPIRE = 50
        const val TOTAL_CAMPAIGN_BARRACKS = 200

        @Volatile
        private var instance: WarNetCampaignService? = null

        fun getInstance(context: Context): WarNetCampaignService {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                val dbId = appContext.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val repo = FirestoreCampaignRepository(firestore, Firebase.auth)
                instance ?: WarNetCampaignService(
                    repository = repo,
                    auth = Firebase.auth,
                    databaseId = dbId
                ).also { instance = it }
            }
        }

        fun createForTest(
            firestore: FirebaseFirestore,
            auth: FirebaseAuth,
            databaseId: String = "test-db"
        ): WarNetCampaignService {
            return WarNetCampaignService(
                repository = FirestoreCampaignRepository(firestore, auth),
                auth = auth,
                databaseId = databaseId
            )
        }
    }

    /**
     * Attaches real-time Firestore snapshot listeners ONLY for an authenticated Google user.
     * Never queries Firestore when auth.currentUser is null.
     */
    fun startObserving(userId: String, callsign: String = currentCallsign) {
        val signedInUid = auth.currentUser?.uid
        if (signedInUid == null || signedInUid != userId) {
            return
        }
        if (activeObservedUid == userId && observerJobs.any { it.isActive }) {
            return
        }

        stopObserving()
        activeObservedUid = userId
        currentCallsign = callsign.ifBlank { "VIPER-7" }.take(32)

        observerJobs += scope.launch {
            repository.ensureUserProfile(currentCallsign)
        }

        observerJobs += scope.launch {
            repository.observeProfile(userId)
                .catch { e ->
                    if (auth.currentUser?.uid == userId) {
                        _campaignState.value = buildState(isConnected = false, error = e.localizedMessage)
                    }
                }
                .collect { profile ->
                    if (profile != null) {
                        currentCallsign = profile.callsign.ifBlank { currentCallsign }
                        profileCredits = profile.credits
                        profileHighScore = profile.highScore
                        _campaignState.value = buildState(isConnected = true)
                    }
                }
        }

        observerJobs += scope.launch {
            repository.observeDestroyedBarracks(userId)
                .catch { e ->
                    if (auth.currentUser?.uid == userId) {
                        _campaignState.value = buildState(isConnected = false, error = e.localizedMessage)
                    }
                }
                .collect { records ->
                    synchronized(destroyedBarracksIds) {
                        destroyedBarracksIds.clear()
                        records.forEach { record ->
                            val id = record.barracksId.ifBlank { record.id }
                            if (id.isNotBlank()) destroyedBarracksIds.add(id)
                        }
                    }
                    _campaignState.value = buildState(isConnected = true)
                }
        }

        observerJobs += scope.launch {
            repository.observeHqVictories(userId)
                .catch { e ->
                    if (auth.currentUser?.uid == userId) {
                        _campaignState.value = buildState(isConnected = false, error = e.localizedMessage)
                    }
                }
                .collect { records ->
                    synchronized(destroyedHqEmpires) {
                        destroyedHqEmpires.clear()
                        records.forEach { record ->
                            val emp = record.empire.ifBlank { record.id }
                            if (emp.isNotBlank()) destroyedHqEmpires.add(emp)
                        }
                    }
                    _campaignState.value = buildState(isConnected = true)
                }
        }
    }

    /**
     * Tears down all snapshot listeners and clears user-scoped session state on Sign-Out.
     */
    fun stopObserving() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()
        activeObservedUid = null
        destroyedBarracksIds.clear()
        destroyedHqEmpires.clear()
        profileCredits = 18500L
        profileHighScore = 0L
        currentCallsign = "VIPER-7"
        _campaignState.value = buildState(isConnected = false)
    }

    /**
     * Performs a direct authenticated one-shot synchronization from Cloud Firestore.
     */
    suspend fun syncFromCloud(callsign: String = currentCallsign): Result<GlobalCampaignState> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Authentication required before syncing campaign."))
        currentCallsign = callsign.ifBlank { currentCallsign }.take(32)

        val profileRes = repository.ensureUserProfile(currentCallsign)
        if (profileRes.isFailure) {
            return Result.failure(profileRes.exceptionOrNull()!!)
        }
        profileRes.getOrNull()?.let {
            currentCallsign = it.callsign
            profileCredits = it.credits
            profileHighScore = it.highScore
        }

        val barracksRes = repository.getDestroyedBarracksOnce(uid)
        if (barracksRes.isFailure) {
            return Result.failure(barracksRes.exceptionOrNull()!!)
        }
        val hqRes = repository.getHqVictoriesOnce(uid)
        if (hqRes.isFailure) {
            return Result.failure(hqRes.exceptionOrNull()!!)
        }

        synchronized(destroyedBarracksIds) {
            destroyedBarracksIds.clear()
            barracksRes.getOrThrow().forEach { rec ->
                val id = rec.barracksId.ifBlank { rec.id }
                if (id.isNotBlank()) destroyedBarracksIds.add(id)
            }
        }
        synchronized(destroyedHqEmpires) {
            destroyedHqEmpires.clear()
            hqRes.getOrThrow().forEach { rec ->
                val emp = rec.empire.ifBlank { rec.id }
                if (emp.isNotBlank()) destroyedHqEmpires.add(emp)
            }
        }

        val state = buildState(isConnected = true)
        _campaignState.value = state
        return Result.success(state)
    }

    fun isBarracksDestroyed(barracksId: String): Boolean {
        return synchronized(destroyedBarracksIds) { destroyedBarracksIds.contains(barracksId) }
    }

    fun getDestroyedCount(empire: EmpireType): Int {
        val prefix = "BARRACKS_${empire.name}_"
        return synchronized(destroyedBarracksIds) {
            destroyedBarracksIds.count { it.startsWith(prefix) }
        }
    }

    fun isHeadquartersUnlocked(empire: EmpireType): Boolean {
        return getDestroyedCount(empire) >= BARRACKS_PER_EMPIRE
    }

    fun isHeadquartersDestroyed(empire: EmpireType): Boolean {
        return synchronized(destroyedHqEmpires) { destroyedHqEmpires.contains(empire.name) }
    }

    /**
     * Synchronous combat-loop hook: deduplicates immediately via synced state and
     * persists atomically to Cloud Firestore on IO dispatcher.
     * Rejects unauthenticated callers and rolls back local state if Firestore denies the write.
     */
    @Synchronized
    fun recordBarracksDestruction(barracks: EnemyBarracks): BarracksDestructionResult {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _campaignState.value = buildState(
                isConnected = false,
                error = "Authentication required to record barracks destruction."
            )
            return BarracksDestructionResult.AlreadyDestroyed
        }

        val added = synchronized(destroyedBarracksIds) {
            if (destroyedBarracksIds.contains(barracks.id)) {
                false
            } else {
                destroyedBarracksIds.add(barracks.id)
                true
            }
        }
        if (!added) {
            return BarracksDestructionResult.AlreadyDestroyed
        }

        val newCount = getDestroyedCount(barracks.empire)
        _campaignState.value = buildState(isConnected = true)

        scope.launch {
            val res = repository.recordBarracksDestruction(
                barracksId = barracks.id,
                empire = barracks.empire.name,
                barracksIndex = barracks.index,
                damageDealt = barracks.maxHealth.toDouble().coerceAtLeast(320.0)
            )
            if (res.isFailure) {
                synchronized(destroyedBarracksIds) {
                    destroyedBarracksIds.remove(barracks.id)
                }
                val syncRes = syncFromCloud(currentCallsign)
                if (syncRes.isFailure) {
                    _campaignState.value = buildState(
                        isConnected = false,
                        error = res.exceptionOrNull()?.localizedMessage ?: "Failed to verify barracks with Cloud Firestore."
                    )
                }
            }
        }

        return BarracksDestructionResult.FirstTimeDestroyed(
            creditsAwarded = barracks.rewardCredits,
            scoreAwarded = barracks.scoreReward,
            newTotalInEmpire = newCount
        )
    }

    /**
     * Suspend API for deterministic persistence & security rules verification.
     */
    suspend fun recordBarracksDestructionAsync(barracks: EnemyBarracks): Result<BarracksDestructionResult> {
        val res = repository.recordBarracksDestruction(
            barracksId = barracks.id,
            empire = barracks.empire.name,
            barracksIndex = barracks.index,
            damageDealt = barracks.maxHealth.toDouble().coerceAtLeast(320.0)
        )
        res.onSuccess { outcome ->
            when (outcome) {
                is BarracksDestructionResult.FirstTimeDestroyed -> {
                    synchronized(destroyedBarracksIds) {
                        destroyedBarracksIds.add(barracks.id)
                    }
                    profileCredits += outcome.creditsAwarded.toLong()
                    profileHighScore += outcome.scoreAwarded.toLong()
                    _campaignState.value = buildState(isConnected = true)
                }
                is BarracksDestructionResult.AlreadyDestroyed -> {
                    synchronized(destroyedBarracksIds) {
                        destroyedBarracksIds.add(barracks.id)
                    }
                    _campaignState.value = buildState(isConnected = true)
                }
            }
        }
        return res
    }

    @Synchronized
    fun recordHeadquartersDestruction(empire: EmpireType) {
        val currentUser = auth.currentUser
        if (currentUser == null || !isHeadquartersUnlocked(empire)) return
        val wasAdded = synchronized(destroyedHqEmpires) {
            destroyedHqEmpires.add(empire.name)
        }
        if (!wasAdded) return
        _campaignState.value = buildState(isConnected = true)

        scope.launch {
            val res = repository.recordHeadquartersVictory(
                empire = empire.name,
                bossId = "BOSS_${empire.name}"
            )
            if (res.isFailure) {
                synchronized(destroyedHqEmpires) {
                    destroyedHqEmpires.remove(empire.name)
                }
                val syncRes = syncFromCloud(currentCallsign)
                if (syncRes.isFailure) {
                    _campaignState.value = buildState(
                        isConnected = false,
                        error = res.exceptionOrNull()?.localizedMessage ?: "Failed to verify HQ victory with Cloud Firestore."
                    )
                }
            }
        }
    }

    suspend fun recordHeadquartersDestructionAsync(empire: EmpireType): Result<Unit> {
        val res = repository.recordHeadquartersVictory(
            empire = empire.name,
            bossId = "BOSS_${empire.name}"
        )
        res.onSuccess {
            val added = synchronized(destroyedHqEmpires) {
                destroyedHqEmpires.add(empire.name)
            }
            if (added) {
                profileCredits += 2500L
                profileHighScore += 15000L
            }
            _campaignState.value = buildState(isConnected = true)
        }
        return res
    }

    @Synchronized
    fun grantRewardedAdSupplyDrop(creditsAmount: Int = com.example.game.ads.AdMobManager.REWARDED_CREDITS_BONUS) {
        val currentUser = auth.currentUser ?: return
        val safeBonus = creditsAmount.coerceIn(1, 2500)
        profileCredits += safeBonus.toLong()
        _campaignState.value = buildState(isConnected = true)

        scope.launch {
            val res = repository.awardSupplyDropCredits(safeBonus.toLong())
            if (res.isFailure) {
                profileCredits = (profileCredits - safeBonus.toLong()).coerceAtLeast(0L)
                val syncRes = syncFromCloud(currentCallsign)
                if (syncRes.isFailure) {
                    _campaignState.value = buildState(
                        isConnected = false,
                        error = res.exceptionOrNull()?.localizedMessage ?: "Failed to credit supply drop in Cloud Firestore."
                    )
                }
            } else {
                res.getOrNull()?.let { authoritativeCredits ->
                    profileCredits = authoritativeCredits
                    _campaignState.value = buildState(isConnected = auth.currentUser?.uid == currentUser.uid)
                }
            }
        }
    }

    fun getProgressForEmpire(empire: EmpireType): CampaignEmpireProgress {
        val state = _campaignState.value
        return when (empire) {
            EmpireType.HUMAN -> state.humanProgress
            EmpireType.ORC -> state.orcProgress
            EmpireType.DRAGON -> state.dragonProgress
            EmpireType.ALIEN -> state.alienProgress
        }
    }

    private fun buildState(isConnected: Boolean, error: String? = null): GlobalCampaignState {
        val humanCount = getDestroyedCount(EmpireType.HUMAN)
        val orcCount = getDestroyedCount(EmpireType.ORC)
        val dragonCount = getDestroyedCount(EmpireType.DRAGON)
        val alienCount = getDestroyedCount(EmpireType.ALIEN)

        val uid = auth.currentUser?.uid.orEmpty()
        return GlobalCampaignState(
            humanProgress = CampaignEmpireProgress(
                empire = EmpireType.HUMAN,
                destroyedCount = humanCount,
                isHeadquartersUnlocked = humanCount >= BARRACKS_PER_EMPIRE,
                isHeadquartersDestroyed = isHeadquartersDestroyed(EmpireType.HUMAN)
            ),
            orcProgress = CampaignEmpireProgress(
                empire = EmpireType.ORC,
                destroyedCount = orcCount,
                isHeadquartersUnlocked = orcCount >= BARRACKS_PER_EMPIRE,
                isHeadquartersDestroyed = isHeadquartersDestroyed(EmpireType.ORC)
            ),
            dragonProgress = CampaignEmpireProgress(
                empire = EmpireType.DRAGON,
                destroyedCount = dragonCount,
                isHeadquartersUnlocked = dragonCount >= BARRACKS_PER_EMPIRE,
                isHeadquartersDestroyed = isHeadquartersDestroyed(EmpireType.DRAGON)
            ),
            alienProgress = CampaignEmpireProgress(
                empire = EmpireType.ALIEN,
                destroyedCount = alienCount,
                isHeadquartersUnlocked = alienCount >= BARRACKS_PER_EMPIRE,
                isHeadquartersDestroyed = isHeadquartersDestroyed(EmpireType.ALIEN)
            ),
            totalBarracksDestroyed = humanCount + orcCount + dragonCount + alienCount,
            authenticatedPilotCallsign = currentCallsign,
            authenticatedUserId = uid,
            credits = profileCredits,
            highScore = profileHighScore,
            isServerConnected = isConnected && uid.isNotEmpty(),
            serverCluster = "FIRESTORE/$databaseId",
            syncErrorMessage = error
        )
    }
}
