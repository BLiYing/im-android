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
import com.libeyond.imandroid.data.clearedUpTo
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.linkUrlOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
                runCatchingCancellable { client.conversationsApi.media(convId, kind, if (reset) 0L else archive.cursor, clearedUpTo = client.repo.clearedUpTo(client.uid.orEmpty(), convId)) }
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
 *
 * @param active 只在「语音」页签上为 true，理由见 [rememberLocalScan]。
 */
@Composable
internal fun rememberVoiceWaveforms(client: IMClient, convId: String, active: Boolean): Map<Long, String> =
    rememberLocalScan(client, convId, active) { local ->
        local.asSequence()
            .filter { it.contentType == "voice" && !it.waveform.isNullOrBlank() }
            .associate { it.convSeq to it.waveform.orEmpty() }
    } ?: emptyMap()

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
 *
 * @param active 只在「语音」页签上为 true，理由见 [rememberLocalScan]。
 */
@Composable
internal fun rememberLocalSenderNames(client: IMClient, convId: String, active: Boolean): Map<String, String> =
    rememberLocalScan(client, convId, active) { local ->
        local.asSequence()
            .filter { !it.fromNickname.isNullOrBlank() }
            .associate { it.sender to it.fromNickname.orEmpty() }
    } ?: emptyMap()

/**
 * 「链接」页签的数据：**本地扫**。
 *
 * 服务端归档接口不覆盖这一格——链接不是独立的 `content_type`，没有可索引的列
 * （`internal/conversation/media.go` 开头写明）。iOS 同样是本地扫（`IMFirstURLInText`），
 * 所以这一格天然只覆盖已加载的那一段，界面上要说清楚（`LINK_TAB_NOTE`）。
 *
 * @param active 只在「链接」页签上为 true，理由见 [rememberLocalScan]。
 * @return `null` = 还没扫完（页签显「加载中…」，别先闪一下「暂无链接」）。
 */
@Composable
internal fun rememberLinkMessages(
    client: IMClient,
    convId: String,
    active: Boolean,
): List<Pair<MessageEntity, String>>? =
    rememberLocalScan(client, convId, active) { local ->
        local.mapNotNull { m -> linkUrlOf(m.contentType, m.content, m.convSeq)?.let { m to it } }
            .sortedByDescending { it.first.convSeq }
    }

/** 本会话里的合格名片（`contact` 且解析出 uid）。**常驻**订阅（页签是否出现取决于它），但只是按类型过滤、不跑正则。 */
@Composable
internal fun rememberContactMessages(client: IMClient, convId: String): List<Pair<MessageEntity, CardContent.Contact>>? =
    rememberLocalScan(client, convId, true) { local ->
        local.filter { it.contentType == ContentType.CONTACT && (it.recalledAt ?: 0L) == 0L }
            .mapNotNull { m -> CardContent.parseContact(m.content)?.let { m to it } }
            .sortedByDescending { it.first.convSeq }
    }

/**
 * 订阅本会话最近 [LINK_SCAN_LIMIT] 条本地消息并扫出一个结果；`null` = 未激活或还没扫完。
 *
 * ### 两条约束（2026-09-17「详情页很卡」一并收的）
 * 1. **只在对应页签上订阅**（[active]）。Room 的失效粒度是**整张表**：任何会话来一条消息、
 *    改一个已读回执，这里就重查 500 行。此前详情页一打开就挂着三份（链接 / 波形 / 发送者名），
 *    而用户在「媒体」页签上滚图时，它们一份都用不到。
 * 2. **扫描不在主线程**（`flowOn(Default)`）。此前是 `remember(local) { … }` 在组合里跑——
 *    链接那份要对 500 条正文跑 URL 正则，活跃账号里每来一条消息就在主线程上扫一遍，
 *    正好撞在滚动帧上。
 */
@Composable
private fun <T : Any> rememberLocalScan(
    client: IMClient,
    convId: String,
    active: Boolean,
    scan: (List<MessageEntity>) -> T,
): T? {
    val owner = client.uid.orEmpty()
    val flow = remember(convId, owner, active) {
        if (owner.isEmpty() || !active) {
            emptyFlow()
        } else {
            client.repo.observeMessages(owner, convId, LINK_SCAN_LIMIT)
                .map { scan(it) }
                .flowOn(Dispatchers.Default)
        }
    }
    val result by flow.collectAsState(initial = null)
    return result
}
