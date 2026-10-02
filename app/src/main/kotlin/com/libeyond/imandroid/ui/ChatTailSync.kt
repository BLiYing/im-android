package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatTailPlan
import com.libeyond.imandroid.data.ChatWindow
import com.libeyond.imandroid.data.ChatWindows
import com.libeyond.imandroid.data.tailState
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.PerfMarks
import kotlinx.coroutines.launch

/**
 * 聊天页**尾窗**的同步：点 ↓ 回到最新 / 超级群 `conv_bump` 到了贴底就补最新一页（OFFLINE_BACKLOG_DESIGN §4.8，C4b）。
 *
 * 此前 ↓ 只是把窗口换回本地 `Tail`——本地没有最新一页（超级群、离线积压留了缺口）时回去的是一屏旧消息。
 * 现在先问区间清单「最新一页齐不齐」（[ChatTailPlan.shouldRequestTail]），不齐才 `window_req(anchor=0)` 取最新一页，
 * 再把尾窗**固定在最新那一段**（[ChatWindow.Tail.fromSeq]），不把缺口另一侧的旧岛拼进来。
 *
 * **补法与 Web 刻意不同**（SYMMETRY 登记）：这里和 iOS 一样「取最新一页整窗替换」，不做「只补差的几条再向下追加」——
 * 向下追加要保持首个可见行不动、不贴底，跟随中的用户会看到新消息落在屏幕下方。**「贴底才补、翻历史只让 ↓N 按 head 计数」
 * 这条不变式三端一致。**
 */
@Stable
class ChatTail internal constructor() {
    /**
     * 列表是否离底很远。**不是 State**：由 `showsJumpToLatest` 回调（`ChatScreen` 每次组合都会调，传的就是它自己算出的
     * `awayFromBottom`）顺手记下，只被 effect 读，写它不该触发重组。
     */
    @Volatile
    internal var awayFromBottom = false

    internal var jump: () -> Unit = {}
    internal var back: () -> Unit = {}

    /** ↓：回到最新（本地没有最新页则先向服务端要）。 */
    fun jumpToLatest() = jump()

    /** 自己发了东西、窗口停在历史：换回尾窗（只看本地，刚发的那条已落库登记）。 */
    fun returnToTail() = back()
}

/** 向服务端要最新一页时取多少条。 */
private const val LATEST_FETCH = 100

@Composable
fun rememberChatTail(
    client: IMClient,
    convId: String,
    owner: String,
    window: ChatWindow,
    setWindow: (ChatWindow) -> Unit,
    /** 当前窗口里最新一条的 `conv_seq`（空窗 0）。 */
    tailHi: Long,
    connected: Boolean,
): ChatTail {
    val scope = rememberCoroutineScope()
    val tail = remember(convId) { ChatTail() }
    val windowNow by rememberUpdatedState(window)
    val tailHiNow by rememberUpdatedState(tailHi)
    val connectedNow by rememberUpdatedState(connected)

    /** 最新页不齐且在线就向服务端要；返回取数后的 [com.libeyond.imandroid.data.TailState]。 */
    suspend fun fetchLatestIfNeeded(): com.libeyond.imandroid.data.TailState {
        val st = client.repo.tailState(owner, convId, LATEST_FETCH)
        if (ChatTailPlan.shouldRequestTail(st.tip, st.covered, tailHiNow, st.visibleFrom) && connectedNow) {
            // 落库在 MessageService 的 WINDOW_RESP 分支里先于 deliver——await 返回时那一页已入库、区间已登记
            client.messages.windows.await(convId, 0L, LATEST_FETCH, 0)
            return client.repo.tailState(owner, convId, LATEST_FETCH)
        }
        return st
    }

    tail.jump = {
        scope.launch {
            PerfMarks.jumpBottomBegin(convId)
            val st = fetchLatestIfNeeded()
            setWindow(ChatWindow.Tail(ChatWindows.TAIL_LIMIT, st.segmentLo))
        }
    }
    tail.back = {
        scope.launch {
            val st = client.repo.tailState(owner, convId, LATEST_FETCH)
            setWindow(ChatWindow.Tail(ChatWindows.TAIL_LIMIT, st.segmentLo))
        }
    }

    // 超级群 conv_bump / 重连补拉把 head 抬高了：只有「正贴着底」才补最新一页（翻历史时补会把人拽走）
    //
    // 两条守卫缺一不可，都是 iOS 踩过的：
    // ① **只响应「开着会话期间 head 又涨了」**，进会话那一刻的 head 只当基线——否则进一个有十万未读的会话就当场
    //    把最新一页拉下来，「可见即读」一上报读位点跳到 head，十万未读打开即清零（iOS 2026-09-03 实测）；
    // ② **窗口里得有东西**才谈得上「正贴着底」——空窗口不是在跟，是还没开窗（开窗归 C3 的进会话）。
    val head by remember(owner, convId) { client.repo.observeHead(owner, convId) }.collectAsState(initial = -1L)
    var baseline by remember(convId) { mutableStateOf(-1L) }
    LaunchedEffect(head) {
        if (head < 0) return@LaunchedEffect // 还没读到
        if (baseline < 0) { baseline = head; return@LaunchedEffect }
        if (head <= baseline) return@LaunchedEffect
        baseline = head
        val following = windowNow is ChatWindow.Tail && !tail.awayFromBottom && tailHiNow > 0
        if (!ChatTailPlan.bumpShouldCatchUp(following, head, tailHiNow)) return@LaunchedEffect
        val st = fetchLatestIfNeeded()
        // 取数期间用户可能已经翻去看历史了：只在仍然贴底时才换窗
        if (windowNow is ChatWindow.Tail && !tail.awayFromBottom) {
            setWindow(ChatWindow.Tail(ChatWindows.TAIL_LIMIT, st.segmentLo))
        }
    }
    return tail
}
