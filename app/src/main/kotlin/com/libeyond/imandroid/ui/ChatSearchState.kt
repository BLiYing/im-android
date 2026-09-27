package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.searchMessages
import com.libeyond.imandroid.data.isLocalComplete
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.data.QuerySource
import com.libeyond.imandroid.data.SearchHit
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.delay

/** 输入过程中不要每敲一个字打一次库/服务端（同 im-web 的 250ms）。 */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * 会话内搜索的状态与逻辑（SEARCH_DESIGN §4）。
 *
 * 对端是 im-web 的 `useChatSearch`（`src/useChatSearch.ts`）与 iOS 的
 * `IMChatViewController+Search.m` + `IMChatSearchState`。**要一致的是三条不变式**，
 * 不是代码形状（`../IMServer/docs/SYMMETRY.md`）：
 *
 * 1. **搜的是整个会话，不是渲染窗口**——本端走 `repo.searchMessages`（查库），
 *    绝不在 `ChatHost` 那个 `windowLimit` 窗里过滤。
 * 2. **本地有缺口时不能假装完整**——[ChatSearch.pickSource] 三态分流，离线降级要说出来。
 * 3. **命中集被单页上限截断时计数补 `+`**，不悄悄显示成"总共就这些"。
 *
 * 单独成文件而不是塞进 `ChatHost`：后者已经 590+ 行，本仓 600 行是硬闸（CODING_STYLE §7）。
 */
@Stable
class ChatSearchController internal constructor() {

    /** 搜索态开着没有（开着时聊天页顶栏换成搜索框、底部换成命中导航条）。 */
    var open by mutableStateOf(false)
        internal set

    var query by mutableStateOf("")
        internal set

    /**
     * 「来自」筛选的发件人 uid；`null` = 没在筛选。与关键词是**与**的关系、互不清空
     * （对齐 iOS `searchState.searchFromUID`：选/清都会触发一次完整的命中集重算，
     * 但不影响另一边的输入内容）。
     */
    var fromUid by mutableStateOf<String?>(null)
        internal set

    /** 筛选发件人的显示名快照——只为了在胶囊上画"来自: X"，不参与查询。 */
    var fromName by mutableStateOf("")
        internal set

    /** 「来自」候选面板开着没有（点 👤 打开，选中或清除后收起）。 */
    var fromPickerOpen by mutableStateOf(false)
        internal set

    /** 命中集，**按 conv_seq 升序**（0 = 最早），与 im-web 同一朝向。 */
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        internal set

    var hitIdx by mutableStateOf(0)
        internal set

    /** 命中集被单页上限截断（真实命中更多）。 */
    var truncated by mutableStateOf(false)
        internal set

    /** 需要如实告诉用户的一句话（离线降级 / 搜索失败）。空串 = 没有。 */
    var notice by mutableStateOf("")
        internal set

    /**
     * 跳转出口。第二个参数是**拒绝时的说法往哪送**——搜索态下键盘占着下半屏，
     * 吐司会落在键盘背后（真机撞见过），所以这一路的拒绝原因要写进搜索条上方那一行。
     */
    internal var onLocate: (Long, (String) -> Unit) -> Unit = { _, _ -> }

    /** 上次「默认跳最新命中」用过的签名（`会话|词`）。同 im-web 的 `searchSigRef`。 */
    internal var jumpedSig: String = ""

    /**
     * 换一批命中集。
     *
     * **尽量保住用户当前正在看的那一条**：数据源会因为断线重连、同步追平而切换
     *（本地 ↔ 服务端），两边的命中集长度不同，光按下标夹一下会让选中项莫名其妙地跳走。
     * 按 `conv_seq` 找回来找不到再夹。
     */
    internal fun applyHits(hits: List<SearchHit>, truncated: Boolean, notice: String) {
        val keep = this.hits.getOrNull(hitIdx)?.convSeq
        this.hits = hits
        this.truncated = truncated
        this.notice = notice
        val found = if (keep == null) -1 else hits.indexOfFirst { it.convSeq == keep }
        hitIdx = if (found >= 0) found else ChatSearch.clampHitIndex(hitIdx, hits.size)
    }

    /** 有没有"在搜"这件事——关键词或「来自」筛选任一非空都算，同 iOS `hasQuery` 的判据。 */
    val hasQuery: Boolean get() = needle.isNotEmpty() || fromUid != null
    val navLabel: String get() = ChatSearch.hitLabel(hitIdx, hits.size, truncated, hasQuery = hasQuery)
    val canPrev: Boolean get() = hits.isNotEmpty() && hitIdx > 0
    val canNext: Boolean get() = hits.isNotEmpty() && hitIdx < hits.size - 1

    /** 命中词高亮用（已 trim；空串 = 不高亮）。 */
    val needle: String get() = query.trim()

    fun begin() {
        open = true
        query = ""
        reset()
    }

    fun close() {
        open = false
        query = ""
        reset()
    }

    private fun reset() {
        hits = emptyList()
        hitIdx = 0
        truncated = false
        notice = ""
        jumpedSig = ""
        fromUid = null
        fromName = ""
        fromPickerOpen = false
    }

    fun setQuery(v: String) {
        query = v
    }

    fun openFromPicker() {
        fromPickerOpen = true
    }

    fun closeFromPicker() {
        fromPickerOpen = false
    }

    /**
     * 选定一个发件人筛选。**触发完整的命中集重算**（与改关键词同一条状态机，
     * 见 [rememberChatSearch] 主取数 effect 的 key 列表里带了 `ctl.fromUid`）——不是另开一条逻辑。
     */
    fun setFrom(uid: String, name: String) {
        fromUid = uid
        fromName = name
        fromPickerOpen = false
    }

    fun clearFrom() {
        fromUid = null
        fromName = ""
    }

    /** 定位被拒时把原因写在搜索条上方那一行（吐司在搜索态下会落在键盘背后）。 */
    fun setNotice(text: String) {
        notice = text
    }

    /** ▲ 更旧 / ▼ 更新。命中可能不在渲染窗口里，所以一律走 [onLocate] 而不是只滚列表。 */
    fun goto(idx: Int) {
        if (hits.isEmpty()) return
        val i = ChatSearch.clampHitIndex(idx, hits.size)
        hitIdx = i
        notice = "" // 先清掉上一次的拒绝提示，跳成了就不该还挂着
        onLocate(hits[i].convSeq, ::setNotice)
    }

}

/**
 * 建一个会话内搜索控制器并接上数据源。
 *
 * @param online   当前是否在线（有缺口时决定问服务端还是给降级结果）
 * @param onLocate 唯一的跳转出口——命中多半**不在渲染窗口内**，由 `ChatHost` 负责撑窗口再滚；
 *                 第二个参数是"跳不了时把话说到哪去"，见 [ChatSearchController.onLocate]。
 */
@Composable
fun rememberChatSearch(
    client: IMClient,
    convId: String,
    online: Boolean,
    onLocate: (Long, (String) -> Unit) -> Unit,
): ChatSearchController {
    val owner = client.uid.orEmpty()
    val ctl = remember(convId) { ChatSearchController() }
    ctl.onLocate = onLocate

    // 本地这个会话齐不齐。**读库取当下的值**，不用点进来那一刻的快照——
    // 同步正跑着时两者差得很远（见 MessageRepository.conversation 的注释）。
    var complete by remember(convId) { mutableStateOf(true) }
    LaunchedEffect(convId, owner, ctl.open) {
        if (owner.isEmpty() || !ctl.open) return@LaunchedEffect
        complete = client.repo.isLocalComplete(owner, convId)
    }

    val source = ChatSearch.pickSource(complete, online)
    val needle = ctl.needle
    val fromUid = ctl.fromUid.orEmpty()

    // ===== ① 取命中集 =====
    // key 里带 source 与 fromUid：断线重连/同步追平换数据源、选或清「来自」都要重取。
    // **但这个 effect 只管数据，不碰 hitIdx、不主动跳** —— 见下面 ② 的注释。
    LaunchedEffect(ctl.open, needle, fromUid, convId, source, owner) {
        if (!ctl.open || (needle.isEmpty() && fromUid.isEmpty()) || owner.isEmpty()) {
            ctl.applyHits(emptyList(), truncated = false, notice = "")
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        val log = IMLog.tag("IM.Search")
        when (source) {
            QuerySource.Local, QuerySource.LocalDegraded -> {
                val page = client.repo.searchMessages(owner, convId, needle, fromUid)
                ctl.applyHits(
                    // DAO 按显示序倒序回（新在前），命中集统一用升序
                    hits = page.rows.map { SearchHit(it.convSeq, it.timestamp) }.reversed(),
                    // 截断与否由查询层给（过滤后的长度反推不出来，见 LocalSearchPage）
                    truncated = page.truncated,
                    notice = if (source == QuerySource.LocalDegraded) ChatSearch.DEGRADED_SEARCH_NOTICE else "",
                )
            }
            QuerySource.Server -> {
                runCatchingCancellable {
                    client.conversationsApi.searchMessages(
                        convId = convId, q = needle, from = fromUid, limit = ChatSearch.SERVER_PAGE_LIMIT,
                    )
                }
                    .onSuccess { page ->
                        ctl.applyHits(
                            hits = page.items.map { SearchHit(it.convSeq, it.timestamp) }.reversed(),
                            truncated = page.hasMore,
                            notice = "",
                        )
                    }
                    .onFailure { e ->
                        // 失败**不静默**：本地有缺口才走的这条路，回空集会被当成"真的没有"
                        ctl.applyHits(emptyList(), truncated = false, notice = Str.s(R.string.group_picker_search_failed))
                        log.w("conv_search_failed", "convId" to convId, "err" to e.javaClass.simpleName)
                    }
            }
        }
        log.d("conv_search_done", "convId" to convId, "src" to source.name, "hits" to ctl.hits.size)
    }

    // ===== ② 默认跳「最新一条命中」=====
    // **刻意与①分开**，且 key 里**没有 source**。合成一个 effect 的话，一次断线重连
    //（Connected→Connecting→Connected）就会把用户翻到第 5 条的阅读位置抢回最新那条。
    // im-web 的 useChatSearch 用 searchSigRef 做的是同一件事（签名 = 会话|词），这里照它的口径：
    // 只有"换会话 / 换词 / 换来自筛选"才重跳；命中集为空时不锁签名（结果可能还在异步路上）。
    LaunchedEffect(ctl.open, convId, needle, fromUid, ctl.hits) {
        if (!ctl.open) {
            ctl.jumpedSig = ""
            return@LaunchedEffect
        }
        val sig = "$convId|$needle|$fromUid"
        if (sig == ctl.jumpedSig || ctl.hits.isEmpty()) return@LaunchedEffect
        ctl.jumpedSig = sig
        ctl.hitIdx = ChatSearch.defaultHitIndex(ctl.hits.size)
        ctl.onLocate(ctl.hits[ctl.hitIdx].convSeq, ctl::setNotice)
    }

    return ctl
}
