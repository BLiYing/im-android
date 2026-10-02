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
import com.libeyond.imandroid.data.extendWindowOlder
import com.libeyond.imandroid.data.localEntryWindow
import com.libeyond.imandroid.data.planEntry
import com.libeyond.imandroid.data.tailState
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.logging.PerfMarks
import com.libeyond.imandroid.sdk.ws.ConnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    internal var older: (Long, Int) -> Unit = { _, _ -> }

    /**
     * 进会话的取数决定下来了（含失败降级）。**并进 `rowsReady`**：占位窗读不出消息，但出箱里有失败待重发的行时
     * 列表并不是空的，首屏定位会拿这几行抢跑——不能靠「没有行」来表达「还没开窗」。
     */
    var entryDecided by mutableStateOf(false)
        internal set

    /** 滚到顶了要更早的：本段本地还有就展开，到段边缘才向服务端要一页（C3）。 */
    fun loadOlder(oldestRendered: Long, renderedCount: Int) = older(oldestRendered, renderedCount)
}

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
    onToast: (String) -> Unit,
    /** **进会话那一刻的快照**（已读位点 / 服务端真实未读数），之后不跟随。 */
    entryReadSeq: Long,
    entryUnread: Int,
): ChatTail {
    val scope = rememberCoroutineScope()
    val tail = remember(convId) { ChatTail() }
    val windowNow by rememberUpdatedState(window)
    val tailHiNow by rememberUpdatedState(tailHi)
    val connectedNow by rememberUpdatedState(connected)

    /** 服务端可见下界：**所有**取 [tailState] 的入口都经它（手抄漂移防线：漏传则尾窗下界会低于服务端说过的下界）。 */
    fun floorNow() = client.messages.historyFloors.get(convId)
    suspend fun stateNow() = client.repo.tailState(owner, convId, ChatWindows.LATEST_FETCH, floorNow())

    /** 最新页不齐且在线就向服务端要；返回取数后的 [com.libeyond.imandroid.data.TailState]。 */
    suspend fun fetchLatestIfNeeded(): com.libeyond.imandroid.data.TailState {
        val st = stateNow()
        if (ChatTailPlan.shouldRequestTail(st.tip, st.covered, tailHiNow, st.visibleFrom) && connectedNow) {
            // 落库在 MessageService 的 WINDOW_RESP 分支里先于 deliver——await 返回时那一页已入库、区间已登记
            client.messages.windows.await(convId, 0L, ChatWindows.LATEST_FETCH, 0)
            return stateNow()
        }
        return st
    }

    // —— 进会话（OFFLINE_BACKLOG_DESIGN §4.6）——
    // 先问区间清单：这一窗本地齐不齐。齐 → 开本地窗；不齐 → 向服务端要**这一屏**（有未读锚到已读位点、无未读取最新），
    // 落库登记后再按本地开窗。**开窗前 ChatHost 给的是一个取不到任何行的占位窗**——换窗之前列表是空的，
    // 首屏定位（ChatScroll 的 didEntry）要等有行才做，于是它第一次看到的就是决定好的那一窗。
    LaunchedEffect(convId, owner) {
        if (owner.isEmpty()) return@LaunchedEffect
        try {
            val plan = client.repo.planEntry(owner, convId, entryReadSeq, entryUnread, floorNow())
            var fetched = true
            if (plan is com.libeyond.imandroid.data.EntryPlan.Server) {
                // 刚冷启动进来时 WS 可能还没连上：等一小会儿，别一上来就判离线
                val up = withTimeoutOrNull(CONNECT_WAIT_MS) { client.socket.state.first { it == ConnState.Connected } }
                fetched = up != null &&
                    client.messages.windows.await(convId, plan.anchor, plan.before, plan.after) != null
            }
            val win = client.repo.localEntryWindow(owner, convId, entryReadSeq, entryUnread, floorNow())
            // 期间用户已经通过定位/搜索换了窗就别覆盖回来：只替换占位窗
            val stillPlaceholder = (windowNow as? ChatWindow.Tail)?.fromSeq == Long.MAX_VALUE
            if (win != null) {
                if (stillPlaceholder) setWindow(win)
            } else {
                // 有未读而读位点附近本地一条没有（服务端那一窗没取到）：保持空窗并如实说，不拿尾窗兜底（见 localEntryWindow）
                log.w("window_entry_unavailable", "convId" to convId, "fetched" to fetched)
                onToast(ChatWindows.NEED_NETWORK_NOTICE)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("window_entry_failed", "convId" to convId, "err" to e.javaClass.simpleName)
            onToast(ChatWindows.NEED_NETWORK_NOTICE)
        } finally {
            tail.entryDecided = true
        }
    }

    var olderBusy by remember(convId) { mutableStateOf(false) }
    tail.older = { oldestRendered, renderedCount ->
        if (!olderBusy) scope.launch {
            olderBusy = true
            try {
                loadOlderStep(client, convId, owner, windowNow, oldestRendered, renderedCount, connectedNow, setWindow)
            } finally {
                olderBusy = false
            }
        }
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
            val st = stateNow()
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

private val log = IMLog.tag("IM.Chat")

/** 进会话时等 WS 连上的上限；超时按离线处理（只开本地窗）。 */
private const val CONNECT_WAIT_MS = 3_000L

/**
 * 上滚一步（OFFLINE_BACKLOG_DESIGN §4.7）：**本段本地还有就展开 → 到可见起点就停（不发请求）→ 否则向服务端要一页**。
 *
 * 三步的顺序就是不变式：先本地（段内取，不跨缺口），再查下界闸，最后才问服务端——
 * 下界闸只放在「问服务端」这一步，不并进总闸：并进去会连本地已有的更早历史（退群再入群抬高下界）也展不出来（iOS 同款）。
 */
private suspend fun loadOlderStep(
    client: IMClient,
    convId: String,
    owner: String,
    window: ChatWindow,
    oldestRendered: Long,
    renderedCount: Int,
    connected: Boolean,
    setWindow: (ChatWindow) -> Unit,
) {
    val historyFloor = client.messages.historyFloors.get(convId)
    when (window) {
        is ChatWindow.Tail -> {
            // 窗口装满才继续加：没装满说明本段本地就这么多了
            if (renderedCount >= window.limit) {
                setWindow(window.copy(limit = window.limit + ChatWindows.TAIL_PAGE))
                return
            }
            if (!askServerOlder(client, convId, owner, oldestRendered, connected)) return
            val st = client.repo.tailState(owner, convId, ChatWindows.LATEST_FETCH, client.messages.historyFloors.get(convId))
            setWindow(window.copy(limit = window.limit + ChatWindows.TAIL_PAGE, fromSeq = st.segmentLo))
        }
        is ChatWindow.Anchored -> {
            val local = client.repo.extendWindowOlder(owner, convId, window, historyFloor = historyFloor)
            if (local != window) { setWindow(local); return }
            if (!askServerOlder(client, convId, owner, window.loSeq, connected)) return
            setWindow(client.repo.extendWindowOlder(owner, convId, window, historyFloor = client.messages.historyFloors.get(convId)))
        }
    }
}

/** 到可见起点就不问；在线才问；返回「是否真的取回了一页」。 */
private suspend fun askServerOlder(client: IMClient, convId: String, owner: String, oldest: Long, connected: Boolean): Boolean {
    val from = client.repo.tailState(owner, convId, ChatWindows.LATEST_FETCH, client.messages.historyFloors.get(convId)).visibleFrom
    if (!ChatTailPlan.hasMoreAbove(oldest, from) || !connected) return false
    return client.messages.windows.await(convId, oldest, ChatWindows.ANCHOR_PAGE, 0) != null
}
