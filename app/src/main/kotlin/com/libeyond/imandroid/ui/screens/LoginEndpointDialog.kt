package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LoginEndpoints
import com.libeyond.imandroid.rtc.RtcConfig
import com.libeyond.imandroid.rtc.RtcEndpointStore
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.theme.IMTheme

private val log = IMLog.tag("IM.Login")

/**
 * 登录页长按 Logo 弹出的「配置地址」：第一行服务器地址，第二行音视频（im-rtc 信令）地址，留空恢复默认。
 * 对齐 iOS `IMLoginViewController presentEndpointConfigWithHost:rtc:error:`。
 *
 * 不合法时弹窗不关、保留已填内容并给出红字。点「保存」即落盘（同 iOS）：服务器地址经 [onHostSave] 写入本机会话存储，
 * 不等点登录/注册；音视频地址写入 [RtcEndpointStore]——登录后新建通话引擎时读取。
 */
@Composable
fun LoginEndpointDialog(
    host: String,
    onDismiss: () -> Unit,
    /** 更新登录页 host 并持久化（AppRoot 写 `client.host`）。 */
    onHostSave: (String) -> Unit,
) {
    var hostText by remember { mutableStateOf(host) }
    var rtcText by remember { mutableStateOf(RtcConfig.load().wsUrl) }
    var error by remember { mutableStateOf<Int?>(null) }

    val save = {
        when (val r = LoginEndpoints.check(hostText, rtcText, BuildConfig.USE_TLS)) {
            LoginEndpoints.Check.HostInvalid -> error = R.string.login_error_host_invalid
            LoginEndpoints.Check.RtcInvalid -> error = R.string.login_error_rtc_url_invalid
            is LoginEndpoints.Check.Ok -> {
                val newHost = r.host ?: BuildConfig.DEFAULT_HOST
                RtcEndpointStore.save(r.rtcWsUrl)
                onHostSave(newHost)
                log.i("login_endpoint_saved", "host" to newHost, "rtc" to RtcConfig.load().wsUrl)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_endpoint_config_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(stringResource(it), color = IMTheme.colors.danger) }
                Text(stringResource(R.string.login_endpoint_config_hint))
                EndpointField(hostText, { hostText = it; error = null }, stringResource(R.string.login_host_placeholder))
                EndpointField(rtcText, { rtcText = it; error = null }, stringResource(R.string.login_rtc_address_placeholder))
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EndpointField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
    )
}
