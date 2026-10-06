package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.os.Handler
import android.os.HandlerThread
import com.v2ray.ang.core.singbox.SingBoxLocalDns
import android.system.OsConstants
import libbox.BridgeOptions
import libbox.BridgeSession
import libbox.ConnectionOwner
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.LocalDNSTransport
import libbox.NeighborUpdateListener
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.PlatformUser
import libbox.ShellSession
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.RuntimeDiagnostics
import libbox.NetworkInterface as BoxNetworkInterface

class SingBoxPlatformInterface(
    context: Context,
    private val protectSocket: (Int) -> Boolean,
    private val openTunCallback: (TunOptions) -> Int,
    private val underlyingNetworkChanged: (Array<Network>?) -> Unit = {},
) : PlatformInterface {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val monitorCallbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()
    @Volatile private var physicalNetwork: Network? = null
    @Volatile private var closed = false
    private val localResolver = SingBoxLocalDns { physicalNetwork }
    private val callbackThread by lazy { HandlerThread("sing-box-network").apply { start() } }
    private var callbackThreadStarted = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protectSocket(fd)) { "android: failed to protect socket $fd" }
    }

    override fun openTun(options: TunOptions): Int = openTunCallback(options)

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "android: connection owner lookup requires API 29" }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        check(uid != Process.INVALID_UID) { "android: connection owner not found" }
        val packages = appContext.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringListIterator(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (monitorCallbacks.containsKey(listener)) return
        val initialInterface = CountDownLatch(1)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var reportedInterface: Pair<String, Int>? = null
            private val state = SingBoxInterfaceState { name, index, expensive, constrained ->
                listener.updateDefaultInterface(name, index, expensive, constrained)
                val value = name to index
                if (value != reportedInterface) {
                    reportedInterface = value
                    RuntimeDiagnostics.core(
                        appContext,
                        "physical_interface network=$physicalNetwork name=$name index=$index",
                    )
                }
                if (index > 0) initialInterface.countDown()
            }

            override fun onAvailable(network: Network) {
                if (closed) return
                physicalNetwork = network
                RuntimeDiagnostics.core(appContext, "physical_network_available network=$network")
                state.onAvailable(network)
                underlyingNetworkChanged(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (closed) return
                state.onCapabilitiesChanged(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (closed) return
                state.onLinkPropertiesChanged(network, linkProperties)
            }

            override fun onLost(network: Network) {
                if (closed) return
                if (physicalNetwork == network) {
                    RuntimeDiagnostics.core(appContext, "physical_network_lost network=$network")
                    physicalNetwork = null
                    underlyingNetworkChanged(null)
                }
                state.onLost(network)
            }
        }
        monitorCallbacks[listener] = callback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                callbackThreadStarted = true
                val handler = Handler(callbackThread.looper)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                } else connectivity.requestNetwork(request, callback, handler)
            } else connectivity.requestNetwork(request, callback)
            // A first interface update after libbox starts can reset newly opened HY2 streams.
            check(initialInterface.await(10, TimeUnit.SECONDS)) {
                "No configured physical network interface became available"
            }
            LogUtil.w(AppConfig.TAG, "sing-box physical interface initialized before core startup")
        } catch (error: Exception) {
            RuntimeDiagnostics.core(appContext, "physical_interface_start_failed type=${error.javaClass.simpleName}")
            monitorCallbacks.remove(listener)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw error
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = monitorCallbacks.remove(listener) ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val interfaces = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val linkProperties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val name = linkProperties.interfaceName ?: return@mapNotNull null
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
                addresses = StringListIterator(
                    javaInterface.interfaceAddresses.mapNotNull { address ->
                        address.address.hostAddress
                            ?.substringBefore('%')
                            ?.let { "$it/${address.networkPrefixLength}" }
                    },
                )
                dnsServer = StringListIterator(linkProperties.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringListIterator(
                    linkProperties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress }
                        .filterNot { it == "0.0.0.0" || it == "::" },
                )
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                flags = buildInterfaceFlags(javaInterface, capabilities)
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return NetworkListIterator(interfaces)
    }

    private fun buildInterfaceFlags(
        networkInterface: NetworkInterface,
        capabilities: NetworkCapabilities,
    ): Int {
        var flags = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
        if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
        if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
        return flags
    }

    fun close() {
        closed = true
        monitorCallbacks.values.forEach { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        monitorCallbacks.clear()
        physicalNetwork = null
        if (callbackThreadStarted) callbackThread.quitSafely()
    }

    override fun localDNSTransport(): LocalDNSTransport = localResolver
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit
    override fun registerMyInterface(interfaceName: String) = Unit
    override fun sendNotification(notification: Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("android: platform shell is unavailable")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        width: Int,
        height: Int,
    ): ShellSession = error("android: platform shell is unavailable")

    override fun lookupUser(userName: String?): PlatformUser = error("android: user lookup is unavailable")
    override fun lookupSFTPServer(): String = ""
    override fun readSystemSSHHostKey(): String = ""
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("android: bridge is unavailable")

    private class StringListIterator(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun len(): Int = values.size
        override fun next(): String = values[index++]
    }

    private class NetworkListIterator(private val values: List<BoxNetworkInterface>) : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): BoxNetworkInterface = values[index++]
    }
}

/** Join callback snapshots instead of racing synchronous ConnectivityManager queries at startup. */
internal class SingBoxInterfaceState(
    private val interfaceIndex: (String) -> Int = { name ->
        runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
    },
    private val publish: (String, Int, Boolean, Boolean) -> Unit,
) {
    private var currentNetwork: Network? = null
    private var capabilities: NetworkCapabilities? = null
    private var linkProperties: LinkProperties? = null
    private var published = false

    fun onAvailable(network: Network) {
        if (network == currentNetwork) return
        // libbox compares interface name/index, but Android can replace a Network on the same wlan0.
        if (published) {
            published = false
            publish("", -1, false, false)
        }
        currentNetwork = network
        capabilities = null
        linkProperties = null
    }

    fun onCapabilitiesChanged(network: Network, value: NetworkCapabilities) {
        if (network != currentNetwork) return
        capabilities = value
        update()
    }

    fun onLinkPropertiesChanged(network: Network, value: LinkProperties) {
        if (network != currentNetwork) return
        linkProperties = value
        update()
    }

    fun onLost(network: Network) {
        if (network != currentNetwork) return
        currentNetwork = null
        capabilities = null
        linkProperties = null
        published = false
        publish("", -1, false, false)
    }

    private fun update() {
        val caps = capabilities ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val link = linkProperties ?: return
        val name = link.interfaceName?.takeIf { it.isNotBlank() } ?: return
        // Mobile and tethered networks can report the interface before its route and DNS arrive.
        if (link.linkAddresses.none { address ->
                !address.address.isAnyLocalAddress &&
                    !address.address.isLoopbackAddress &&
                    !address.address.isLinkLocalAddress
            } || link.dnsServers.isEmpty() ||
            link.routes.none { it.isDefaultRoute }) return
        val index = interfaceIndex(name)
        if (index <= 0) return
        published = true
        publish(
            name,
            index,
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
        )
    }
}
