package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.linkUrlOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** 「链接」页签扫多少条本地消息。与聊天页的渲染窗口同量级，再多也只是扫更旧的已加载记录。 */
private const val LINK_SCAN_LIMIT = 500

/**
 * 会话归档（媒体 / 文件 / 语音页签）的一页数据。
 *
 * 单聊详情与群资料**共用这一份**：归档在两种会话里完全一样（同一个接口、同一套分页、
 * 同一个查看器）。此前是两份拷贝（`ChatDetailHost` 一份、`ConvMediaHost` 一份），
 * 分两份的代价不是重复代码，是**分页语义会分叉**——一边守了在途、一边没守，同一页被追加两次。
 */
@Stable
class ConvArchive internal constructor() {
    var items by mutableStateOf<List<ConvMediaItem>>(emptyList())
        internal set
    var loading by mutableStateOf(false)
        internal set
    var hasMore by mutableStateOf(false)
        internal set

    /**
     * 上一次取数失败了（网络不通 / 服务端出错）。
     * 页签本身靠"空列表 + 空态文案"兜着，但**查看器翻页要据此说一句**：
     * 停在已拉到的那几页没错，不说才是错（OFFLINE_BACKLOG_DESIGN §4.9）。
     */
    var failed by mutableStateOf(false)
        internal set

    internal var cursor = 0L
    internal var run: (Boolean) -> Unit = {}

    /** 滚到底续拉。没有下一页就什么都不做。 */
    fun loadMore() {
        if (hasMore) run(false)
    }

    /** 从头重拉（删除之后要用：这一页是服务端来的）。 */
    fun reload() = run(true)
}

/**
 * 取归档数据。换 [tab] 或换会话即从头拉；「链接 / 成员」这两格不走这个接口
 * （`DetailTabs.apiKind` 回 null），此时恒为空集。
 *
 * **过滤全在服务端**（撤回 / 为所有人删除 / 「仅为我删除」 / `history_visible` 下界都已滤掉），
 * 端上不再判一遍——判据分叉的话，归档里会出现聊天页看不到的消息。
 */
@Composable
internal fun rememberConvArchive(client: IMClient, convId: String, tab: DetailTab): ConvArchive {
    val scope = rememberCoroutineScope()
    val archive = remember(convId) { ConvArchive() }
    val kind = DetailTabs.apiKind(tab)

    archive.run = { reset ->
        if (kind != null && !archive.loading) { // 在途守卫：滚到底会连续触发，不守就把同一页追加两次
            archive.loading = true
            scope.launch {
                // 同 ChatMediaTimelineState：裸 runCatching 连 CancellationException 一起吞，
                // 页面被关掉时 onFailure 还会去写 failed 标志
                runCatchingCancellable { client.conversationsApi.media(convId, kind, if (reset) 0L else archive.cursor) }
                    .onSuccess { p ->
                        archive.items = if (reset) {
                            p.items
                        } else {
                            // 按 conv_seq 去重再追加——即便守卫被绕过也不会出现重复行
                            val seen = archive.items.mapTo(HashSet()) { it.convSeq }
                            archive.items + p.items.filter { it.convSeq !in seen }
                        }
                        archive.cursor = p.nextCursor
                        archive.hasMore = p.hasMore
                        archive.failed = false
                    }
                    .onFailure {
                        archive.failed = true
                        IMLog.tag("IM.Detail").w("conv_media_failed", "kind" to kind)
                    }
                archive.loading = false
            }
        }
    }

    LaunchedEffect(convId, tab) {
        archive.items = emptyList()
        archive.cursor = 0L
        archive.hasMore = false
        archive.failed = false
        archive.run(true)
    }
    return archive
}

/**
 * 「语音」页签的波形兜底：`conv_seq → waveform`，**从本地消息表来**。
 *
 * 服务端归档接口不回带 `waveform`（`internal/conversation/media.go` 没有这一列），
 * 于是归档里的语音行此前一律是等高条纹，**从那里转发出去的语音也丢波形**（老限制，
 * 记在 im-android `current_task.md` 的「已知坑」里）。本地库里有那条消息时就能补上——
 * 与「链接」页签同一条路子（都是服务端接口不覆盖、只能本地扫）。
 *
 * 只覆盖**本地已加载**的那一段，与链接页签同一语义；拿不到就退回等高条纹，
 * 那是协议允许的合法状态（见 `data/Waveform.kt`），不是错误。
 */
@Composable
internal fun rememberVoiceWaveforms(client: IMClient, convId: String): Map<Long, String> {
    val owner = client.uid.orEmpty()
    val local by remember(convId, owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeMessages(owner, convId, LINK_SCAN_LIMIT)
    }.collectAsState(initial = emptyList<MessageEntity>())
    return remember(local) {
        local.asSequence()
            .filter { it.contentType == "voice" && !it.waveform.isNullOrBlank() }
            .associate { it.convSeq to it.waveform.orEmpty() }
    }
}

/**
 * `uid → 公开显示名`，**从本地消息里的 `from_nickname` 快照取**。
 *
 * 归档语音行要显发送者（iOS 那行第一行就是它），常规来源是群成员表——但**超级群拿不到**：
 * `GET /groups/{id}` 对超级群只回我自己（协议里写明的降级）。于是大群的语音行整行没有名字，
 * 而大群恰恰最需要"这段语音是谁发的"（2026-09-17 真机实测撞见：1997 人的群里名字是空的）。
 * 本地消息带着发送时的昵称快照，拿它兜底即可；都拿不到才回空串（**不落内部 uid**，
 * 10 位随机数字对人没有意义）。
 *
 * 与波形兜底同一条路子：只覆盖本地已加载的那一段，拿不到不是错误。
 */
@Composable
internal fun rememberLocalSenderNames(client: IMClient, convId: String): Map<String, String> {
    val owner = client.uid.orEmpty()
    val local by remember(convId, owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeMessages(owner, convId, LINK_SCAN_LIMIT)
    }.collectAsState(initial = emptyList<MessageEntity>())
    return remember(local) {
        local.asSequence()
            .filter { !it.fromNickname.isNullOrBlank() }
            .associate { it.sender to it.fromNickname.orEmpty() }
    }
}

/**
 * 「链接」页签的数据：**本地扫**。
 *
 * 服务端归档接口不覆盖这一格——链接不是独立的 `content_type`，没有可索引的列
 * （`internal/conversation/media.go` 开头写明）。iOS 同样是本地扫（`IMFirstURLInText`），
 * 所以这一格天然只覆盖已加载的那一段，界面上要说清楚（`LINK_TAB_NOTE`）。
 */
@Composable
internal fun rememberLinkMessages(client: IMClient, convId: String): List<Pair<MessageEntity, String>> {
    val owner = client.uid.orEmpty()
    val local by remember(convId, owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeMessages(owner, convId, LINK_SCAN_LIMIT)
    }.collectAsState(initial = emptyList<MessageEntity>())
    return remember(local) {
        local.mapNotNull { m -> linkUrlOf(m.contentType, m.content, m.convSeq)?.let { m to it } }
            .sortedByDescending { it.first.convSeq }
    }
}
