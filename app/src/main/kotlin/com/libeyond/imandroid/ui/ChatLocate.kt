package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.sdk.IMClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「跳到某条 conv_seq」的**唯一出口**（引用块跳转 / 搜索命中共用）。
 *
 * 为什么要独立一层而不是让 `ChatScreen` 自己滚：本端的渲染窗口是「最近 N 条」
 * （`ChatHost` 的 `windowLimit`），目标多半**不在窗口里**，只滚列表必然落空。
 * 这一层先查本地库确认那条在不在、要把窗口撑到多大，撑完再让 `ChatScreen` 滚过去。
 *
 * 对端是 iOS 的 `-[IMChatViewController jumpToConvSeq:]` 与 im-web 的 `locateInChat`
 * ——**要一致的是「先保证目标进得了视野再滚」这条不变式**，手段各端不同：
 * 那两端是按锚点开窗（`window_req`），本端是撑窗口（见 [ChatSearch.MAX_LOCATE_WINDOW]
 * 的注释：锚点开窗是还欠着的一档）。
 */
@Stable
class ChatLocator internal constructor() {

    /** 当前要定位的 conv_seq（`0` = 没有）。`ChatScreen` 见到它出现在 rows 里就滚过去 + 高亮。 */
    var target by mutableStateOf(0L)
        internal set

    internal var request: (Long, ((String) -> Unit)?) -> Unit = { _, _ -> }

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

    /** `ChatScreen` 滚到了。 */
    fun consumed() {
        target = 0L
    }
}

/**
 * @param currentWindow 取**此刻**的渲染窗口条数。**必须是取值函数不是数值**：查库要花时间，
 *                      按组合时捕获的快照去比较，会把这期间用户上翻撑大的窗口又缩回去；
 *                      窗口一缩，`ChatScreen` 的翻页保位条件（rows 变多）再也不成立，
 *                      「滚到顶加载更早」就被在途标志永久卡死了。
 * @param onGrowWindow  请求把窗口撑到给定条数。「只增不减」这条不变式**下沉在它的实现里**
 *                      （`ChatHost` 取 max），不靠这里比一下就算数。
 * @param onToast       跳不了时如实说一句
 */
@Composable
fun rememberChatLocator(
    client: IMClient,
    convId: String,
    currentWindow: () -> Int,
    onGrowWindow: (Int) -> Unit,
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
                // 本地有没有这一条 + 要多大的窗口才盖得住它
                val need = client.repo.windowNeededFor(owner, convId, seq)
                when {
                    // 本地根本没有。**如实说，且要说对原因**：本地有缺口 = 还没同步下来；
                    // 本地齐全 = 它是真的没了（撤回 / 为所有人删除 / 仅为我删除都是物理删行）。
                    // 只在这条落空的路上多查一次库，不占正常路径。
                    need <= 0 -> refuse(
                        if (client.repo.isLocalComplete(owner, convId)) ChatSearch.GONE_NOTICE
                        else ChatSearch.NEED_NETWORK_NOTICE,
                    )
                    need > ChatSearch.MAX_LOCATE_WINDOW -> refuse(ChatSearch.TOO_EARLY_NOTICE)
                    else -> {
                        // 多给一页余量：刚好撑到目标那一条时余量为 0，
                        // 期间只要再落库一条消息，最新 N 条就已经不含目标（见 LOCATE_WINDOW_MARGIN）。
                        val want = need + ChatSearch.LOCATE_WINDOW_MARGIN
                        if (want > currentWindow()) onGrowWindow(want)
                        locator.target = seq
                        // 兜底：窗口撑完还是没滚过去，就认输并说一句。
                        // 没有它的话，这是本功能里唯一一个**不给任何反馈**的失败分支。
                        // 认领要看 generation 不能只看 seq：连点同一条时，上一次的超时会误伤这一次。
                        delay(ChatSearch.LOCATE_TIMEOUT_MS)
                        if (gen == locator.generation && locator.target == seq) {
                            locator.target = 0L
                            refuse(ChatSearch.LOCATE_FAILED_NOTICE)
                        }
                    }
                }
            }
        }
    }
    return locator
}
