package com.example.game.network

import android.util.Log
import com.example.BuildConfig

/**
 * Centralized privacy-safe logging utility.
 * - Redacts Firebase UIDs, emails, tenant IDs, provider details, and auth/JWT tokens from all messages.
 * - Masks UID segments inside Cloud Firestore document/collection paths.
 * - Gates all diagnostic Logcat output strictly behind BuildConfig.DEBUG so release builds never leak logs.
 */
object SafeLog {

    enum class Level { DEBUG, WARN, ERROR }

    fun interface LogSink {
        fun log(level: Level, tag: String, message: String)
    }

    private val defaultAndroidSink = LogSink { level, tag, message ->
        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message)
            }
        }
    }

    @Volatile
    private var sinkOverride: LogSink? = null

    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")
    private val BEARER_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val KEY_VALUE_PII_REGEX = Regex(
        """(?i)\b(uid|userId|user_id|email|tenantId|tenant_id|idToken|id_token|accessToken|access_token|token)\s*([=:])\s*("?)([^"\s,;}]+)("?)"""
    )
    private val FIRESTORE_USER_PATH_REGEX = Regex("""\b(users|leaderboard)/([A-Za-z0-9_\-]+)""")

    fun d(
        tag: String,
        message: String,
        isDebug: Boolean = BuildConfig.DEBUG,
        sink: LogSink? = null
    ): String? = emit(Level.DEBUG, tag, message, isDebug, sink)

    fun w(
        tag: String,
        message: String,
        isDebug: Boolean = BuildConfig.DEBUG,
        sink: LogSink? = null
    ): String? = emit(Level.WARN, tag, message, isDebug, sink)

    fun e(
        tag: String,
        message: String,
        isDebug: Boolean = BuildConfig.DEBUG,
        sink: LogSink? = null
    ): String? = emit(Level.ERROR, tag, message, isDebug, sink)

    private fun emit(
        level: Level,
        tag: String,
        message: String,
        isDebug: Boolean,
        sink: LogSink?
    ): String? {
        if (!isDebug) return null
        val sanitized = redactText(message)
        (sink ?: sinkOverride ?: defaultAndroidSink).log(level, tag, sanitized)
        return sanitized
    }

    /**
     * Masks user-scoped ID segments in Firestore paths such as:
     * - "users/abc123UID/destroyed_barracks/BARRACKS_HUMAN_1" -> "users/[REDACTED_UID]/destroyed_barracks/BARRACKS_HUMAN_1"
     * - "leaderboard/abc123UID" -> "leaderboard/[REDACTED_UID]"
     */
    fun sanitizeFirestorePath(path: String?): String? {
        if (path.isNullOrBlank()) return path
        val maskedPath = FIRESTORE_USER_PATH_REGEX.replace(path.trim()) { match ->
            "${match.groupValues[1]}/[REDACTED_UID]"
        }
        return redactText(maskedPath)
    }

    /**
     * Redacts emails, JWT/OAuth tokens, bearer credentials, Firestore user paths, and
     * key=value / JSON pairs referencing UID, email, tenantId, or tokens.
     */
    fun redactText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var cleaned = raw
        cleaned = EMAIL_REGEX.replace(cleaned, "[REDACTED_EMAIL]")
        cleaned = JWT_REGEX.replace(cleaned, "[REDACTED_TOKEN]")
        cleaned = BEARER_REGEX.replace(cleaned, "Bearer [REDACTED_TOKEN]")
        cleaned = FIRESTORE_USER_PATH_REGEX.replace(cleaned) { match ->
            "${match.groupValues[1]}/[REDACTED_UID]"
        }
        cleaned = KEY_VALUE_PII_REGEX.replace(cleaned) { match ->
            val key = match.groupValues[1]
            val sep = match.groupValues[2]
            val qOpen = match.groupValues[3]
            val qClose = match.groupValues[5]
            val replacement = when (key.lowercase()) {
                "email" -> "[REDACTED_EMAIL]"
                "tenantid", "tenant_id" -> "[REDACTED_TENANT]"
                "idtoken", "id_token", "accesstoken", "access_token", "token" -> "[REDACTED_TOKEN]"
                else -> "[REDACTED_UID]"
            }
            "$key$sep$qOpen$replacement$qClose"
        }
        return cleaned
    }

    /**
     * Produces a safe one-line summary of a Throwable without dumping raw stack traces or PII.
     */
    fun safeThrowableSummary(throwable: Throwable?): String {
        if (throwable == null) return "UnknownError"
        val className = throwable::class.java.simpleName.ifBlank { "Exception" }
        val sanitizedMsg = redactText(throwable.message).trim()
        return if (sanitizedMsg.isNotEmpty()) "$className: $sanitizedMsg" else className
    }

    internal fun setSinkForTest(sink: LogSink?) {
        sinkOverride = sink
    }
}
