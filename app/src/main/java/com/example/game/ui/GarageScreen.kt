package com.example.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.ads.AdMobBannerBar
import com.example.game.ads.AdMobManager
import com.example.game.engine.Camera3D
import com.example.game.engine.PlayerHelo
import com.example.game.engine.Renderer3D
import com.example.game.model.HelicopterClass
import com.example.game.model.HelicopterSkin
import com.example.game.model.HelicopterUpgrades
import com.example.game.model.PlayerProfile
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.MilitaryGold
import com.example.ui.theme.ShieldBlue
import com.example.ui.theme.ThreatRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    profile: PlayerProfile,
    onProfileUpdated: (PlayerProfile) -> Unit,
    onBack: () -> Unit,
    onWatchSupplyAd: (onStatus: (String) -> Unit) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Fleet Selection, 1: Upgrades & Systems, 2: Paints & Skins
    var currentHeloClass by remember { mutableStateOf(profile.selectedHelicopterClass) }
    var currentSkin by remember { mutableStateOf(profile.selectedSkin) }
    var transactionMessage by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    val currentUpgrades = profile.getUpgrades(currentHeloClass)

    // Interactive 3D Hangar Preview rotation using a single remembered preview engine
    var previewRotorAngle by remember { mutableFloatStateOf(0f) }
    val previewEngine = remember(currentHeloClass, currentSkin, currentUpgrades) {
        com.example.game.engine.GameEngine(
            initialHeloClass = currentHeloClass,
            initialSkin = currentSkin,
            initialUpgrades = currentUpgrades
        )
    }
    LaunchedEffect(previewEngine) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastNs != 0L) {
                    val dt = ((frameNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    previewRotorAngle = (previewRotorAngle + dt * 14f) % (kotlin.math.PI.toFloat() * 2f)
                    previewEngine.player.mainRotorAngle = previewRotorAngle
                    previewEngine.player.tailRotorAngle = previewRotorAngle * 1.5f
                }
                lastNs = frameNs
            }
        }
    }
    val renderer = remember { Renderer3D() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MILITARY HANGAR",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = AmberWarning
                        )
                        // Online Currency Balance
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF151F2C),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MilitaryGold)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WAR CREDITS: ",
                                    fontSize = 9.sp,
                                    color = Color(0xFF90A4AE),
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${profile.credits} CR",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MilitaryGold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("credits_display")
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("garage_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Main Menu",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C121A))
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
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Live 3D Helicopter Hangar Viewport
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(Color(0xFF0A1017))
                        .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_VARIABLE")
                        val rotorTick = previewRotorAngle
                        renderer.render(this, previewEngine, size.width, size.height)
                    }

                // Overlay Callout Badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, currentHeloClass.themeColor)
                    ) {
                        Text(
                            text = currentHeloClass.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = currentHeloClass.themeColor,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (profile.selectedHelicopterClass == currentHeloClass) {
                        Surface(
                            color = Color(0xCC00E676),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "DEPLOYMENT READY",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Black,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Skin Indicator Badge
                Text(
                    text = "PAINT: ${currentSkin.label.uppercase()}",
                    fontSize = 9.sp,
                    color = Color(0xFFCFD8DC),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                )
            }

            // Rewarded Video Supply Drop Strip
            val isRewardedReady by AdMobManager.isRewardedReady.collectAsState()
            Surface(
                onClick = {
                    onWatchSupplyAd { msg ->
                        transactionMessage = msg
                        messageIsError = !msg.contains("CREDITED")
                    }
                },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF151F2C),
                border = androidx.compose.foundation.BorderStroke(1.dp, MilitaryGold.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("garage_rewarded_ad_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = "Watch Supply Ad",
                            tint = MilitaryGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "SUPPLY AIRDROP: +${AdMobManager.REWARDED_CREDITS_BONUS} CR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = MilitaryGold
                            )
                            Text(
                                text = if (isRewardedReady) "Watch short rewarded video ad" else "Fetching rewarded supply video...",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF90A4AE)
                            )
                        }
                    }
                    Text(
                        text = if (isRewardedReady) "WATCH AD" else "LOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = CyberCyan
                    )
                }
            }

            // Transaction feedback alert banner
            transactionMessage?.let { msg ->
                Surface(
                    color = if (messageIsError) Color(0x33FF1744) else Color(0x3300E676),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (messageIsError) ThreatRed else HealthGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (messageIsError) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (messageIsError) ThreatRed else HealthGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 2. Navigation Tabs (Fleet, Upgrades, Skins)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0C131D),
                contentColor = CyberCyan,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AmberWarning
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("FLEET", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.testTag("tab_fleet")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("UPGRADES", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.testTag("tab_upgrades")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("PAINTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.testTag("tab_paints")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Tab Contents
            when (selectedTab) {
                0 -> FleetSelectionSection(
                    profile = profile,
                    currentSelected = currentHeloClass,
                    onSelectClass = { chosen ->
                        currentHeloClass = chosen
                    },
                    onDeployHelicopter = { chosen ->
                        if (profile.unlockedHelicopters.contains(chosen.id)) {
                            val updated = profile.deepCopy(selectedHelicopterClass = chosen)
                            onProfileUpdated(updated)
                            transactionMessage = "DEPLOYMENT CONFIRMED: ${chosen.title}"
                            messageIsError = false
                        }
                    },
                    onUnlockHelicopter = { chosen ->
                        if (profile.unlockedHelicopters.contains(chosen.id)) {
                            val updated = profile.deepCopy(selectedHelicopterClass = chosen)
                            onProfileUpdated(updated)
                            transactionMessage = "DEPLOYMENT CONFIRMED: ${chosen.title}"
                            messageIsError = false
                        } else if (profile.credits >= chosen.unlockCost) {
                            val updated = profile.deepCopy(
                                credits = profile.credits - chosen.unlockCost,
                                selectedHelicopterClass = chosen
                            )
                            updated.unlockedHelicopters.add(chosen.id)
                            onProfileUpdated(updated)
                            transactionMessage = "PURCHASE VALIDATED: ${chosen.title} UNLOCKED!"
                            messageIsError = false
                        } else {
                            transactionMessage = "INSUFFICIENT CREDITS: NEED ${chosen.unlockCost} CR"
                            messageIsError = true
                        }
                    }
                )

                1 -> UpgradesSection(
                    heloClass = currentHeloClass,
                    upgrades = currentUpgrades,
                    playerCredits = profile.credits,
                    onUpgrade = { category ->
                        val currentLvl = when (category) {
                            UpgradeType.ENGINE -> currentUpgrades.engineLevel
                            UpgradeType.ARMOR -> currentUpgrades.armorLevel
                            UpgradeType.WEAPON -> currentUpgrades.weaponLevel
                            UpgradeType.DEFENSE -> currentUpgrades.defenseLevel
                            UpgradeType.RADAR -> currentUpgrades.radarLevel
                        }
                        val cost = currentUpgrades.getUpgradeCost(currentLvl)
                        if (currentLvl >= 5) {
                            transactionMessage = "$category IS ALREADY AT MAXIMUM LEVEL"
                            messageIsError = true
                        } else if (profile.credits < cost) {
                            transactionMessage = "TRANSACTION REJECTED: NEED $cost CR"
                            messageIsError = true
                        } else {
                            val updated = profile.deepCopy(credits = profile.credits - cost)
                            val updatedUpgrades = updated.getUpgrades(currentHeloClass)
                            when (category) {
                                UpgradeType.ENGINE -> updatedUpgrades.engineLevel++
                                UpgradeType.ARMOR -> updatedUpgrades.armorLevel++
                                UpgradeType.WEAPON -> updatedUpgrades.weaponLevel++
                                UpgradeType.DEFENSE -> updatedUpgrades.defenseLevel++
                                UpgradeType.RADAR -> updatedUpgrades.radarLevel++
                            }
                            onProfileUpdated(updated)
                            transactionMessage = "UPGRADE INSTALLED: $category LEVEL ${currentLvl + 1}"
                            messageIsError = false
                        }
                    }
                )

                2 -> SkinsSection(
                    currentSkin = currentSkin,
                    onSelectSkin = { skin ->
                        currentSkin = skin
                        val updated = profile.deepCopy(selectedSkin = skin)
                        onProfileUpdated(updated)
                        transactionMessage = "APPLIED CAMO SKIN: ${skin.label.uppercase()}"
                        messageIsError = false
                    }
                )
            }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

enum class UpgradeType { ENGINE, ARMOR, WEAPON, DEFENSE, RADAR }

@Composable
private fun FleetSelectionSection(
    profile: PlayerProfile,
    currentSelected: HelicopterClass,
    onSelectClass: (HelicopterClass) -> Unit,
    onDeployHelicopter: (HelicopterClass) -> Unit,
    onUnlockHelicopter: (HelicopterClass) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HelicopterClass.values().forEach { helo ->
            val isUnlocked = profile.unlockedHelicopters.contains(helo.id)
            val isDeployed = profile.selectedHelicopterClass == helo
            val isInspecting = currentSelected == helo

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isInspecting) Color(0xFF131E2B) else Color(0xFF0F1722),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isInspecting) 1.8.dp else 1.dp,
                    color = if (isInspecting) helo.themeColor else Color(0xFF263238)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectClass(helo) }
                    .testTag("helo_card_${helo.id}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = helo.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isUnlocked) helo.themeColor else Color(0xFF90A4AE),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = helo.role,
                                fontSize = 9.sp,
                                color = Color(0xFF78909C),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (!isUnlocked) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF263238)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = "Locked", tint = AmberWarning, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("${helo.unlockCost} CR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AmberWarning, fontFamily = FontFamily.Monospace)
                                }
                            }
                        } else if (isDeployed) {
                            Text("ACTIVE SORTIE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = HealthGreen, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = helo.description,
                        fontSize = 9.5.sp,
                        color = Color(0xFFCFD8DC),
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Stat Gauges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniSpec("HULL", "${helo.baseHealth.toInt()}", helo.baseHealth / 150f, HealthGreen, Modifier.weight(1f))
                        MiniSpec("SPEED", "${helo.baseSpeed.toInt()}", helo.baseSpeed / 32f, CyberCyan, Modifier.weight(1f))
                        MiniSpec("ARMOR", "${helo.baseArmor.toInt()}", helo.baseArmor / 100f, ShieldBlue, Modifier.weight(1f))
                        MiniSpec("ROCKETS", "${helo.baseRocketCapacity}", helo.baseRocketCapacity / 24f, MilitaryGold, Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!isUnlocked) {
                            Button(
                                onClick = { onUnlockHelicopter(helo) },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(38.dp).testTag("unlock_btn_${helo.id}")
                            ) {
                                Text("UNLOCK AIRFRAME (${helo.unlockCost} CR)", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (!isDeployed) {
                            Button(
                                onClick = { onDeployHelicopter(helo) },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(38.dp).testTag("deploy_btn_${helo.id}")
                            ) {
                                Text("SELECT FOR SORTIE", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpgradesSection(
    heloClass: HelicopterClass,
    upgrades: HelicopterUpgrades,
    playerCredits: Int,
    onUpgrade: (UpgradeType) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF131D2A),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ACTIVE AIRFRAME BENCH", fontSize = 10.sp, color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                    Text(heloClass.title, fontSize = 13.sp, fontWeight = FontWeight.Black, color = heloClass.themeColor, fontFamily = FontFamily.Monospace)
                }
                Text("TIER 5 CEILING", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MilitaryGold, fontFamily = FontFamily.Monospace)
            }
        }

        UpgradeRow(
            title = "TURBOSHAFT ENGINE",
            description = "Increases movement speed & lateral acceleration (+8% / level)",
            level = upgrades.engineLevel,
            cost = upgrades.getUpgradeCost(upgrades.engineLevel),
            canAfford = playerCredits >= upgrades.getUpgradeCost(upgrades.engineLevel),
            icon = Icons.Default.Speed,
            color = CyberCyan,
            onUpgrade = { onUpgrade(UpgradeType.ENGINE) },
            testTag = "upgrade_engine"
        )

        UpgradeRow(
            title = "TITAN-COMPOSITE ARMOR",
            description = "Increases hull durability & damage resistance (+12% / level)",
            level = upgrades.armorLevel,
            cost = upgrades.getUpgradeCost(upgrades.armorLevel),
            canAfford = playerCredits >= upgrades.getUpgradeCost(upgrades.armorLevel),
            icon = Icons.Default.Security,
            color = HealthGreen,
            onUpgrade = { onUpgrade(UpgradeType.ARMOR) },
            testTag = "upgrade_armor"
        )

        UpgradeRow(
            title = "AVIONICS & WEAPONS PODS",
            description = "Increases MG bullet damage & MRL rocket ammunition capacity",
            level = upgrades.weaponLevel,
            cost = upgrades.getUpgradeCost(upgrades.weaponLevel),
            canAfford = playerCredits >= upgrades.getUpgradeCost(upgrades.weaponLevel),
            icon = Icons.Default.Build,
            color = MilitaryGold,
            onUpgrade = { onUpgrade(UpgradeType.WEAPON) },
            testTag = "upgrade_weapons"
        )

        UpgradeRow(
            title = "KINETIC ENERGY SHIELD",
            description = "Boosts protective energy shield capacity & recharge rate (+15% / level)",
            level = upgrades.defenseLevel,
            cost = upgrades.getUpgradeCost(upgrades.defenseLevel),
            canAfford = playerCredits >= upgrades.getUpgradeCost(upgrades.defenseLevel),
            icon = Icons.Default.Security,
            color = ShieldBlue,
            onUpgrade = { onUpgrade(UpgradeType.DEFENSE) },
            testTag = "upgrade_defense"
        )

        UpgradeRow(
            title = "MILLIMETER-WAVE RADAR",
            description = "Enhances target lock acquisition speed & detects boss weak points",
            level = upgrades.radarLevel,
            cost = upgrades.getUpgradeCost(upgrades.radarLevel),
            canAfford = playerCredits >= upgrades.getUpgradeCost(upgrades.radarLevel),
            icon = Icons.Default.Radar,
            color = Color(0xFFD500F9),
            onUpgrade = { onUpgrade(UpgradeType.RADAR) },
            testTag = "upgrade_radar"
        )
    }
}

@Composable
private fun UpgradeRow(
    title: String,
    description: String,
    level: Int,
    cost: Int,
    canAfford: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onUpgrade: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F1722),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263238)),
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
                        modifier = Modifier.size(32.dp).background(color.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace)
                        Text(text = "LEVEL $level / 5", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
                    }
                }

                // Level Dots
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..5) {
                        Box(
                            modifier = Modifier
                                .size(10.dp, 8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i <= level) color else Color(0xFF263238))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, fontSize = 9.sp, color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (level < 5) {
                    Text(text = "COST: $cost CR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (canAfford) MilitaryGold else ThreatRed, fontFamily = FontFamily.Monospace)
                    Button(
                        onClick = onUpgrade,
                        enabled = canAfford,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canAfford) AmberWarning else Color(0xFF37474F)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp).testTag(testTag)
                    ) {
                        Text("UPGRADE", fontSize = 9.sp, color = if (canAfford) Color.Black else Color(0xFF90A4AE), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(text = "MAXIMUM UPGRADE ACHIEVED", fontSize = 9.sp, fontWeight = FontWeight.Black, color = HealthGreen, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SkinsSection(
    currentSkin: HelicopterSkin,
    onSelectSkin: (HelicopterSkin) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "TACTICAL COATINGS & CAMOUFLAGE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = CyberCyan,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Select high-contrast military camo or infrared radar-absorbent radar coatings.",
            fontSize = 9.sp,
            color = Color(0xFF90A4AE),
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(6.dp))

        HelicopterSkin.values().forEach { skin ->
            val isSelected = skin == currentSkin

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Color(0xFF131F2E) else Color(0xFF0F1722),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) skin.accentColor else Color(0xFF263238)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSkin(skin) }
                    .testTag("skin_${skin.id}")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Color Swatch
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(skin.primaryColor)
                                .border(2.dp, skin.accentColor, RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = skin.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                            Text(text = "PBR MATTE SPECULAR", fontSize = 8.sp, color = Color(0xFF78909C), fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (isSelected) {
                        Text("EQUIPPED", fontSize = 10.sp, fontWeight = FontWeight.Black, color = HealthGreen, fontFamily = FontFamily.Monospace)
                    } else {
                        Button(
                            onClick = { onSelectSkin(skin) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("EQUIP", fontSize = 9.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniSpec(label: String, value: String, progress: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 7.5.sp, color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
            Text(value, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = Color(0x22FFFFFF)
        )
    }
}
