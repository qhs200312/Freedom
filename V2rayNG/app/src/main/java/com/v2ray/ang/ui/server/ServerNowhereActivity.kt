package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.NowhereFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerNowhereActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.NOWHERE

    @Composable
    override fun ScreenContent() {
        val state = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }.apply { configType = serverConfigType }
        ServerEditorScaffold("Nowhere", { saveServer(state) }) {
            CommonBasicFields(state)
            FormTextField(stringResource(R.string.nowhere_shared_key), state.password, { state.password = it })
            FormDropdownField(
                stringResource(R.string.nowhere_carrier),
                state.network,
                listOf("tcp", "udp"),
                { state.network = it },
            )
            SettingsSwitchItem(
                title = stringResource(R.string.nowhere_morph),
                checked = state.nowhereMorph,
                onCheckedChange = { state.nowhereMorph = it },
            )
            FormTextField(stringResource(R.string.server_lab_sni), state.sni, { state.sni = it })
            FormTextField(
                stringResource(R.string.server_lab_pinned_ca256),
                state.pinnedCA256,
                { state.pinnedCA256 = it },
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val result = runCatching { NowhereFmt.endpoint(config) }
        if (result.isFailure) toast(result.exceptionOrNull()?.message.orEmpty())
        return result.isSuccess
    }
}
