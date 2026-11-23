package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.model.PlayerProfile
import com.example.game.network.CampaignEmpireProgress
import com.example.game.network.GlobalCampaignState
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
    modifier: Modifier = Modifier
) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .padding(16.dp)
            ) {
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

                Spacer(modifier = Modifier.height(12.dp))

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
                                text = "ZERO-TRUST PRIVACY: Cross-player listing is disabled by owner Firestore rules; only your authenticated UID record is synced.",
                                fontSize = 9.sp,
                                color = Color(0xFF90A4AE),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "THEATER CONQUEST BREAKDOWN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(theaterProgress, key = { it.empire.name }) { progress ->
                        TheaterRecordCard(progress = progress)
                    }
                }
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
