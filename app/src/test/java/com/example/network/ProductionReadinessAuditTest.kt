package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.game.engine.GameEngine
import com.example.game.model.EmpireType
import com.example.game.network.BarracksDestructionResult
import com.example.game.network.CampaignEmpireProgress
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.GlobalCampaignState
import com.example.game.network.NetworkMonitor
import com.example.game.network.OnlineStatus
import com.example.game.network.SortieCheckpoint
import com.example.game.network.WarNetCampaignService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities

class ProductionReadinessAuditTest : FirestoreEmulatorTestBase() {

    private suspend fun awaitStatus(
        monitor: NetworkMonitor,
        predicate: (OnlineStatus) -> Boolean
    ): OnlineStatus = withTimeout(5000L) {
        while (!predicate(monitor.status.value)) {
            delay(20L)
        }
        monitor.status.value
    }

    @Test
    fun networkMonitor_rejectsOfflineAndUnvalidated_andHandlesDisconnectReconnect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)

        var probeLatency: Long? = 28L
        val monitor = NetworkMonitor(context, pingProbe = { probeLatency })
        try {
            // 1. Offline hardware (no active network) -> strictly OfflineError
            shadowCm.setDefaultNetworkActive(false)
            shadowCm.setActiveNetworkInfo(null)
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val emptyCaps = ShadowNetworkCapabilities.newInstance()
                shadowCm.setNetworkCapabilities(activeNet, emptyCaps)
            }
            monitor.triggerConnectionVerification()
            val offline = awaitStatus(monitor) { it is OnlineStatus.OfflineError } as OnlineStatus.OfflineError
            assertEquals("ERR_OFFLINE_FORBIDDEN", offline.errorCode)
            assertTrue(offline.isHardwareDisconnected)

            // 2. Unvalidated network (INTERNET present, VALIDATED missing) -> stays OfflineError
            val net = cm.activeNetwork
            if (net != null) {
                val unvalidated = ShadowNetworkCapabilities.newInstance()
                Shadows.shadowOf(unvalidated).apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }
                shadowCm.setNetworkCapabilities(net, unvalidated)
                monitor.confirmOnlineFromAuth()
                val unvalErr = awaitStatus(monitor) { it is OnlineStatus.OfflineError } as OnlineStatus.OfflineError
                assertEquals("ERR_AUTH_TIMEOUT", unvalErr.errorCode)

                // 3. Validated network + working server probe -> transitions to Connected
                val validated = ShadowNetworkCapabilities.newInstance()
                Shadows.shadowOf(validated).apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }
                shadowCm.setNetworkCapabilities(net, validated)
                monitor.triggerConnectionVerification()
                val connected = awaitStatus(monitor) { it is OnlineStatus.Connected } as OnlineStatus.Connected
                assertEquals(28L, connected.latencyMs)

                // 4. Disconnection mid-gameplay via NetworkCallback.onLost -> immediately OfflineError
                shadowCm.networkCallbacks.forEach { it.onLost(net) }
                val dropped = awaitStatus(monitor) { it is OnlineStatus.OfflineError } as OnlineStatus.OfflineError
                assertEquals("ERR_NET_LOST", dropped.errorCode)
                assertTrue(dropped.isHardwareDisconnected)

                // 5. Reconnection during gameplay -> transitions cleanly back to Connected
                shadowCm.networkCallbacks.forEach { it.onAvailable(net) }
                val reconnected = awaitStatus(monitor) { it is OnlineStatus.Connected } as OnlineStatus.Connected
                assertEquals(28L, reconnected.latencyMs)
            }
        } finally {
            monitor.cleanup()
        }
    }

    @Test
    fun networkMonitor_pingTimeoutEmitsAuthTimeoutOfflineError() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val net = cm.activeNetwork
        if (net != null) {
            val validated = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(validated).apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            shadowCm.setNetworkCapabilities(net, validated)
        }

        val monitor = NetworkMonitor(context, pingProbe = { null })
        try {
            monitor.triggerConnectionVerification()
            val err = awaitStatus(monitor) { it is OnlineStatus.OfflineError } as OnlineStatus.OfflineError
            assertEquals("ERR_AUTH_TIMEOUT", err.errorCode)
            assertFalse(err.isHardwareDisconnected)
        } finally {
            monitor.cleanup()
        }
    }

    @Test
    fun networkMonitor_preventsDuplicateAndStaleVerificationOverwrites_andTransitionsOfflineToAuthenticatingToOnline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val net = cm.activeNetwork
        if (net != null) {
            val validated = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(validated).apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }
            shadowCm.setNetworkCapabilities(net, validated)
        }

        val firstSlowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowFirstSlowFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val probeCalls = java.util.concurrent.atomic.AtomicInteger(0)

        val monitor = NetworkMonitor(
            context = context,
            pingProbe = {
                val callNum = probeCalls.incrementAndGet()
                if (callNum == 1) {
                    firstSlowStarted.complete(Unit)
                    allowFirstSlowFinish.await()
                    null // Slow stale probe fails; must never overwrite newer generation
                } else {
                    19L
                }
            }
        )
        try {
            // Wait until the slow initial verification is in-flight
            withTimeout(2000L) { firstSlowStarted.await() }
            assertEquals(OnlineStatus.Authenticating, monitor.status.value)

            // Trigger a superseding verification while the first is still blocked
            monitor.triggerConnectionVerification()
            assertEquals(OnlineStatus.Authenticating, monitor.status.value)

            // Let the stale first probe finish with null after the second succeeds with 19ms
            val connected = awaitStatus(monitor) { it is OnlineStatus.Connected } as OnlineStatus.Connected
            assertEquals(19L, connected.latencyMs)

            allowFirstSlowFinish.complete(Unit)
            delay(60L)
            assertTrue("Stale generation must not overwrite Connected", monitor.status.value is OnlineStatus.Connected)
            assertEquals(19L, (monitor.status.value as OnlineStatus.Connected).latencyMs)

            // Simulate offline -> confirmOnlineFromAuth() cancels in-flight verification -> Authenticating -> Connected
            if (net != null) {
                shadowCm.setNetworkCapabilities(net, ShadowNetworkCapabilities.newInstance())
                monitor.confirmOnlineFromAuth()
                val offline = monitor.status.value as OnlineStatus.OfflineError
                assertEquals("ERR_OFFLINE_FORBIDDEN", offline.errorCode)

                val validated = ShadowNetworkCapabilities.newInstance()
                Shadows.shadowOf(validated).apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }
                shadowCm.setNetworkCapabilities(net, validated)
                monitor.triggerConnectionVerification()
                assertEquals(OnlineStatus.Authenticating, monitor.status.value)
                val reconnected = awaitStatus(monitor) { it is OnlineStatus.Connected } as OnlineStatus.Connected
                assertEquals(19L, reconnected.latencyMs)
            }
        } finally {
            monitor.cleanup()
        }
    }

    @Test
    fun stageProgression_ignoresLocalCacheWhenCampaignStatePresent_evenIfDisconnected() {
        val mgr = CampaignProgressionManager(prefs = null)
        val uid = "pilot_tamper_test"
        mgr.markStageCompleted(uid, EmpireType.DRAGON)
        mgr.markStageCompleted(uid, EmpireType.ALIEN)
        mgr.markStageCompleted(uid, EmpireType.ORC)

        // Even while Firebase snapshot is disconnected or failing (isServerConnected = false),
        // non-null GlobalCampaignState MUST remain strictly server-authoritative.
        val disconnectedOrFailingState = GlobalCampaignState(
            dragonProgress = CampaignEmpireProgress(EmpireType.DRAGON, 0, 50, false, false),
            isServerConnected = false,
            syncErrorMessage = "PERMISSION_DENIED"
        )
        assertFalse(mgr.isStageCompleted(EmpireType.DRAGON, disconnectedOrFailingState, uid))
        assertTrue(mgr.isStageUnlocked(EmpireType.DRAGON, disconnectedOrFailingState, uid))
        assertFalse(mgr.isStageUnlocked(EmpireType.ALIEN, disconnectedOrFailingState, uid))
        assertFalse(mgr.isStageUnlocked(EmpireType.ORC, disconnectedOrFailingState, uid))
        assertFalse(mgr.isStageUnlocked(EmpireType.HUMAN, disconnectedOrFailingState, uid))
    }

    @Test
    fun checkpointRestore_cannotBypassServerLockWithTamperedHqOrBarracks() = runBlocking {
        signInTestUser("audit_checkpoint@test.com")
        val service = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        withTimeout(5000L) { service.syncFromCloud("AUDITOR").getOrThrow() }

        val engine = GameEngine(selectedEmpire = EmpireType.DRAGON, campaignService = service)
        val forgedBarracksIds = engine.allBarracks.map { it.id }.toSet()
        val tamperedCheckpoint = SortieCheckpoint(
            empire = EmpireType.DRAGON,
            stageNumber = 1,
            playerX = 0f,
            playerY = 980f,
            hearts = 5,
            hitsRemainingInHeart = 10,
            score = 99999,
            kills = 50,
            creditsEarned = 10000,
            destroyedBarracksIds = forgedBarracksIds,
            bossSpawned = true,
            bossDefeated = true,
            bossHitsRemaining = 0,
            hqUnlocked = true,
            hqHitsRemaining = 1
        )

        engine.restoreFromCheckpoint(tamperedCheckpoint)

        assertEquals(0, engine.barracksDestroyedCount)
        assertFalse("Tampered checkpoint must not unlock HQ", engine.enemyHeadquarters!!.isUnlocked)
        assertFalse("Tampered checkpoint must not mark boss defeated", engine.enemyHeadquarters!!.isBossDefeated)
        engine.destroyHeadquarters()
        assertFalse("HQ cannot be destroyed via tampered checkpoint", engine.isVictory)
    }

    @Test
    fun warNetService_rejectsUnauthenticatedWrites_andRollsBackDeniedHqWrites() = runBlocking {
        // Unauthenticated write attempt is rejected immediately
        auth.signOut()
        val unauthService = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        val unauthEngine = GameEngine(selectedEmpire = EmpireType.HUMAN, campaignService = unauthService)
        val unauthOutcome = unauthService.recordBarracksDestruction(unauthEngine.allBarracks[0])
        assertEquals(BarracksDestructionResult.AlreadyDestroyed, unauthOutcome)
        assertFalse(unauthService.isBarracksDestroyed(unauthEngine.allBarracks[0].id))

        // Signed in user with 0 barracks cannot unlock HQ
        signInTestUser("audit_rollback@test.com")
        val authedService = WarNetCampaignService.createForTest(firestore, auth, databaseId)
        withTimeout(5000L) { authedService.syncFromCloud("VIPER-7").getOrThrow() }

        authedService.recordHeadquartersDestruction(EmpireType.DRAGON)
        assertFalse(authedService.isHeadquartersDestroyed(EmpireType.DRAGON))

        val prematureAsync = withTimeout(5000L) {
            authedService.recordHeadquartersDestructionAsync(EmpireType.DRAGON)
        }
        assertTrue(prematureAsync.isFailure)
        assertFalse(authedService.isHeadquartersDestroyed(EmpireType.DRAGON))
    }
}
