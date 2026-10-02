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
import com.libeyond.imandroid.data.activeLocalDayStarts
import com.libeyond.imandroid.data.clearedUpTo
import com.libeyond.imandroid.data.ChatWindows
import com.libeyond.imandroid.data.firstConvSeqAtOrAfter
import com.libeyond.imandroid.data.firstConvSeqOnDay
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

    /**
     * 弹层圆点打点集合（本地时区分桶 ms，对齐 iOS `IMChatDateJumpViewController` 的 `_activeDayKeys`）：
     * 本地打点 ∪ 服务端打点（有缺口且在线才有后者）。**打开弹层前恒为空**——别在没查过库时画"全灰"，
     * 那看着像"真没消息"。
     */
    var activeDays by mutableStateOf<Set<Long>>(emptySet())
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
    /** 「最早」专用出口（[ChatLocator.locateEarliest]）——**不是** `onLocate(1L, …)`，见该方法注释。 */
    onLocateEarliest: ((String) -> Unit) -> Unit,
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
        val nowMs = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(nowMs).toLong()
        // 本地打点恒先查一遍——即便有缺口/离线也至少能画出本地已下载部分的点（镜像 iOS
        // `activeDaysFromMessages` 恒查库，不是只在"本地完整"分支才查）。
        val localDays = client.repo.activeLocalDayStarts(owner, convId, offset)
        ctl.activeDays = localDays
        val complete = client.repo.isLocalComplete(owner, convId, client.messages.historyFloors.get(convId))
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
        runCatchingCancellable {
            client.conversationsApi.calendar(convId, nowMs - ChatCalendar.QUERY_SPAN_MS, nowMs, offset, client.repo.clearedUpTo(owner, convId))
        }
            .onSuccess { resp ->
                ctl.serverDays = resp.days
                ctl.activeDays = localDays + resp.days.map { it.dayStartMs }.toSet()
            }
            // 悄悄失败——跳转时会退化到本地查询，日历只是个次要入口，不必为它打断整页；
            // 打点也保留刚查到的本地版本，不整层清空。
            .onFailure { e -> log.w("calendar_fetch_failed", "convId" to convId, "err" to e.javaClass.simpleName) }
    }

    ctl.pickDay = { localDayStart ->
        ctl.open = false
        scope.launch {
            // 服务端天表会把「当天第一条在清空位点以内」的整天丢掉（ConvQueryFloor），而那天清空后本机又收到的新消息
            // 只在本地打点里——所以本地当天有就取较小者，别被天表带到后面某一天去
            val serverSeq = ctl.serverDays?.let { ChatCalendar.firstSeqOnOrAfter(it, localDayStart) }
            val localDay = client.repo.firstConvSeqOnDay(owner, convId, localDayStart)
            val fromServer = listOfNotNull(serverSeq, localDay).minOrNull()
            val complete = client.repo.isLocalComplete(owner, convId, client.messages.historyFloors.get(convId))
            // 本地有缺口又没有服务端天表（离线 / 拉取失败）：「那天或之后第一条」会跳过缺口静默落到别的日子，
            // 只认当天；找不到不能说「没有消息」，缺口里可能就有——如实说需要联网（§4.9 第 4 项）。
            val seq = fromServer ?: if (complete) {
                client.repo.firstConvSeqAtOrAfter(owner, convId, localDayStart)
            } else {
                client.repo.firstConvSeqOnDay(owner, convId, localDayStart)
            }
            when {
                seq != null -> onLocate(seq, onToast)
                complete || ctl.serverDays != null -> onToast(ChatCalendar.NO_MESSAGE_ON_OR_AFTER)
                else -> onToast(ChatWindows.NEED_NETWORK_NOTICE)
            }
        }
    }

    // 「最早」**不是**无脑 conv_seq=1：入群前历史不可见的成员永远拿不到 1 号，
    // conv_seq=1 常常根本不是一条我能看见的消息——见 [ChatLocator.locateEarliest] 的实现注释。
    ctl.pickEarliest = {
        ctl.open = false
        onLocateEarliest(onToast)
    }

    ctl.pickToday = {
        ctl.open = false
        scope.launch {
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            val todayStart = ChatCalendar.dayStartMs(System.currentTimeMillis(), offset)
            val complete = client.repo.isLocalComplete(owner, convId, client.messages.historyFloors.get(convId))
            // 有缺口时「今天有没有消息」本地答不了：有服务端天表就看它里面今天那一格，没有就只认本地当天的，
            // 都没有也**不能**宣布「今天没有消息」（缺口里可能就有）——如实说需要联网。
            val seq = if (complete) {
                client.repo.firstConvSeqAtOrAfter(owner, convId, todayStart)
            } else {
                ctl.serverDays?.firstOrNull { it.dayStartMs == todayStart }?.firstConvSeq?.takeIf { it > 0 }
                    ?: client.repo.firstConvSeqOnDay(owner, convId, todayStart)
            }
            when {
                seq != null -> onLocate(seq, onToast)
                !complete && ctl.serverDays == null -> onToast(ChatWindows.NEED_NETWORK_NOTICE)
                else -> {
                    // 今天没有消息：退到会话最新一条，且必须说清楚退了——不能装作用户要的就是这条
                    val conv = client.repo.conversation(owner, convId)
                    onToast(ChatCalendar.NOTHING_TODAY)
                    if (conv != null && conv.lastConvSeq > 0) onLocate(conv.lastConvSeq, onToast)
                }
            }
        }
    }

    return ctl
}
