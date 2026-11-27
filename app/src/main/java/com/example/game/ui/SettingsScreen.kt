package com.example.game.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.ads.AdConsentManager
import com.example.game.ads.AdMobManager
import com.example.game.ads.findActivity
import com.example.game.network.TacticalSettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.ThreatRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pilotEmail: String,
    pilotUid: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember(context) { TacticalSettingsManager.getInstance(context) }
    val settings by settingsManager.settings.collectAsState()
    val canRequestAds by AdConsentManager.canRequestAds.collectAsState()
    val isPrivacyOptionsRequired by AdConsentManager.isPrivacyOptionsRequired.collectAsState()
    var uidCopied by remember(pilotUid) { mutableStateOf(false) }

    LaunchedEffect(uidCopied) {
        if (uidCopied) {
            delay(2000L)
            uidCopied = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        text = "TACTICAL SETTINGS",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberWarning
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
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
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "COMBAT AUDIO & HAPTICS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan,
                    fontFamily = FontFamily.Monospace
                )

                SettingToggleItem(
                    title = "COMBAT SOUND EFFECTS",
                    subtitle = "30mm Vulcan, missile launch & explosion SFX",
                    checked = settings.sfxEnabled,
                    testTag = "setting_sfx_toggle",
                    onCheckedChange = { settingsManager.setSfxEnabled(it) }
                )
                SettingToggleItem(
                    title = "WEAPON RECOIL HAPTICS",
                    subtitle = "Tactile pulse on missiles, rockets & Overdrive",
                    checked = settings.hapticsEnabled,
                    testTag = "setting_haptics_toggle",
                    onCheckedChange = { settingsManager.setHapticsEnabled(it) }
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "FLIGHT & CAMERA SYSTEMS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan,
                    fontFamily = FontFamily.Monospace
                )

                SettingToggleItem(
                    title = "AUTO-FIRE ON SORTIE LAUNCH",
                    subtitle = "Arm twin 30mm autocannons automatically at takeoff",
                    checked = settings.autoFireByDefault,
                    testTag = "setting_autofire_toggle",
                    onCheckedChange = { settingsManager.setAutoFireByDefault(it) }
                )
                SettingToggleItem(
                    title = "EXPLOSION CAMERA SHAKE",
                    subtitle = "Cinematic impact recoil on explosions & boss emergence",
                    checked = settings.screenShakeEnabled,
                    testTag = "setting_shake_toggle",
                    onCheckedChange = { settingsManager.setScreenShakeEnabled(it) }
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "CLOUD SESSION & SECURITY ISOLATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HealthGreen,
                    fontFamily = FontFamily.Monospace
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1722), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "BACKEND: CLOUD FIRESTORE (LIVE SYNC)",
                        fontSize = 10.sp,
                        color = HealthGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SECURITY: ZERO-TRUST UID ISOLATION (OWNER-ONLY RULES)",
                        fontSize = 9.5.sp,
                        color = CyberCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "ACCOUNT: ${pilotEmail.ifBlank { "Google Pilot" }}",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "UID: $pilotUid",
                        fontSize = 9.5.sp,
                        color = Color(0xFF90A4AE),
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val copyLabel = if (uidCopied) {
                        stringResource(R.string.settings_user_id_copied)
                    } else {
                        stringResource(R.string.settings_copy_user_id)
                    }

                    OutlinedButton(
                        onClick = {
                            if (pilotUid.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(pilotUid))
                                uidCopied = true
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("User ID copied for support")
                                }
                            }
                        },
                        enabled = pilotUid.isNotBlank(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (uidCopied) HealthGreen else CyberCyan
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (uidCopied) HealthGreen else CyberCyan.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("copy_user_id_button")
                    ) {
                        Icon(
                            imageVector = if (uidCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "Copy User ID",
                            tint = if (uidCopied) HealthGreen else CyberCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = copyLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (canRequestAds) {
                            "AD CONSENT STATUS: PERMITTED"
                        } else {
                            "AD CONSENT STATUS: PENDING / RESTRICTED"
                        },
                        fontSize = 10.sp,
                        color = if (canRequestAds) HealthGreen else AmberWarning,
                        fontFamily = FontFamily.Monospace
                    )

                    if (isPrivacyOptionsRequired) {
                        val privacyUpdatedMsg = stringResource(R.string.settings_privacy_ad_consent_updated)
                        OutlinedButton(
                            onClick = {
                                val activity = context.findActivity()
                                AdConsentManager.showPrivacyOptionsForm(activity) { allowed ->
                                    if (allowed) {
                                        AdMobManager.initialize(context)
                                    }
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(privacyUpdatedMsg)
                                    }
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning),
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("privacy_options_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PrivacyTip,
                                contentDescription = "Privacy and Ad Consent Options",
                                tint = AmberWarning
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_privacy_ad_consent),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onSignOut,
                        colors = ButtonDefaults.buttonColors(containerColor = ThreatRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign Out",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SIGN OUT OF ACCOUNT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1722), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )
            Text(text = subtitle, fontSize = 9.5.sp, color = Color(0xFF90A4AE))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmberWarning,
                checkedTrackColor = AmberWarning.copy(alpha = 0.4f)
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
