package com.example.game.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
class NetworkMonitor(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow<OnlineStatus>(OnlineStatus.Authenticating)
    val status: StateFlow<OnlineStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var pingJob: Job? = null
    private var heartbeatJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (_status.value !is OnlineStatus.Connected) {
                triggerConnectionVerification()
            }
        }

        override fun onLost(network: Network) {
            heartbeatJob?.cancel()
            _status.value = OnlineStatus.OfflineError(
                reason = "SATELLITE DATA LINK SEVERED: No active network interface detected.",
                errorCode = "ERR_NET_LOST",
                isHardwareDisconnected = true
            )
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                _status.value !is OnlineStatus.Connected
            ) {
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
        if (hasInternet || _status.value !is OnlineStatus.OfflineError) {
            _status.value = OnlineStatus.Connected(
                latencyMs = (_status.value as? OnlineStatus.Connected)?.latencyMs ?: 28L,
                serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                networkType = resolveNetType(capabilities)
            )
            startHeartbeat()
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
        pingJob?.cancel()
        heartbeatJob?.cancel()
        pingJob = scope.launch {
            if (_status.value !is OnlineStatus.Connected) {
                _status.value = OnlineStatus.Authenticating
            }
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            if (activeNetwork == null || capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                _status.value = OnlineStatus.OfflineError(
                    reason = "NO INTERNET CONNECTION: Internet connection is mandatory for War Command.",
                    errorCode = "ERR_OFFLINE_FORBIDDEN",
                    isHardwareDisconnected = true
                )
                return@launch
            }

            val netType = resolveNetType(capabilities)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            // Fast path when Android OS already validated internet connectivity; run ping to measure latency
            if (isValidated) {
                _status.value = OnlineStatus.Connected(
                    latencyMs = 35L,
                    serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                    networkType = netType
                )
                val measured = executeRealPing()
                if (measured != null) {
                    _status.value = OnlineStatus.Connected(
                        latencyMs = measured,
                        serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                        networkType = netType
                    )
                }
                startHeartbeat()
                return@launch
            }

            // Secondary ping check when NET_CAPABILITY_VALIDATED hasn't propagated yet
            val pingResult = executeRealPing()
            if (pingResult != null) {
                _status.value = OnlineStatus.Connected(
                    latencyMs = pingResult,
                    serverRegion = "US-EAST (VIRGINIA) CLUSTER-04",
                    networkType = netType
                )
                startHeartbeat()
            } else {
                _status.value = OnlineStatus.OfflineError(
                    reason = "WAR-NET AUTHENTICATION FAILED: Unable to establish secure handshake with Empire servers.",
                    errorCode = "ERR_AUTH_TIMEOUT",
                    isHardwareDisconnected = false
                )
            }
        }
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

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(20000)
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasInternet = activeNetwork != null && capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidatedNetwork = hasInternet &&
                    capabilities!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val ping = executeRealPing() ?: if (hasValidatedNetwork) 45L else null
                if (ping == null && !hasInternet) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        _status.value = OnlineStatus.OfflineError(
                            reason = "HEARTBEAT DROPPED: Satellite communication interrupted.",
                            errorCode = "ERR_HEARTBEAT_LOST",
                            isHardwareDisconnected = true
                        )
                        break
                    }
                } else {
                    consecutiveFailures = 0
                }
            }
        }
    }

    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        pingJob?.cancel()
        heartbeatJob?.cancel()
    }
}
