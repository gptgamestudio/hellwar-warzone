package com.example.network

import com.example.base.FirestoreEmulatorTestBase
import com.example.game.engine.GameEngine
import com.example.game.model.EmpireType
import com.example.game.network.BarracksDestructionResult
import com.example.game.network.FirestoreCampaignRepository
import com.example.game.network.LeaderboardRecord
import com.example.game.network.WarNetCampaignService
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WarNetCampaignRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun unauthenticatedCaller_rejectedWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = FirestoreCampaignRepository(firestore, auth)

        // Repository write must fail fast before any unauthenticated write
        val writeRes = repository.recordBarracksDestruction(
            barracksId = "BARRACKS_HUMAN_1",
            empire = "HUMAN",
            barracksIndex = 1,
            damageDealt = 320.0
        )
        assertTrue(writeRes.isFailure)

        // Direct Firestore server read & list without auth MUST fail with PERMISSION_DENIED
        var readException: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document("unauth_target").get().await()
            }
        } catch (e: Exception) {
            readException = e.unwrapFirestoreException()
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, readException?.code)

        var listException: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document("unauth_target")
                    .collection("destroyed_barracks").get().await()
            }
        } catch (e: Exception) {
            listException = e.unwrapFirestoreException()
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, listException?.code)
    }

    @Test
    fun crossUserIsolation_aliceCannotReadOrWriteBobsProgress() = runBlocking {
        val aliceEmail = "alice_${UUID.randomUUID()}@test.com"
        val bobEmail = "bob_${UUID.randomUUID()}@test.com"

        val aliceUid = signInTestUser(aliceEmail)
        val repo = FirestoreCampaignRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("ALICE-1").getOrThrow()
            repo.recordBarracksDestruction("BARRACKS_HUMAN_1", "HUMAN", 1, 320.0).getOrThrow()
        }

        // Switch active auth session to Bob
        signInTestUser(bobEmail)
        val bobProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("BOB-2").getOrThrow()
        }
        assertFalse(bobProfile.userId == aliceUid)

        // Bob attempting to read or write Alice's documents MUST fail with PERMISSION_DENIED
        val aliceReadRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getDestroyedBarracksOnce(aliceUid) }
        assertTrue(aliceReadRes.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            aliceReadRes.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val aliceTamperRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.recordBarracksDestruction("BARRACKS_HUMAN_2", "HUMAN", 2, 320.0, targetUserId = aliceUid)
        }
        assertTrue(aliceTamperRes.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            aliceTamperRes.exceptionOrNull()?.unwrapFirestoreException()?.code
        )
    }

    @Test
    fun twoDeviceSynchronization_andAntiDuplicateRewards_verifiedAgainstFirestoreEmulator() = runBlocking {
        val sharedAccountEmail = "pilot_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(sharedAccountEmail)

        // Device A logs in and destroys 5 Human Empire barracks
        val deviceAService = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        withTimeout(DEFAULT_TIMEOUT_MS) { deviceAService.syncFromCloud("VIPER-7").getOrThrow() }

        val deviceAEngine = GameEngine(selectedEmpire = EmpireType.HUMAN, campaignService = deviceAService)
        for (i in 0 until 5) {
            val outcome = withTimeout(DEFAULT_TIMEOUT_MS) {
                deviceAService.recordBarracksDestructionAsync(deviceAEngine.allBarracks[i]).getOrThrow()
            }
            assertTrue(outcome is BarracksDestructionResult.FirstTimeDestroyed)
        }
        assertEquals(5, deviceAService.getDestroyedCount(EmpireType.HUMAN))

        // Anti-duplicate guard: re-destroying BARRACKS_HUMAN_1 returns AlreadyDestroyed and gives zero extra credits
        val dupOutcome = withTimeout(DEFAULT_TIMEOUT_MS) {
            deviceAService.recordBarracksDestructionAsync(deviceAEngine.allBarracks[0]).getOrThrow()
        }
        assertEquals(BarracksDestructionResult.AlreadyDestroyed, dupOutcome)

        // Direct tamper/overwrite attempt on immutable barracks doc MUST fail at Firestore rules layer
        var tamperError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid)
                    .collection("destroyed_barracks").document("BARRACKS_HUMAN_1")
                    .update("creditsAwarded", 9999).await()
            }
        } catch (e: Exception) {
            tamperError = e.unwrapFirestoreException()
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, tamperError?.code)

        // Device B logs in using the same authenticated user account (fresh in-memory service)
        val deviceBService = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        val deviceBSyncedState = withTimeout(DEFAULT_TIMEOUT_MS) {
            deviceBService.syncFromCloud("VIPER-7").getOrThrow()
        }
        assertEquals("Device B must restore 5/50 Human barracks from Cloud Firestore", 5, deviceBSyncedState.humanProgress.destroyedCount)

        // Device B verifies GameEngine builds destroyed state accurately and destroys 6th barracks
        val deviceBEngine = GameEngine(selectedEmpire = EmpireType.HUMAN, campaignService = deviceBService)
        for (i in 0 until 5) {
            assertTrue("Barracks #$i must appear destroyed on Device B", deviceBEngine.allBarracks[i].isDestroyed)
        }
        assertFalse("Barracks #5 (6th) must still stand", deviceBEngine.allBarracks[5].isDestroyed)

        val sixthOutcome = withTimeout(DEFAULT_TIMEOUT_MS) {
            deviceBService.recordBarracksDestructionAsync(deviceBEngine.allBarracks[5]).getOrThrow()
        }
        assertTrue(sixthOutcome is BarracksDestructionResult.FirstTimeDestroyed)
        assertEquals(6, deviceBService.getDestroyedCount(EmpireType.HUMAN))

        // Device A reconnects & syncs updated 6/50 count from Cloud Firestore
        val deviceAResync = withTimeout(DEFAULT_TIMEOUT_MS) {
            deviceAService.syncFromCloud("VIPER-7").getOrThrow()
        }
        assertEquals("Device A must receive Device B's 6th barracks destruction", 6, deviceAResync.humanProgress.destroyedCount)
        assertTrue(deviceAService.isBarracksDestroyed(deviceBEngine.allBarracks[5].id))
    }

    @Test
    fun headquartersVictory_rejectedBelow50_allowedStrictlyAt50() = runBlocking {
        val email = "hq_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val service = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        withTimeout(DEFAULT_TIMEOUT_MS) { service.syncFromCloud("VIPER-7").getOrThrow() }

        val dragonEngine = GameEngine(selectedEmpire = EmpireType.DRAGON, campaignService = service)

        // Destroy 49 Dragon barracks in Cloud Firestore
        for (i in 0 until 49) {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                service.recordBarracksDestructionAsync(dragonEngine.allBarracks[i]).getOrThrow()
            }
        }
        assertEquals(49, service.getDestroyedCount(EmpireType.DRAGON))
        assertFalse(service.isHeadquartersUnlocked(EmpireType.DRAGON))

        // HQ victory at 49/50 MUST be rejected by Firestore Security Rules
        val prematureVictory = withTimeout(DEFAULT_TIMEOUT_MS) {
            service.recordHeadquartersDestructionAsync(EmpireType.DRAGON)
        }
        assertTrue(prematureVictory.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            prematureVictory.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        // Destroy 50th barracks and confirm HQ victory succeeds
        withTimeout(DEFAULT_TIMEOUT_MS) {
            service.recordBarracksDestructionAsync(dragonEngine.allBarracks[49]).getOrThrow()
        }
        assertEquals(50, service.getDestroyedCount(EmpireType.DRAGON))
        assertTrue(service.isHeadquartersUnlocked(EmpireType.DRAGON))

        val finalVictory = withTimeout(DEFAULT_TIMEOUT_MS) {
            service.recordHeadquartersDestructionAsync(EmpireType.DRAGON)
        }
        assertTrue("HQ victory must succeed strictly at 50/50", finalVictory.isSuccess)

        // Fresh observer reads HQ destruction persisted on server
        val verifierService = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        val verifiedState = withTimeout(DEFAULT_TIMEOUT_MS) {
            verifierService.syncFromCloud("VIPER-7").getOrThrow()
        }
        assertTrue(verifiedState.dragonProgress.isHeadquartersDestroyed)
    }

    @Test
    fun publicLeaderboard_syncsFromBarracksDestruction_readableUnauthenticatedTop5_blocksInflation() = runBlocking {
        val email = "ace_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val service = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        withTimeout(DEFAULT_TIMEOUT_MS) { service.syncFromCloud("HAWK-9").getOrThrow() }

        val engine = GameEngine(selectedEmpire = EmpireType.HUMAN, campaignService = service)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            service.recordBarracksDestructionAsync(engine.allBarracks[0]).getOrThrow()
        }

        // Spoofed score in /leaderboard MUST fail security rules
        var spoofError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("leaderboard").document(uid).set(
                    mapOf(
                        "callsign" to "HAWK-9",
                        "highScore" to 99999999L,
                        "totalBarracksDestroyed" to 1L,
                        "hqClearedCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: Exception) {
            spoofError = e.unwrapFirestoreException()
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, spoofError?.code)

        // Unauthenticated visitor CAN query Top 5 leaderboard sorted descending
        auth.signOut()
        val topSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection("leaderboard")
                .orderBy("highScore", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .await()
        }
        val records = topSnap.toObjects(LeaderboardRecord::class.java)
        val syncedEntry = records.firstOrNull { it.id == uid }
        assertTrue("Synced pilot must appear in public Top 5 query", syncedEntry != null)
        assertEquals("HAWK-9", syncedEntry!!.callsign)
        assertEquals(1000L, syncedEntry.highScore)
        assertEquals(1L, syncedEntry.totalBarracksDestroyed)
        assertEquals(0L, syncedEntry.hqClearedCount)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10000L
    }
}
