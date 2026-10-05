package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatSubtitle
import com.libeyond.imandroid.data.ChatSubtitleSpec
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.sendWatch
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import kotlinx.coroutines.delay

// 从 ChatHost.kt 拆出（2026-09-10，那份文件顶在 600/600 行）：聊天页副标题与群备注——
// 有自己的心跳定时器、watch 订阅与帧监听，按 CODING_STYLE §7① 本就该是独立的一块。
// 2026-10-06：副标题改按 `ChatSubtitle.resolve` 选（对齐 iOS：输入 → 连接态 → 在线态 / 成员数），新增群备注。

/**
 * 聊天页标题下那一行。空串 = 不显示。选哪一种由 [ChatSubtitle.resolve] 决定（对齐 iOS
 * `im_navigationSubtitle`：输入 → 连接态 → 单聊在线态 / 群成员数），这里只管心跳 / watch 与文案渲染。
 *
 * @param groupInfo 聊天页已拉的群资料（取 memberCount / isSuper）；单聊或未拉回为 null。
 * @param nameOf 群里「谁在输入」的 uid → 显示名（好友备注 > 群昵称 > uid）。
 * @param showConnState 连接态是否参与副标题。聊天页开；会话详情页头部不开（iOS 详情页也没有连接态）。
 */
@Composable
internal fun rememberChatSubtitle(
    client: IMClient,
    conv: ConversationEntity,
    groupInfo: GroupInfo? = null,
    nameOf: (String) -> String = { it },
    showConnState: Boolean = true,
): String {
    // —— 在线态：租约模型要求客户端自己敲心跳重算 ——
    // 「租约到期」是纯粹的时间流逝，不触发任何回调；不主动重算的话，
    // 用户静止不动时副标题会**永远**停在「在线」（PROTOCOL §5.5）。
    val presenceMap by client.presence.presence.collectAsState()
    val typingMap by client.presence.typing.collectAsState()
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(conv.convId) {
        // 进会话即上报 watch。force=true：服务端对每次 watch（含集合不变的重发）都回快照，
        // 返回聊天页正靠它刷新，别因为集合相同就跳过。
        if (!conv.isGroup && conv.peerUid.isNotEmpty()) {
            client.messages.sendWatch(setOf(conv.peerUid), force = true)
        }
        var sinceSnapshot = 0L
        while (true) {
            delay(Presence.TICK_MS)
            tick = System.currentTimeMillis()
            sinceSnapshot += Presence.TICK_MS
            // 对端不在线时定期重拉快照：单聊 topic 随首条消息才建，
            // 「刚加好友但从未聊过」的对端收不到上线帧，只靠帧永远升不回「在线」。
            if (sinceSnapshot >= Presence.SNAPSHOT_REFRESH_MS && !conv.isGroup) {
                sinceSnapshot = 0
                val p = client.presence.snapshotOf(conv.peerUid)
                val d = Presence.display(p.status, p.onlineUntil, p.lastSeen, tick)
                if (Presence.needsSnapshotRefresh(d)) client.messages.refreshConversations()
            }
        }
    }

    // 退出聊天页清空 watch 集（全量替换语义）
    DisposableEffect(conv.convId) {
        onDispose { client.messages.sendWatch(emptySet(), force = true) }
    }

    // —— 正在输入：需要精确到点清除，不能靠上面 30s 一次的粗粒度 tick ——
    // typing TTL 只有 5s（PresenceStore.TYPING_TTL_MS），若只靠 `tick` 触发重算，
    // 最坏要等下一次心跳（最多 30s 后）才会发现已过期，表现为「正在输入」赖着不消失（用户报）。
    // 借鉴 iOS `cancelPreviousPerformRequestsWithTarget` + `performSelector:afterDelay:` 的debounce
    // 套路：LaunchedEffect 的 key 换成新到期时间即自动取消上一个定时器、重新掐表——效果等价。
    var typingNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val typingExpiry = typingMap[conv.convId]?.second
    LaunchedEffect(typingExpiry) {
        if (typingExpiry == null) return@LaunchedEffect
        val wait = typingExpiry - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        typingNow = System.currentTimeMillis()
    }

    // 详情页不用连接态：不订阅，免得每次重连抖动都重组整页
    val conn = if (showConnState) client.socket.state.collectAsState().value else ConnState.Connected
    val spec = remember(conv.convId, presenceMap, typingMap, tick, typingNow, conn, groupInfo) {
        ChatSubtitle.resolve(
            isGroup = conv.isGroup,
            typingUid = client.presence.typingIn(conv.convId, maxOf(tick, typingNow)),
            conn = conn,
            peerPresence = if (conv.isGroup) "" else client.presence.snapshotOf(conv.peerUid).let { p ->
                Presence.label(Presence.display(p.status, p.onlineUntil, p.lastSeen, tick), tick)
            },
            memberCount = groupInfo?.memberCount ?: 0,
            loadedMembers = groupInfo?.members?.size ?: 0,
            isSuper = groupInfo?.isSuper ?: conv.isSuper,
        )
    }
    return when (val sp = spec) {
        ChatSubtitleSpec.None -> ""
        ChatSubtitleSpec.Typing -> stringResource(R.string.chat_typing)
        is ChatSubtitleSpec.TypingNamed -> stringResource(R.string.chat_typing_named, nameOf(sp.uid))
        ChatSubtitleSpec.Connecting -> stringResource(R.string.conn_state_connecting)
        ChatSubtitleSpec.Disconnected -> stringResource(R.string.conn_state_disconnected)
        is ChatSubtitleSpec.PeerPresence -> sp.text
        is ChatSubtitleSpec.Members -> pluralStringResource(
            if (sp.isSuper) R.plurals.chat_header_member_count_super else R.plurals.chat_header_member_count,
            sp.count, sp.count,
        )
        ChatSubtitleSpec.SuperOnly -> stringResource(R.string.group_text_super)
    }
}

/**
 * 群备注（G1，仅本人可见、多端同步）：进页拉一次单会话设置，之后随 conv_update 帧的全值就地刷新。
 * 对齐 iOS `loadConvRemark` + `onConvUpdatedForRemark`。单聊恒为空串。
 */
@Composable
internal fun rememberGroupRemark(client: IMClient, conv: ConversationEntity): String {
    var remark by remember(conv.convId) { mutableStateOf("") }
    // 帧版本：GET 在途时若先收到 conv_update 帧（更新鲜），GET 的旧结果不能再覆盖它
    var frameRev by remember(conv.convId) { mutableStateOf(0) }
    LaunchedEffect(conv.convId) {
        if (!conv.isGroup) return@LaunchedEffect
        client.convRemarks.collect { if (it.convId == conv.convId) { remark = it.remark; frameRev++ } }
    }
    // 连上（含重连）就拉一次：进页时断网拉失败，重连后自愈，不必等下一帧
    val connected = client.socket.state.collectAsState().value == ConnState.Connected
    LaunchedEffect(conv.convId, connected) {
        if (!conv.isGroup || !connected) return@LaunchedEffect
        val rev = frameRev
        runCatchingCancellable { client.conversationsApi.settings(conv.convId) }
            .onSuccess { if (rev == frameRev) remark = it.remark }
            .onFailure { IMLog.tag("IM.Chat").w("group_remark_load_failed", "err" to it.javaClass.simpleName) }
    }
    return remark
}
