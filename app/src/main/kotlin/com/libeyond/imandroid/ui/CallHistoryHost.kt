package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.imrtc.engine.IMCallHistoryPage
import com.imrtc.engine.IMCallHistoryRecord
import com.imrtc.engine.IMRTCError
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CallHistory
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.screens.CallHistoryScreen
import com.libeyond.imandroid.ui.screens.CallHistoryTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val log = IMLog.tag("IM.CallHistory")

/** 服务端 `1101 token_invalid`（`im-rtc-android` `IMErrorCode.TOKEN_INVALID` 的码，SDK 里是 internal 摸不到，另存一份同值）。 */
private const val RTC_TOKEN_INVALID = 1101

/**
 * 已加载的通话记录（游标分页累积）。与 `FavoriteList`（`FavoritesHost.kt`）同一形状：
 * 状态与在途请求的守卫都在这里；「全部 / 未接」两个 tab **共用同一份 [items]**，
 * 不是两套请求状态机（CALL_HISTORY_DESIGN.md §3.5）。
 */
@Stable
internal class CallHistoryList {
    var items by mutableStateOf<List<IMCallHistoryRecord>>(emptyList())
    var nextCursor by mutableStateOf<Long?>(null)
    var loading by mutableStateOf(true)
    var loadingMore by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var unauthorized by mutableStateOf(false)

    val hasMore: Boolean get() = nextCursor != null

    /** 每次 [reload] 加一：旧请求的应答回来时代数对不上就丢掉，不覆盖新状态（同 im-rtc Demo）。 */
    private var generation = 0

    fun reload(scope: CoroutineScope) {
        generation++
        val gen = generation
        loading = true
        failed = false
        unauthorized = false
        scope.launch {
            val (page, error) = fetch(null)
            if (gen != generation) return@launch
            loading = false
            if (page != null) {
                items = page.records
                nextCursor = page.nextCursor
            } else {
                failed = true
                unauthorized = error?.code == RTC_TOKEN_INVALID
                log.w("call_history_load_failed", "code" to (error?.code ?: -1))
            }
        }
    }

    fun loadMore(scope: CoroutineScope) {
        if (loadingMore || loading || !hasMore) return
        loadingMore = true
        val gen = generation
        scope.launch {
            val (page, error) = fetch(nextCursor)
            if (gen != generation) return@launch
            loadingMore = false
            if (page != null) {
                items = CallHistory.merge(items, page.records)
                nextCursor = page.nextCursor
            } else {
                // 翻页失败静默（同 FavoriteList.loadMore）：已加载部分保留，UX 稿 §04-B，下次滚到底再试
                log.w("call_history_load_more_failed", "code" to (error?.code ?: -1))
            }
        }
    }

    private suspend fun fetch(cursor: Long?): Pair<IMCallHistoryPage?, IMRTCError?> =
        suspendCancellableCoroutine { cont ->
            RtcCall.fetchCallHistory(CallHistory.PAGE_SIZE, cursor) { page, error ->
                if (cont.isActive) cont.resume(page to error)
            }
        }
}

/**
 * 「我 ▸ 最近通话」的接线层（CALL_HISTORY_DESIGN.md）：取数、身份解析、点击回拨 / 跳转群会话。
 *
 * 身份解析**复用会话表**（与 [FavoritesHost] 的 `sourceOf` 同一套路）：1v1 对方优先用本机会话行
 * （备注 > 昵称），本机没聊过的再补拉一次名片；群通话优先用本机群会话标题，查不到就回退
 * 「群语音/视频通话 · N人」（设计文档 §2）。**不复用 [com.libeyond.imandroid.rtc.RtcProfileSources]**：
 * 那是喂给通话中界面（Kit）的解析器，生命周期绑定一通正在进行的电话，这里要的是"历史列表批量查名"，
 * 直接读会话表更直接，两处各自的生命周期不该绑死。
 */
@Composable
internal fun CallHistoryHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val owner = client.uid.orEmpty()
    val list = remember { CallHistoryList() }
    var tab by remember { mutableStateOf(CallHistoryTab.All) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { list.reload(scope) }

    // 通话结束仍留在本页：重拉首页，在途翻页作废（CallHistoryList.generation 已处理）
    DisposableEffect(Unit) {
        RtcCall.onCallEnded = { list.reload(scope) }
        onDispose { RtcCall.onCallEnded = null }
    }

    // ——「未接」tab：过滤后不够一屏就自动续拉下一页，直到凑够或到底（设计文档 §3.5）——
    LaunchedEffect(tab, list.items, list.nextCursor, list.loading, list.loadingMore) {
        if (tab != CallHistoryTab.Missed || list.loading || list.loadingMore) return@LaunchedEffect
        val filtered = CallHistory.missedOnly(list.items, owner).size
        if (CallHistory.shouldAutoContinue(filtered, list.hasMore)) list.loadMore(scope)
    }

    // —— 1v1 对方身份：本机会话表优先，查不到的再逐个补拉名片（同 FavoritesHost.sourceOf 的两段式）——
    val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
    val peerConvByUid = remember(convs) { convs.filter { !it.isGroup && it.peerUid.isNotEmpty() }.associateBy { it.peerUid } }
    val groupConvById = remember(convs) { convs.filter { it.isGroup }.associateBy { it.convId } }
    val cards = remember { mutableStateMapOf<String, UserCard>() }
    val triedCards = remember { HashSet<String>() }

    LaunchedEffect(list.items, peerConvByUid) {
        list.items.asSequence()
            .filter { !it.isGroup }
            .map { CallHistory.peerUid(it, owner) }
            .filter { it.isNotBlank() && it !in peerConvByUid && it !in cards }
            .distinct()
            .filter { triedCards.add(it) }
            .forEach { uid ->
                scope.launch {
                    runCatchingCancellable { client.contacts.card(uid) }
                        .onSuccess { cards[uid] = it }
                        .onFailure { log.w("call_history_card_failed", "uid" to uid) }
                }
            }
    }

    fun peerName(uid: String): String {
        peerConvByUid[uid]?.let { return it.peerRemark.ifBlank { it.title }.ifBlank { DisplayName.UNNAMED } }
        cards[uid]?.let { return it.displayName.ifBlank { DisplayName.UNNAMED } }
        return DisplayName.UNNAMED
    }

    fun peerAvatar(uid: String): String = peerConvByUid[uid]?.avatarUrl ?: cards[uid]?.avatarUrl.orEmpty()

    fun nameOf(record: IMCallHistoryRecord): String =
        if (record.isGroup) groupConvById[record.chatGroupId]?.title.orEmpty() else peerName(CallHistory.peerUid(record, owner))

    fun avatarOf(record: IMCallHistoryRecord): String =
        if (record.isGroup) "" else peerAvatar(CallHistory.peerUid(record, owner))

    fun open(record: IMCallHistoryRecord) {
        if (record.isGroup) {
            val conv = groupConvById[record.chatGroupId] ?: client.groupConversationStubFor(record.chatGroupId, "", "")
            onOpenChat(conv)
            return
        }
        val peer = CallHistory.peerUid(record, owner)
        if (peer.isBlank()) return
        // 按原类型直接回拨，不弹确认——与聊天气泡回拨同一个入口（CALL_RECORD_DESIGN.md §5）
        RtcCall.placeSingle(peer, video = record.mediaType == "video")?.let { toast = it }
    }

    val shown = if (tab == CallHistoryTab.Missed) CallHistory.missedOnly(list.items, owner) else list.items
    val groups = CallHistory.groupByDay(shown, System.currentTimeMillis(), TimeFormat::dayLabel)
    val errorText = stringResource(if (list.unauthorized) R.string.rtc_error_not_started else R.string.common_load_failed)

    CallHistoryScreen(
        me = owner,
        tab = tab,
        onTabChange = { tab = it },
        groups = groups,
        noneAtAll = list.items.isEmpty(),
        loading = list.loading,
        failed = list.failed,
        errorText = errorText,
        hasMore = list.hasMore,
        loadedCount = shown.size,
        onLoadMore = { list.loadMore(scope) },
        onRetry = { list.reload(scope) },
        nameOf = ::nameOf,
        avatarOf = ::avatarOf,
        onOpen = ::open,
        onBack = onBack,
    )

    toast?.let { t -> IMToast(t) { toast = null } }
}
