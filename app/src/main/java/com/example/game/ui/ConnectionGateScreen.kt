package com.example.game.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.network.GoogleSignInButton
import com.example.game.network.OnlineStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.GunmetalBlack
import com.example.ui.theme.HealthGreen
import com.example.ui.theme.ThreatRed
import com.google.firebase.auth.FirebaseUser

@Composable
fun ConnectionGateScreen(
    onlineStatus: OnlineStatus,
    currentUser: FirebaseUser?,
    onRetry: () -> Unit,
    onEnterGame: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    onSignInStarted: () -> Unit = {},
    onAuthSuccess: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )
    var authError by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(GunmetalBlack)) {
        Image(
            painter = painterResource(id = R.drawable.bg_hangar_warfare_1794118009655),
            contentDescription = "Hangar Warzone",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.35f)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE090D12),
                            Color(0xDD090D12),
                            Color(0xF0090D12)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, AmberWarning, RoundedCornerShape(20.dp))
                    .padding(4.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_helicopter_war_launcher_1794117995132),
                    contentDescription = "Helicopter War Icon",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "HELICOPTER WAR",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = AmberWarning,
                letterSpacing = 2.sp
            )
            Text(
                text = "EMPIRE ENDLESS BATTLE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = CyberCyan,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xDD111822),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (onlineStatus) {
                        is OnlineStatus.Connected -> if (currentUser != null) HealthGreen else AmberWarning
                        is OnlineStatus.OfflineError -> ThreatRed
                        OnlineStatus.Authenticating -> CyberCyan
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (onlineStatus) {
                        is OnlineStatus.Authenticating -> {
                            CircularProgressIndicator(
                                color = CyberCyan,
                                modifier = Modifier.size(42.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "ESTABLISHING SATELLITE UPLINK...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Verifying live TLS link to Firebase Cloud Firestore",
                                fontSize = 11.sp,
                                color = Color(0xFF90A4AE),
                                textAlign = TextAlign.Center
                            )
                        }

                        is OnlineStatus.Connected -> {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "Online",
                                tint = if (currentUser != null) HealthGreen else AmberWarning,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (currentUser != null) "PILOT AUTHENTICATED & ONLINE" else "GOOGLE SIGN-IN REQUIRED",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = if (currentUser != null) HealthGreen else AmberWarning,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0C131D), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                DiagnosticRow("NETWORK LINK", onlineStatus.networkType, HealthGreen)
                                DiagnosticRow("CLOUD BACKEND", "CLOUD FIRESTORE", CyberCyan)
                                DiagnosticRow("PING LATENCY", "${onlineStatus.latencyMs} ms", AmberWarning)
                                DiagnosticRow(
                                    "AUTH TOKEN",
                                    if (currentUser != null) "FIREBASE ID TOKEN (ACTIVE)" else "UNAUTHENTICATED",
                                    if (currentUser != null) HealthGreen else ThreatRed
                                )
                                if (currentUser != null) {
                                    DiagnosticRow(
                                        "PILOT ACCOUNT",
                                        currentUser.email ?: currentUser.displayName ?: currentUser.uid.take(12),
                                        Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (currentUser == null) {
                                Text(
                                    text = "Sign in with Google to synchronize your 4-Empire campaign and 200 barracks across devices.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCFD8DC),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                GoogleSignInButton(
                                    onBeforeSignIn = onSignInStarted,
                                    onAuthSuccess = {
                                        authError = null
                                        onAuthSuccess()
                                    },
                                    onAuthError = { msg -> authError = msg },
                                    onAuthCancelled = {
                                        authError = "Google Sign-In was dismissed. Tap SIGN IN WITH GOOGLE to enter."
                                    }
                                )
                                if (!authError.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = authError!!,
                                        fontSize = 11.sp,
                                        color = ThreatRed,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Button(
                                    onClick = onEnterGame,
                                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("enter_combat_button")
                                ) {
                                    Text(
                                        text = "ENTER COMBAT THEATER",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = onSignOut,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("sign_out_gate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "Sign Out",
                                        tint = Color(0xFFCFD8DC),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "SWITCH ACCOUNT / SIGN OUT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFCFD8DC),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        is OnlineStatus.OfflineError -> {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Offline Error",
                                tint = ThreatRed,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "OFFLINE MODE FORBIDDEN",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = ThreatRed,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = onlineStatus.reason,
                                fontSize = 11.sp,
                                color = Color(0xFFCFD8DC),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Helicopter War requires an active internet connection for Firebase Authentication and live Cloud Firestore cross-device campaign synchronization.",
                                fontSize = 10.sp,
                                color = Color(0xFF78909C),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = ThreatRed),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("retry_connection_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "RETRY SATELLITE CONNECTION",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (currentUser != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = onSignOut,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("sign_out_offline_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "Sign Out",
                                        tint = Color(0xFFCFD8DC),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "SWITCH ACCOUNT / SIGN OUT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFCFD8DC),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 10.sp, color = Color(0xFF78909C), fontFamily = FontFamily.Monospace)
        Text(text = value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = FontFamily.Monospace)
    }
}
