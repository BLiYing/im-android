package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatCalendar
import com.libeyond.imandroid.data.firstConvSeqAtOrAfter
import com.libeyond.imandroid.data.isLocalComplete
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvCalendarDay
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * 聊天页 📅 日历跳转（对齐 iOS `IMChatViewController+Search.m` 的 `searchCalTapped` +
 * `handleDateJumpKind:day:`；纯逻辑在 [ChatCalendar]，UI 在 `ChatCalendarDialog`）。
 *
 * 与会话内搜索是**两条独立的状态机**（不像"来自"那样要接进搜索的命中集重算）：
 * 日历跳完之后搜索框内容、命中集都不受影响，用户还能接着用 ▲▼ 翻——理由与做法见 iOS 那侧的注释。
 */
@Stable
class ChatCalendarController internal constructor() {

    /** 日历弹层开着没有。 */
    var open by mutableStateOf(false)
        internal set

    /**
     * 服务端日历天表（本地有缺口且在线时异步拉到的近 13 个月）；`null` = 还没有 / 用不上
     * （本地库已经齐全，见 [rememberChatCalendar] 里的判据）。
     */
    var serverDays by mutableStateOf<List<ConvCalendarDay>?>(null)
        internal set

    /** 本次搜索会话里"仅本地"降级提示是否已经弹过一次——同一次别反复打扰。 */
    internal var degradedNoticed = false

    internal var pickDay: (Long) -> Unit = {}
    internal var pickEarliest: () -> Unit = {}
    internal var pickToday: () -> Unit = {}

    fun show() {
        open = true
    }

    fun dismiss() {
        open = false
    }

    /** @param localDayStartMs 用户选中那天、**本地时区**的 00:00 对应 UTC 毫秒（见 [ChatCalendar.dayStartMs]）。 */
    fun pick(localDayStartMs: Long) = pickDay(localDayStartMs)

    fun earliest() = pickEarliest()

    fun today() = pickToday()
}

/**
 * @param onLocate 复用聊天页唯一的跳转出口（[rememberChatLocator]），日历跳转不是另一套机制。
 * @param onToast  降级提示 / 跳不了时的说法——日历是弹层浮在聊天页上，吐司够用，不必像搜索那样接管成一行字。
 */
@Composable
fun rememberChatCalendar(
    client: IMClient,
    convId: String,
    online: Boolean,
    onLocate: (Long, (String) -> Unit) -> Unit,
    onToast: (String) -> Unit,
): ChatCalendarController {
    val owner = client.uid.orEmpty()
    val ctl = remember(convId) { ChatCalendarController() }
    val scope = rememberCoroutineScope()
    val log = remember { IMLog.tag("IM.Calendar") }

    // 打开日历那一刻才判断要不要问服务端——本地齐全时按需现查本地就够了，没必要每次开会话都请求一遍。
    // key 带 online：日历开着时网络从离线恢复，要能补一次服务端请求，不能停在"离线降级"的答案上不再问。
    LaunchedEffect(ctl.open, convId, owner, online) {
        if (!ctl.open || owner.isEmpty()) return@LaunchedEffect
        val complete = client.repo.isLocalComplete(owner, convId)
        if (complete) {
            ctl.serverDays = null
            return@LaunchedEffect
        }
        if (!online) {
            if (!ctl.degradedNoticed) {
                ctl.degradedNoticed = true
                onToast(ChatCalendar.DEGRADED_NOTICE)
            }
            return@LaunchedEffect
        }
        val nowMs = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(nowMs).toLong()
        runCatchingCancellable {
            client.conversationsApi.calendar(convId, nowMs - ChatCalendar.QUERY_SPAN_MS, nowMs, offset)
        }
            .onSuccess { ctl.serverDays = it.days }
            // 悄悄失败——跳转时会退化到本地查询，日历只是个次要入口，不必为它打断整页
            .onFailure { e -> log.w("calendar_fetch_failed", "convId" to convId, "err" to e.javaClass.simpleName) }
    }

    ctl.pickDay = { localDayStart ->
        ctl.open = false
        scope.launch {
            val seq = ctl.serverDays?.let { ChatCalendar.firstSeqOnOrAfter(it, localDayStart) }
                ?: client.repo.firstConvSeqAtOrAfter(owner, convId, localDayStart)
            if (seq != null) onLocate(seq, onToast) else onToast(ChatCalendar.NO_MESSAGE_ON_OR_AFTER)
        }
    }

    // 「最早」= conv_seq 1（每个会话的序号从 1 起、连续不跳号）。直接走既有跳转出口：
    // 本地有就地开窗，本地没有（有缺口）就发 window_req 问服务端要——不必另起一条"要最早一条"的协议。
    ctl.pickEarliest = {
        ctl.open = false
        onLocate(1L, onToast)
    }

    ctl.pickToday = {
        ctl.open = false
        scope.launch {
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            val todayStart = ChatCalendar.dayStartMs(System.currentTimeMillis(), offset)
            val seq = client.repo.firstConvSeqAtOrAfter(owner, convId, todayStart)
            if (seq != null) {
                onLocate(seq, onToast)
            } else {
                // 今天没有消息：退到会话最新一条，且必须说清楚退了——不能装作用户要的就是这条
                val conv = client.repo.conversation(owner, convId)
                if (conv != null && conv.lastConvSeq > 0) {
                    onToast(ChatCalendar.NOTHING_TODAY)
                    onLocate(conv.lastConvSeq, onToast)
                } else {
                    onToast(ChatCalendar.NOTHING_TODAY)
                }
            }
        }
    }

    return ctl
}
