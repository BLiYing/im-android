package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.windowAround
import com.libeyond.imandroid.data.isLocalComplete
import com.libeyond.imandroid.data.firstConvSeqAtOrAfter
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.data.ChatWindow
import com.libeyond.imandroid.data.ChatWindows
import com.libeyond.imandroid.sdk.IMClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「跳到某条 conv_seq」的**唯一出口**（引用块跳转 / 搜索命中共用）。
 *
 * 为什么要独立一层而不是让 `ChatScreen` 自己滚：目标多半**不在当前窗口里**，只滚列表必然落空。
 * 这一层负责「先保证目标进得了视野」——**按锚点换窗**（[ChatWindow.Anchored]），
 * 本地有就开本地窗，本地没有就发 `window_req` 让服务端给一窗（`MESSAGE_WINDOW_DESIGN` §4）。
 *
 * 对端是 iOS 的 `-[IMChatViewController jumpToConvSeq:]` 与 im-web 的 `locateInChat`，
 * 三端同一条不变式、同一条协议。2026-09-09 上午本端曾用「把最近 N 条的 N 撑大」凑合过，
 * 那必须带一个 5000 条的上限（13 万条整窗会把聊天页渲染成空白），上限之外只能说跳不过去；
 * 锚点窗没有这个问题——不论目标多早，取的都是它前后各一页。
 */
@Stable
class ChatLocator internal constructor() {

    /** 当前要定位的 conv_seq（`0` = 没有）。`ChatScreen` 见到它出现在 rows 里就滚过去 + 高亮。 */
    var target by mutableStateOf(0L)
        internal set

    internal var request: (Long, ((String) -> Unit)?) -> Unit = { _, _ -> }

    internal var requestEarliest: (((String) -> Unit)?) -> Unit = { _ -> }

    /**
     * 第几次定位请求。超时兜底靠它认领自己那一次——
     * 光比 `target == seq` 不够：连点同一条引用块时，上一次的超时会把这一次刚设好的目标清掉。
     */
    internal var generation = 0

    /**
     * @param onRefused 跳不了时由**调用方**决定怎么说。会话内搜索传它，把话写进搜索条上方那一行
     *   ——搜索态下键盘占着屏幕下半截，吐司恰好落在键盘背后，等于没提示（2026-09-09 真机撞见）。
     *   不传则走默认的吐司。
     */
    fun locate(seq: Long, onRefused: ((String) -> Unit)? = null) = request(seq, onRefused)

    /**
     * 跳到「会话最早」——**不是**无脑跳 conv_seq=1（[locate] 那条通用路不适用于这里）。
     * 见 [rememberChatLocator] 里 `requestEarliest` 的实现注释。
     */
    fun locateEarliest(onRefused: ((String) -> Unit)? = null) = requestEarliest(onRefused)

    /** `ChatScreen` 滚到了。 */
    fun consumed() {
        target = 0L
    }
}

/**
 * @param onOpenWindow 换到给定的锚点窗（`ChatHost` 持有窗口状态）。
 * @param onToast      跳不了时如实说一句（搜索那一路会把它接管到搜索条上方那一行）
 */
@Composable
fun rememberChatLocator(
    client: IMClient,
    convId: String,
    onOpenWindow: (ChatWindow.Anchored) -> Unit,
    onToast: (String) -> Unit,
): ChatLocator {
    val owner = client.uid.orEmpty()
    val scope = rememberCoroutineScope()
    val locator = remember(convId) { ChatLocator() }
    locator.request = { seq, onRefused ->
        val refuse: (String) -> Unit = onRefused ?: onToast
        if (seq > 0 && owner.isNotEmpty()) {
            val gen = ++locator.generation
            scope.launch {
                // ① 本地有这一条 → 直接开本地锚点窗
                var window = client.repo.windowAround(owner, convId, seq)
                if (window == null) {
                    // ② 本地没有。有缺口且在线 → 问服务端要一窗（window_req）；
                    //    本地齐全 → 它是真的没了（撤回 / 为所有人删除 / 仅为我删除都是物理删行）。
                    if (client.repo.isLocalComplete(owner, convId)) {
                        refuse(ChatWindows.GONE_NOTICE)
                        return@launch
                    }
                    val resp = client.messages.windows.await(
                        convId, anchor = seq,
                        before = ChatWindows.ANCHOR_HALF, after = ChatWindows.ANCHOR_HALF,
                    )
                    when {
                        resp == null -> { refuse(ChatWindows.NEED_NETWORK_NOTICE); return@launch }
                        // anchor_found=false 才是**真的**「不在了」——这正是这条协议要区分的两件事
                        !resp.anchorFound -> { refuse(ChatWindows.GONE_NOTICE); return@launch }
                    }
                    // 服务端那一窗已经落库（MessageService 收帧时落的），再算一次本地窗
                    window = client.repo.windowAround(owner, convId, seq)
                    if (window == null) { refuse(ChatWindows.LOCATE_FAILED_NOTICE); return@launch }
                }
                if (gen != locator.generation) return@launch // 期间又点了别的，这一次作废
                onOpenWindow(window)
                locator.target = seq
                // 兜底：换完窗还是没滚过去，就认输并说一句。
                // 没有它的话，这是本功能里唯一一个**不给任何反馈**的失败分支。
                // 认领要看 generation 不能只看 seq：连点同一条时，上一次的超时会误伤这一次。
                delay(ChatWindows.LOCATE_TIMEOUT_MS)
                if (gen == locator.generation && locator.target == seq) {
                    locator.target = 0L
                    refuse(ChatWindows.LOCATE_FAILED_NOTICE)
                }
            }
        }
    }
    // 「跳到最早」（对齐 iOS `IMChatViewController+Search.m` 的 `IMEarliestJumpNeedsServer` +
    // `requestServerWindowAnchor:isJump:earliest:`，同一根因同一份修法）。
    //
    // **不能复用上面 [locator.request]（走 `anchor_found=false` ⇒ GONE_NOTICE 那条路）**：
    // conv_seq=1 常常**不是**一条我能看见的消息——它可能是 msg_op 事件行、墓碑，或者（大群里
    // 更常见）压根是"入群前"的历史，对这个账号从来就不可见、永远拿不到。这种情况下服务端
    // `window_resp` 回的 `anchor_found` 必然是 false，但那一窗**仍然带回了"我能看见的最早一段"**
    // （服务端按 `visibleFloor` 天然截断）——`anchor_found=false` 在这里不代表白问，
    // 反而是**预期状态**。用通用 [locator.request] 的语义会把这判成"原消息不在了"直接拒答，
    // 这正是"点最早没反应"这个 bug 的根因。
    locator.requestEarliest = { onRefused ->
        val refuse: (String) -> Unit = onRefused ?: onToast
        if (owner.isNotEmpty()) {
            val gen = ++locator.generation
            scope.launch {
                // 本地已经握着的最早一条（`fromMs=0` ⇒ 全会话最早）。
                val localEarliest = client.repo.firstConvSeqAtOrAfter(owner, convId, 0L)
                if (localEarliest == null || localEarliest <= 0L) {
                    refuse(ChatWindows.NO_MESSAGES_NOTICE)
                    return@launch
                }
                if (localEarliest <= 1L) {
                    // 本地已经拿到 1 号，就是真的握着会话开头——直接走通用路开窗即可。
                    locator.request(localEarliest, onRefused)
                    return@launch
                }
                // 1 号本地没有：问服务端要一窗（锚点仍写 1，只是**不看** anchor_found）。
                val resp = client.messages.windows.await(
                    convId, anchor = 1L,
                    before = ChatWindows.ANCHOR_HALF, after = ChatWindows.ANCHOR_HALF,
                )
                if (gen != locator.generation) return@launch
                if (resp == null) {
                    // 离线/超时：退到本地已经握着的那一条，但必须说清楚这不是会话开头。
                    val window = client.repo.windowAround(owner, convId, localEarliest)
                    if (window != null) {
                        onOpenWindow(window)
                        locator.target = localEarliest
                        refuse(ChatWindows.OFFLINE_JUMPED_EARLIEST_NOTICE)
                    } else {
                        refuse(ChatWindows.NEED_NETWORK_NOTICE)
                    }
                    return@launch
                }
                // 落库后再查一次本地最早——不管 anchor_found，这一窗已经把能看见的最早一段带回来了。
                val target = client.repo.firstConvSeqAtOrAfter(owner, convId, 0L) ?: localEarliest
                val window = client.repo.windowAround(owner, convId, target)
                if (window == null) {
                    refuse(ChatWindows.LOCATE_FAILED_NOTICE)
                    return@launch
                }
                onOpenWindow(window)
                locator.target = target
                delay(ChatWindows.LOCATE_TIMEOUT_MS)
                if (gen == locator.generation && locator.target == target) {
                    locator.target = 0L
                    refuse(ChatWindows.LOCATE_FAILED_NOTICE)
                }
            }
        }
    }
    return locator
}

/**
 * 从覆盖页（会话详情 / 群资料）回到聊天页时，**顺带要做的那一件事**。
 *
 * 为什么需要它：那两页**盖在**聊天页之上，与 `ChatHost` 是 MainScreen 里的**兄弟**而不是父子，
 * 拿不到聊天页里的定位器/搜索态，做不了"在聊天页里定位/开搜索"这类事——只能先关掉自己，
 * 再由导航层把这件待办交给聊天页。同 im-web `useChatSearch.armInChatSearch`。
 * （2026-09-10 前聊天页在详情页打开期间是整个移出组合的；现在为了返回保位它一直活着，
 * 待办由 `ChatHost` 里的 `LaunchedEffect(arm)` 在详情页关掉的同一帧接住。）
 *
 * 两件待办合成一个类型而不是两个布尔/长整型参数：**它们互斥**（一次只可能带一件回去），
 * 分开放迟早出现"既要开搜索又要定位"的状态组合，而那个组合没有意义。
 */
data class ChatArm(
    val openSearch: Boolean = false,
    val locateSeq: Long = 0L,
) {
    val isEmpty: Boolean get() = !openSearch && locateSeq <= 0L
}
