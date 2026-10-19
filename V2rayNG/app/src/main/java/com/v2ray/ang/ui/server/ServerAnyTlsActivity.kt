package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.AnyTlsFmt
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerAnyTlsActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.ANYTLS

    @Composable
    override fun ScreenContent() {
        val options = rememberFieldOptions()
        val state = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }.apply {
            configType = serverConfigType
            streamSecurity = AppConfig.TLS
            network = "tcp"
        }
        ServerEditorScaffold("AnyTLS", {
            if (state.anytlsMinIdleSession.isNotBlank() && state.anytlsMinIdleSession.toIntOrNull() == null) {
                toast(R.string.anytls_invalid_settings)
            } else saveServer(state)
        }) {
            CommonBasicFields(state)
            FormTextField(stringResource(R.string.server_lab_id3), state.password, { state.password = it })
            FormTextField(stringResource(R.string.server_lab_sni), state.sni, { state.sni = it })
            SettingsSwitchItem(
                title = stringResource(R.string.server_lab_allow_insecure),
                checked = state.allowInsecure,
                onCheckedChange = { state.allowInsecure = it },
            )
            FormTextField(stringResource(R.string.server_lab_stream_alpn), state.alpn, { state.alpn = it })
            FormDropdownField(
                stringResource(R.string.server_lab_stream_fingerprint),
                state.fingerPrint,
                options.uTlsOptions,
                { state.fingerPrint = it },
            )
            FormTextField(
                stringResource(R.string.anytls_idle_check_interval),
                state.anytlsIdleSessionCheckInterval,
                { state.anytlsIdleSessionCheckInterval = it },
            )
            FormTextField(
                stringResource(R.string.anytls_idle_timeout),
                state.anytlsIdleSessionTimeout,
                { state.anytlsIdleSessionTimeout = it },
            )
            FormTextField(
                stringResource(R.string.anytls_min_idle_session),
                state.anytlsMinIdleSession,
                { state.anytlsMinIdleSession = it },
                keyboardType = KeyboardType.Number,
            )
            FormTextField(
                stringResource(R.string.anytls_client_metadata),
                state.anytlsClientMetadata,
                { state.anytlsClientMetadata = it },
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean =
        runCatching { AnyTlsFmt.validate(config) }.isSuccess.also {
            if (!it) toast(R.string.anytls_invalid_settings)
        }
}
