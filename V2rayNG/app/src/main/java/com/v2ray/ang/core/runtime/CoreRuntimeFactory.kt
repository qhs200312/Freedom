package com.v2ray.ang.core.runtime

import android.app.Service
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreSelector
import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.core.singbox.SingBoxRuntime
import com.v2ray.ang.core.xray.XrayRuntime
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

data class CoreLaunchPlan(val guid: String, val profile: ProfileItem, val core: RuntimeCore) {
    val opensTunThroughPlatform: Boolean get() = core == RuntimeCore.SING_BOX
}

internal object CoreRuntimeFactory {
    fun selectedPlan(): CoreLaunchPlan {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val profile = MmkvManager.decodeServerConfig(guid) ?: error("Invalid selected profile")
        val selected = CoreSelector.selectConfigured(NodeProfile.from(profile, guid))
        val fallback = if (selected == RuntimeCore.SING_BOX) SingBoxConfigGenerator.unsupportedRoutingReason() else null
        if (fallback != null) LogUtil.w(AppConfig.TAG, "sing-box fallback to Xray: $fallback")
        return CoreLaunchPlan(guid, profile, if (fallback == null) selected else RuntimeCore.XRAY)
    }

    fun create(
        plan: CoreLaunchPlan,
        service: Service,
        control: ServiceControl,
        tun: ParcelFileDescriptor?,
        recover: () -> Unit,
    ): CoreRuntime = when (plan.core) {
        RuntimeCore.XRAY -> XrayRuntime(service, control, plan.guid, plan.profile, tun, recover)
        RuntimeCore.SING_BOX -> SingBoxRuntime(service, control, NodeProfile.from(plan.profile, plan.guid))
    }
}
