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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.model.EmpireType
import com.example.game.model.WarOrder
import com.example.game.network.GlobalCampaignState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreen(
    campaignState: GlobalCampaignState = GlobalCampaignState(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val liveOrders = remember(campaignState) {
        val theaters = listOf(
            Triple("ORD-01", "OPERATION VOLCANO BREACH", campaignState.dragonProgress),
            Triple("ORD-02", "PROJECT MOTHERSHIP CRACKDOWN", campaignState.alienProgress),
            Triple("ORD-03", "IRONFANG SIEGE BLOCKADE", campaignState.orcProgress),
            Triple("ORD-04", "ROGUE BLACKWATCH PURGE", campaignState.humanProgress)
        )
        buildList {
            theaters.forEachIndexed { idx, (id, title, progress) ->
                add(
                    WarOrder(
                        id = id,
                        title = title,
                        empire = progress.empire,
                        requirement = "Destroy all 50 ${progress.empire.name} fortified barracks (+200 CR each)",
                        current = progress.destroyedCount.coerceIn(0, 50),
                        target = 50,
                        rewardCredits = 10_000,
                        isCompleted = progress.destroyedCount >= 50
                    )
                )
                add(
                    WarOrder(
                        id = "HQ-0${idx + 1}",
                        title = "${progress.empire.name} CITADEL CONQUEST",
                        empire = progress.empire,
                        requirement = "Neutralize ${progress.empire.bossName} & annihilate Main HQ",
                        current = if (progress.isHeadquartersDestroyed) 1 else 0,
                        target = 1,
                        rewardCredits = 2500,
                        isCompleted = progress.isHeadquartersDestroyed
                    )
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        text = "WAR COMMAND CENTER",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberWarning
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("command_back_button")) {
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
                        text = "LIVE HIGH COMMAND ORDERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${campaignState.totalBarracksDestroyed}/200 CLEARED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = HealthGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(liveOrders, key = { it.id }) { order ->
                        WarOrderCard(order = order)
                    }
                }
            }
        }
    }
}

@Composable
private fun WarOrderCard(order: WarOrder) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F1722),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            if (order.isCompleted) HealthGreen else Color(0xFF263238)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(order.empire.primaryColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = order.empire.name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = order.empire.primaryColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = order.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (order.isCompleted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = HealthGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = order.requirement, fontSize = 11.sp, color = Color(0xFF90A4AE))

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LinearProgressIndicator(
                    progress = { (order.current.toFloat() / order.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (order.isCompleted) HealthGreen else AmberWarning,
                    trackColor = Color(0x22FFFFFF)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${order.current}/${order.target}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (order.isCompleted) "BOUNTY CREDITED (+${order.rewardCredits} CR)" else "TOTAL REWARD: +${order.rewardCredits} CR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = if (order.isCompleted) HealthGreen else MilitaryGold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
