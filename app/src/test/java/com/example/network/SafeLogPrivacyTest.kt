package com.example.network

import com.example.game.network.OperationType
import com.example.game.network.SafeLog
import com.example.game.network.handleFirestoreError
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class SafeLogPrivacyTest {

    @After
    fun tearDown() {
        SafeLog.setSinkForTest(null)
    }

    @Test
    fun redactText_stripsEmailsTokensUidTenantAndUserPaths() {
        val fakeJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJwaWxvdF91aWRfMTIzIn0.c2lnbmF0dXJl"
        val raw = "Auth error uid=FirebaseUid9988 email=pilot.ace@example.com tenantId=tenant-abc-77 " +
            "Bearer $fakeJwt at users/FirebaseUid9988/destroyed_barracks/BARRACKS_HUMAN_1 " +
            "and leaderboard/FirebaseUid9988"

        val redacted = SafeLog.redactText(raw)

        assertFalse("Must remove raw UID", redacted.contains("FirebaseUid9988"))
        assertFalse("Must remove raw email", redacted.contains("pilot.ace@example.com"))
        assertFalse("Must remove tenantId value", redacted.contains("tenant-abc-77"))
        assertFalse("Must remove raw JWT", redacted.contains(fakeJwt))
        assertTrue(redacted.contains("[REDACTED_EMAIL]"))
        assertTrue(redacted.contains("[REDACTED_TOKEN]"))
        assertTrue(redacted.contains("[REDACTED_TENANT]"))
        assertTrue(redacted.contains("users/[REDACTED_UID]/destroyed_barracks/BARRACKS_HUMAN_1"))
        assertTrue(redacted.contains("leaderboard/[REDACTED_UID]"))
    }

    @Test
    fun sanitizeFirestorePath_masksUserSegmentsWithoutCorruptingCollectionNames() {
        assertEquals(
            "users/[REDACTED_UID]/destroyed_barracks/BARRACKS_HUMAN_1",
            SafeLog.sanitizeFirestorePath("users/uid_secret_12345/destroyed_barracks/BARRACKS_HUMAN_1")
        )
        assertEquals(
            "leaderboard/[REDACTED_UID]",
            SafeLog.sanitizeFirestorePath("leaderboard/uid_secret_12345")
        )
        assertEquals(
            "leaderboard",
            SafeLog.sanitizeFirestorePath("leaderboard")
        )
        assertNull(SafeLog.sanitizeFirestorePath(null))
    }

    @Test
    fun safeLog_suppressesAllLogsWhenNotDebug_andSanitizesWhenDebug() {
        val captured = mutableListOf<Triple<SafeLog.Level, String, String>>()
        val sink = SafeLog.LogSink { level, tag, msg ->
            captured += Triple(level, tag, msg)
        }

        // Release simulation (isDebug = false): must emit zero logs
        assertNull(SafeLog.d("AuthTag", "uid=uid123 email=user@test.com", isDebug = false, sink = sink))
        assertNull(SafeLog.w("AuthTag", "token=abc123SECRET", isDebug = false, sink = sink))
        assertNull(SafeLog.e("AuthTag", "tenantId=my-tenant", isDebug = false, sink = sink))
        assertTrue("Release logging must emit 0 lines", captured.isEmpty())

        // Debug simulation (isDebug = true): logs are emitted but scrubbed
        val emitted = SafeLog.e(
            "AuthTag",
            "Sign-in failed uid=SecretUid99 email=secret@pilot.org",
            isDebug = true,
            sink = sink
        )
        assertEquals(1, captured.size)
        assertFalse(emitted!!.contains("SecretUid99"))
        assertFalse(emitted.contains("secret@pilot.org"))
        assertTrue(emitted.contains("[REDACTED_UID]"))
        assertTrue(emitted.contains("[REDACTED_EMAIL]"))
    }

    @Test
    fun handleFirestoreError_preservesDiagnosticsMasksPathAndExcludesAllPii() {
        val logged = mutableListOf<String>()
        val sink = SafeLog.LogSink { _, _, msg -> logged += msg }

        val exception = FirebaseFirestoreException(
            "Missing permissions for uid=SecretFirebaseUid42 email=commander@hellwar.io tenantId=tenant-xyz Bearer eyJabc.def.ghi",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )

        // 1. Release mode (isDebug = false): must NOT log anything, JSON must still stay redacted
        val releaseJsonString = handleFirestoreError(
            exception = exception,
            operationType = OperationType.WRITE,
            path = "users/SecretFirebaseUid42/destroyed_barracks/BARRACKS_DRAGON_2",
            isDebug = false,
            hasActiveSession = true,
            sink = sink
        )
        assertTrue("Release build must never log Firestore errors", logged.isEmpty())

        val releaseJson = JSONObject(releaseJsonString)
        assertEquals("PERMISSION_DENIED", releaseJson.getString("errorCode"))
        assertEquals("write", releaseJson.getString("operationType"))
        assertEquals(
            "users/[REDACTED_UID]/destroyed_barracks/BARRACKS_DRAGON_2",
            releaseJson.getString("path")
        )
        assertEquals("signed_in", releaseJson.getString("sessionState"))
        assertFalse("Must never contain authInfo key", releaseJson.has("authInfo"))
        assertFalse("Must never contain emailVerified", releaseJsonString.contains("emailVerified"))
        assertFalse("Must never contain providerInfo", releaseJsonString.contains("providerInfo"))
        assertFalse("Must never contain raw UID", releaseJsonString.contains("SecretFirebaseUid42"))
        assertFalse("Must never contain raw email", releaseJsonString.contains("commander@hellwar.io"))
        assertFalse("Must never contain raw tenantId", releaseJsonString.contains("tenant-xyz"))
        assertFalse("Must never contain raw token", releaseJsonString.contains("eyJabc.def.ghi"))

        // 2. Debug mode (isDebug = true): logs sanitized JSON with signed_out state
        val debugJsonString = handleFirestoreError(
            exception = exception,
            operationType = OperationType.GET,
            path = "leaderboard/SecretFirebaseUid42",
            isDebug = true,
            hasActiveSession = false,
            sink = sink
        )
        assertEquals(1, logged.size)
        val loggedLine = logged.single()
        val loggedJson = JSONObject(loggedLine.removePrefix("Firestore Error: "))
        assertFalse(loggedLine.contains("SecretFirebaseUid42"))
        assertFalse(loggedLine.contains("commander@hellwar.io"))
        assertEquals("PERMISSION_DENIED", loggedJson.getString("errorCode"))
        assertEquals("leaderboard/[REDACTED_UID]", loggedJson.getString("path"))
        assertEquals("signed_out", loggedJson.getString("sessionState"))
        assertEquals("get", JSONObject(debugJsonString).getString("operationType"))
    }

    @Test
    fun safeThrowableSummary_returnsSingleLineWithoutStackTraceOrPii() {
        val err = IllegalStateException("Credential failed for pilot@gmail.com uid=xyz_777\n\tat com.example.Secret()")
        val summary = SafeLog.safeThrowableSummary(err)

        assertTrue(summary.startsWith("IllegalStateException:"))
        assertFalse(summary.contains("pilot@gmail.com"))
        assertFalse(summary.contains("xyz_777"))
        assertTrue(summary.contains("[REDACTED_EMAIL]"))
        assertTrue(summary.contains("[REDACTED_UID]"))
    }
}
