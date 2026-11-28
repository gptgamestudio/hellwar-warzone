package com.example.game.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

sealed class OnlineStatus {
    object Authenticating : OnlineStatus()
    data class Connected(
        val latencyMs: Long,
        val serverRegion: String,
        val networkType: String,
        val encryptionMode: String = "TLS_AES_256_GCM_SHA384"
    ) : OnlineStatus()
    data class OfflineError(
        val reason: String,
        val errorCode: String,
        val isHardwareDisconnected: Boolean
    ) : OnlineStatus()
}

/**
 * Real Android network monitor enforcing the strict ONLINE-ONLY architecture.
 * Offline play is strictly prevented. Uses ConnectivityManager and real HTTP handshake.
 */
class NetworkMonitor(
    private val context: Context,
    private val pingProbe: (suspend () -> Long?)? = null
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow<OnlineStatus>(OnlineStatus.Authenticating)
    val status: StateFlow<OnlineStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val stateLock = Any()
    private var pingJob: Job? = null
    private var heartbeatJob: Job? = null
    private val verificationGeneration = AtomicLong(0L)
    @Volatile
    private var isCleanedUp = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val shouldVerify = synchronized(stateLock) {
                !isCleanedUp && _status.value !is OnlineStatus.Connected
            }
            if (shouldVerify) {
                triggerConnectionVerification()
            }
        }

        override fun onLost(network: Network) {
            synchronized(stateLock) {
                if (isCleanedUp) return
                verificationGeneration.incrementAndGet()
                pingJob?.cancel()
                heartbeatJob?.cancel()
                _status.value = OnlineStatus.OfflineError(
                    reason = "SATELLITE DATA LINK SEVERED: No active network interface detected.",
                    errorCode = "ERR_NET_LOST",
                    isHardwareDisconnected = true
                )
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val shouldVerify = synchronized(stateLock) {
                if (isCleanedUp) return
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    verificationGeneration.incrementAndGet()
                    pingJob?.cancel()
                    heartbeatJob?.cancel()
                    _status.value = OnlineStatus.OfflineError(
                        reason = "SATELLITE DATA LINK SEVERED: Active network lost internet capability.",
                        errorCode = "ERR_NET_LOST",
                        isHardwareDisconnected = true
                    )
                    false
                } else {
                    (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        _status.value !is OnlineStatus.Connected) ||
                        (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                            _status.value is OnlineStatus.Connected)
                }
            }
            if (shouldVerify) {
                triggerConnectionVerification()
            }
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // fallback if permission restriction
        }
        triggerConnectionVerification()
    }

    fun confirmOnlineFromAuth() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val hasInternet = activeNetwork != null && capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = hasInternet &&
            capabilities!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!hasInternet) {
            synchronized(stateLock) {
                if (isCleanedUp) return
                verificationGeneration.incrementAndGet()
                pingJob?.cancel()
                heartbeatJob?.cancel()
                _status.value = OnlineStatus.OfflineError(
                    reason = "NO INTERNET CONNECTION: Internet connection is mandatory for War Command.",
                    errorCode = "ERR_OFFLINE_FORBIDDEN",
                    isHardwareDisconnected = true
                )
            }
            return
        }

        if (isValidated) {
            synchronized(stateLock) {
                if (isCleanedUp) return
                val gen = verificationGeneration.incrementAndGet()
                pingJob?.cancel()
                _status.value = OnlineStatus.Connected(
                    latencyMs = (_status.value as? OnlineStatus.Connected)?.latencyMs ?: 32L,
                    serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                    networkType = resolveNetType(capabilities)
                )
                startHeartbeatLocked(gen)
            }
        } else {
            triggerConnectionVerification()
        }
    }

    private fun resolveNetType(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "MIL-NET RELAY"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "HIGH-SPEED WI-FI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR 5G/LTE"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "TACTICAL ETHERNET"
        else -> "MIL-NET RELAY"
    }

    fun triggerConnectionVerification() {
        val gen: Long
        synchronized(stateLock) {
            if (isCleanedUp) return
            gen = verificationGeneration.incrementAndGet()
            pingJob?.cancel()
            heartbeatJob?.cancel()
            if (_status.value !is OnlineStatus.Connected) {
                _status.value = OnlineStatus.Authenticating
            }
            pingJob = scope.launch {
                if (isCleanedUp || !isActive || verificationGeneration.get() != gen) return@launch
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

                if (activeNetwork == null || capabilities == null ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    synchronized(stateLock) {
                        if (!isCleanedUp && isActive && verificationGeneration.get() == gen) {
                            _status.value = OnlineStatus.OfflineError(
                                reason = "NO INTERNET CONNECTION: Internet connection is mandatory for War Command.",
                                errorCode = "ERR_OFFLINE_FORBIDDEN",
                                isHardwareDisconnected = true
                            )
                        }
                    }
                    return@launch
                }

                val netType = resolveNetType(capabilities)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val pingResult = if (isValidated) runPingProbe() else null

                synchronized(stateLock) {
                    if (isCleanedUp || !isActive || verificationGeneration.get() != gen) return@launch
                    if (isValidated && pingResult != null) {
                        _status.value = OnlineStatus.Connected(
                            latencyMs = pingResult,
                            serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                            networkType = netType
                        )
                        startHeartbeatLocked(gen)
                    } else {
                        _status.value = OnlineStatus.OfflineError(
                            reason = if (!isValidated) {
                                "NETWORK NOT VALIDATED: Waiting for carrier/Wi-Fi captive-portal verification."
                            } else {
                                "WAR-NET AUTHENTICATION FAILED: Unable to reach live servers."
                            },
                            errorCode = "ERR_AUTH_TIMEOUT",
                            isHardwareDisconnected = false
                        )
                    }
                }
            }
        }
    }

    private suspend fun runPingProbe(): Long? {
        return if (pingProbe != null) pingProbe.invoke() else executeRealPing()
    }

    private suspend fun executeRealPing(): Long? = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://clients3.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        for (endpoint in endpoints) {
            val startTime = System.currentTimeMillis()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                }
                val responseCode = connection.responseCode
                if (responseCode == 204 || responseCode == 200) {
                    val latency = System.currentTimeMillis() - startTime
                    return@withContext latency.coerceAtLeast(18L)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {}
            }
        }
        null
    }

    private fun startHeartbeatLocked(generation: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (!isCleanedUp && isActive && verificationGeneration.get() == generation) {
                delay(20000)
                if (isCleanedUp || !isActive || verificationGeneration.get() != generation) break
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasInternet = activeNetwork != null && capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val ping = if (hasInternet) runPingProbe() else null
                var shouldStop = false
                synchronized(stateLock) {
                    if (isCleanedUp || !isActive || verificationGeneration.get() != generation) {
                        shouldStop = true
                    } else if (ping == null) {
                        consecutiveFailures++
                        if (!hasInternet || consecutiveFailures >= 2) {
                            _status.value = OnlineStatus.OfflineError(
                                reason = "HEARTBEAT DROPPED: Live server communication interrupted.",
                                errorCode = "ERR_HEARTBEAT_LOST",
                                isHardwareDisconnected = !hasInternet
                            )
                            shouldStop = true
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                }
                if (shouldStop) break
            }
        }
    }

    fun cleanup() {
        synchronized(stateLock) {
            isCleanedUp = true
            verificationGeneration.incrementAndGet()
            pingJob?.cancel()
            heartbeatJob?.cancel()
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        scope.cancel()
    }
}
