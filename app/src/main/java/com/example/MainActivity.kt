package com.example

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.findActivity
import com.example.game.engine.GameMode
import com.example.game.model.EmpireType
import com.example.game.model.PlayerProfile
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.CloudAuthorityRepository
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.EndlessSurvivalRecord
import com.example.game.network.HangarPersistenceManager
import com.example.game.network.NetworkMonitor
import com.example.game.network.OnlineStatus
import com.example.game.network.RewardSettlementStatus
import com.example.game.network.SafeLog
import com.example.game.network.SortieCheckpoint
import com.example.game.network.WarNetCampaignService
import com.example.game.network.attemptAutoSignIn
import com.example.game.network.authStateFlow
import com.example.game.network.signOutFromGoogle
import com.example.game.ui.CampaignScreen
import com.example.game.ui.CommandScreen
import com.example.game.ui.ConnectionGateScreen
import com.example.game.ui.GamePlayScreen
import com.example.game.ui.GarageScreen
import com.example.game.ui.LeaderboardScreen
import com.example.game.ui.MainMenuScreen
import com.example.game.ui.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

enum class GameScreen {
    CONNECTION_GATE,
    MAIN_MENU,
    GAMEPLAY,
    CAMPAIGN,
    GARAGE,
    COMMAND,
    LEADERBOARD,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()

        networkMonitor = NetworkMonitor(applicationContext)
        AdConsentManager.gatherConsent(this) { canRequest ->
            if (canRequest) {
                AdMobManager.initialize(applicationContext)
            }
        }

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val credentialManager = remember(context) { CredentialManager.create(context) }
                val onlineStatus by networkMonitor.status.collectAsState()
                val canRequestAds by AdConsentManager.canRequestAds.collectAsState()
                val flowUser by remember { Firebase.auth.authStateFlow() }
                    .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
                var manualAuthTick by remember { mutableIntStateOf(0) }
                var autoSignInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

                // Initialize/reload AdMob inventory only when online AND user consent permits ads
                LaunchedEffect(onlineStatus, canRequestAds) {
                    if (canRequestAds) {
                        AdMobManager.initialize(applicationContext)
                        if (onlineStatus is OnlineStatus.Connected) {
                            AdMobManager.ensureAdsLoaded(applicationContext)
                        }
                    }
                }

                // Attempt silent auto-sign-in for previously authorized Google users
                LaunchedEffect(Unit) {
                    if (Firebase.auth.currentUser == null) {
                        autoSignInJob = attemptAutoSignIn(
                            context = this@MainActivity,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                manualAuthTick++
                                networkMonitor.confirmOnlineFromAuth()
                            },
                            onUnauthenticated = {},
                            scope = lifecycleScope
                        )
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    val signedInUser = remember(flowUser, manualAuthTick) {
                        flowUser ?: Firebase.auth.currentUser
                    }
                    if (signedInUser == null || onlineStatus !is OnlineStatus.Connected) {
                        ConnectionGateScreen(
                            onlineStatus = onlineStatus,
                            currentUser = signedInUser,
                            onRetry = { networkMonitor.triggerConnectionVerification() },
                            onEnterGame = {
                                manualAuthTick++
                                networkMonitor.confirmOnlineFromAuth()
                            },
                            onSignInStarted = {
                                autoSignInJob?.cancel()
                                autoSignInJob = null
                            },
                            onAuthSuccess = {
                                manualAuthTick++
                                networkMonitor.confirmOnlineFromAuth()
                            },
                            onSignOut = {
                                signOutFromGoogle(credentialManager, lifecycleScope) {
                                    manualAuthTick++
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        // Key authenticated theater state strictly by UID so account switches reset UI cleanly
                        key(signedInUser.uid) {
                            AuthenticatedGameNavigation(
                                user = signedInUser,
                                onlineStatus = onlineStatus,
                                onRetryConnection = { networkMonitor.triggerConnectionVerification() },
                                onSignOut = {
                                    signOutFromGoogle(credentialManager, lifecycleScope) {
                                        manualAuthTick++
                                    }
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.cleanup()
    }
}

@Composable
private fun AuthenticatedGameNavigation(
    user: FirebaseUser,
    onlineStatus: OnlineStatus,
    onRetryConnection: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val campaignService = remember(user.uid) {
        WarNetCampaignService.getInstance(context.applicationContext)
    }
    val progressionManager = remember(context) {
        CampaignProgressionManager.getInstance(context.applicationContext)
    }
    val hangarPersistence = remember(context) {
        HangarPersistenceManager.getInstance(context.applicationContext)
    }
    val cloudAuthority = remember(context) {
        CloudAuthorityRepository.getInstance(context.applicationContext)
    }
    val campaignState by campaignService.campaignState.collectAsState()
    val cloudUiState by cloudAuthority.state.collectAsState()

    val displayCallsign = remember(user.uid, user.displayName, campaignState.authenticatedPilotCallsign) {
        user.displayName?.takeIf { it.isNotBlank() }?.uppercase()?.take(18)
            ?: campaignState.authenticatedPilotCallsign
    }

    LaunchedEffect(user.uid) {
        campaignService.startObserving(
            userId = user.uid,
            callsign = user.displayName?.takeIf { it.isNotBlank() }?.uppercase()?.take(18) ?: "VIPER-7"
        )
        cloudAuthority.startObserving(user.uid)
    }

    DisposableEffect(user.uid) {
        onDispose {
            campaignService.stopObserving()
            cloudAuthority.stopObserving()
        }
    }

    var currentScreen by remember { mutableStateOf(GameScreen.MAIN_MENU) }
    var activeGameMode by remember(user.uid) { mutableStateOf(GameMode.CAMPAIGN) }
    var garageInitialTab by remember { mutableIntStateOf(0) }
    var progressionTick by remember { mutableIntStateOf(0) }
    val highestUnlockedEmpire = remember(campaignState, progressionTick, user.uid) {
        progressionManager.getHighestUnlockedEmpire(campaignState, user.uid)
    }
    val highestStageNumber = progressionManager.getStageNumber(highestUnlockedEmpire)
    var selectedEmpire by remember { mutableStateOf(highestUnlockedEmpire) }
    var savedCheckpoint by remember(user.uid) { mutableStateOf(progressionManager.loadCheckpoint(user.uid)) }
    var activeResumeCheckpoint by remember(user.uid) { mutableStateOf<SortieCheckpoint?>(null) }
    var savedEndlessCheckpoint by remember(user.uid) { mutableStateOf(progressionManager.loadEndlessCheckpoint(user.uid)) }
    var activeEndlessResumeCheckpoint by remember(user.uid) { mutableStateOf<EndlessCheckpoint?>(null) }
    var localEndlessRecord by remember(user.uid) { mutableStateOf(progressionManager.loadEndlessRecord(user.uid)) }
    var sortieSessionId by remember(user.uid) { mutableIntStateOf(0) }
    var localHangarCache by remember(user.uid) { mutableStateOf(hangarPersistence.loadState(user.uid)) }
    var adFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val effectiveEndlessRecord = remember(localEndlessRecord, cloudUiState.endlessStats) {
        EndlessSurvivalRecord(
            highestWave = maxOf(localEndlessRecord.highestWave, cloudUiState.endlessStats.highestWave),
            bestSurvivalScore = maxOf(localEndlessRecord.bestSurvivalScore, cloudUiState.endlessStats.bestSurvivalScore),
            totalWavesCompleted = maxOf(localEndlessRecord.totalWavesCompleted, cloudUiState.endlessStats.totalWavesCompleted)
        )
    }

    val effectiveHangarState = remember(cloudUiState, localHangarCache) {
        if (cloudUiState.isLoadedFromCloud) cloudUiState.hangarState else localHangarCache
    }
    val adContinueQuota = remember(cloudUiState, progressionTick, user.uid) {
        if (cloudUiState.isLoadedFromCloud) {
            cloudUiState.toAdContinueQuota()
        } else {
            progressionManager.getAdContinueQuota(user.uid)
        }
    }

    val availableCredits = remember(campaignState.credits, effectiveHangarState.creditsSpent) {
        (campaignState.credits.toInt() - effectiveHangarState.creditsSpent).coerceAtLeast(0)
    }
    val syncedProfile = remember(displayCallsign, availableCredits, campaignState.highScore, effectiveHangarState) {
        PlayerProfile(
            callsign = displayCallsign,
            credits = availableCredits,
            highScore = campaignState.highScore.toInt(),
            selectedHelicopterClass = effectiveHangarState.selectedHelicopterClass,
            selectedSkin = effectiveHangarState.selectedSkin,
            unlockedHelicopters = effectiveHangarState.unlockedHelicopters.toMutableSet(),
            upgradesMap = effectiveHangarState.upgradesByClass.mapValues { (_, up) -> up.copy() }.toMutableMap()
        )
    }

    val refreshProgression: () -> Unit = {
        savedCheckpoint = progressionManager.loadCheckpoint(user.uid)
        savedEndlessCheckpoint = progressionManager.loadEndlessCheckpoint(user.uid)
        localEndlessRecord = progressionManager.loadEndlessRecord(user.uid)
        progressionTick++
    }

    val triggerRewardedContinueAd: (onContinued: () -> Unit, onStatus: (String) -> Unit) -> Unit =
        { onContinued, onStatus ->
            if (!adContinueQuota.canWatchAdToContinue) {
                onStatus("DAILY 10/10 CONTINUE LIMIT REACHED (RESETS IN 24H)")
            } else {
                val activity = context.findActivity()
                coroutineScope.launch {
                    onStatus("VERIFYING SECURE AD SESSION...")
                    val prep = cloudAuthority.prepareRewardedSession("AD_CONTINUE")
                    if (prep.isFailure) {
                        val summary = SafeLog.safeThrowableSummary(prep.exceptionOrNull())
                        onStatus(
                            if (summary.contains("limit", ignoreCase = true)) {
                                "DAILY 10/10 CONTINUE LIMIT REACHED (RESETS IN 24H)"
                            } else {
                                "SERVER SSV SESSION UNAVAILABLE — CHECK CONNECTION"
                            }
                        )
                        return@launch
                    }
                    val session = prep.getOrThrow()
                    AdMobManager.showRewardedAd(
                        activity = activity,
                        ssvUserId = user.uid,
                        ssvCustomData = session.nonceId,
                        onRewardEarned = {
                            onStatus("VERIFYING AD REWARD WITH CLOUD SERVER...")
                            coroutineScope.launch {
                                val settlementRes = cloudAuthority.awaitRewardedSettlement(session.nonceId)
                                val outcome = settlementRes.getOrNull()
                                if (outcome != null &&
                                    outcome.status == RewardSettlementStatus.VERIFIED_SETTLED &&
                                    outcome.continueGranted
                                ) {
                                    progressionTick++
                                    onContinued()
                                    onStatus("+5 MAIN HEARTS RESTORED! (${outcome.adContinueRemaining}/10 LEFT TODAY)")
                                } else if (outcome?.status == RewardSettlementStatus.PENDING_SSV || settlementRes.isFailure) {
                                    onStatus("AD WATCH RECORDED — WAITING FOR SERVER SSV CONFIRMATION")
                                } else {
                                    onStatus("CONTINUE REWARD NOT AUTHORIZED (${outcome?.status ?: "REJECTED"})")
                                }
                            }
                        },
                        onDismissedWithoutReward = {
                            onStatus("WATCH FULL AD TO CONTINUE WITH +5 HEARTS")
                        },
                        onUnavailable = {
                            onStatus("LOADING CONTINUE AD... TRY AGAIN IN A MOMENT")
                        }
                    )
                }
            }
        }

    val triggerRewardedSupplyDrop: (onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit =
        { onGranted, onStatus ->
            val activity = context.findActivity()
            coroutineScope.launch {
                onStatus("PREPARING SSV SUPPLY DROP...")
                val prep = cloudAuthority.prepareRewardedSession("SUPPLY_DROP")
                if (prep.isFailure) {
                    onStatus("UNABLE TO VERIFY REWARDED SESSION — CHECK CONNECTION")
                    return@launch
                }
                val session = prep.getOrThrow()
                AdMobManager.showRewardedAd(
                    activity = activity,
                    ssvUserId = user.uid,
                    ssvCustomData = session.nonceId,
                    onRewardEarned = {
                        onStatus("CONFIRMING SUPPLY DROP WITH CLOUD SERVER...")
                        coroutineScope.launch {
                            val settlementRes = cloudAuthority.awaitRewardedSettlement(session.nonceId)
                            val outcome = settlementRes.getOrNull()
                            if (outcome != null &&
                                outcome.status == RewardSettlementStatus.VERIFIED_SETTLED &&
                                outcome.settledCredits > 0
                            ) {
                                campaignService.syncFromCloud(displayCallsign)
                                onGranted(outcome.settledCredits)
                                onStatus("SUPPLY DROP CREDITED: +${outcome.settledCredits} CR")
                            } else if (outcome?.status == RewardSettlementStatus.PENDING_SSV || settlementRes.isFailure) {
                                onStatus("AD VERIFIED — SERVER SSV SETTLEMENT PENDING")
                            } else {
                                onStatus("SUPPLY DROP REWARD NOT AUTHORIZED BY SERVER")
                            }
                        }
                    },
                    onDismissedWithoutReward = {
                        onStatus("WATCH FULL AD TO CLAIM +${AdMobManager.REWARDED_CREDITS_BONUS} CR")
                    },
                    onUnavailable = {
                        onStatus("LOADING REWARDED AD... TRY AGAIN IN A MOMENT")
                    }
                )
            }
        }

    when (currentScreen) {
        GameScreen.CONNECTION_GATE -> {
            ConnectionGateScreen(
                onlineStatus = onlineStatus,
                currentUser = user,
                onRetry = onRetryConnection,
                onEnterGame = { currentScreen = GameScreen.MAIN_MENU },
                onSignOut = {
                    campaignService.stopObserving()
                    onSignOut()
                },
                modifier = modifier
            )
        }

        GameScreen.MAIN_MENU -> {
            MainMenuScreen(
                profile = syncedProfile,
                resumeCheckpoint = savedCheckpoint,
                highestUnlockedEmpire = highestUnlockedEmpire,
                highestStageNumber = highestStageNumber,
                endlessResumeCheckpoint = savedEndlessCheckpoint,
                endlessRecord = effectiveEndlessRecord,
                onContinueEndless = {
                    val endlessCp = progressionManager.loadEndlessCheckpoint(user.uid)
                    activeGameMode = GameMode.ENDLESS_SURVIVAL
                    selectedEmpire = endlessCp?.activeEmpire ?: EmpireType.DRAGON
                    activeResumeCheckpoint = null
                    activeEndlessResumeCheckpoint = endlessCp
                    sortieSessionId++
                    currentScreen = GameScreen.GAMEPLAY
                },
                onContinueSortie = {
                    val cp = progressionManager.loadCheckpoint(user.uid)
                    activeGameMode = GameMode.CAMPAIGN
                    activeEndlessResumeCheckpoint = null
                    if (cp != null && progressionManager.isStageUnlocked(cp.empire, campaignState, user.uid)) {
                        selectedEmpire = cp.empire
                        activeResumeCheckpoint = cp
                    } else {
                        selectedEmpire = highestUnlockedEmpire
                        activeResumeCheckpoint = null
                    }
                    sortieSessionId++
                    currentScreen = GameScreen.GAMEPLAY
                },
                onPlayWar = {
                    activeGameMode = GameMode.CAMPAIGN
                    selectedEmpire = highestUnlockedEmpire
                    activeResumeCheckpoint = null
                    activeEndlessResumeCheckpoint = null
                    sortieSessionId++
                    currentScreen = GameScreen.GAMEPLAY
                },
                onEmpireCampaign = { currentScreen = GameScreen.CAMPAIGN },
                onEndlessBattle = {
                    activeGameMode = GameMode.ENDLESS_SURVIVAL
                    selectedEmpire = EmpireType.DRAGON
                    activeResumeCheckpoint = null
                    activeEndlessResumeCheckpoint = null
                    sortieSessionId++
                    currentScreen = GameScreen.GAMEPLAY
                },
                onGarage = {
                    garageInitialTab = 0
                    currentScreen = GameScreen.GARAGE
                },
                onWeaponsUpgrades = {
                    garageInitialTab = 1
                    currentScreen = GameScreen.GARAGE
                },
                onWarCommand = { currentScreen = GameScreen.COMMAND },
                onLeaderboard = { currentScreen = GameScreen.LEADERBOARD },
                onSettings = { currentScreen = GameScreen.SETTINGS },
                onWatchSupplyAd = {
                    triggerRewardedSupplyDrop({}, { msg -> adFeedbackMessage = msg })
                },
                supplyAdStatus = adFeedbackMessage,
                modifier = modifier
            )
        }

        GameScreen.GAMEPLAY -> {
            key(activeGameMode, selectedEmpire, sortieSessionId) {
                GamePlayScreen(
                    selectedEmpire = selectedEmpire,
                    profile = syncedProfile,
                    resumeCheckpoint = activeResumeCheckpoint,
                    adContinueQuota = adContinueQuota,
                    onSaveCheckpoint = { cp ->
                        progressionManager.saveCheckpoint(user.uid, cp)
                        savedCheckpoint = cp
                    },
                    onClearCheckpoint = {
                        progressionManager.clearCheckpoint(user.uid)
                        savedCheckpoint = null
                    },
                    onWatchAdContinue = triggerRewardedContinueAd,
                    onStageCompleted = { clearedEmpire ->
                        progressionManager.markStageCompleted(user.uid, clearedEmpire)
                        refreshProgression()
                    },
                    onAutoDeployNextStage = { nextEmpire ->
                        progressionManager.markStageCompleted(user.uid, selectedEmpire)
                        progressionManager.clearCheckpoint(user.uid)
                        savedCheckpoint = null
                        activeResumeCheckpoint = null
                        selectedEmpire = nextEmpire
                        sortieSessionId++
                        refreshProgression()
                    },
                    onClaimDebriefAdBonus = { onGranted, onStatus ->
                        triggerRewardedSupplyDrop(onGranted, onStatus)
                    },
                    onReturnToMenu = {
                        refreshProgression()
                        currentScreen = GameScreen.MAIN_MENU
                    },
                    gameMode = activeGameMode,
                    resumeEndlessCheckpoint = activeEndlessResumeCheckpoint,
                    initialEndlessRecord = effectiveEndlessRecord,
                    onSaveEndlessCheckpoint = { endlessCp ->
                        progressionManager.saveEndlessCheckpoint(user.uid, endlessCp)
                        savedEndlessCheckpoint = endlessCp
                    },
                    onClearEndlessCheckpoint = {
                        progressionManager.clearEndlessCheckpoint(user.uid)
                        savedEndlessCheckpoint = null
                    },
                    onUpdateEndlessRecord = { highestWave, bestScore, wavesCompleted ->
                        localEndlessRecord = progressionManager.saveEndlessRecord(
                            uid = user.uid,
                            highestWaveReached = highestWave,
                            survivalScore = bestScore,
                            completedWavesCount = wavesCompleted
                        )
                    },
                    onStartEndlessSession = {
                        cloudAuthority.startEndlessSession()
                    },
                    onClaimEndlessWave = { sid, waveNumber, waveKills ->
                        val res = cloudAuthority.claimEndlessWaveCompletion(sid, waveNumber, waveKills)
                        if (res.isSuccess) {
                            campaignService.syncFromCloud(displayCallsign)
                        }
                        res
                    },
                    modifier = modifier
                )
            }
        }

        GameScreen.CAMPAIGN -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            CampaignScreen(
                currentEmpire = selectedEmpire,
                uid = user.uid,
                onSelectEmpire = { selectedEmpire = it },
                onDeploy = { empire ->
                    if (progressionManager.isStageUnlocked(empire, campaignState, user.uid)) {
                        activeGameMode = GameMode.CAMPAIGN
                        selectedEmpire = empire
                        activeResumeCheckpoint = null
                        activeEndlessResumeCheckpoint = null
                        sortieSessionId++
                        currentScreen = GameScreen.GAMEPLAY
                    }
                },
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.GARAGE -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            GarageScreen(
                profile = syncedProfile,
                initialTab = garageInitialTab,
                isSyncingCloudHangar = cloudUiState.isSyncing,
                quarantineNotice = cloudUiState.quarantineNotice,
                onUnlockHelicopterAsync = { chosen, onDone ->
                    coroutineScope.launch {
                        val res = cloudAuthority.unlockHelicopter(chosen)
                        res.fold(
                            onSuccess = { msg -> onDone(true, msg) },
                            onFailure = { err ->
                                onDone(false, SafeLog.safeThrowableSummary(err))
                            }
                        )
                    }
                },
                onUpgradeComponentAsync = { heloClass, category, onDone ->
                    coroutineScope.launch {
                        val res = cloudAuthority.upgradeComponent(heloClass, category)
                        res.fold(
                            onSuccess = { msg -> onDone(true, msg) },
                            onFailure = { err ->
                                onDone(false, SafeLog.safeThrowableSummary(err))
                            }
                        )
                    }
                },
                onEquipLoadoutAsync = { heloClass, skin, onDone ->
                    coroutineScope.launch {
                        val res = cloudAuthority.equipLoadout(heloClass, skin)
                        res.fold(
                            onSuccess = { msg -> onDone(true, msg) },
                            onFailure = { err ->
                                onDone(false, SafeLog.safeThrowableSummary(err))
                            }
                        )
                    }
                },
                onProfileUpdated = { updated ->
                    localHangarCache = hangarPersistence.applyTransaction(
                        uid = user.uid,
                        previousCredits = availableCredits,
                        updatedProfile = updated
                    )
                },
                onWatchSupplyAd = { onStatus ->
                    triggerRewardedSupplyDrop({}, onStatus)
                },
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.COMMAND -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            CommandScreen(
                campaignState = campaignState,
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.LEADERBOARD -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            LeaderboardScreen(
                profile = syncedProfile,
                campaignState = campaignState,
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.SETTINGS -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            SettingsScreen(
                pilotEmail = user.email ?: user.displayName ?: "Authenticated Pilot",
                pilotUid = user.uid,
                onSignOut = {
                    campaignService.stopObserving()
                    onSignOut()
                },
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }
    }
}
