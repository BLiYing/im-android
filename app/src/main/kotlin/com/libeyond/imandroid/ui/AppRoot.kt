package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.session.RestoreOutcome
import com.libeyond.imandroid.sdk.ws.SessionEndReason
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import androidx.compose.runtime.DisposableEffect
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.screens.ChatScreen
import com.libeyond.imandroid.ui.screens.ConversationListScreen
import com.libeyond.imandroid.ui.screens.LoginScreen
import com.libeyond.imandroid.ui.screens.buildChatRows
import kotlinx.coroutines.flow.emptyFlow
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/** typing 上报节流间隔：每次按键都发是错的，服务端要给全体成员中继。 */
private const val TYPING_THROTTLE_MS = 3_000L

/** 应用阶段。 */
private enum class Phase { Restoring, Login, Main }

/**
 * 应用外壳：恢复会话 → 登录页 / 主界面。
 *
 * 刻意**不引入 Navigation 库**：当前只有两个顶层阶段，一个 enum 就够；
 * 等真的有多层路由（会话 → 聊天 → 资料）再引，别为两个页面背一套导航图。
 */
@Composable
fun AppRoot(client: IMClient) {
    val c = IMTheme.colors
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.Restoring) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(client.host) }
    var endedNotice by remember { mutableStateOf("") }

    // —— 冷启动恢复会话（只跑一次）——
    LaunchedEffect(Unit) {
        phase = when (val r = client.restore()) {
            RestoreOutcome.Alive, RestoreOutcome.Refreshed -> {
                client.connect(); Phase.Main
            }
            RestoreOutcome.NoCredentials -> Phase.Login
            is RestoreOutcome.Dead -> {
                endedNotice = "登录已失效，请重新登录"
                Phase.Login
            }
            // 判据三：网络不通 ≠ 会话已死。凭据还在，进主界面靠自动重连自愈，
            // **不要**因为一次连不上就把用户踢回登录页。
            RestoreOutcome.Unreachable -> if (client.isLoggedIn) {
                client.connect(); Phase.Main
            } else Phase.Login
        }
    }

    // —— 被踢 / 被封：回登录页 ——
    LaunchedEffect(Unit) {
        client.sessionEnded.collect { reason ->
            endedNotice = when (reason) {
                SessionEndReason.Revoked -> "你的账号已在别处登录，或该设备已被移除"
                SessionEndReason.Banned -> "账号已被封禁"
            }
            phase = Phase.Login
        }
    }

    when (phase) {
        Phase.Restoring -> Splash()

        Phase.Login -> LoginScreen(
            onLogin = { u, p ->
                scope.launch {
                    busy = true; error = ""
                    try {
                        client.host = host
                        client.login(u, p)
                        endedNotice = ""
                        phase = Phase.Main
                    } catch (e: ApiException) {
                        error = friendlyMessage(e)
                    } finally { busy = false }
                }
            },
            onRegister = { u, p, n ->
                scope.launch {
                    busy = true; error = ""
                    try {
                        client.host = host
                        client.register(u, p, n)
                        endedNotice = ""
                        phase = Phase.Main
                    } catch (e: ApiException) {
                        error = friendlyMessage(e)
                    } finally { busy = false }
                }
            },
            onDevLogin = { u ->
                scope.launch {
                    busy = true; error = ""
                    try {
                        client.host = host
                        client.login(u, null)
                        endedNotice = ""
                        phase = Phase.Main
                    } catch (e: ApiException) {
                        error = friendlyMessage(e)
                    } finally { busy = false }
                }
            },
            host = host,
            onHostChange = { host = it },
            busy = busy,
            error = if (error.isNotEmpty()) error else endedNotice,
            devLoginEnabled = BuildConfig.DEBUG,
        )

        Phase.Main -> MainScreen(
            client = client,
            onLogout = {
                scope.launch {
                    client.logout()
                    phase = Phase.Login
                }
            },
        )
    }
}

@Composable
private fun Splash() {
    val c = IMTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(c.groupedBackground),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = c.accent) }
}

/**
 * 业务码 → 用户可读文案。**按码分支，绝不 parse 服务端文案**
 * （文案会改、会多语言；PROTOCOL §8 也写明 message 只给开发看）。
 *
 * 未覆盖的码回退服务端文案——比显示一个码号强，且能暴露我们还没处理的分支。
 */
private fun friendlyMessage(e: ApiException): String = when {
    e.isTransport -> "网络连接失败，请检查服务器地址"
    else -> when (e.code) {
        com.libeyond.imandroid.sdk.protocol.ErrCode.WRONG_PASSWORD -> "用户名或密码错误"
        com.libeyond.imandroid.sdk.protocol.ErrCode.USER_NOT_FOUND -> "用户不存在"
        com.libeyond.imandroid.sdk.protocol.ErrCode.USER_ALREADY_EXISTS -> "该用户名已被占用"
        com.libeyond.imandroid.sdk.protocol.ErrCode.ACCOUNT_BANNED -> "账号已被封禁"
        com.libeyond.imandroid.sdk.protocol.ErrCode.PARAM_INVALID -> e.message.ifEmpty { "输入不合法" }
        com.libeyond.imandroid.sdk.protocol.ErrCode.RATE_LIMITED -> "操作太频繁，请稍后再试"
        else -> e.message.ifEmpty { "请求失败（${e.code}）" }
    }
}
