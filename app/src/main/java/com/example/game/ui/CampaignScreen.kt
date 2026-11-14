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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.CheckCircle
import com.example.game.model.EmpireType
import com.example.game.network.WarNetCampaignService
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.ThreatRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignScreen(
    currentEmpire: EmpireType,
    onSelectEmpire: (EmpireType) -> Unit,
    onDeploy: (EmpireType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val campaignService = remember { WarNetCampaignService.getInstance(context) }
    val campaignState by campaignService.campaignState.collectAsState()

    var selected by remember { mutableStateOf(currentEmpire) }
    val selectedProgress = campaignService.getProgressForEmpire(selected)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "FOUR EMPIRES TOTAL WAR",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = AmberWarning
                        )
                        Text(
                            text = "GLOBAL CONQUEST: ${campaignState.totalBarracksDestroyed} / 200 BARRACKS ANNIHILATED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = CyberCyan
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0C121A)
                )
            )
        },
        bottomBar = { com.example.game.ads.AdMobBannerBar() },
        containerColor = GunmetalBlack,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Global War Progress Bar (0 to 200)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "WORLD CAMPAIGN THEATERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = "${campaignState.totalBarracksDestroyed} / 200",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberWarning
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                val globalPct = (campaignState.totalBarracksDestroyed.toFloat() / 200f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(globalPct)
                            .height(8.dp)
                            .background(
                                Brush.horizontalGradient(listOf(CyberCyan, AmberWarning)),
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "SELECT ENEMY EMPIRE THEATER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Empire Selector Tabs (Showing 0/50 for each)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EmpireType.values().forEach { empire ->
                    val isSelected = empire == selected
                    val p = campaignService.getProgressForEmpire(empire)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(
                                if (isSelected) empire.primaryColor.copy(alpha = 0.25f) else Color(0xFF131B26),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.5.dp,
                                if (isSelected) empire.primaryColor else Color(0xFF263238),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selected = empire
                                onSelectEmpire(empire)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = empire.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) empire.primaryColor else Color(0xFF90A4AE)
                            )
                            Text(
                                text = "${p.destroyedCount}/50",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (p.destroyedCount >= 50) Color(0xFF00E676) else Color(0xFFFFB300)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Empire Tactical Dossier Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF101824),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, selected.primaryColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = selected.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = selected.primaryColor
                            )
                            Text(
                                text = selected.subtitle,
                                fontSize = 11.sp,
                                color = selected.accentColor,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.Dangerous,
                            contentDescription = "Threat",
                            tint = selected.primaryColor,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Empire 50-Barracks Progress & HQ Status Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF151D2A), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (selectedProgress.isHeadquartersUnlocked) AmberWarning else Color(0xFF37474F),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BARRACKS ANNIHILATION PROGRESS:",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF90A4AE)
                            )
                            Text(
                                text = "${selectedProgress.destroyedCount} / 50 DESTROYED",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = selected.primaryColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(Color(0xFF1E293B), RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(selectedProgress.progressPercent)
                                    .height(6.dp)
                                    .background(selected.primaryColor, RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // HQ Lock / Unlock Status Indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, tint, statusText) = when {
                                selectedProgress.isHeadquartersDestroyed -> Triple(
                                    Icons.Default.CheckCircle,
                                    Color(0xFF00E676),
                                    "HEADQUARTERS DESTROYED: ${selected.name} CITADEL ANNIHILATED"
                                )
                                selectedProgress.isHeadquartersUnlocked -> Triple(
                                    Icons.Default.LockOpen,
                                    AmberWarning,
                                    "HEADQUARTERS UNLOCKED: FORCEFIELD DOWN — ENGAGE ${selected.bossName.uppercase()}!"
                                )
                                else -> Triple(
                                    Icons.Default.Lock,
                                    ThreatRed,
                                    "HEADQUARTERS LOCKED: DESTROY 50/50 BARRACKS TO LOWER FORCEFIELD (${selectedProgress.destroyedCount}/50)"
                                )
                            }
                            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = tint,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3D Environment Theater Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, selected.primaryColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Image(
                            painter = painterResource(id = getEnvironmentImageRes(selected)),
                            contentDescription = selected.environmentName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0xDD0A0F17))
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "BATTLEFIELD: ${selected.environmentName.uppercase()}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "HAZARD: ${selected.hazardName.uppercase()}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberWarning,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "INTELLIGENCE BRIEFING:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF78909C),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selected.description,
                        fontSize = 12.sp,
                        color = Color(0xFFECEFF1),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Environmental Hazard Warning Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF151B24), RoundedCornerShape(8.dp))
                            .border(1.dp, ThreatRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Dangerous,
                                contentDescription = null,
                                tint = ThreatRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ENVIRONMENTAL HAZARD ADVISORY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = ThreatRed,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${selected.hazardName}: ${selected.hazardDescription}",
                            fontSize = 11.sp,
                            color = Color(0xFFCFD8DC),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hostile Units
                    Text(
                        text = "IDENTIFIED SQUADRONS & UNITS:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF78909C),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selected.units.forEach { unit ->
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF162334), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = unit,
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Boss Dossier
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF192330), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF37474F), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "FLAGSHIP / BOSS TARGET:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = AmberWarning,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = selected.bossName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "WEAPONS ARSENAL: ${selected.weapons}",
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Deploy Button
                    Button(
                        onClick = { onDeploy(selected) },
                        colors = ButtonDefaults.buttonColors(containerColor = selected.primaryColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("deploy_empire_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ENGAGE ${selected.name} FORCES",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun getEnvironmentImageRes(empire: EmpireType): Int = when (empire) {
    EmpireType.DRAGON -> com.example.R.drawable.env_dragon_volcano_1794120842626
    EmpireType.ALIEN -> com.example.R.drawable.env_alien_planet_1794120862388
    EmpireType.ORC -> com.example.R.drawable.env_orc_wasteland_1794120878509
    EmpireType.HUMAN -> com.example.R.drawable.env_human_airbase_1794120893118
}
