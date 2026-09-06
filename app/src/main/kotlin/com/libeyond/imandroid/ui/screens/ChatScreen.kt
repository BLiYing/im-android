package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SendHorizontal
import com.libeyond.imandroid.data.ChatEntry
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/** 聊天页显示的一行：已确认消息 或 待发消息。 */
sealed interface ChatRow {
    /** 身份键——列表 key 一律用它。 */
    val key: String

    data class Confirmed(val msg: MessageEntity) : ChatRow {
        /**
         * **用 convSeq 作身份，绝不用下标**。
         * 入站消息没有 clientMsgId；向上翻页 prepend 后下标整体平移，
         * 用下标会让 Compose 错绑已有节点（播放中的视频、展开的长文跳到别的行）。
         * iOS 与 Web 各踩过一次，见 `../IMServer/docs/SYMMETRY.md`。
         */
        override val key get() = "s${msg.convSeq}"
    }

    data class Pending(val msg: PendingMessageEntity) : ChatRow {
        /** 待发消息还没有 convSeq，用 clientMsgId——它本就是幂等键。 */
        override val key get() = "c${msg.clientMsgId}"
    }

    data class DayLabel(val timestamp: Long) : ChatRow {
        override val key get() = "d$timestamp"
    }

    /** 未读分割线（CHAT_UX §3）。 */
    data object UnreadDivider : ChatRow {
        override val key get() = "unread-divider"
    }
}

/** 各行的 conv_seq（非消息行为 0），供 [com.libeyond.imandroid.data.ChatEntry] 定位。 */
fun ChatRow.seqOrZero(): Long = when (this) {
    is ChatRow.Confirmed -> msg.convSeq
    else -> 0L
}

/**
 * 把已确认 + 待发两路合成一条显示流。**纯函数**，便于单测。
 *
 * 排序口径：`timestamp` 主排（三端契约）。待发消息恒在末尾——它们还没有服务端时间戳，
 * 用本地 createdAt，天然就是最新的。
 */
fun buildChatRows(
    confirmed: List<MessageEntity>,
    pending: List<PendingMessageEntity>,
    /** 本人已读位点；插未读分割线用。传 0 且 unread=0 时不插。 */
    readSeq: Long = 0,
    unread: Int = 0,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var prevTs = 0L
    var dividerPlaced = !ChatEntry.hasUnread(unread)
    for (m in confirmed.sortedWith(compareBy({ it.timestamp }, { it.convSeq }))) {
        if (TimeFormat.needsDaySeparator(prevTs, m.timestamp)) rows += ChatRow.DayLabel(m.timestamp)
        // 分割线插在首条未读**之前**
        if (!dividerPlaced && m.convSeq > readSeq) {
            rows += ChatRow.UnreadDivider
            dividerPlaced = true
        }
        rows += ChatRow.Confirmed(m)
        prevTs = m.timestamp
    }
    for (p in pending.sortedBy { it.createdAt }) {
        if (TimeFormat.needsDaySeparator(prevTs, p.createdAt)) rows += ChatRow.DayLabel(p.createdAt)
        rows += ChatRow.Pending(p)
        prevTs = p.createdAt
    }
    return rows
}

@Composable
fun ChatScreen(
    convId: String,
    title: String,
    myUid: String,
    /** 本人已读位点，用于未读分割线与首屏定位。 */
    readSeq: Long,
    /** **服务端算出的真实未读数**。判据只认它，见 [ChatEntry.hasUnread]。 */
    unread: Int,
    /** 副标题：在线态 / 「正在输入」。空串不显示。 */
    subtitle: String,
    /** 对端已读位点（单聊）。我发的 conv_seq ≤ 它 → 绿双勾。群聊传 0。 */
    peerReadSeq: Long,
    /** 输入变化时回调，供节流上报 typing。 */
    onTyping: () -> Unit,
    rows: List<ChatRow>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onRetry: (String) -> Unit,
    /** 可见即读：把已读位点推到这一条。 */
    onVisibleSeq: (Long) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val listState = rememberLazyListState()

    // —— 首屏定位（CHAT_UX §3）：有未读锚到首条未读，无未读贴底 ——
    // 只跑一次（key 用 convId），**不能与下面的自动贴底合并**：
    // 合并会让「停在首条未读」被贴底当场覆盖掉。
    var didEntryScroll by remember(convId) { mutableStateOf(false) }
    LaunchedEffect(convId, rows.size) {
        if (!didEntryScroll && rows.isNotEmpty()) {
            didEntryScroll = true
            val idx = ChatEntry.entryScrollIndex(rows.map { it.seqOrZero() }, readSeq, unread)
            listState.scrollToItem(idx)   // 瞬时滚动，不用 animate——首屏动画会被用户看成"跳了一下"
        }
    }

    // —— 新内容到达时贴底：只在用户本来就贴着底时 ——
    LaunchedEffect(rows.size) {
        if (!didEntryScroll || rows.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (ChatEntry.shouldAutoScroll(last, rows.size)) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    // —— 可见即读 ——
    // **注意**：程序化滚到底之后紧跟"可见即读"上报，等于替用户把消息读完
    // （十万条未读进一次会话清零就是这么来的，CHAT_UX §3）。
    // 故只在**首屏定位完成后**才上报，且只报真正可见的行。
    LaunchedEffect(rows.size, listState.layoutInfo.visibleItemsInfo.size) {
        if (!didEntryScroll) return@LaunchedEffect
        val maxSeq = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { (rows.getOrNull(it.index) as? ChatRow.Confirmed)?.msg?.convSeq }
            .maxOrNull() ?: return@LaunchedEffect
        onVisibleSeq(maxSeq)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding()
            .imePadding(),
    ) {
        // —— 标题栏 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = d.space3, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                imageVector = Lucide.ArrowLeft,
                contentDescription = "返回",
                modifier = Modifier.size(24.dp).clickable { onBack() },
                colorFilter = ColorFilter.tint(c.accent),
            )
            Spacer(Modifier.width(d.space3))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.textPrimary,
                    maxLines = 1,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (subtitle == "在线") c.online else c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = d.space3),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows.size, key = { rows[it].key }) { i ->
                when (val r = rows[i]) {
                    is ChatRow.DayLabel -> DaySeparator(r.timestamp)
                    is ChatRow.UnreadDivider -> UnreadDividerRow()
                    is ChatRow.Confirmed -> Bubble(
                        text = r.msg.content,
                        mine = r.msg.sender == myUid,
                        timestamp = r.msg.timestamp,
                        senderName = if (r.msg.sender != myUid) r.msg.fromNickname else null,
                        // 已读双勾：我发的、且对端读位点已越过它
                        read = r.msg.sender == myUid && peerReadSeq >= r.msg.convSeq,
                        delivered = r.msg.sender == myUid,
                    )
                    is ChatRow.Pending -> Bubble(
                        text = r.msg.content,
                        mine = true,
                        timestamp = r.msg.createdAt,
                        senderName = null,
                        sending = r.msg.state == SendState.Sending.name,
                        failed = r.msg.state == SendState.Failed.name,
                        onRetry = { onRetry(r.msg.clientMsgId) },
                    )
                }
            }
        }

        // ↓ 悬浮跳转：离底较远时出现，点了瞬时贴底
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val awayFromBottom = rows.isNotEmpty() && lastVisible in 0 until (rows.size - 1 - ChatEntry.NEAR_BOTTOM_SLACK)
        if (awayFromBottom) {
            val scope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = d.space4, bottom = d.space3)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable {
                        // 瞬时滚动，不用 animate——长列表上动画会滚很久，看着像卡住
                        scope.launch { listState.scrollToItem(rows.size - 1) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = "回到最新",
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
        }
        }

        Composer(
            input = input,
            onInputChange = {
                onInputChange(it)
                if (it.isNotEmpty()) onTyping()
            },
            onSend = onSend,
        )
    }
}

@Composable
private fun DaySeparator(ts: Long) {
    val c = IMTheme.colors
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.datePillBackground)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(TimeFormat.dayLabel(ts), color = c.onMedia, fontSize = 11.sp)
        }
    }
}

@Composable
private fun UnreadDividerRow() {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
        Text(
            text = "以下为新消息",
            color = c.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
    }
}

@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
    timestamp: Long,
    senderName: String?,
    sending: Boolean = false,
    failed: Boolean = false,
    delivered: Boolean = false,
    read: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (failed && onRetry != null) {
            // 红❗点击重发。放气泡外侧，不遮正文。
            Text(
                text = "❗",
                fontSize = 16.sp,
                modifier = Modifier.clickable { onRetry() }.padding(end = 4.dp),
            )
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(appearance.bubbleRadius))
                    .background(if (mine) c.bubbleMe else c.bubbleThem)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = text,
                        color = c.textPrimary,
                        fontSize = appearance.chatFontSize,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TimeFormat.bubbleTime(timestamp),
                            color = if (mine) c.metaTime else c.textTertiary,
                            fontSize = 10.sp,
                        )
                        if (sending) {
                            Spacer(Modifier.width(3.dp))
                            Text("🕐", fontSize = 9.sp)
                        } else if (delivered) {
                            Spacer(Modifier.width(3.dp))
                            // 已读=绿双勾 / 未读=灰单勾，与 iOS/Web 同一表意
                            Text(
                                text = if (read) "✓✓" else "✓",
                                color = if (read) c.checkRead else c.textTertiary,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(input: String, onInputChange: (String) -> Unit, onSend: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = d.space3, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(c.pageBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (input.isEmpty()) {
                Text("发送消息…", color = c.textTertiary, fontSize = 15.sp)
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (input.isNotBlank()) c.accent else c.neutralControl)
                .clickable(enabled = input.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.SendHorizontal,
                contentDescription = "发送",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
    }
}
