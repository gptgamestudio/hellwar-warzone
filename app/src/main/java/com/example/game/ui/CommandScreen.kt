package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.model.EmpireType
import com.example.game.model.WarOrder
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sampleOrders = listOf(
        WarOrder("ORD-01", "OPERATION CINDER RAIN", EmpireType.DRAGON, "Neutralize 15 Pyro Drakes in Volcano Sector", 8, 15, 3500),
        WarOrder("ORD-02", "PROJECT MOTHERSHIP CRACKDOWN", EmpireType.ALIEN, "Down 10 Plasma UFO Saucers with Hellfire Missiles", 4, 10, 4200),
        WarOrder("ORD-03", "IRONFANG SUPPLY BLOCKADE", EmpireType.ORC, "Demolish 6 Orc Bombardier Zeppelins", 6, 6, 5000, isCompleted = true),
        WarOrder("ORD-04", "ROGUE BLACKWATCH PURGE", EmpireType.HUMAN, "Destroy 12 Renegade AH-64 Gunships", 3, 12, 3800)
    )

    Scaffold(
        topBar = {
            TopAppBar(
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
                    IconButton(onClick = onBack) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "ACTIVE HIGH COMMAND BATTLE ORDERS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sampleOrders) { order ->
                    WarOrderCard(order = order)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        imageVector = Icons.Default.AssignmentTurnedIn,
                        contentDescription = "Done",
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
                    progress = { (order.current.toFloat() / order.target).coerceIn(0f, 1f) },
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
                    text = "BOUNTY: +${order.rewardCredits} CR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = MilitaryGold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
