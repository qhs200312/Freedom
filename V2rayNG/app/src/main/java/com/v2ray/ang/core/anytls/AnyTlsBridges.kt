package com.v2ray.ang.core.anytls

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreSelector
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.core.singbox.SingBoxEnvironment
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AnyTlsFmt
import com.v2ray.ang.util.Utils
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.Libbox
import libbox.OverrideOptions
import libbox.SystemProxyStatus
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Standalone services share the initialized library, never its command socket or traffic client. */
internal class AnyTlsBridges(
    private val context: Context,
    private val protectSocket: (Int) -> Boolean = { bindPhysicalSocket(context, it) },
    private val onUnexpectedExit: () -> Unit = {},
) : Closeable {
    private data class Bridge(
        val server: CommandServer,
        val platform: SingBoxPlatformInterface,
        val profile: ProfileItem,
        val cacheFile: File,
    )
    private val bridges = linkedMapOf<ProfileItem, Bridge>()
    @Volatile private var failed = false
    val isAlive: Boolean get() = !failed

    fun prepare(profile: ProfileItem): ProfileItem {
        if (profile.configType != EConfigType.ANYTLS) return profile
        val identity = profile.duplicateIdentity()
        bridges[identity]?.let { return it.profile }
        AnyTlsFmt.validate(profile)
        val node = NodeProfile.from(profile)
        require(CoreSelector.canGenerateSingBox(node)) { "Unsupported AnyTLS TLS or session settings" }
        SingBoxEnvironment.initialize(context)
        val port = Utils.findRandomFreePort()
        val username = UUID.randomUUID().toString()
        val password = UUID.randomUUID().toString()
        val cacheFile = File(context.cacheDir, "anytls-${UUID.randomUUID()}.db")
        val platform = SingBoxPlatformInterface(
            context, protectSocket, { error("AnyTLS local bridge cannot open a TUN") },
        )
        var server: CommandServer? = null
        try {
            val config = SingBoxConfigGenerator.generate(
                profile = node,
                socksPort = port,
                httpPort = port,
                socksUsername = username,
                socksPassword = password,
                logLevel = "error",
                cacheFilePath = cacheFile.absolutePath,
            )
            Libbox.checkConfig(config)
            server = Libbox.newCommandServer(object : CommandServerHandler {
                override fun serviceStop() {
                    failed = true
                    onUnexpectedExit()
                }
                override fun serviceReload() = Unit
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun setSystemProxyEnabled(enabled: Boolean) = Unit
                override fun triggerNativeCrash() = Unit
                override fun writeDebugMessage(message: String?) = Unit
                override fun connectSSHAgent(): Int = -1
            }, platform)
            server.startOrReloadService(config, OverrideOptions())
            val local = ProfileItem(
                configType = EConfigType.SOCKS,
                remarks = profile.remarks,
                server = AppConfig.LOOPBACK,
                serverPort = port.toString(),
                username = username,
                password = password,
            )
            bridges[identity] = Bridge(server, platform, local, cacheFile)
            return local
        } catch (error: Exception) {
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            platform.close()
            cacheFile.delete()
            throw error
        }
    }

    override fun close() {
        bridges.values.forEach { bridge ->
            runCatching { bridge.server.closeService() }
            runCatching { bridge.server.close() }
            bridge.platform.close()
            bridge.cacheFile.delete()
        }
        bridges.clear()
    }

    companion object {
        private fun bindPhysicalSocket(context: Context, fd: Int): Boolean = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            } ?: return false
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            true
        }.getOrDefault(false)
    }
}
