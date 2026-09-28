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
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.rtc.RtcProfileResolver
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.session.DeviceIdentity
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
import androidx.compose.runtime.CompositionLocalProvider
import com.libeyond.imandroid.ui.components.LocalMediaGate
import com.libeyond.imandroid.ui.components.MediaGateEnv
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
                endedNotice = Str.s(R.string.common_login_expired)
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
                SessionEndReason.Revoked -> Str.s(R.string.login_revoked_notice)
                SessionEndReason.Banned -> Str.s(R.string.err_200003)
            }
            phase = Phase.Login
        }
    }

    // im-rtc 通话的生命周期跟着 IM 登录态走：进主界面上线，回登录页（退出 / 被踢 / 被封）下线。
    // 只在 Login 停、Restoring 不动：Activity 重建时 phase 会回到 Restoring，那不是退出，不能挂掉在途通话。
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(phase) {
        when (phase) {
            Phase.Main -> {
                val profiles = RtcProfileResolver.forClient(appContext, client)
                // 通话记录：主叫端在 SDK 的 callSummary 到达时发一条 `call` 消息（id 固定 call-<call_id>）。
                // 失败只写日志，不影响通话；被拉黑（200102）由仓库层吞掉。
                RtcCall.onCallRecord = { plan ->
                    scope.launch {
                        runCatching {
                            val convId = if (plan.isGroup) plan.chatGroupId
                            else client.conversationStubFor(plan.peerUid, "", "").convId
                            client.messages.sendCallRecord(convId, if (plan.isGroup) plan.chatGroupId else plan.peerUid, plan.callId, plan.json)
                        }.onFailure { com.libeyond.imandroid.sdk.logging.IMLog.tag("IM.RTC").w("call_record_send_failed", "cid" to plan.callId, "err" to it.javaClass.simpleName) }
                    }
                }
                RtcCall.start(
                    appContext, client.uid.orEmpty(), DeviceIdentity(appContext).deviceId,
                    profiles = profiles,
                    invites = RtcProfileResolver.inviteProviderFor(client, profiles),
                )
            }
            Phase.Login -> { RtcCall.onCallRecord = null; RtcCall.stop() }
            Phase.Restoring -> Unit
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
                        error = LoginError.friendly(e, appContext)
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
                        error = LoginError.friendly(e, appContext)
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
                        error = LoginError.friendly(e, appContext)
                    } finally { busy = false }
                }
            },
            host = host,
            onHostChange = { host = it },
            busy = busy,
            error = if (error.isNotEmpty()) error else endedNotice,
            devLoginEnabled = BuildConfig.DEBUG,
        )

        Phase.Main -> {
            // 自动下载策略：进主界面先拉一次，不等 WS 连上（HTTP 往往比握手先通）；
            // 之后「连上即重拉」与 capabilities_update 由 IMClient 负责（DownloadSettingsStore）。
            // **拉不到就按出厂默认走**，不是全关——全关会让所有图片都要手点。
            LaunchedEffect(Unit) { client.refreshDownloadSettings() }
            // 下载门控的环境（下载器 + 策略 + 网络类型）。**整棵树共用一份**：
            // 气泡 / 宫格 / 文件 / 详情四处必须看到同一份在途状态，
            // 各建一个的话同一条媒体会被下两遍、进度各显各的。
            CompositionLocalProvider(
                LocalMediaGate provides MediaGateEnv(
                    downloads = client.downloads,
                    settings = { client.downloadSettings },
                    onWifi = rememberOnWifi(),
                    myUid = { client.uid.orEmpty() },
                ),
                // 语音播放器：整棵树共用一份（气泡 / 资料页 / 收藏 / 记录页点这条就停那条）
                com.libeyond.imandroid.ui.voice.LocalVoicePlayer provides client.voice,
                // 录音机：同理进程内一份（中断后回到原会话锁定行还在）
                com.libeyond.imandroid.ui.voice.LocalVoiceRecorder provides client.recorder,
                // 语音转文字：同理进程内一份（展开态、长按菜单「转文字/取消转文字」都读它）
                com.libeyond.imandroid.ui.voice.LocalVoiceTranscriber provides client.voiceTranscriber,
            ) {
                // 应用内浏览器盖在整个主界面之上：聊天 / 详情 / 群资料 / 聊天记录里点开的链接都走它
                WebLinkHost {
                    MainScreen(
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
        }
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

