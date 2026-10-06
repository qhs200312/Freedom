package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Used by XrayRuntime from Android P onward. sing-box owns its interface monitor.
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
) {
    internal companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L

        internal fun isEligibleUpstream(
            isVpn: Boolean,
            hasInternet: Boolean,
            isValidated: Boolean,
        ): Boolean = !isVpn && hasInternet && isValidated
    }

    @Volatile
    private var upstream: Network? = null
    private var hasObservedNetwork = false
    private var handoverJob: Job? = null
    @Volatile private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!registered) return
            if (!isPhysicalUpstream(network)) return
            acceptUpstream(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (!registered) return
            if (!isPhysicalUpstream(networkCapabilities)) return
            if (network != upstream) {
                acceptUpstream(network)
            } else {
                onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        private fun acceptUpstream(network: Network) {
            val previous = upstream
            val shouldReload = hasObservedNetwork && (previous == null || previous != network)
            upstream = network
            hasObservedNetwork = true
            onUnderlyingNetworksChanged(arrayOf(network))
            if (shouldReload) {
                scheduleHandover(network)
            }
        }

        override fun onLost(network: Network) {
            if (!registered) return
            // During make-before-break handover Android reports the old network as lost after
            // the replacement is already active. Do not clear the newly selected upstream.
            if (network != upstream) return
            upstream = null
            onUnderlyingNetworksChanged(null)
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            registered = true
            connectivity.requestNetwork(request, callback)
        } catch (e: Exception) {
            registered = false
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        handoverJob?.cancel()
        handoverJob = null
        upstream = null
        hasObservedNetwork = false
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    fun hasAvailableNetwork(): Boolean = upstream != null

    private fun isPhysicalUpstream(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return isPhysicalUpstream(capabilities)
    }

    private fun isPhysicalUpstream(capabilities: NetworkCapabilities): Boolean = isEligibleUpstream(
        isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                if (registered && upstream == network) onHandover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
