package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.game.ads.AdMobManager
import com.example.game.ads.findActivity
import com.example.game.model.EmpireType
import com.example.game.model.PlayerProfile
import com.example.game.network.NetworkMonitor
import com.example.game.network.OnlineStatus
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
        enableEdgeToEdge()

        networkMonitor = NetworkMonitor(applicationContext)
        AdMobManager.initialize(applicationContext)

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val credentialManager = remember(context) { CredentialManager.create(context) }
                val onlineStatus by networkMonitor.status.collectAsState()
                val flowUser by remember { Firebase.auth.authStateFlow() }
                    .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
                var manualAuthTick by remember { mutableStateOf(0) }
                var autoSignInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val signedInUser = remember(flowUser, manualAuthTick) {
                        flowUser ?: Firebase.auth.currentUser
                    }
                    val isHardwareOffline = onlineStatus is OnlineStatus.OfflineError &&
                        (onlineStatus as OnlineStatus.OfflineError).isHardwareDisconnected
                    if (signedInUser == null || isHardwareOffline) {
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
    val campaignService = remember(user.uid) {
        WarNetCampaignService.getInstance(context.applicationContext)
    }
    val campaignState by campaignService.campaignState.collectAsState()

    val displayCallsign = remember(user.uid, user.displayName, campaignState.authenticatedPilotCallsign) {
        user.displayName?.takeIf { it.isNotBlank() }?.uppercase()?.take(18)
            ?: campaignState.authenticatedPilotCallsign
    }

    LaunchedEffect(user.uid) {
        campaignService.startObserving(
            userId = user.uid,
            callsign = user.displayName?.takeIf { it.isNotBlank() }?.uppercase()?.take(18) ?: "VIPER-7"
        )
    }

    DisposableEffect(user.uid) {
        onDispose {
            campaignService.stopObserving()
        }
    }

    var currentScreen by remember { mutableStateOf(GameScreen.MAIN_MENU) }
    var selectedEmpire by remember { mutableStateOf(EmpireType.DRAGON) }
    var profile by remember { mutableStateOf(PlayerProfile(callsign = displayCallsign)) }
    var adFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val syncedProfile = profile.copy(
        callsign = displayCallsign,
        credits = maxOf(profile.credits, campaignState.credits.toInt()),
        highScore = maxOf(profile.highScore, campaignState.highScore.toInt())
    )

    val triggerRewardedSupplyDrop: (onGranted: (Int) -> Unit, onStatus: (String) -> Unit) -> Unit =
        { onGranted, onStatus ->
            val activity = context.findActivity()
            AdMobManager.showRewardedAd(
                activity = activity,
                onRewardEarned = { bonus ->
                    campaignService.grantRewardedAdSupplyDrop(bonus)
                    profile = syncedProfile.copy(credits = syncedProfile.credits + bonus)
                    onGranted(bonus)
                    onStatus("SUPPLY DROP CREDITED: +$bonus CR")
                },
                onDismissedWithoutReward = {
                    onStatus("WATCH FULL AD TO CLAIM +${AdMobManager.REWARDED_CREDITS_BONUS} CR")
                },
                onUnavailable = {
                    onStatus("LOADING REWARDED AD... TRY AGAIN IN A MOMENT")
                }
            )
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
                onPlayWar = { currentScreen = GameScreen.GAMEPLAY },
                onEmpireCampaign = { currentScreen = GameScreen.CAMPAIGN },
                onEndlessBattle = {
                    selectedEmpire = EmpireType.values().random()
                    currentScreen = GameScreen.GAMEPLAY
                },
                onGarage = { currentScreen = GameScreen.GARAGE },
                onWeaponsUpgrades = { currentScreen = GameScreen.GARAGE },
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
            GamePlayScreen(
                selectedEmpire = selectedEmpire,
                profile = syncedProfile,
                onClaimDebriefAdBonus = { onGranted, onStatus ->
                    triggerRewardedSupplyDrop(onGranted, onStatus)
                },
                onReturnToMenu = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.CAMPAIGN -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            CampaignScreen(
                currentEmpire = selectedEmpire,
                onSelectEmpire = { selectedEmpire = it },
                onDeploy = { empire ->
                    selectedEmpire = empire
                    currentScreen = GameScreen.GAMEPLAY
                },
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.GARAGE -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            GarageScreen(
                profile = syncedProfile,
                onProfileUpdated = { updated -> profile = updated },
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
                onBack = { currentScreen = GameScreen.MAIN_MENU },
                modifier = modifier
            )
        }

        GameScreen.LEADERBOARD -> {
            BackHandler { currentScreen = GameScreen.MAIN_MENU }
            LeaderboardScreen(
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
