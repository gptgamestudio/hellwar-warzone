package com.example.game.network

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreCampaignRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProfile(userId: String): Flow<UserCampaignProfile?> = flow {
        val uid = requireUserId()
        require(uid == userId) { "Cannot observe another user's profile" }
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        UserCampaignProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeDestroyedBarracks(userId: String): Flow<List<DestroyedBarracksRecord>> = flow {
        val uid = requireUserId()
        require(uid == userId) { "Cannot observe another user's barracks records" }
        val colRef = db.collection("users").document(uid).collection("destroyed_barracks")
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DestroyedBarracksRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeHqVictories(userId: String): Flow<List<HeadquartersVictoryRecord>> = flow {
        val uid = requireUserId()
        require(uid == userId) { "Cannot observe another user's HQ victory records" }
        val colRef = db.collection("users").document(uid).collection("hq_victories")
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HeadquartersVictoryRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(callsign: String = "VIPER-7"): Result<UserCampaignProfile> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val safeCallsign = callsign.trim().ifBlank { "VIPER-7" }.take(32).padEnd(2, '7')
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                val existing = snap.toObject(
                    UserCampaignProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) {
                    val lbSnap = db.collection("leaderboard").document(uid).get().await()
                    if (!lbSnap.exists()) {
                        syncPublicLeaderboardEntry(uid)
                    }
                    return Result.success(existing)
                }
            }
            val payload = mapOf(
                "userId" to uid,
                "callsign" to safeCallsign,
                "credits" to 18500L,
                "highScore" to 0L,
                "humanDestroyedCount" to 0L,
                "orcDestroyedCount" to 0L,
                "dragonDestroyedCount" to 0L,
                "alienDestroyedCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            val created = docRef.get().await().toObject(
                UserCampaignProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: UserCampaignProfile(userId = uid, callsign = safeCallsign)
            syncPublicLeaderboardEntry(uid)
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getProfileOnce(targetUserId: String? = null): Result<UserCampaignProfile?> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            Result.success(
                snap.toObject(
                    UserCampaignProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getDestroyedBarracksOnce(targetUserId: String? = null): Result<List<DestroyedBarracksRecord>> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val colRef = db.collection("users").document(uid).collection("destroyed_barracks")
        return try {
            val snap = colRef.get().await()
            Result.success(
                snap.toObjects(
                    DestroyedBarracksRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path)
            Result.failure(e)
        }
    }

    suspend fun getHqVictoriesOnce(targetUserId: String? = null): Result<List<HeadquartersVictoryRecord>> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val colRef = db.collection("users").document(uid).collection("hq_victories")
        return try {
            val snap = colRef.get().await()
            Result.success(
                snap.toObjects(
                    HeadquartersVictoryRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path)
            Result.failure(e)
        }
    }

    suspend fun recordBarracksDestruction(
        barracksId: String,
        empire: String,
        barracksIndex: Int,
        damageDealt: Double,
        targetUserId: String? = null
    ): Result<BarracksDestructionResult> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userRef = db.collection("users").document(uid)
        val barracksRef = userRef.collection("destroyed_barracks").document(barracksId)

        return try {
            // Ensure profile exists prior to atomic destruction transaction
            if (!userRef.get().await().exists()) {
                ensureUserProfile().getOrThrow()
            }

            val result = db.runTransaction { tx ->
                val existingBarracks = tx.get(barracksRef)
                if (existingBarracks.exists()) {
                    return@runTransaction BarracksDestructionResult.AlreadyDestroyed
                }

                val profileSnap = tx.get(userRef)
                val currentCredits = profileSnap.getLong("credits") ?: 18500L
                val currentHighScore = profileSnap.getLong("highScore") ?: 0L
                val humanCount = (profileSnap.getLong("humanDestroyedCount") ?: 0L).toInt()
                val orcCount = (profileSnap.getLong("orcDestroyedCount") ?: 0L).toInt()
                val dragonCount = (profileSnap.getLong("dragonDestroyedCount") ?: 0L).toInt()
                val alienCount = (profileSnap.getLong("alienDestroyedCount") ?: 0L).toInt()

                val newHuman = if (empire == "HUMAN") (humanCount + 1).coerceAtMost(50) else humanCount
                val newOrc = if (empire == "ORC") (orcCount + 1).coerceAtMost(50) else orcCount
                val newDragon = if (empire == "DRAGON") (dragonCount + 1).coerceAtMost(50) else dragonCount
                val newAlien = if (empire == "ALIEN") (alienCount + 1).coerceAtMost(50) else alienCount

                val barracksPayload = mapOf(
                    "userId" to uid,
                    "barracksId" to barracksId,
                    "empire" to empire,
                    "barracksIndex" to barracksIndex.toLong(),
                    "damageDealt" to damageDealt,
                    "creditsAwarded" to 200L,
                    "scoreAwarded" to 1000L,
                    "destroyedAt" to FieldValue.serverTimestamp()
                )
                tx.set(barracksRef, barracksPayload)

                val profileUpdates = mapOf(
                    "credits" to currentCredits + 200L,
                    "highScore" to currentHighScore + 1000L,
                    "humanDestroyedCount" to newHuman.toLong(),
                    "orcDestroyedCount" to newOrc.toLong(),
                    "dragonDestroyedCount" to newDragon.toLong(),
                    "alienDestroyedCount" to newAlien.toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.update(userRef, profileUpdates)

                val updatedTotalForEmpire = when (empire) {
                    "HUMAN" -> newHuman
                    "ORC" -> newOrc
                    "DRAGON" -> newDragon
                    "ALIEN" -> newAlien
                    else -> 0
                }
                BarracksDestructionResult.FirstTimeDestroyed(
                    creditsAwarded = 200,
                    scoreAwarded = 1000,
                    newTotalInEmpire = updatedTotalForEmpire
                )
            }.await()

            if (result is BarracksDestructionResult.FirstTimeDestroyed) {
                syncPublicLeaderboardEntry(uid)
            }

            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, barracksRef.path)
            Result.failure(e)
        }
    }

    suspend fun recordHeadquartersVictory(
        empire: String,
        bossId: String,
        targetUserId: String? = null
    ): Result<Unit> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userRef = db.collection("users").document(uid)
        val hqRef = userRef.collection("hq_victories").document(empire)

        return try {
            val recordedNewVictory = db.runTransaction { tx ->
                val existingHq = tx.get(hqRef)
                if (existingHq.exists()) {
                    return@runTransaction false
                }

                val profileSnap = tx.get(userRef)
                val currentCredits = profileSnap.getLong("credits") ?: 18500L
                val currentHighScore = profileSnap.getLong("highScore") ?: 0L

                val hqPayload = mapOf(
                    "userId" to uid,
                    "empire" to empire,
                    "bossId" to bossId,
                    "creditsAwarded" to 2500L,
                    "scoreAwarded" to 15000L,
                    "victoryAt" to FieldValue.serverTimestamp()
                )
                tx.set(hqRef, hqPayload)

                tx.update(
                    userRef,
                    mapOf(
                        "credits" to currentCredits + 2500L,
                        "highScore" to currentHighScore + 15000L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                true
            }.await()
            if (recordedNewVictory) {
                syncPublicLeaderboardEntry(uid)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, hqRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncPublicLeaderboardEntry(targetUserId: String? = null): Result<Unit> {
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userRef = db.collection("users").document(uid)
        val leaderboardRef = db.collection("leaderboard").document(uid)
        return try {
            val profileSnap = userRef.get().await()
            if (!profileSnap.exists()) {
                return Result.failure(IllegalStateException("User campaign profile does not exist for leaderboard sync."))
            }
            val profile = profileSnap.toObject(
                UserCampaignProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(IllegalStateException("Unable to deserialize user campaign profile."))

            val hqSnap = userRef.collection("hq_victories").get().await()
            val hqClearedCount = hqSnap.documents
                .mapNotNull {
                    it.getString("empire")?.takeIf { emp -> emp.isNotBlank() }
                        ?: it.id.takeIf { id -> id.isNotBlank() }
                }
                .distinct()
                .size
                .coerceIn(0, 4)
                .toLong()

            val totalBarracks = (
                profile.humanDestroyedCount +
                    profile.orcDestroyedCount +
                    profile.dragonDestroyedCount +
                    profile.alienDestroyedCount
                ).coerceIn(0L, 200L)

            val safeCallsign = if (profile.callsign.length in 2..32) {
                profile.callsign
            } else {
                profile.callsign.trim().ifBlank { "VIPER-7" }.take(32).padEnd(2, '7')
            }
            val payload = mapOf(
                "callsign" to safeCallsign,
                "highScore" to profile.highScore.coerceIn(0L, 100_000_000L),
                "totalBarracksDestroyed" to totalBarracks,
                "hqClearedCount" to hqClearedCount,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            leaderboardRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, leaderboardRef.path)
            Result.failure(e)
        }
    }

    suspend fun awardSupplyDropCredits(
        amount: Long,
        targetUserId: String? = null
    ): Result<Long> {
        val safeAmount = amount.coerceIn(1L, 2500L)
        val uid = try {
            targetUserId ?: requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userRef = db.collection("users").document(uid)

        return try {
            if (!userRef.get().await().exists()) {
                ensureUserProfile().getOrThrow()
            }
            val updatedCredits = db.runTransaction { tx ->
                val profileSnap = tx.get(userRef)
                val currentCredits = profileSnap.getLong("credits") ?: 18500L
                val nextCredits = (currentCredits + safeAmount).coerceAtMost(100_000_000L)
                tx.update(
                    userRef,
                    mapOf(
                        "credits" to nextCredits,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                nextCredits
            }.await()
            Result.success(updatedCredits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun getTopLeaderboardEntries(limit: Long = 5L): Result<List<LeaderboardRecord>> {
        val safeLimit = limit.coerceIn(1L, 25L)
        return try {
            val snap = db.collection("leaderboard")
                .orderBy("highScore", Query.Direction.DESCENDING)
                .limit(safeLimit)
                .get()
                .await()
            val records = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    LeaderboardRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            Result.success(records)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "leaderboard")
            Result.failure(e)
        }
    }
}
