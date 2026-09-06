package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.screens.ChatScreen
import com.libeyond.imandroid.ui.screens.buildChatRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** typing 上报节流：每次按键都发是错的，服务端要给全体成员中继。 */
private const val TYPING_THROTTLE_MS = 3_000L

/**
 * 聊天页的接线层：读库、算副标题、发消息、管 watch。
 * [ChatScreen] 保持纯展示（CODING_STYLE §7②）。
 */
@Composable
fun ChatHost(client: IMClient, conv: ConversationEntity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val owner = client.uid.orEmpty()
    var input by remember(conv.convId) { mutableStateOf("") }

    val messages by remember(owner, conv.convId) {
        client.repo.observeMessages(owner, conv.convId)
    }.collectAsState(initial = emptyList())

    val pending by remember(owner, conv.convId) {
        client.repo.observePending(owner, conv.convId)
    }.collectAsState(initial = emptyList())

    // **进会话那一刻的快照，之后不再跟随**。
    //
    // 直接用实时的 conv.readSeq / conv.unread 会让未读分割线在进会话后当场消失：
    // 「可见即读」一上报就把 unread 清零，重组时 buildChatRows 拿到 unread=0，
    // 分割线随之不见——用户根本来不及看到自己从哪里开始没读。
    // iOS/Web 同样是冻结入会话快照，不是实时值。
    val entry = remember(conv.convId) { conv.readSeq to conv.unread }

    val rows = remember(messages, pending, entry) {
        buildChatRows(messages, pending, entry.first, entry.second)
    }

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

    val subtitle = remember(conv.convId, presenceMap, typingMap, tick) {
        val who = client.presence.typingIn(conv.convId, tick)
        when {
            who != null -> "正在输入…"
            conv.isGroup -> ""
            else -> {
                val p = client.presence.snapshotOf(conv.peerUid)
                Presence.label(Presence.display(p.status, p.onlineUntil, p.lastSeen, tick), tick)
            }
        }
    }

    var lastTypingSent by remember(conv.convId) { mutableStateOf(0L) }

    ChatScreen(
        convId = conv.convId,
        title = conv.title.ifBlank { conv.convId },
        myUid = owner,
        rows = rows,
        readSeq = entry.first,
        unread = entry.second,
        subtitle = subtitle,
        peerReadSeq = if (conv.isGroup) 0 else conv.peerReadSeq,
        input = input,
        onInputChange = { input = it },
        onTyping = {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent >= TYPING_THROTTLE_MS) {
                lastTypingSent = now
                client.messages.sendTyping(conv.convId)
            }
        },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                input = ""
                scope.launch {
                    client.messages.sendText(
                        convId = conv.convId,
                        to = if (conv.isGroup) conv.convId else conv.peerUid,
                        text = text,
                    )
                }
            }
        },
        onBack = onBack,
        onRetry = { cid -> scope.launch { client.messages.resend(cid) } },
        onVisibleSeq = { seq -> scope.launch { client.messages.markRead(conv.convId, seq) } },
    )
}
