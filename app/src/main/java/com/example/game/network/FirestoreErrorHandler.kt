package com.example.game.network

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

/**
 * Sanitizes Firestore errors into non-PII structured JSON diagnostics and logs only in debug builds.
 * Never logs or serializes Firebase UID, email, emailVerified, tenantId, tokens, or provider emails.
 */
fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    isDebug: Boolean = BuildConfig.DEBUG,
    hasActiveSession: Boolean = runCatching { Firebase.auth.currentUser != null }.getOrDefault(false),
    sink: SafeLog.LogSink? = null
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val firestoreCode = (rootError as? FirebaseFirestoreException)?.code?.name
        ?: rootError::class.java.simpleName.ifBlank { "FirestoreError" }
    val sanitizedMessage = SafeLog.redactText(rootError.message)
        .ifBlank { firestoreCode }
    val sanitizedPath = SafeLog.sanitizeFirestorePath(path)

    val errorInfoJson = JSONObject().apply {
        put("errorCode", firestoreCode)
        put("error", sanitizedMessage)
        put("operationType", operationType.value)
        put("path", sanitizedPath ?: JSONObject.NULL)
        put("sessionState", if (hasActiveSession) "signed_in" else "signed_out")
    }

    val jsonString = errorInfoJson.toString()
    SafeLog.e(
        tag = "FirestoreError",
        message = "Firestore Error: $jsonString",
        isDebug = isDebug,
        sink = sink
    )
    return jsonString
}
