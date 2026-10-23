package com.example.workflowocr.licensing

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.workflowocr.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

private val Context.licenseDataStore by preferencesDataStore(name = "license_preferences")

@Serializable
data class ActiveDeviceSlotInfo(
    val slotNumber: Int,
    val deviceLabel: String,
    val assignedAtMillis: Long,
    val lastSeenAtMillis: Long
)

@Serializable
data class StoredLicenseState(
    val isSignedIn: Boolean = false,
    val sessionToken: String = "",
    val organizationCode: String = "",
    val organizationName: String = "",
    val slotNumber: Int = 0,
    val maxDevices: Int = 0,
    val deviceLabel: String = "",
    val paidUntilMillis: Long = 0L,
    val lastVerifiedWallClockMillis: Long = 0L,
    val lastVerifiedElapsedRealtimeMillis: Long = 0L,
    val maxObservedWallClockMillis: Long = 0L,
    val deviceInstallId: String = "",
    val serverBaseUrl: String = BuildConfig.LICENSE_SERVER_URL,
    // Internal flavor only: when true (default in internal builds), stubbed active subscription is used
    val internalStubActive: Boolean = BuildConfig.IS_INTERNAL_FLAVOR
)

enum class LicenseLockReason {
    NONE,
    SUBSCRIPTION_EXPIRED_OVER_3_DAYS,
    OFFLINE_OVER_3_DAYS,
    CLOCK_ROLLBACK_DETECTED,
    SESSION_REVOKED
}

sealed class LoginOutcome {
    data class Success(val state: StoredLicenseState) : LoginOutcome()
    data class SlotsFull(
        val orgCode: String,
        val orgName: String,
        val maxDevices: Int,
        val takeoverToken: String,
        val deviceLabel: String,
        val activeDevices: List<ActiveDeviceSlotInfo>
    ) : LoginOutcome()
    data class Error(val message: String) : LoginOutcome()
}

/**
 * Central authority for Organization Subscription & Device Seat (#1..#N) licensing.
 *
 * Exposes the two primary guards requested:
 * - [hasSubscriptionAccess]: true only when signed in with an active subscription (or within 3-day grace)
 *   and verified online within the last 3 days.
 * - [hasDemoAccess]: true when [hasSubscriptionAccess] is true OR when not signed in (pure Demo Mode).
 *   Returns false when signed in to an account whose subscription expired > 3 days ago or hasn't
 *   verified online in > 3 days, locking the app until renewed or rolled back to Demo Mode.
 */
class LicenseManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        internal const val THREE_DAYS_MILLIS: Long = 3L * 24L * 60L * 60L * 1000L
        private const val CLOCK_ROLLBACK_TOLERANCE_MILLIS: Long = 15L * 60L * 1000L // 15 min tolerance for NTP adjustments
        private val STATE_KEY = stringPreferencesKey("stored_license_state_v1")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun defaultDeviceModelName(): String {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model".trim()
            }.ifBlank { "Android Device" }
        }

        internal fun evaluateStateLockReason(
            current: StoredLicenseState,
            nowWallMillis: Long,
            currentElapsedRealtimeMillis: Long
        ): LicenseLockReason {
            if (!current.isSignedIn) {
                return LicenseLockReason.NONE
            }
            if (current.sessionToken == "REVOKED") {
                return LicenseLockReason.SESSION_REVOKED
            }

            // 1. Check for system clock rollback tampering
            if (current.maxObservedWallClockMillis > 0L &&
                nowWallMillis + CLOCK_ROLLBACK_TOLERANCE_MILLIS < current.maxObservedWallClockMillis
            ) {
                return LicenseLockReason.CLOCK_ROLLBACK_DETECTED
            }

            // 2. Check 3-day offline verification requirement (both wall clock and monotonic clock if same boot)
            val wallElapsedSinceVerify = nowWallMillis - current.lastVerifiedWallClockMillis
            val monotonicElapsedSinceVerify = if (
                current.lastVerifiedElapsedRealtimeMillis > 0L &&
                currentElapsedRealtimeMillis >= current.lastVerifiedElapsedRealtimeMillis
            ) {
                currentElapsedRealtimeMillis - current.lastVerifiedElapsedRealtimeMillis
            } else {
                0L
            }

            if (current.lastVerifiedWallClockMillis <= 0L ||
                wallElapsedSinceVerify > THREE_DAYS_MILLIS ||
                monotonicElapsedSinceVerify > THREE_DAYS_MILLIS
            ) {
                return LicenseLockReason.OFFLINE_OVER_3_DAYS
            }

            // 3. Check if paid duration finished more than 3 days ago
            if (nowWallMillis > current.paidUntilMillis + THREE_DAYS_MILLIS) {
                return LicenseLockReason.SUBSCRIPTION_EXPIRED_OVER_3_DAYS
            }

            return LicenseLockReason.NONE
        }

        internal fun evaluateHasSubscriptionAccess(
            current: StoredLicenseState,
            nowWallMillis: Long,
            currentElapsedRealtimeMillis: Long
        ): Boolean {
            if (BuildConfig.IS_INTERNAL_FLAVOR && current.internalStubActive) {
                return true
            }
            if (!current.isSignedIn || current.sessionToken.isBlank()) {
                return false
            }
            return evaluateStateLockReason(current, nowWallMillis, currentElapsedRealtimeMillis) == LicenseLockReason.NONE
        }

        internal fun evaluateHasDemoAccess(
            current: StoredLicenseState,
            nowWallMillis: Long,
            currentElapsedRealtimeMillis: Long
        ): Boolean {
            if (BuildConfig.IS_INTERNAL_FLAVOR && current.internalStubActive) {
                return true
            }
            if (!current.isSignedIn) {
                return true
            }
            return evaluateHasSubscriptionAccess(current, nowWallMillis, currentElapsedRealtimeMillis)
        }
    }

    private val loadedDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    var state by mutableStateOf(
        StoredLicenseState(
            deviceInstallId = UUID.randomUUID().toString(),
            deviceLabel = defaultDeviceModelName(),
            internalStubActive = BuildConfig.IS_INTERNAL_FLAVOR
        )
    )
        private set

    var isLoaded by mutableStateOf(false)
        private set

    var isVerifying by mutableStateOf(false)
        private set

    var lastStatusMessage by mutableStateOf<String?>(null)
        private set

    var showSignInDialog by mutableStateOf(false)
    var showRollbackConfirmDialog by mutableStateOf(false)
    var pendingSlotsFull by mutableStateOf<LoginOutcome.SlotsFull?>(null)

    init {
        scope.launch {
            loadFromDisk()
            if (state.isSignedIn && !(BuildConfig.IS_INTERNAL_FLAVOR && state.internalStubActive)) {
                verifyOnlineSilently()
            }
        }
    }

    /**
     * Suspends until the initial license state has been loaded from disk.
     */
    suspend fun awaitLoaded() {
        loadedDeferred.await()
    }

    private suspend fun loadFromDisk() {
        try {
            val prefs = context.licenseDataStore.data.first()
            val raw = prefs[STATE_KEY]
            if (raw != null) {
                val decoded = json.decodeFromString<StoredLicenseState>(raw)
                val now = System.currentTimeMillis()
                val updatedMaxClock = maxOf(decoded.maxObservedWallClockMillis, now)
                state = decoded.copy(
                    deviceInstallId = decoded.deviceInstallId.ifBlank { UUID.randomUUID().toString() },
                    deviceLabel = decoded.deviceLabel.ifBlank { defaultDeviceModelName() },
                    maxObservedWallClockMillis = updatedMaxClock,
                    serverBaseUrl = BuildConfig.LICENSE_SERVER_URL
                )
            } else {
                val initial = StoredLicenseState(
                    deviceInstallId = UUID.randomUUID().toString(),
                    deviceLabel = defaultDeviceModelName(),
                    maxObservedWallClockMillis = System.currentTimeMillis(),
                    internalStubActive = BuildConfig.IS_INTERNAL_FLAVOR
                )
                state = initial
                writeStateToDisk(initial)
            }
        } catch (e: Exception) {
            Log.e("LicenseManager", "Failed to load license state", e)
        } finally {
            isLoaded = true
            loadedDeferred.complete(Unit)
        }
    }

    private suspend fun saveState(newState: StoredLicenseState) {
        awaitLoaded()
        val now = System.currentTimeMillis()
        val withClock = newState.copy(
            maxObservedWallClockMillis = maxOf(newState.maxObservedWallClockMillis, now)
        )
        state = withClock
        writeStateToDisk(withClock)
    }

    private suspend fun writeStateToDisk(stateToWrite: StoredLicenseState) {
        context.licenseDataStore.edit { prefs ->
            prefs[STATE_KEY] = json.encodeToString(stateToWrite)
        }
    }

    /**
     * Returns true ONLY when the device has an active paid subscription (within the 3-day grace window)
     * AND has verified online with the licensing server within the last 3 days.
     *
     * In the `internal` flavor, when [StoredLicenseState.internalStubActive] is enabled (the default),
     * this returns true automatically so automated tests and developer workflows work out-of-the-box.
     */
    fun hasSubscriptionAccess(nowWallMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isLoaded) return false
        return evaluateHasSubscriptionAccess(state, nowWallMillis, SystemClock.elapsedRealtime())
    }

    /**
     * Returns true when:
     * - [hasSubscriptionAccess] is true, OR
     * - The user is not signed in (Demo Mode: can use "Use previous photo", cannot scan new pages).
     *
     * Returns false when the user IS signed in, but their subscription expired > 3 days ago OR
     * they haven't verified online in > 3 days (or rolled back the system clock).
     * When false, the entire app is locked until they verify online, sign in to another account,
     * or confirm rolling back to Demo Mode (which deletes all non-settings data).
     */
    fun hasDemoAccess(nowWallMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isLoaded) return false
        return evaluateHasDemoAccess(state, nowWallMillis, SystemClock.elapsedRealtime())
    }

    fun getLockReason(nowWallMillis: Long = System.currentTimeMillis()): LicenseLockReason {
        if (!isLoaded) return LicenseLockReason.NONE
        val current = state
        if (BuildConfig.IS_INTERNAL_FLAVOR && current.internalStubActive) {
            return LicenseLockReason.NONE
        }
        if (!current.isSignedIn) {
            return LicenseLockReason.NONE
        }
        return evaluateStateLockReason(current, nowWallMillis, SystemClock.elapsedRealtime())
    }

    /**
     * Checks if the user is in the 3-day post-expiration grace period (`paidUntil < now <= paidUntil + 3 days`).
     */
    fun isInExpiryGracePeriod(nowWallMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isLoaded) return false
        val current = state
        if (BuildConfig.IS_INTERNAL_FLAVOR && current.internalStubActive) return false
        if (!current.isSignedIn) return false
        return nowWallMillis > current.paidUntilMillis &&
            nowWallMillis <= current.paidUntilMillis + THREE_DAYS_MILLIS &&
            hasSubscriptionAccess(nowWallMillis)
    }

    /**
     * Guards an action that requires an active subscription (e.g. scanning a new page).
     * If [hasSubscriptionAccess] is true, runs [action] immediately; otherwise opens the Sign-In dialog.
     */
    fun guardSubscriptionAction(action: () -> Unit) {
        if (!isLoaded) return
        if (hasSubscriptionAccess()) {
            action()
        } else {
            showSignInDialog = true
        }
    }

    fun setInternalStubEnabled(enabled: Boolean) {
        if (!BuildConfig.IS_INTERNAL_FLAVOR) return
        scope.launch {
            awaitLoaded()
            saveState(state.copy(internalStubActive = enabled))
        }
    }

    fun updateServerBaseUrl(newUrl: String) {
        val trimmed = newUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        scope.launch {
            awaitLoaded()
            saveState(state.copy(serverBaseUrl = trimmed))
        }
    }

    /**
     * Signs in with Organization credentials (`orgCode` + `password`) and optional custom `nickname`.
     */
    suspend fun signIn(
        orgCode: String,
        password: String,
        customNickname: String
    ): LoginOutcome = withContext(Dispatchers.IO) {
        awaitLoaded()
        val cleanOrg = orgCode.trim().uppercase()
        val cleanPass = password.trim()
        val model = defaultDeviceModelName()
        val fullLabel = if (customNickname.isNotBlank()) {
            "$model (${customNickname.trim()})"
        } else {
            model
        }

        // Built-in offline/demo test credentials for internal builds or local testing when prefixed with TEST-
        if (BuildConfig.IS_INTERNAL_FLAVOR && cleanOrg == "INTERNAL-TEST") {
            val now = System.currentTimeMillis()
            val newState = state.copy(
                isSignedIn = true,
                sessionToken = "internal_session_${UUID.randomUUID()}",
                organizationCode = "INTERNAL-TEST",
                organizationName = "Internal Test Organization",
                slotNumber = 1,
                maxDevices = 5,
                deviceLabel = fullLabel,
                paidUntilMillis = now + 30L * 24L * 60L * 60L * 1000L,
                lastVerifiedWallClockMillis = now,
                lastVerifiedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                maxObservedWallClockMillis = now,
                internalStubActive = false
            )
            saveState(newState)
            return@withContext LoginOutcome.Success(newState)
        }

        try {
            val payload = JsonObject(
                mapOf(
                    "orgCode" to kotlinx.serialization.json.JsonPrimitive(cleanOrg),
                    "password" to kotlinx.serialization.json.JsonPrimitive(cleanPass),
                    "deviceInstallId" to kotlinx.serialization.json.JsonPrimitive(state.deviceInstallId),
                    "deviceLabel" to kotlinx.serialization.json.JsonPrimitive(fullLabel)
                )
            )
            val (httpCode, responseJson) = postJson("${state.serverBaseUrl}/api/v1/login", payload.toString())

            when (httpCode) {
                200 -> {
                    val now = System.currentTimeMillis()
                    val newState = state.copy(
                        isSignedIn = true,
                        sessionToken = responseJson["sessionToken"]?.jsonPrimitive?.content.orEmpty(),
                        organizationCode = responseJson["organizationCode"]?.jsonPrimitive?.content ?: cleanOrg,
                        organizationName = responseJson["organizationName"]?.jsonPrimitive?.content ?: cleanOrg,
                        slotNumber = responseJson["slotNumber"]?.jsonPrimitive?.intOrNull ?: 1,
                        maxDevices = responseJson["maxDevices"]?.jsonPrimitive?.intOrNull ?: 1,
                        deviceLabel = responseJson["deviceLabel"]?.jsonPrimitive?.content ?: fullLabel,
                        paidUntilMillis = responseJson["paidUntilMillis"]?.jsonPrimitive?.longOrNull ?: now,
                        lastVerifiedWallClockMillis = now,
                        lastVerifiedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        maxObservedWallClockMillis = now,
                        internalStubActive = false
                    )
                    saveState(newState)
                    withContext(Dispatchers.Main) {
                        pendingSlotsFull = null
                        lastStatusMessage = null
                    }
                    LoginOutcome.Success(newState)
                }
                409 -> {
                    val activeDevices = responseJson["activeDevices"]?.jsonArray?.map { elem ->
                        val obj = elem.jsonObject
                        ActiveDeviceSlotInfo(
                            slotNumber = obj["slotNumber"]?.jsonPrimitive?.intOrNull ?: 1,
                            deviceLabel = obj["deviceLabel"]?.jsonPrimitive?.content ?: "Device",
                            assignedAtMillis = obj["assignedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                            lastSeenAtMillis = obj["lastSeenAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    }.orEmpty().sortedBy { it.slotNumber }

                    val slotsFull = LoginOutcome.SlotsFull(
                        orgCode = responseJson["organizationCode"]?.jsonPrimitive?.content ?: cleanOrg,
                        orgName = responseJson["organizationName"]?.jsonPrimitive?.content ?: cleanOrg,
                        maxDevices = responseJson["maxDevices"]?.jsonPrimitive?.intOrNull ?: activeDevices.size,
                        takeoverToken = responseJson["takeoverToken"]?.jsonPrimitive?.content.orEmpty(),
                        deviceLabel = fullLabel,
                        activeDevices = activeDevices
                    )
                    withContext(Dispatchers.Main) {
                        pendingSlotsFull = slotsFull
                    }
                    slotsFull
                }
                else -> {
                    val errMsg = responseJson["message"]?.jsonPrimitive?.content
                        ?: "Sign-in failed (HTTP $httpCode)."
                    LoginOutcome.Error(errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e("LicenseManager", "Sign-in network error", e)
            LoginOutcome.Error("Could not reach licensing server: ${e.localizedMessage ?: "Network error"}")
        }
    }

    /**
     * Replaces an existing device slot (`#1 .. #maxDevices`) chosen by the user when all slots were full.
     */
    suspend fun takeoverSlot(
        slotsFull: LoginOutcome.SlotsFull,
        chosenSlotNumber: Int
    ): LoginOutcome = withContext(Dispatchers.IO) {
        awaitLoaded()
        try {
            val payload = JsonObject(
                mapOf(
                    "orgCode" to kotlinx.serialization.json.JsonPrimitive(slotsFull.orgCode),
                    "takeoverToken" to kotlinx.serialization.json.JsonPrimitive(slotsFull.takeoverToken),
                    "slotNumber" to kotlinx.serialization.json.JsonPrimitive(chosenSlotNumber),
                    "deviceInstallId" to kotlinx.serialization.json.JsonPrimitive(state.deviceInstallId),
                    "deviceLabel" to kotlinx.serialization.json.JsonPrimitive(slotsFull.deviceLabel)
                )
            )
            val (httpCode, responseJson) = postJson("${state.serverBaseUrl}/api/v1/takeover", payload.toString())
            if (httpCode == 200) {
                val now = System.currentTimeMillis()
                val newState = state.copy(
                    isSignedIn = true,
                    sessionToken = responseJson["sessionToken"]?.jsonPrimitive?.content.orEmpty(),
                    organizationCode = responseJson["organizationCode"]?.jsonPrimitive?.content ?: slotsFull.orgCode,
                    organizationName = responseJson["organizationName"]?.jsonPrimitive?.content ?: slotsFull.orgName,
                    slotNumber = responseJson["slotNumber"]?.jsonPrimitive?.intOrNull ?: chosenSlotNumber,
                    maxDevices = responseJson["maxDevices"]?.jsonPrimitive?.intOrNull ?: slotsFull.maxDevices,
                    deviceLabel = responseJson["deviceLabel"]?.jsonPrimitive?.content ?: slotsFull.deviceLabel,
                    paidUntilMillis = responseJson["paidUntilMillis"]?.jsonPrimitive?.longOrNull ?: now,
                    lastVerifiedWallClockMillis = now,
                    lastVerifiedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    maxObservedWallClockMillis = now,
                    internalStubActive = false
                )
                saveState(newState)
                withContext(Dispatchers.Main) {
                    pendingSlotsFull = null
                    lastStatusMessage = null
                }
                LoginOutcome.Success(newState)
            } else {
                val errMsg = responseJson["message"]?.jsonPrimitive?.content
                    ?: "Could not replace Device #$chosenSlotNumber (HTTP $httpCode)."
                LoginOutcome.Error(errMsg)
            }
        } catch (e: Exception) {
            Log.e("LicenseManager", "Takeover network error", e)
            LoginOutcome.Error("Network error while claiming Device #$chosenSlotNumber: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Contacts `/api/v1/verify` to refresh `paidUntilMillis` and `lastVerifiedWallClockMillis`.
     */
    suspend fun verifyOnlineNow(): Boolean = withContext(Dispatchers.IO) {
        awaitLoaded()
        val current = state
        if (!current.isSignedIn || current.sessionToken.isBlank()) return@withContext false
        if (current.organizationCode == "INTERNAL-TEST") {
            val now = System.currentTimeMillis()
            saveState(
                current.copy(
                    lastVerifiedWallClockMillis = now,
                    lastVerifiedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    maxObservedWallClockMillis = now
                )
            )
            return@withContext true
        }

        withContext(Dispatchers.Main) { isVerifying = true }
        try {
            val payload = JsonObject(
                mapOf(
                    "sessionToken" to kotlinx.serialization.json.JsonPrimitive(current.sessionToken),
                    "deviceInstallId" to kotlinx.serialization.json.JsonPrimitive(current.deviceInstallId)
                )
            )
            val (httpCode, responseJson) = postJson("${current.serverBaseUrl}/api/v1/verify", payload.toString())
            when (httpCode) {
                200 -> {
                    val now = System.currentTimeMillis()
                    val updated = current.copy(
                        organizationCode = responseJson["organizationCode"]?.jsonPrimitive?.content ?: current.organizationCode,
                        organizationName = responseJson["organizationName"]?.jsonPrimitive?.content ?: current.organizationName,
                        slotNumber = responseJson["slotNumber"]?.jsonPrimitive?.intOrNull ?: current.slotNumber,
                        maxDevices = responseJson["maxDevices"]?.jsonPrimitive?.intOrNull ?: current.maxDevices,
                        deviceLabel = responseJson["deviceLabel"]?.jsonPrimitive?.content ?: current.deviceLabel,
                        paidUntilMillis = responseJson["paidUntilMillis"]?.jsonPrimitive?.longOrNull ?: current.paidUntilMillis,
                        lastVerifiedWallClockMillis = now,
                        lastVerifiedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        maxObservedWallClockMillis = now
                    )
                    saveState(updated)
                    withContext(Dispatchers.Main) {
                        lastStatusMessage = "License verified successfully."
                    }
                    true
                }
                401, 403 -> {
                    val msg = responseJson["message"]?.jsonPrimitive?.content
                        ?: "This device session was revoked or suspended."
                    saveState(current.copy(sessionToken = "REVOKED"))
                    withContext(Dispatchers.Main) {
                        lastStatusMessage = msg
                    }
                    false
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        lastStatusMessage = responseJson["message"]?.jsonPrimitive?.content
                            ?: "Verification failed (HTTP $httpCode)."
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.w("LicenseManager", "Online verification failed: ${e.message}")
            withContext(Dispatchers.Main) {
                lastStatusMessage = "Offline — could not reach licensing server."
            }
            false
        } finally {
            withContext(Dispatchers.Main) { isVerifying = false }
        }
    }

    private suspend fun verifyOnlineSilently() {
        verifyOnlineNow()
    }

    /**
     * Rolls back the app to Demo Mode:
     * 1. Best-effort notifies the server to free this device's slot (`#1..#N`).
     * 2. Invokes [onWipeNonSettingsData] to delete all saved schedules, snippets, projections, and VLH tables.
     * 3. Clears the stored organization credentials so [hasDemoAccess] becomes `true` and [hasSubscriptionAccess] becomes `false`.
     */
    suspend fun rollbackToDemoMode(onWipeNonSettingsData: suspend () -> Unit) {
        awaitLoaded()
        val current = state
        if (current.isSignedIn && current.sessionToken.isNotBlank() && current.sessionToken != "REVOKED") {
            withContext(Dispatchers.IO) {
                try {
                    val payload = JsonObject(
                        mapOf("sessionToken" to kotlinx.serialization.json.JsonPrimitive(current.sessionToken))
                    )
                    postJson("${current.serverBaseUrl}/api/v1/logout", payload.toString())
                } catch (e: Exception) {
                    Log.w("LicenseManager", "Best-effort logout failed (continuing local wipe): ${e.message}")
                }
            }
        }

        // Wipe all user data except UniversalSettings, PresetType, and TableLayout
        onWipeNonSettingsData()

        val cleared = StoredLicenseState(
            isSignedIn = false,
            sessionToken = "",
            organizationCode = "",
            organizationName = "",
            slotNumber = 0,
            maxDevices = 0,
            deviceLabel = defaultDeviceModelName(),
            paidUntilMillis = 0L,
            lastVerifiedWallClockMillis = 0L,
            lastVerifiedElapsedRealtimeMillis = 0L,
            maxObservedWallClockMillis = System.currentTimeMillis(),
            deviceInstallId = current.deviceInstallId,
            serverBaseUrl = current.serverBaseUrl,
            internalStubActive = false
        )
        saveState(cleared)
        withContext(Dispatchers.Main) {
            showRollbackConfirmDialog = false
            pendingSlotsFull = null
            lastStatusMessage = null
        }
    }

    private fun postJson(urlString: String, bodyJson: String): Pair<Int, JsonObject> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(bodyJson)
                writer.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = if (text.isNotBlank()) {
                try {
                    json.parseToJsonElement(text).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
            } else {
                JsonObject(emptyMap())
            }
            code to parsed
        } finally {
            conn.disconnect()
        }
    }
}
