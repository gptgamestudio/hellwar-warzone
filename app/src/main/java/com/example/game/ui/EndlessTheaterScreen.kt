package com.example.game.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.ads.AdMobBannerBar
import com.example.game.engine.GameEngine
import com.example.game.model.EmpireType
import com.example.game.network.CampaignProgressionManager
import com.example.game.network.EmpireEndlessProgress
import com.example.game.network.EndlessCheckpoint
import com.example.game.network.EndlessSurvivalRecord
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ThreatRed
import java.text.NumberFormat
import java.util.Locale

private val ENDLESS_EMPIRE_ORDER = listOf(
    EmpireType.DRAGON,
    EmpireType.ALIEN,
    EmpireType.ORC,
    EmpireType.HUMAN
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndlessTheaterScreen(
    endlessRecord: EndlessSurvivalRecord,
    checkpointsByEmpire: Map<EmpireType, EndlessCheckpoint> = emptyMap(),
    onDeployEmpire: (EmpireType) -> Unit,
    onResumeEmpire: (EmpireType) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val aggregateKills = endlessRecord.cappedFourEmpireKills
    val aggregatePct = (aggregateKills.toFloat() / CampaignProgressionManager.TOTAL_ENDLESS_KILL_TARGET.toFloat())
        .coerceIn(0f, 1f)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Column {
                        Text(
                            text = "ENDLESS BATTLE THEATERS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = ThreatRed
                        )
                        Text(
                            text = "FOUR EMPIRES × 250,000 ENEMY CHALLENGE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = CyberCyan
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("endless_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Dashboard",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0C121A)
                )
            )
        },
        bottomBar = { AdMobBannerBar() },
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
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Overall 1,000,000 (4 × 250,000 capped) Progress Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(1.2.dp, CyberCyan.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("endless_overall_summary_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "4-EMPIRE CHALLENGE: ${formatter.format(aggregateKills)} / 1,000,000",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (aggregateKills >= CampaignProgressionManager.TOTAL_ENDLESS_KILL_TARGET) MilitaryGold else Color.White,
                                modifier = Modifier.testTag("endless_overall_progress_text")
                            )
                            Text(
                                text = "${endlessRecord.conqueredEmpireCount}/4 EMPIRES",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = HealthGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(aggregatePct)
                                    .height(7.dp)
                                    .background(
                                        Brush.horizontalGradient(listOf(CyberCyan, AmberWarning, MilitaryGold)),
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "BIG BOSSES DEFEATED: ${endlessRecord.totalBossesAcrossEmpires}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AmberWarning
                            )
                            Text(
                                text = "ALL 4 EMPIRES UNLOCKED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyberCyan
                            )
                        }
                    }
                }

                ENDLESS_EMPIRE_ORDER.forEach { empire ->
                    val empProgress = endlessRecord.progressFor(empire)
                    val checkpoint = checkpointsByEmpire[empire]
                    EndlessEmpireCard(
                        empire = empire,
                        progress = empProgress,
                        checkpoint = checkpoint,
                        formatter = formatter,
                        onDeploy = { onDeployEmpire(empire) },
                        onResume = { onResumeEmpire(empire) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun EndlessEmpireCard(
    empire: EmpireType,
    progress: EmpireEndlessProgress,
    checkpoint: EndlessCheckpoint?,
    formatter: NumberFormat,
    onDeploy: () -> Unit,
    onResume: () -> Unit
) {
    val isConquered = progress.isTargetReached
    val bossName = GameEngine.endlessThemedBossName(empire)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF101824),
        border = BorderStroke(
            width = 1.4.dp,
            color = if (isConquered) MilitaryGold else empire.primaryColor.copy(alpha = 0.78f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("endless_empire_card_${empire.name}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 76.dp, height = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, empire.primaryColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                ) {
                    Image(
                        painter = painterResource(id = getEndlessEnvironmentImageRes(empire)),
                        contentDescription = "${empire.title} Battlefield",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x44000000))
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = empire.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = empire.primaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isConquered) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("endless_empire_conquered_${empire.name}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Empire Conquered",
                                    tint = HealthGreen,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "CONQUERED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = HealthGreen
                                )
                            }
                        }
                    }

                    Text(
                        text = empire.subtitle,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB0BEC5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "BOSS (100 KILLS): $bossName",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AmberWarning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(9.dp))

            // 250,000 Empire Target Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kills: ${formatter.format(progress.regularKills)} / 250,000",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = if (isConquered) MilitaryGold else Color.White,
                    modifier = Modifier.testTag("endless_empire_kills_${empire.name}")
                )
                Text(
                    text = "Next Boss: ${progress.cycleKills}/100 • Bosses: ${progress.bossesDefeated}",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyberCyan
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1E293B))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.progressFraction)
                        .height(6.dp)
                        .background(
                            if (isConquered) MilitaryGold else empire.primaryColor,
                            RoundedCornerShape(3.dp)
                        )
                )
            }

            Spacer(modifier = Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (checkpoint != null) {
                    Button(
                        onClick = onResume,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HealthGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("endless_resume_${empire.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "RESUME W${checkpoint.currentWave}",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Black,
                            maxLines = 1
                        )
                    }
                }

                Button(
                    onClick = onDeploy,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = empire.primaryColor),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("endless_deploy_${empire.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (checkpoint != null) "NEW SORTIE" else "DEPLOY ${empire.name}",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun getEndlessEnvironmentImageRes(empire: EmpireType): Int = when (empire) {
    EmpireType.DRAGON -> R.drawable.env_dragon_volcano_1794120842626
    EmpireType.ALIEN -> R.drawable.env_alien_planet_1794120862388
    EmpireType.ORC -> R.drawable.env_orc_wasteland_1794120878509
    EmpireType.HUMAN -> R.drawable.env_human_airbase_1794120893118
}
