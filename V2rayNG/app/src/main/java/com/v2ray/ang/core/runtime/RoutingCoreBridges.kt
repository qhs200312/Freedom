package com.v2ray.ang.core.runtime

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxPlatformInterface
import com.v2ray.ang.core.singbox.SingBoxEnvironment
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.RuntimeDiagnostics
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.Libbox
import libbox.OverrideOptions
import libbox.SystemProxyStatus
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.io.Closeable

internal class RoutingCoreBridges(
    private val context: Context,
    host: RuntimeCore,
    private val protectSocket: (Int) -> Boolean,
    private val onUnexpectedExit: () -> Unit,
    private val enabled: Boolean = true,
) : Closeable {
    private var plan = RoutingBridgePlan(host, enabled = enabled)
    private val hostCore = host
    private var xray: CoreController? = null
    private var singBox: CommandServer? = null
    private var platform: SingBoxPlatformInterface? = null
    private var special: XrayOutboundBridges? = null
    @Volatile private var stopping = false
    @Volatile private var failed = false
    private var started = false
    val needed: Boolean get() = plan.entries.isNotEmpty()
    val isAlive: Boolean get() = !failed && (!started || plan.target != RuntimeCore.XRAY || xray?.isRunning == true) &&
        special?.isAlive != false

    fun prepare(profile: ProfileItem): ProfileItem {
        check(!started) { "Auxiliary core is already started" }
        return plan.prepare(profile)
    }

    fun start() {
        if (!needed) return
        check(!started)
        stopping = false
        failed = false
        try {
            if (plan.target == RuntimeCore.XRAY) {
                CoreNativeManager.initCoreEnv(context)
                val adapters = XrayOutboundBridges(context, protectSocket, ::unexpectedExit).also { special = it }
                val entries = plan.entries.map { it.copy(original = adapters.prepare(it.original)) }
                val config = RoutingBridgeConfig.xray(entries)
                xray = CoreNativeManager.newCoreController(object : CoreCallbackHandler {
                    override fun startup() = 0L
                    override fun shutdown(): Long { unexpectedExit(); return 0L }
                    override fun onEmitStatus(l: Long, s: String?) = 0L
                }).also { it.startLoop(config, 0) }
                check(xray?.isRunning == true && adapters.isAlive) { "Auxiliary Xray did not start" }
            } else {
                SingBoxEnvironment.initialize(context)
                val network = SingBoxPlatformInterface(
                    context, protectSocket, { error("Auxiliary core cannot open a VPN interface") },
                ).also { platform = it }
                val config = RoutingBridgeConfig.singBox(plan.entries)
                Libbox.checkConfig(config)
                singBox = Libbox.newCommandServer(object : CommandServerHandler {
                    override fun serviceStop() = unexpectedExit()
                    override fun serviceReload() = Unit
                    override fun getSystemProxyStatus(): SystemProxyStatus? = null
                    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
                    override fun triggerNativeCrash() = Unit
                    override fun writeDebugMessage(message: String?) = Unit
                    override fun connectSSHAgent(): Int = -1
                }, network).also { it.startOrReloadService(config, OverrideOptions()) }
            }
            check(!failed) { "Auxiliary core stopped during startup" }
            started = true
            RuntimeDiagnostics.core(context, "routing_bridge running host=$hostCore auxiliary=${plan.target} nodes=${plan.entries.size}")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun unexpectedExit() {
        if (!stopping) {
            failed = true
            onUnexpectedExit()
        }
    }

    override fun close() {
        stopping = true
        runCatching { if (xray?.isRunning == true) xray?.stopLoop() }
        xray?.callbackHandler = null
        xray = null
        runCatching { singBox?.closeService() }
        runCatching { singBox?.close() }
        singBox = null
        platform?.close()
        platform = null
        special?.close()
        special = null
        if (started) RuntimeDiagnostics.core(context, "routing_bridge stopped auxiliary=${plan.target}")
        started = false
        plan = RoutingBridgePlan(hostCore, enabled = enabled)
    }
}
