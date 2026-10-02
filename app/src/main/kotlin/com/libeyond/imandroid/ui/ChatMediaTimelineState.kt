package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatTailPlan
import com.libeyond.imandroid.data.MediaTimeline
import com.libeyond.imandroid.data.isLocalComplete
import com.libeyond.imandroid.data.ViewerMedia
import com.libeyond.imandroid.data.clearedUpTo
import com.libeyond.imandroid.data.conversationMediaInSegment
import com.libeyond.imandroid.data.toViewerMedia
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.MediaKind
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch

/**
 * 聊天页查看器的**翻页序列**（会话媒体时间线，CLIENT_PARITY 任务3）。
 *
 * ### 为什么本地打底 + 服务端续拉
 * - **本地打底**：翻页要即时可用，且断网时也该能在已下载的那段里翻（iOS 干脆全量查本地库）。
 * - **只往更旧续拉**：服务端媒体接口是 `conv_seq < cursor` 的倒序分页
 *   （`internal/store/sqlite_message.go` 的 `convSearchDESC`），没有"往更新翻"的方向；
 *   而更新的那一端本地一定有（用户就是从聊天页点进来的）。
 * - **为什么非续拉不可**：本端的本地库天然有缺口（离线积压 C1~C6 整套未做），
 *   不续拉就会在缺口边缘静默停住——用户看到的是「这张图前面没有别的图了」，而那是假的
 *   （OFFLINE_BACKLOG_DESIGN §4.9 第 5 项）。
 *
 * 归档那侧（详情页 / 群资料的「媒体」页签）**不用这个**：它手上已经有一份服务端分页的列表
 * （[ConvArchive]），再拉一遍是白跑。两边共用的是 [MediaTimeline] 那组判据，不是这段状态。
 */
@Stable
internal class ChatMediaTimeline internal constructor() {
    /** 升序（旧→新）。 */
    var items by mutableStateOf<List<ViewerMedia>>(emptyList())
        internal set
    var loading by mutableStateOf(false)
        internal set

    /**
     * 本地段上沿之外服务端还有更新的（只在本地有缺口时才可能为真）。初值 = 打底取的是**缺口会话里的一段**；
     * 拉到空页 / 到头 / 失败后置假（失败也停：同 [hasMore]，离线时别每翻到头都重试）。
     */
    var hasMoreNewer by mutableStateOf(false)
        internal set
    internal var loadNewerImpl: () -> Unit = {}

    /** 快翻到**最新**那一端时调（本地段上沿，向服务端 `after=` 方向续拉）。 */
    fun loadNewerIfNeeded(index: Int) {
        if (MediaTimeline.wantsNewer(index, items.size, hasMoreNewer, loading)) loadNewerImpl()
    }

    /**
     * 服务端那边还有没有更早的。
     *
     * 初值 `true` = **还不知道**（本地那段之前很可能还有）。拉到空页或失败后置 false：
     * 失败也停是刻意的——离线时每翻到头都重试一次，只会让用户在没网的地方反复卡顿，
     * 而本地已有的那段照常能翻（"可以少，不可以错；少了要说出来"）。
     */
    var hasMore by mutableStateOf(true)
        internal set

    /**
     * 一句降级说明（null = 没有）。续拉失败时给出——OFFLINE_BACKLOG_DESIGN §4.9 的口径是
     * 「可以少，不可以错；**少了要说出来**」：停在已下载的那段本身没错，不说才是错。
     */
    var notice by mutableStateOf<String?>(null)
        internal set

    internal var loadOlderImpl: () -> Unit = {}

    /** 快翻到最旧一端时调。**在途与"还有没有"由 [MediaTimeline.wantsOlder] 判**。 */
    fun loadOlderIfNeeded(index: Int) {
        if (MediaTimeline.wantsOlder(index, items.size, hasMore, loading)) loadOlderImpl()
    }
}

@Composable
internal fun rememberChatMediaTimeline(client: IMClient, convId: String, viewingSeq: Long = 0L): ChatMediaTimeline {
    val scope = rememberCoroutineScope()
    val owner = client.uid.orEmpty()
    val timeline = remember(convId, owner) { ChatMediaTimeline() }
    val log = remember { IMLog.tag("IM.Viewer") }

    timeline.loadOlderImpl = {
        if (!timeline.loading) {
            timeline.loading = true
            scope.launch {
                // 游标是**当前最旧那条**：服务端取的是严格更旧的（`conv_seq < cursor`），不会把它重复给回来
                val cursor = timeline.items.firstOrNull()?.convSeq ?: 0L
                // 用 runCatchingCancellable：裸 runCatching 会把 CancellationException 也吞掉，
                // 关掉查看器时恰好撞上挂起点的话，onFailure 还会去写 hasMore/notice（取消传播被破坏）
                runCatchingCancellable { client.conversationsApi.media(convId, MediaKind.MEDIA, cursor = cursor, clearedUpTo = client.repo.clearedUpTo(owner, convId)) }
                    .onSuccess { page ->
                        val older = page.items.mapNotNull { it.toViewerMedia() }
                        val merged = MediaTimeline.prependOlder(timeline.items, older)
                        timeline.items = merged.items
                        // 服务端说没有了，或这一页一条都没并进来（重复页/全被过滤）→ 停，别空转
                        timeline.hasMore = page.hasMore && merged.added > 0
                        timeline.notice = null
                    }
                    .onFailure {
                        timeline.hasMore = false
                        timeline.notice = Str.s(R.string.media_viewer_offline_partial_notice)
                        log.w("viewer_media_page_failed", "conv" to convId)
                    }
                timeline.loading = false
            }
        }
    }

    // 进会话（或换会话）先从本地库打底。**不观察 Flow**：序列是"打开查看器那一刻的整条会话媒体"，
    // 看图期间新来的图不该把序列在脚下改长（iOS 同样是打开时查一次）。
    timeline.loadNewerImpl = {
        if (!timeline.loading) {
            timeline.loading = true
            scope.launch {
                val after = timeline.items.lastOrNull()?.convSeq ?: 0L
                runCatchingCancellable { client.conversationsApi.media(convId, MediaKind.MEDIA, after = after, clearedUpTo = client.repo.clearedUpTo(owner, convId)) }
                    .onSuccess { page ->
                        val merged = MediaTimeline.appendNewer(timeline.items, page.items.mapNotNull { it.toViewerMedia() })
                        timeline.items = merged.items
                        // 服务端说没有了，或这一页一条都没并进来（重复页 / 全被过滤）→ 停，别空转
                        timeline.hasMoreNewer = page.hasMore && merged.added > 0
                        timeline.notice = null
                    }
                    .onFailure {
                        timeline.hasMoreNewer = false
                        timeline.notice = Str.s(R.string.media_viewer_offline_partial_notice)
                        log.w("viewer_media_newer_failed", "conv" to convId)
                    }
                timeline.loading = false
            }
        }
    }

    // **本地有缺口时只取点中那条所在的本地段**（[conversationMediaInSegment]），不拼缺口另一侧的旧岛：
    // 否则「往更旧续拉」的游标取的是最旧岛的第一张，两段之间缺口里的图永远翻不到。
    LaunchedEffect(convId, owner, viewingSeq) {
        if (owner.isNotEmpty()) {
            val floor = client.messages.historyFloors.get(convId)
            val complete = client.repo.isLocalComplete(owner, convId, floor)
            val base = runCatchingCancellable {
                if (complete || viewingSeq <= 0L) client.repo.conversationMedia(owner, convId) to 0L
                else client.repo.conversationMediaInSegment(owner, convId, viewingSeq)
            }.getOrElse {
                log.w("viewer_media_local_failed", "conv" to convId)
                emptyList<com.libeyond.imandroid.data.db.MessageEntity>() to 0L
            }
            timeline.items = base.first.mapNotNull { it.toViewerMedia() }
            val visibleFrom = ChatTailPlan.visibleFrom(client.repo.clearedUpTo(owner, convId), floor)
            // 段下沿已经是可见起点（或 1）就没有更旧的可问；其余初值 true = 还不知道
            timeline.hasMore = base.second == 0L || base.second > maxOf(1L, visibleFrom)
            // 取的是缺口会话里的一段（segLo>0）：段上沿之外服务端可能还有更新的
            timeline.hasMoreNewer = !complete && viewingSeq > 0L && base.second > 0L
            timeline.notice = null
        }
    }
    return timeline
}
