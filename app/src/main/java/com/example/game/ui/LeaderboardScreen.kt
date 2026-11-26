package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.model.PlayerProfile
import com.example.game.network.CampaignEmpireProgress
import com.example.game.network.FirestoreCampaignRepository
import com.example.game.network.GlobalCampaignState
import com.example.game.network.LeaderboardRecord
import com.example.game.network.SafeLog
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ThreatRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    profile: PlayerProfile = PlayerProfile(),
    campaignState: GlobalCampaignState = GlobalCampaignState(),
    onBack: () -> Unit,
    onFetchTopLeaderboard: (suspend () -> Result<List<LeaderboardRecord>>)? = null,
    initialTopLeaderboard: List<LeaderboardRecord>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val theaterProgress = listOf(
        campaignState.dragonProgress,
        campaignState.alienProgress,
        campaignState.orcProgress,
        campaignState.humanProgress
    )
    val conqueredHqCount = theaterProgress.count { it.isHeadquartersDestroyed }
    val globalCompletionPct = ((campaignState.totalBarracksDestroyed.toFloat() / 200f) * 100f)
        .toInt()
        .coerceIn(0, 100)

    var topEntries by remember(initialTopLeaderboard) {
        mutableStateOf(initialTopLeaderboard ?: emptyList())
    }
    var isLoadingLeaderboard by remember { mutableStateOf(initialTopLeaderboard == null) }
    var leaderboardError by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger, onFetchTopLeaderboard) {
        if (initialTopLeaderboard != null && refreshTrigger == 0) {
            isLoadingLeaderboard = false
            return@LaunchedEffect
        }
        isLoadingLeaderboard = true
        leaderboardError = null
        val fetcher: suspend () -> Result<List<LeaderboardRecord>> = onFetchTopLeaderboard
            ?: { FirestoreCampaignRepository(context.applicationContext).getTopLeaderboardEntries(5L) }
        val result = runCatching { fetcher() }.getOrElse { Result.failure(it) }
        result.onSuccess { list ->
            topEntries = list.sortedByDescending { it.highScore }.take(5)
            leaderboardError = null
        }.onFailure { err ->
            leaderboardError = SafeLog.safeThrowableSummary(err)
        }
        isLoadingLeaderboard = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        text = "PILOT COMBAT RECORD",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberWarning
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("leaderboard_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refreshTrigger++ },
                        enabled = !isLoadingLeaderboard,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("leaderboard_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Global Leaderboard",
                            tint = CyberCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C121A))
            )
        },
        bottomBar = { com.example.game.ads.AdMobBannerBar() },
        containerColor = GunmetalBlack,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .testTag("leaderboard_scroll_list")
            ) {
                item(key = "dossier_header") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "VERIFIED FIRESTORE SERVICE DOSSIER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.FiberManualRecord,
                                    contentDescription = null,
                                    tint = if (campaignState.isServerConnected) HealthGreen else AmberWarning,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (campaignState.isServerConnected) "UID SYNCED" else "CONNECTING",
                                    fontSize = 10.sp,
                                    color = if (campaignState.isServerConnected) HealthGreen else AmberWarning,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Pilot Verified Summary Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF132030),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.MilitaryTech,
                                            contentDescription = "Pilot Rank",
                                            tint = AmberWarning,
                                            modifier = Modifier.size(26.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = profile.callsign,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "ACTIVE GUNSHIP: ${profile.selectedHelicopterClass.title}",
                                                fontSize = 9.sp,
                                                color = CyberCyan,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    Text(
                                        text = "$globalCompletionPct% WAR",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black,
                                        color = HealthGreen,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    StatTile("HIGH SCORE", "${campaignState.highScore}", AmberWarning, Modifier.weight(1f))
                                    StatTile("CAREER CR", "${campaignState.credits}", MilitaryGold, Modifier.weight(1f))
                                    StatTile("BARRACKS", "${campaignState.totalBarracksDestroyed}/200", HealthGreen, Modifier.weight(1f))
                                    StatTile("HQ CLEARED", "$conqueredHqCount/4", CyberCyan, Modifier.weight(1f))
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0B131E), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = HealthGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "PUBLIC LEADERBOARD: Top 5 rankings expose only callsign, score & conquer totals (zero PII). Personal saves remain strictly owner-isolated.",
                                        fontSize = 9.sp,
                                        color = Color(0xFF90A4AE),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "global_top5_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = "Global Top 5 Pilots",
                                tint = MilitaryGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GLOBAL TOP 5 PILOTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MilitaryGold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "LIVE RANKINGS",
                            fontSize = 9.sp,
                            color = Color(0xFF90A4AE),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                when {
                    isLoadingLeaderboard -> {
                        item(key = "leaderboard_loading") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F1722),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("leaderboard_loading_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = CyberCyan,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "FETCHING TOP 5 GLOBAL PILOTS...",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFCFD8DC)
                                    )
                                }
                            }
                        }
                    }

                    leaderboardError != null -> {
                        item(key = "leaderboard_error") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x22FF1744),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ThreatRed),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("leaderboard_error_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = ThreatRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "RANKINGS UNAVAILABLE (${leaderboardError ?: "NETWORK ERROR"})",
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White
                                        )
                                    }
                                    Button(
                                        onClick = { refreshTrigger++ },
                                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("leaderboard_retry_button")
                                    ) {
                                        Text("RETRY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                    }
                                }
                            }
                        }
                    }

                    topEntries.isEmpty() -> {
                        item(key = "leaderboard_empty") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F1722),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("leaderboard_empty_card")
                            ) {
                                Text(
                                    text = "NO GLOBAL RANKINGS YET — DESTROY BARRACKS TO CLAIM RANK #1.",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF90A4AE),
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }

                    else -> {
                        itemsIndexed(
                            items = topEntries,
                            key = { index, item -> item.id.ifBlank { "rank_${index}_${item.callsign}" } }
                        ) { index, entry ->
                            val isCurrentUser = campaignState.authenticatedUserId.isNotBlank() &&
                                entry.id == campaignState.authenticatedUserId
                            GlobalPilotRankCard(
                                rank = index + 1,
                                entry = entry,
                                isCurrentUser = isCurrentUser
                            )
                        }
                    }
                }

                item(key = "theater_header") {
                    Text(
                        text = "THEATER CONQUEST BREAKDOWN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                items(theaterProgress, key = { it.empire.name }) { progress ->
                    TheaterRecordCard(progress = progress)
                }
            }
        }
    }
}

@Composable
private fun GlobalPilotRankCard(
    rank: Int,
    entry: LeaderboardRecord,
    isCurrentUser: Boolean
) {
    val rankAccent = when (rank) {
        1 -> MilitaryGold
        2 -> Color(0xFFE2E8F0)
        3 -> Color(0xFFFB923C)
        else -> CyberCyan
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isCurrentUser) Color(0xFF142436) else Color(0xFF0F1722),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isCurrentUser || rank == 1) 1.5.dp else 1.dp,
            color = if (isCurrentUser) HealthGreen else rankAccent.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("leaderboard_rank_row_$rank")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(rankAccent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .border(1.dp, rankAccent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#$rank",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = rankAccent
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.callsign.ifBlank { "VIPER-7" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                        if (isCurrentUser) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(HealthGreen, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "YOU",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Text(
                        text = "${entry.totalBarracksDestroyed}/200 BARRACKS • ${entry.hqClearedCount}/4 HQ",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF90A4AE)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.highScore}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = rankAccent
                )
                Text(
                    text = "PTS",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF78909C)
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0C131D), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF233245), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            color = Color(0xFF90A4AE),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TheaterRecordCard(progress: CampaignEmpireProgress) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F1722),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            if (progress.isHeadquartersDestroyed) HealthGreen else Color(0xFF1E2836)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progress.empire.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = progress.empire.primaryColor
                )
                Text(
                    text = when {
                        progress.isHeadquartersDestroyed -> "HQ CONQUERED"
                        progress.isHeadquartersUnlocked -> "BOSS UNLOCKED"
                        else -> "ACTIVE FRONT"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        progress.isHeadquartersDestroyed -> HealthGreen
                        progress.isHeadquartersUnlocked -> AmberWarning
                        else -> ThreatRed
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (progress.isHeadquartersDestroyed) HealthGreen else progress.empire.primaryColor,
                trackColor = Color(0x22FFFFFF)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "FLAGSHIP: ${progress.empire.bossName}",
                    fontSize = 9.sp,
                    color = Color(0xFF90A4AE),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${progress.destroyedCount}/${progress.totalCount} BARRACKS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
