package com.example.game.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.ads.AdMobBannerBar
import com.example.game.ads.AdMobManager
import com.example.game.model.PlayerProfile
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ArmorCard
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ThreatRed

@Composable
fun MainMenuScreen(
    profile: PlayerProfile,
    onPlayWar: () -> Unit,
    onEmpireCampaign: () -> Unit,
    onEndlessBattle: () -> Unit,
    onGarage: () -> Unit,
    onWeaponsUpgrades: () -> Unit,
    onWarCommand: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onWatchSupplyAd: () -> Unit = {},
    supplyAdStatus: String? = null,
    modifier: Modifier = Modifier
) {
    val isRewardedReady by AdMobManager.isRewardedReady.collectAsState()

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF090D14))) {
        // Hero Cinematic Background: Combat helicopter in military hangar
        Image(
            painter = painterResource(id = R.drawable.bg_hangar_warfare_1794118009655),
            contentDescription = "Hangar Combat Helicopter",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Dark gradient vignette for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEE090D14),
                            Color(0x77090D14),
                            Color(0xDD090D14),
                            Color(0xFF090D14)
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Top Bar: Player profile, callsign, rank, credits, online indicator
            PlayerProfileHeader(profile = profile)

            Spacer(modifier = Modifier.height(14.dp))

            // Game Logo / Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "HELICOPTER WAR",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = AmberWarning,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "EMPIRE ENDLESS BATTLE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyberCyan,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "VERTICAL COMBAT SIMULATOR // ONLINE CLUSTER ACTIVE",
                    fontSize = 9.sp,
                    color = Color(0xFF90A4AE),
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 1. PRIMARY BUTTON: PLAY WAR
            Button(
                onClick = onPlayWar,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("menu_play_war")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "PLAY WAR",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black
                    )
                    Text(
                        text = "LAUNCH ACTIVE SORTIE NOW",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xCC000000)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. EMPIRE CAMPAIGN
            MenuSecondaryButton(
                icon = Icons.Default.Public,
                title = "EMPIRE CAMPAIGN",
                subtitle = "Dragon, Alien, Orc & Human Warfronts",
                accentColor = CyberCyan,
                onClick = onEmpireCampaign,
                tag = "menu_empire_campaign"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. ENDLESS BATTLE
            MenuSecondaryButton(
                icon = Icons.Default.FlashOn,
                title = "ENDLESS BATTLE",
                subtitle = "Survive Infinite Combat Waves",
                accentColor = ThreatRed,
                onClick = onEndlessBattle,
                tag = "menu_endless_battle"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. HELICOPTER GARAGE
            MenuSecondaryButton(
                icon = Icons.Default.Flight,
                title = "HELICOPTER GARAGE",
                subtitle = "Fleet Hangars, Armor & Specs",
                accentColor = Color(0xFF64B5F6),
                onClick = onGarage,
                tag = "menu_helicopter_garage"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 5. WEAPONS & UPGRADES
            MenuSecondaryButton(
                icon = Icons.Default.Build,
                title = "WEAPONS & UPGRADES",
                subtitle = "Gatling, Hellfire Missiles, Shielding",
                accentColor = MilitaryGold,
                onClick = onWeaponsUpgrades,
                tag = "menu_weapons_upgrades"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 6. WAR COMMAND
            MenuSecondaryButton(
                icon = Icons.Default.Assignment,
                title = "WAR COMMAND",
                subtitle = "Combat Orders, Dossiers & Intel",
                accentColor = Color(0xFFA78BFA),
                onClick = onWarCommand,
                tag = "menu_war_command"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 7. ONLINE LEADERBOARD
            MenuSecondaryButton(
                icon = Icons.Default.Leaderboard,
                title = "ONLINE LEADERBOARD",
                subtitle = "Global Ace Rankings & Statistics",
                accentColor = HealthGreen,
                onClick = onLeaderboard,
                tag = "menu_online_leaderboard"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 8. REWARDED SUPPLY DROP
            MenuSecondaryButton(
                icon = Icons.Default.CardGiftcard,
                title = "SUPPLY AIRDROP (+${AdMobManager.REWARDED_CREDITS_BONUS} CR)",
                subtitle = supplyAdStatus
                    ?: if (isRewardedReady) "Rewarded Video Ready — Boost War Credits"
                    else "Preparing Supply Video Ad...",
                accentColor = MilitaryGold,
                onClick = onWatchSupplyAd,
                tag = "menu_rewarded_supply_ad"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 9. SETTINGS
            MenuSecondaryButton(
                icon = Icons.Default.Settings,
                title = "SETTINGS",
                subtitle = "Audio, Sensitivity, Network Link",
                accentColor = Color(0xFF90A4AE),
                onClick = onSettings,
                tag = "menu_settings"
            )

            Spacer(modifier = Modifier.height(16.dp))
            }

            AdMobBannerBar()
        }
    }
}

@Composable
private fun PlayerProfileHeader(profile: PlayerProfile) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xDD0D1520),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3A4F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF1E2836), CircleShape)
                        .border(1.5.dp, CyberCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MilitaryTech,
                        contentDescription = "Rank",
                        tint = AmberWarning,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = profile.callsign,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = profile.rank,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Online",
                        tint = HealthGreen,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ONLINE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = HealthGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "${profile.credits} CR",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = MilitaryGold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MenuSecondaryButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xCC111822),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263238)),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        fontSize = 9.sp,
                        color = Color(0xFF90A4AE),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
