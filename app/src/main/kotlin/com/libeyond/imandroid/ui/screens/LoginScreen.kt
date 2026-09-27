package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSecondaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.IMThemeMode

/** 登录页的两个页签，与 Web 的「密码 / 扫码」结构对齐（扫码待 QR P1 接）。 */
enum class LoginTab { Password, Register }

/**
 * 登录页。**纯展示 + 本地表单态**，网络动作全经参数注入（CODING_STYLE §7②）。
 *
 * @param devLoginEnabled 后端开了 `-dev-login` 时显示免密入口。端上无法探知，
 *   故由构建变体决定：debug 显示、release 不显示。
 */
@Composable
fun LoginScreen(
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, password: String, nickname: String) -> Unit,
    onDevLogin: (username: String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    busy: Boolean,
    error: String,
    devLoginEnabled: Boolean,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var tab by remember { mutableStateOf(LoginTab.Password) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var showHost by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding()
            // 输入法弹起时整页上移，否则输入框被键盘盖住（docs/UI_COLOR.md §4.2）
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = d.space4, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.space3),
    ) {
        // —— Logo ——
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.MessageCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
        Text(
            text = "IM",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
        )

        // —— 页签 ——
        Row(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(d.space2),
        ) {
            LoginTab.entries.forEach { t ->
                val selected = t == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(d.radiusCard))
                        .background(if (selected) c.accentSoft else c.cardBackground)
                        .clickable { tab = t }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(if (t == LoginTab.Password) R.string.login_button_login else R.string.login_tab_register),
                        color = if (selected) c.accent else c.textSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(d.space3),
        ) {
            IMTextField(username, { username = it }, stringResource(R.string.settings_info_username), enabled = !busy)
            IMTextField(password, { password = it }, stringResource(R.string.login_password), isPassword = true, enabled = !busy)
            if (tab == LoginTab.Register) {
                IMTextField(nickname, { nickname = it }, stringResource(R.string.login_nickname), enabled = !busy)
                Text(
                    stringResource(R.string.login_register_format_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                )
            }

            IMErrorText(error)

            if (tab == LoginTab.Password) {
                IMPrimaryButton(
                    text = stringResource(R.string.login_button_login),
                    onClick = { onLogin(username.trim(), password) },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    loading = busy,
                )
            } else {
                IMPrimaryButton(
                    text = stringResource(R.string.login_button_register),
                    onClick = { onRegister(username.trim(), password, nickname.trim()) },
                    enabled = username.isNotBlank() && password.isNotBlank() && nickname.isNotBlank(),
                    loading = busy,
                )
            }

            if (devLoginEnabled && tab == LoginTab.Password) {
                IMSecondaryButton(
                    text = "免密登录（开发）",
                    onClick = { onDevLogin(username.trim()) },
                    enabled = username.isNotBlank() && !busy,
                )
            }

            // —— 服务器地址（开发期常要改，收在一个可折叠行里）——
            Text(
                text = if (showHost) "服务器地址" else "服务器：$host",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { showHost = !showHost },
            )
            if (showHost) {
                IMTextField(host, onHostChange, "host:port", enabled = !busy)
                Text(
                    "模拟器连宿主机用 10.0.2.2:8080（127.0.0.1 在模拟器里指模拟器自己）；" +
                        "真机填开发机内网 IP，并把该 IP 加进 network_security_config.xml",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                )
            }
        }
    }
}

@Preview(name = "登录·浅色", showBackground = true)
@Composable
private fun PreviewLoginLight() {
    IMAppTheme(mode = IMThemeMode.Light) {
        LoginScreen({ _, _ -> }, { _, _, _ -> }, {}, "10.0.2.2:8080", {}, false, "", true)
    }
}

@Preview(name = "登录·深色", showBackground = true)
@Composable
private fun PreviewLoginDark() {
    IMAppTheme(mode = IMThemeMode.Dark) {
        LoginScreen({ _, _ -> }, { _, _, _ -> }, {}, "10.0.2.2:8080", {}, false, "用户名或密码错误", true)
    }
}
