package com.example.base

import com.example.game.network.DestroyedBarracksRecord
import com.example.game.network.HeadquartersVictoryRecord
import com.example.game.network.UserCampaignProfile
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.AdditionalUserInfo
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserInfo
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local in-memory Firebase Auth & Firestore security-rules test engine used automatically
 * when the external Firebase Emulator Suite (ports 8085 / 9099) is not running during
 * JVM/Robolectric unit tests. Enforces the exact rules defined in `/firestore.rules`.
 */
object LocalFirebaseRuleEngine {
    @Volatile
    var useLocalShadows: Boolean = false

    private data class LocalUser(val uid: String, val email: String)

    private val usersByEmail = ConcurrentHashMap<String, LocalUser>()

    @Volatile
    private var activeUser: LocalUser? = null

    private val documents = ConcurrentHashMap<String, MutableMap<String, Any?>>()
    private val dbLock = Any()

    private val validEmpires = setOf("HUMAN", "ORC", "DRAGON", "ALIEN")
    private val idRegex = Regex("^[a-zA-Z0-9_\\-]+$")

    fun reset() {
        synchronized(dbLock) {
            usersByEmail.clear()
            activeUser = null
            documents.clear()
        }
    }

    fun currentUser(): FirebaseUser? {
        val user = activeUser ?: return null
        return buildFirebaseUser(user.uid, user.email)
    }

    fun signOut() {
        activeUser = null
    }

    fun signInWithEmailAndPassword(email: String): Task<AuthResult> {
        val existing = usersByEmail[email]
            ?: return Tasks.forException(
                FirebaseAuthInvalidUserException(
                    "ERROR_USER_NOT_FOUND",
                    "There is no user record corresponding to $email."
                )
            )
        activeUser = existing
        return Tasks.forResult(buildAuthResult(buildFirebaseUser(existing.uid, existing.email)))
    }

    fun createUserWithEmailAndPassword(email: String): Task<AuthResult> {
        val created = usersByEmail.computeIfAbsent(email) {
            LocalUser(uid = "uid_${UUID.randomUUID().toString().replace("-", "").take(16)}", email = email)
        }
        activeUser = created
        return Tasks.forResult(buildAuthResult(buildFirebaseUser(created.uid, created.email)))
    }

    private fun buildFirebaseUser(uid: String, email: String): FirebaseUser {
        val app = FirebaseApp.getInstance()
        val userInfoClass = Class.forName("com.google.firebase.auth.internal.zzz")
        val ctor = userInfoClass.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java
        )
        val userInfo = ctor.newInstance(uid, "firebase", email, null, email, null, true, null) as UserInfo
        val userClass = Class.forName("com.google.firebase.auth.internal.zzad")
        val userCtor = userClass.getConstructor(FirebaseApp::class.java, List::class.java)
        return userCtor.newInstance(app, listOf(userInfo)) as FirebaseUser
    }

    private fun buildAuthResult(firebaseUser: FirebaseUser): AuthResult {
        return Proxy.newProxyInstance(
            AuthResult::class.java.classLoader,
            arrayOf(AuthResult::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getUser" -> firebaseUser
                "getAdditionalUserInfo" -> null as AdditionalUserInfo?
                "getCredential" -> null as AuthCredential?
                "describeContents" -> 0
                else -> null
            }
        } as AuthResult
    }

    private fun permissionDenied(message: String): FirebaseFirestoreException {
        return FirebaseFirestoreException(
            "PERMISSION_DENIED: $message",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
    }

    private fun isValidId(id: String): Boolean {
        return id.length in 1..128 && idRegex.matches(id)
    }

    private fun isOwner(userId: String): Boolean {
        val currentUid = activeUser?.uid ?: return false
        return currentUid == userId
    }

    private fun materializeMap(raw: Any): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val sourceMap = raw as? Map<String, Any?>
            ?: throw permissionDenied("Unsupported write payload type")
        val out = LinkedHashMap<String, Any?>()
        val now = Timestamp.now()
        for ((k, v) in sourceMap) {
            out[k] = when {
                v == null -> null
                v === FieldValue.serverTimestamp() || v is FieldValue -> now
                v is Int -> v.toLong()
                else -> v
            }
        }
        return out
    }

    private fun asStrictLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            else -> null
        }
    }

    private fun isValidTimestamp(value: Any?, requestTime: Timestamp = Timestamp.now()): Boolean {
        val ts = value as? Timestamp ?: return false
        return ts.seconds < requestTime.seconds ||
            (ts.seconds == requestTime.seconds && ts.nanoseconds <= requestTime.nanoseconds)
    }

    private fun isValidUserProfile(data: Map<String, Any?>, userId: String): Boolean {
        val uId = data["userId"] as? String ?: return false
        if (uId != userId || uId.length !in 1..128) return false
        val callsign = data["callsign"] as? String ?: return false
        if (callsign.length !in 2..32) return false
        val credits = asStrictLong(data["credits"]) ?: return false
        val highScore = asStrictLong(data["highScore"]) ?: return false
        val human = asStrictLong(data["humanDestroyedCount"]) ?: return false
        val orc = asStrictLong(data["orcDestroyedCount"]) ?: return false
        val dragon = asStrictLong(data["dragonDestroyedCount"]) ?: return false
        val alien = asStrictLong(data["alienDestroyedCount"]) ?: return false
        if (credits !in 0L..100_000_000L || highScore !in 0L..100_000_000L) return false
        if (human !in 0L..50L || orc !in 0L..50L || dragon !in 0L..50L || alien !in 0L..50L) return false
        val now = Timestamp.now()
        if (!isValidTimestamp(data["createdAt"], now) || !isValidTimestamp(data["updatedAt"], now)) return false
        return true
    }

    private fun isValidDestroyedBarracks(
        data: Map<String, Any?>,
        userId: String,
        docId: String
    ): Boolean {
        val uId = data["userId"] as? String ?: return false
        if (uId != userId || uId.length > 128) return false
        val empire = data["empire"] as? String ?: return false
        if (empire.length > 16 || empire !in validEmpires) return false
        val index = asStrictLong(data["barracksIndex"]) ?: return false
        if (index !in 1L..50L) return false
        val barracksId = data["barracksId"] as? String ?: return false
        if (barracksId.length > 40 || barracksId != "BARRACKS_${empire}_$index" || docId != barracksId) {
            return false
        }
        val damage = (data["damageDealt"] as? Number)?.toDouble() ?: return false
        if (damage.isNaN() || damage.isInfinite() || damage < 320.0) return false
        val creditsAwarded = asStrictLong(data["creditsAwarded"]) ?: return false
        val scoreAwarded = asStrictLong(data["scoreAwarded"]) ?: return false
        if (creditsAwarded != 200L || scoreAwarded != 1000L) return false
        if (!isValidTimestamp(data["destroyedAt"])) return false
        return true
    }

    private fun isValidHqVictory(
        data: Map<String, Any?>,
        userId: String,
        docId: String
    ): Boolean {
        val uId = data["userId"] as? String ?: return false
        if (uId != userId || uId.length > 128) return false
        val empire = data["empire"] as? String ?: return false
        if (empire.length > 16 || empire !in validEmpires || docId != empire) return false
        val bossId = data["bossId"] as? String ?: return false
        if (bossId.length > 32 || bossId != "BOSS_$empire") return false
        val creditsAwarded = asStrictLong(data["creditsAwarded"]) ?: return false
        val scoreAwarded = asStrictLong(data["scoreAwarded"]) ?: return false
        if (creditsAwarded != 2500L || scoreAwarded != 15000L) return false
        if (!isValidTimestamp(data["victoryAt"])) return false
        return true
    }

    private fun hasCompletedAll50Barracks(
        backing: Map<String, MutableMap<String, Any?>>,
        userId: String,
        empire: String
    ): Boolean {
        val profile = backing["users/$userId"] ?: return false
        val field = when (empire) {
            "HUMAN" -> "humanDestroyedCount"
            "ORC" -> "orcDestroyedCount"
            "DRAGON" -> "dragonDestroyedCount"
            "ALIEN" -> "alienDestroyedCount"
            else -> return false
        }
        val count = asStrictLong(profile[field]) ?: 0L
        return count >= 50L
    }

    private fun validateCreate(
        backing: Map<String, MutableMap<String, Any?>>,
        path: String,
        incoming: Map<String, Any?>
    ) {
        val parts = path.split("/")
        when {
            parts.size == 2 && parts[0] == "users" -> {
                val userId = parts[1]
                if (!isOwner(userId) || !isValidId(userId)) {
                    throw permissionDenied("Unauthorized user profile create for $path")
                }
                val expectedKeys = setOf(
                    "userId", "callsign", "credits", "highScore",
                    "humanDestroyedCount", "orcDestroyedCount",
                    "dragonDestroyedCount", "alienDestroyedCount",
                    "createdAt", "updatedAt"
                )
                if (incoming.keys != expectedKeys) {
                    throw permissionDenied("Invalid user profile keys for $path")
                }
                if (asStrictLong(incoming["credits"]) != 18500L ||
                    asStrictLong(incoming["highScore"]) != 0L ||
                    asStrictLong(incoming["humanDestroyedCount"]) != 0L ||
                    asStrictLong(incoming["orcDestroyedCount"]) != 0L ||
                    asStrictLong(incoming["dragonDestroyedCount"]) != 0L ||
                    asStrictLong(incoming["alienDestroyedCount"]) != 0L ||
                    !isValidUserProfile(incoming, userId)
                ) {
                    throw permissionDenied("User profile creation failed rules validation")
                }
            }
            parts.size == 4 && parts[0] == "users" && parts[2] == "destroyed_barracks" -> {
                val userId = parts[1]
                val barracksId = parts[3]
                if (!isOwner(userId) || !isValidId(userId) || !isValidId(barracksId)) {
                    throw permissionDenied("Unauthorized barracks write for $path")
                }
                val expectedKeys = setOf(
                    "userId", "barracksId", "empire", "barracksIndex",
                    "damageDealt", "creditsAwarded", "scoreAwarded", "destroyedAt"
                )
                if (incoming.keys != expectedKeys || !isValidDestroyedBarracks(incoming, userId, barracksId)) {
                    throw permissionDenied("Barracks write failed rules validation for $path")
                }
            }
            parts.size == 4 && parts[0] == "users" && parts[2] == "hq_victories" -> {
                val userId = parts[1]
                val empire = parts[3]
                if (!isOwner(userId) || !isValidId(userId) || !isValidId(empire)) {
                    throw permissionDenied("Unauthorized HQ victory write for $path")
                }
                val expectedKeys = setOf(
                    "userId", "empire", "bossId",
                    "creditsAwarded", "scoreAwarded", "victoryAt"
                )
                if (incoming.keys != expectedKeys ||
                    !isValidHqVictory(incoming, userId, empire) ||
                    !hasCompletedAll50Barracks(backing, userId, empire)
                ) {
                    throw permissionDenied("HQ victory rejected for $path (requires 50/50 barracks)")
                }
            }
            else -> throw permissionDenied("Default deny for create on $path")
        }
    }

    private fun validateUpdate(
        path: String,
        existing: Map<String, Any?>,
        incoming: Map<String, Any?>,
        changedKeys: Set<String>
    ) {
        val parts = path.split("/")
        if (parts.size == 2 && parts[0] == "users") {
            val userId = parts[1]
            if (!isOwner(userId) || !isValidId(userId) ||
                !isValidUserProfile(incoming, userId) ||
                incoming["userId"] != existing["userId"] ||
                incoming["createdAt"] != existing["createdAt"]
            ) {
                throw permissionDenied("Unauthorized or invalid user profile update for $path")
            }
            val renameKeys = setOf("callsign", "updatedAt")
            val campaignKeys = setOf(
                "credits", "highScore", "humanDestroyedCount",
                "orcDestroyedCount", "dragonDestroyedCount",
                "alienDestroyedCount", "updatedAt"
            )
            val isRename = changedKeys.all { it in renameKeys }
            val inCredits = asStrictLong(incoming["credits"])!!
            val exCredits = asStrictLong(existing["credits"])!!
            val inScore = asStrictLong(incoming["highScore"])!!
            val exScore = asStrictLong(existing["highScore"])!!
            val inHuman = asStrictLong(incoming["humanDestroyedCount"])!!
            val exHuman = asStrictLong(existing["humanDestroyedCount"])!!
            val inOrc = asStrictLong(incoming["orcDestroyedCount"])!!
            val exOrc = asStrictLong(existing["orcDestroyedCount"])!!
            val inDragon = asStrictLong(incoming["dragonDestroyedCount"])!!
            val exDragon = asStrictLong(existing["dragonDestroyedCount"])!!
            val inAlien = asStrictLong(incoming["alienDestroyedCount"])!!
            val exAlien = asStrictLong(existing["alienDestroyedCount"])!!

            val isCampaign = changedKeys.all { it in campaignKeys } &&
                (inCredits in exCredits..(exCredits + 2500L)) &&
                (inScore in exScore..(exScore + 15000L)) &&
                (inHuman in exHuman..(exHuman + 1L)) &&
                (inOrc in exOrc..(exOrc + 1L)) &&
                (inDragon in exDragon..(exDragon + 1L)) &&
                (inAlien in exAlien..(exAlien + 1L))

            if (!isRename && !isCampaign) {
                throw permissionDenied("Profile update violated allowed keys or monotonic limits on $path")
            }
            return
        }
        throw permissionDenied("Updates are denied on $path")
    }

    private fun checkGetPermission(path: String) {
        val parts = path.split("/")
        when {
            parts.size == 2 && parts[0] == "users" -> {
                if (!isOwner(parts[1]) || !isValidId(parts[1])) {
                    throw permissionDenied("Read denied on $path")
                }
            }
            parts.size == 4 && parts[0] == "users" &&
                (parts[2] == "destroyed_barracks" || parts[2] == "hq_victories") -> {
                if (!isOwner(parts[1]) || !isValidId(parts[1]) || !isValidId(parts[3])) {
                    throw permissionDenied("Read denied on $path")
                }
            }
            else -> throw permissionDenied("Default deny read on $path")
        }
    }

    private fun checkListPermission(colPath: String) {
        val parts = colPath.split("/")
        when {
            parts.size == 3 && parts[0] == "users" &&
                (parts[2] == "destroyed_barracks" || parts[2] == "hq_victories") -> {
                if (!isOwner(parts[1])) {
                    throw permissionDenied("List denied on $colPath")
                }
            }
            else -> throw permissionDenied("List denied on $colPath")
        }
    }

    fun getDocumentSync(
        firestore: FirebaseFirestore,
        path: String,
        backing: Map<String, MutableMap<String, Any?>> = documents
    ): DocumentSnapshot {
        checkGetPermission(path)
        val stored = backing[path]?.let { LinkedHashMap(it) }
        return buildDocumentSnapshot(firestore, path, stored)
    }

    fun getDocumentTask(firestore: FirebaseFirestore, path: String): Task<DocumentSnapshot> {
        return synchronized(dbLock) {
            try {
                Tasks.forResult(getDocumentSync(firestore, path))
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
    }

    fun listCollectionTask(firestore: FirebaseFirestore, colPath: String): Task<QuerySnapshot> {
        return synchronized(dbLock) {
            try {
                checkListPermission(colPath)
                val prefix = "$colPath/"
                val snaps = documents.entries
                    .filter { (k, _) -> k.startsWith(prefix) && !k.removePrefix(prefix).contains('/') }
                    .map { (k, v) -> buildDocumentSnapshot(firestore, k, LinkedHashMap(v)) }
                Tasks.forResult(buildQuerySnapshot(snaps))
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
    }

    fun setDocumentSync(
        backing: MutableMap<String, MutableMap<String, Any?>>,
        path: String,
        rawData: Any
    ) {
        val incoming = materializeMap(rawData)
        val existing = backing[path]
        if (existing == null) {
            validateCreate(backing, path, incoming)
        } else {
            val changed = (existing.keys + incoming.keys).filter {
                !existing.containsKey(it) || !incoming.containsKey(it) || existing[it] != incoming[it]
            }.toSet()
            validateUpdate(path, existing, incoming, changed)
        }
        backing[path] = incoming
    }

    fun setDocumentTask(path: String, rawData: Any): Task<Void> {
        return synchronized(dbLock) {
            try {
                setDocumentSync(documents, path, rawData)
                Tasks.forResult(null)
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
    }

    fun updateDocumentSync(
        backing: MutableMap<String, MutableMap<String, Any?>>,
        path: String,
        updates: Map<String, Any?>
    ) {
        val existing = backing[path] ?: throw permissionDenied("Document $path does not exist for update")
        val materializedUpdates = materializeMap(updates)
        val merged = LinkedHashMap(existing)
        merged.putAll(materializedUpdates)
        validateUpdate(path, existing, merged, materializedUpdates.keys)
        backing[path] = merged
    }

    fun updateDocumentTask(path: String, updates: Map<String, Any?>): Task<Void> {
        return synchronized(dbLock) {
            try {
                updateDocumentSync(documents, path, updates)
                Tasks.forResult(null)
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
    }

    fun <T> runTransactionTask(
        firestore: FirebaseFirestore,
        fn: Transaction.Function<T>
    ): Task<T> {
        return synchronized(dbLock) {
            try {
                val workingCopy = ConcurrentHashMap<String, MutableMap<String, Any?>>()
                for ((k, v) in documents) {
                    workingCopy[k] = LinkedHashMap(v)
                }
                val tx = Shadow.newInstanceOf(Transaction::class.java)
                val txShadow = Shadow.extract<ShadowLocalTransaction>(tx)
                txShadow.bind(firestore, workingCopy)

                val result = fn.apply(tx)
                txShadow.commitStagedWrites()

                documents.clear()
                documents.putAll(workingCopy)
                Tasks.forResult(result)
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
    }

    fun buildDocumentSnapshot(
        firestore: FirebaseFirestore,
        path: String,
        data: MutableMap<String, Any?>?
    ): DocumentSnapshot {
        val snap = Shadow.newInstanceOf(DocumentSnapshot::class.java)
        val shadow = Shadow.extract<ShadowLocalDocumentSnapshot>(snap)
        shadow.bind(firestore, path, data)
        return snap
    }

    fun buildQuerySnapshot(docs: List<DocumentSnapshot>): QuerySnapshot {
        val qSnap = Shadow.newInstanceOf(QuerySnapshot::class.java)
        val shadow = Shadow.extract<ShadowLocalQuerySnapshot>(qSnap)
        shadow.bind(docs)
        return qSnap
    }

    fun <T> mapToObject(path: String, data: Map<String, Any?>?, clazz: Class<T>): T? {
        if (data == null) return null
        val docId = path.substringAfterLast('/')
        @Suppress("UNCHECKED_CAST")
        return when (clazz) {
            UserCampaignProfile::class.java -> UserCampaignProfile(
                userId = data["userId"] as? String ?: "",
                callsign = data["callsign"] as? String ?: "VIPER-7",
                credits = (data["credits"] as? Number)?.toLong() ?: 18500L,
                highScore = (data["highScore"] as? Number)?.toLong() ?: 0L,
                humanDestroyedCount = (data["humanDestroyedCount"] as? Number)?.toLong() ?: 0L,
                orcDestroyedCount = (data["orcDestroyedCount"] as? Number)?.toLong() ?: 0L,
                dragonDestroyedCount = (data["dragonDestroyedCount"] as? Number)?.toLong() ?: 0L,
                alienDestroyedCount = (data["alienDestroyedCount"] as? Number)?.toLong() ?: 0L,
                createdAt = data["createdAt"] as? Timestamp,
                updatedAt = data["updatedAt"] as? Timestamp
            ) as T
            DestroyedBarracksRecord::class.java -> DestroyedBarracksRecord(
                id = docId,
                userId = data["userId"] as? String ?: "",
                barracksId = data["barracksId"] as? String ?: docId,
                empire = data["empire"] as? String ?: "",
                barracksIndex = (data["barracksIndex"] as? Number)?.toLong() ?: 0L,
                damageDealt = (data["damageDealt"] as? Number)?.toDouble() ?: 0.0,
                creditsAwarded = (data["creditsAwarded"] as? Number)?.toLong() ?: 0L,
                scoreAwarded = (data["scoreAwarded"] as? Number)?.toLong() ?: 0L,
                destroyedAt = data["destroyedAt"] as? Timestamp
            ) as T
            HeadquartersVictoryRecord::class.java -> HeadquartersVictoryRecord(
                id = docId,
                userId = data["userId"] as? String ?: "",
                empire = data["empire"] as? String ?: docId,
                bossId = data["bossId"] as? String ?: "",
                creditsAwarded = (data["creditsAwarded"] as? Number)?.toLong() ?: 0L,
                scoreAwarded = (data["scoreAwarded"] as? Number)?.toLong() ?: 0L,
                victoryAt = data["victoryAt"] as? Timestamp
            ) as T
            else -> null
        }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class ShadowLocalFirebaseAuth {
    @RealObject
    private lateinit var realAuth: FirebaseAuth

    @Implementation
    fun useEmulator(host: String, port: Int) {
        if (!LocalFirebaseRuleEngine.useLocalShadows) {
            Shadow.directlyOn<Any?, FirebaseAuth>(
                realAuth,
                FirebaseAuth::class.java,
                "useEmulator",
                ClassParameter.from(String::class.java, host),
                ClassParameter.from(Int::class.javaPrimitiveType, port)
            )
        }
    }

    @Implementation
    fun getCurrentUser(): FirebaseUser? {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.currentUser()
        }
        return Shadow.directlyOn(realAuth, FirebaseAuth::class.java, "getCurrentUser")
    }

    @Implementation
    fun signOut() {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            LocalFirebaseRuleEngine.signOut()
            return
        }
        Shadow.directlyOn<Any?, FirebaseAuth>(realAuth, FirebaseAuth::class.java, "signOut")
    }

    @Implementation
    fun signInWithEmailAndPassword(email: String, password: String): Task<AuthResult> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.signInWithEmailAndPassword(email)
        }
        return Shadow.directlyOn(
            realAuth,
            FirebaseAuth::class.java,
            "signInWithEmailAndPassword",
            ClassParameter.from(String::class.java, email),
            ClassParameter.from(String::class.java, password)
        )
    }

    @Implementation
    fun createUserWithEmailAndPassword(email: String, password: String): Task<AuthResult> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.createUserWithEmailAndPassword(email)
        }
        return Shadow.directlyOn(
            realAuth,
            FirebaseAuth::class.java,
            "createUserWithEmailAndPassword",
            ClassParameter.from(String::class.java, email),
            ClassParameter.from(String::class.java, password)
        )
    }
}

@Implements(value = FirebaseFirestore::class, isInAndroidSdk = false)
class ShadowLocalFirebaseFirestore {
    @RealObject
    private lateinit var realFirestore: FirebaseFirestore

    @Implementation
    fun useEmulator(host: String, port: Int) {
        if (!LocalFirebaseRuleEngine.useLocalShadows) {
            Shadow.directlyOn<Any?, FirebaseFirestore>(
                realFirestore,
                FirebaseFirestore::class.java,
                "useEmulator",
                ClassParameter.from(String::class.java, host),
                ClassParameter.from(Int::class.javaPrimitiveType, port)
            )
        }
    }

    @Implementation
    fun setFirestoreSettings(settings: FirebaseFirestoreSettings) {
        if (!LocalFirebaseRuleEngine.useLocalShadows) {
            Shadow.directlyOn<Any?, FirebaseFirestore>(
                realFirestore,
                FirebaseFirestore::class.java,
                "setFirestoreSettings",
                ClassParameter.from(FirebaseFirestoreSettings::class.java, settings)
            )
        }
    }

    @Implementation
    fun collection(collectionPath: String): CollectionReference {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val col = Shadow.newInstanceOf(CollectionReference::class.java)
            Shadow.extract<ShadowLocalCollectionReference>(col).bind(realFirestore, collectionPath)
            return col
        }
        return Shadow.directlyOn(
            realFirestore,
            FirebaseFirestore::class.java,
            "collection",
            ClassParameter.from(String::class.java, collectionPath)
        )
    }

    @Implementation
    fun <TResult> runTransaction(updateFunction: Transaction.Function<TResult>): Task<TResult> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.runTransactionTask(realFirestore, updateFunction)
        }
        return Shadow.directlyOn(
            realFirestore,
            FirebaseFirestore::class.java,
            "runTransaction",
            ClassParameter.from(Transaction.Function::class.java, updateFunction)
        )
    }

    @Implementation
    fun <TResult> runTransaction(
        options: TransactionOptions,
        updateFunction: Transaction.Function<TResult>
    ): Task<TResult> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.runTransactionTask(realFirestore, updateFunction)
        }
        return Shadow.directlyOn(
            realFirestore,
            FirebaseFirestore::class.java,
            "runTransaction",
            ClassParameter.from(TransactionOptions::class.java, options),
            ClassParameter.from(Transaction.Function::class.java, updateFunction)
        )
    }
}

@Implements(value = Query::class, isInAndroidSdk = false)
open class ShadowLocalQuery {
    @RealObject
    private lateinit var realQuery: Query

    internal lateinit var firestore: FirebaseFirestore
    internal lateinit var colPath: String

    fun bind(fs: FirebaseFirestore, path: String) {
        firestore = fs
        colPath = path
    }

    @Implementation
    fun get(): Task<QuerySnapshot> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.listCollectionTask(firestore, colPath)
        }
        return Shadow.directlyOn(realQuery, Query::class.java, "get")
    }

    @Implementation
    fun get(source: Source): Task<QuerySnapshot> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.listCollectionTask(firestore, colPath)
        }
        return Shadow.directlyOn(
            realQuery,
            Query::class.java,
            "get",
            ClassParameter.from(Source::class.java, source)
        )
    }
}

@Implements(value = CollectionReference::class, isInAndroidSdk = false)
class ShadowLocalCollectionReference : ShadowLocalQuery() {
    @RealObject
    private lateinit var realCol: CollectionReference

    @Implementation
    fun getPath(): String {
        if (LocalFirebaseRuleEngine.useLocalShadows) return colPath
        return Shadow.directlyOn(realCol, CollectionReference::class.java, "getPath")
    }

    @Implementation
    fun getId(): String {
        if (LocalFirebaseRuleEngine.useLocalShadows) return colPath.substringAfterLast('/')
        return Shadow.directlyOn(realCol, CollectionReference::class.java, "getId")
    }

    @Implementation
    fun document(): DocumentReference {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return document("doc_${UUID.randomUUID().toString().replace("-", "").take(16)}")
        }
        return Shadow.directlyOn(realCol, CollectionReference::class.java, "document")
    }

    @Implementation
    fun document(documentPath: String): DocumentReference {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val doc = Shadow.newInstanceOf(DocumentReference::class.java)
            Shadow.extract<ShadowLocalDocumentReference>(doc).bind(firestore, "$colPath/$documentPath")
            return doc
        }
        return Shadow.directlyOn(
            realCol,
            CollectionReference::class.java,
            "document",
            ClassParameter.from(String::class.java, documentPath)
        )
    }
}

@Implements(value = DocumentReference::class, isInAndroidSdk = false)
class ShadowLocalDocumentReference {
    @RealObject
    private lateinit var realDoc: DocumentReference

    internal lateinit var firestore: FirebaseFirestore
    internal lateinit var docPath: String

    fun bind(fs: FirebaseFirestore, path: String) {
        firestore = fs
        docPath = path
    }

    @Implementation
    fun getPath(): String {
        if (LocalFirebaseRuleEngine.useLocalShadows) return docPath
        return Shadow.directlyOn(realDoc, DocumentReference::class.java, "getPath")
    }

    @Implementation
    fun getId(): String {
        if (LocalFirebaseRuleEngine.useLocalShadows) return docPath.substringAfterLast('/')
        return Shadow.directlyOn(realDoc, DocumentReference::class.java, "getId")
    }

    @Implementation
    fun collection(collectionPath: String): CollectionReference {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val col = Shadow.newInstanceOf(CollectionReference::class.java)
            Shadow.extract<ShadowLocalCollectionReference>(col).bind(firestore, "$docPath/$collectionPath")
            return col
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "collection",
            ClassParameter.from(String::class.java, collectionPath)
        )
    }

    @Implementation
    fun get(): Task<DocumentSnapshot> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.getDocumentTask(firestore, docPath)
        }
        return Shadow.directlyOn(realDoc, DocumentReference::class.java, "get")
    }

    @Implementation
    fun get(source: Source): Task<DocumentSnapshot> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.getDocumentTask(firestore, docPath)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "get",
            ClassParameter.from(Source::class.java, source)
        )
    }

    @Implementation
    fun set(data: Any): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.setDocumentTask(docPath, data)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "set",
            ClassParameter.from(Any::class.java, data)
        )
    }

    @Implementation
    fun set(data: Any, options: SetOptions): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.setDocumentTask(docPath, data)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "set",
            ClassParameter.from(Any::class.java, data),
            ClassParameter.from(SetOptions::class.java, options)
        )
    }

    @Implementation
    fun update(data: Map<String, Any?>): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.updateDocumentTask(docPath, data)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "update",
            ClassParameter.from(Map::class.java, data)
        )
    }

    @Implementation
    fun update(field: String, value: Any?, vararg moreFieldsAndValues: Any?): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val map = LinkedHashMap<String, Any?>()
            map[field] = value
            var i = 0
            while (i + 1 < moreFieldsAndValues.size) {
                map[moreFieldsAndValues[i].toString()] = moreFieldsAndValues[i + 1]
                i += 2
            }
            return LocalFirebaseRuleEngine.updateDocumentTask(docPath, map)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "update",
            ClassParameter.from(String::class.java, field),
            ClassParameter.from(Any::class.java, value),
            ClassParameter.from(Array<Any>::class.java, moreFieldsAndValues)
        )
    }

    @Implementation
    fun update(fieldPath: FieldPath, value: Any?, vararg moreFieldsAndValues: Any?): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return update(fieldPath.toString(), value, *moreFieldsAndValues)
        }
        return Shadow.directlyOn(
            realDoc,
            DocumentReference::class.java,
            "update",
            ClassParameter.from(FieldPath::class.java, fieldPath),
            ClassParameter.from(Any::class.java, value),
            ClassParameter.from(Array<Any>::class.java, moreFieldsAndValues)
        )
    }

    @Implementation
    fun delete(): Task<Void> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return Tasks.forException(
                FirebaseFirestoreException(
                    "PERMISSION_DENIED: Delete denied on $docPath",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                )
            )
        }
        return Shadow.directlyOn(realDoc, DocumentReference::class.java, "delete")
    }
}

@Implements(value = Transaction::class, isInAndroidSdk = false)
class ShadowLocalTransaction {
    @RealObject
    private lateinit var realTx: Transaction

    private lateinit var firestore: FirebaseFirestore
    private lateinit var workingCopy: MutableMap<String, MutableMap<String, Any?>>
    private val pendingOps = mutableListOf<() -> Unit>()

    fun bind(fs: FirebaseFirestore, copy: MutableMap<String, MutableMap<String, Any?>>) {
        firestore = fs
        workingCopy = copy
        pendingOps.clear()
    }

    fun commitStagedWrites() {
        for (op in pendingOps) {
            op()
        }
    }

    @Implementation
    @Throws(FirebaseFirestoreException::class)
    fun get(documentRef: DocumentReference): DocumentSnapshot {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val path = Shadow.extract<ShadowLocalDocumentReference>(documentRef).docPath
            return LocalFirebaseRuleEngine.getDocumentSync(firestore, path, workingCopy)
        }
        return Shadow.directlyOn(
            realTx,
            Transaction::class.java,
            "get",
            ClassParameter.from(DocumentReference::class.java, documentRef)
        )
    }

    @Implementation
    fun set(documentRef: DocumentReference, data: Any): Transaction {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val path = Shadow.extract<ShadowLocalDocumentReference>(documentRef).docPath
            pendingOps.add { LocalFirebaseRuleEngine.setDocumentSync(workingCopy, path, data) }
            return realTx
        }
        return Shadow.directlyOn(
            realTx,
            Transaction::class.java,
            "set",
            ClassParameter.from(DocumentReference::class.java, documentRef),
            ClassParameter.from(Any::class.java, data)
        )
    }

    @Implementation
    fun set(documentRef: DocumentReference, data: Any, options: SetOptions): Transaction {
        return set(documentRef, data)
    }

    @Implementation
    fun update(documentRef: DocumentReference, data: Map<String, Any?>): Transaction {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            val path = Shadow.extract<ShadowLocalDocumentReference>(documentRef).docPath
            pendingOps.add { LocalFirebaseRuleEngine.updateDocumentSync(workingCopy, path, data) }
            return realTx
        }
        return Shadow.directlyOn(
            realTx,
            Transaction::class.java,
            "update",
            ClassParameter.from(DocumentReference::class.java, documentRef),
            ClassParameter.from(Map::class.java, data)
        )
    }
}

@Implements(value = DocumentSnapshot::class, isInAndroidSdk = false)
class ShadowLocalDocumentSnapshot {
    @RealObject
    private lateinit var realSnap: DocumentSnapshot

    private lateinit var firestore: FirebaseFirestore
    private lateinit var docPath: String
    private var storedData: MutableMap<String, Any?>? = null

    fun bind(fs: FirebaseFirestore, path: String, data: MutableMap<String, Any?>?) {
        firestore = fs
        docPath = path
        storedData = data
    }

    @Implementation
    fun exists(): Boolean {
        if (LocalFirebaseRuleEngine.useLocalShadows) return storedData != null
        return Shadow.directlyOn(realSnap, DocumentSnapshot::class.java, "exists")
    }

    @Implementation
    fun getId(): String {
        if (LocalFirebaseRuleEngine.useLocalShadows) return docPath.substringAfterLast('/')
        return Shadow.directlyOn(realSnap, DocumentSnapshot::class.java, "getId")
    }

    @Implementation
    fun getData(): Map<String, Any?>? {
        if (LocalFirebaseRuleEngine.useLocalShadows) return storedData?.let { LinkedHashMap(it) }
        return Shadow.directlyOn(realSnap, DocumentSnapshot::class.java, "getData")
    }

    @Implementation
    fun getLong(field: String): Long? {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return (storedData?.get(field) as? Number)?.toLong()
        }
        return Shadow.directlyOn(
            realSnap,
            DocumentSnapshot::class.java,
            "getLong",
            ClassParameter.from(String::class.java, field)
        )
    }

    @Implementation
    fun <T> toObject(valueType: Class<T>): T? {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.mapToObject(docPath, storedData, valueType)
        }
        return Shadow.directlyOn(
            realSnap,
            DocumentSnapshot::class.java,
            "toObject",
            ClassParameter.from(Class::class.java, valueType)
        )
    }

    @Implementation
    fun <T> toObject(valueType: Class<T>, behavior: DocumentSnapshot.ServerTimestampBehavior): T? {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return LocalFirebaseRuleEngine.mapToObject(docPath, storedData, valueType)
        }
        return Shadow.directlyOn(
            realSnap,
            DocumentSnapshot::class.java,
            "toObject",
            ClassParameter.from(Class::class.java, valueType),
            ClassParameter.from(DocumentSnapshot.ServerTimestampBehavior::class.java, behavior)
        )
    }
}

@Implements(value = QuerySnapshot::class, isInAndroidSdk = false)
class ShadowLocalQuerySnapshot {
    @RealObject
    private lateinit var realQuerySnap: QuerySnapshot

    private var snapshots: List<DocumentSnapshot> = emptyList()

    fun bind(docs: List<DocumentSnapshot>) {
        snapshots = docs
    }

    @Implementation
    fun getDocuments(): List<DocumentSnapshot> {
        if (LocalFirebaseRuleEngine.useLocalShadows) return snapshots
        return Shadow.directlyOn(realQuerySnap, QuerySnapshot::class.java, "getDocuments")
    }

    @Implementation
    fun <T> toObjects(clazz: Class<T>): List<T> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return snapshots.mapNotNull { it.toObject(clazz) }
        }
        return Shadow.directlyOn(
            realQuerySnap,
            QuerySnapshot::class.java,
            "toObjects",
            ClassParameter.from(Class::class.java, clazz)
        )
    }

    @Implementation
    fun <T> toObjects(clazz: Class<T>, behavior: DocumentSnapshot.ServerTimestampBehavior): List<T> {
        if (LocalFirebaseRuleEngine.useLocalShadows) {
            return snapshots.mapNotNull { it.toObject(clazz, behavior) }
        }
        return Shadow.directlyOn(
            realQuerySnap,
            QuerySnapshot::class.java,
            "toObjects",
            ClassParameter.from(Class::class.java, clazz),
            ClassParameter.from(DocumentSnapshot.ServerTimestampBehavior::class.java, behavior)
        )
    }
}
