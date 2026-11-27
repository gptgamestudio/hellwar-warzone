package com.example.game.network

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.AmberWarning
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    trySend(currentUser)
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@SuppressLint("DiscouragedApi")
private fun resolveWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId == 0) return null
    return runCatching { context.getString(resId) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
): Job? {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return null
    }
    val clientId = resolveWebClientId(context)
    if (clientId == null) {
        onUnauthenticated()
        return null
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return scope.launch {
        try {
            val activityContext = context.findActivity() ?: context
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                withContext(Dispatchers.Main) {
                    onAuthSuccess()
                }
            } else {
                withContext(Dispatchers.Main) {
                    onUnauthenticated()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                onUnauthenticated()
            }
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = resolveWebClientId(context)
    if (clientId == null) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    scope.launch {
        try {
            val activityContext = context.findActivity() ?: context
            val result = try {
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
                val primaryRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()
                credentialManager.getCredential(activityContext, primaryRequest)
            } catch (primaryEx: Exception) {
                if (primaryEx is CancellationException || primaryEx is GetCredentialCancellationException) {
                    throw primaryEx
                }
                if (primaryEx is NoCredentialException || primaryEx is GetCredentialProviderConfigurationException) {
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(false)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    credentialManager.getCredential(activityContext, fallbackRequest)
                } else {
                    throw primaryEx
                }
            }

            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                withContext(Dispatchers.Main) {
                    onAuthSuccess()
                }
            } else {
                withContext(Dispatchers.Main) {
                    onAuthError("Unexpected credential type returned by Google Sign-In")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            SafeLog.w("Auth", "Google Sign-In flow cancelled or dismissed (${SafeLog.safeThrowableSummary(e)})")
            withContext(Dispatchers.Main) {
                onAuthCancelled()
            }
        } catch (e: NoCredentialException) {
            SafeLog.e("Auth", "No Google account available for Sign-In (${SafeLog.safeThrowableSummary(e)})")
            withContext(Dispatchers.Main) {
                onAuthError("No Google account found on device or OAuth SHA-1 mismatch. Please add/select a Google account and try again.")
            }
        } catch (e: Exception) {
            SafeLog.e("Auth", "Google Sign-In failed (${SafeLog.safeThrowableSummary(e)})")
            withContext(Dispatchers.Main) {
                onAuthError(e.localizedMessage ?: "Google Sign-In failed. Please try again.")
            }
        }
    }
}

fun signOutFromGoogle(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                SafeLog.w("Auth", "Failed to clear credential state (${SafeLog.safeThrowableSummary(e)})")
            } finally {
                Firebase.auth.signOut()
                withContext(Dispatchers.Main) {
                    onSignOutComplete()
                }
            }
        }
    }
}

@Composable
fun GoogleSignInButton(
    enabled: Boolean = true,
    onBeforeSignIn: () -> Unit = {},
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    onAuthCancelled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fallbackScope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val authScope = remember(activity, fallbackScope) {
        (activity as? LifecycleOwner)?.lifecycleScope ?: fallbackScope
    }
    val credentialManager = remember(context) { CredentialManager.create(activity ?: context) }
    var isLoading by remember { mutableStateOf(false) }

    Button(
        onClick = {
            onBeforeSignIn()
            isLoading = true
            onGoogleSignInClicked(
                context = activity ?: context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    isLoading = false
                    onAuthSuccess()
                },
                onAuthError = { errorMsg ->
                    isLoading = false
                    onAuthError(errorMsg)
                },
                scope = authScope,
                onAuthCancelled = {
                    isLoading = false
                    onAuthCancelled()
                }
            )
        },
        enabled = enabled && !isLoading,
        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("google_sign_in_button")
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.Black,
                strokeWidth = 2.5.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "SIGN IN WITH GOOGLE",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
