package com.libeyond.imandroid.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MsgOpSignal
import com.libeyond.imandroid.data.PinnedBanner
import com.libeyond.imandroid.data.sendMsgOp
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.screens.BannerContent
import com.libeyond.imandroid.ui.screens.BannerIcons
import com.libeyond.imandroid.ui.screens.ChatBannerStack
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聊天页顶部横幅（置顶 / 公告 / 入群申请）的状态与动作，对齐 iOS `IMChatViewController+PinnedBanner`。
 * 从 `ChatHost` 拆出（那份文件贴着 600 行硬闸）；宿主只需 [rememberChatBanners] + 一个 `banners` 槽。
 *
 * 置顶集合**不落本地库**：进会话拉一次，之后靠 `msg_op` 信号 / 重连 / 删除消息对齐（见 [rememberChatBanners]）；
 * 拉失败就保留旧集合、不打断聊天（尽力而为，iOS 同）。
 */
class ChatBannersState {
    /** 置顶拉取的代次：多个触发源会并发拉，**后发的请求才有资格落地**，先发后到的旧响应丢弃。 */
    var reloadGen = 0
    var pinned by mutableStateOf<List<PinnedMessage>>(emptyList())
    var index by mutableStateOf(0)
    var dismissedPin by mutableStateOf<String?>(null)
    var dismissedAnn by mutableStateOf<String?>(null)
    var dismissedApproval by mutableStateOf<Int?>(null)
    var listOpen by mutableStateOf(false)
    var annOpen by mutableStateOf(false)
}

/**
 * @param jumpTo 跳到某条消息（`ChatLocate.locate`，目标不在本地窗口时它会开窗）
 * @param onOpenApproval 点入群申请横幅
 */
@Composable
fun rememberChatBanners(
    client: IMClient,
    conv: ConversationEntity,
    gs: ChatGroupState,
    connected: Boolean,
    covered: Boolean,
    jumpTo: (Long) -> Unit,
    onToast: (String) -> Unit,
    onOpenApproval: () -> Unit,
): ChatBannersHolder {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = client.uid.orEmpty()
    val st = remember(conv.convId) { ChatBannersState() }
    val prefs = remember { context.getSharedPreferences("chat_banners", Context.MODE_PRIVATE) }

    // 收起记忆：按内容签名，内容一变自动重新出现（决策 20）。首次读 SharedPreferences 要加载文件，放 IO 线程
    LaunchedEffect(conv.convId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pin = prefs.getString(PinnedBanner.dismissKey("pin", uid, conv.convId), null)
            val ann = prefs.getString(PinnedBanner.dismissKey("ann", uid, conv.convId), null)
            val approval = prefs.getInt(PinnedBanner.dismissKey("approval", uid, conv.convId), -1).takeIf { it >= 0 }
            st.dismissedPin = pin; st.dismissedAnn = ann; st.dismissedApproval = approval
        }
    }

    suspend fun reload() {
        val my = ++st.reloadGen
        runCatchingCancellable { client.conversationsApi.pinned(conv.convId) }.onSuccess {
            if (my != st.reloadGen) return@onSuccess // 有更晚发出的请求，这份是旧的
            st.pinned = it
            st.index = PinnedBanner.clampIndex(st.index, it.size)
        }
    }
    // 进会话拉一次；重连后（可能错过了离线期间的置顶帧）再拉一次
    LaunchedEffect(conv.convId, connected) { if (connected) reload() }

    // 消息被操作：置顶/取消置顶 → 立刻重拉；撤回/删除命中某条置顶 → 先本地摘掉（弱网重拉失败也不留指向墓碑的横幅），
    // 再重拉对齐；删除类**无条件**走 300ms 尾沿去抖（批量删除会连来几百帧，必须并成一次请求）
    LaunchedEffect(conv.convId) {
        var gen = 0
        client.msgOps.collect { s: MsgOpSignal ->
            if (s.convId != conv.convId) return@collect
            val hit = st.pinned.any { it.convSeq in s.seqs }
            when (s.op) {
                MsgOp.PIN -> launch { reload() }
                MsgOp.EDIT -> if (hit) launch { reload() }
                MsgOp.RECALL, MsgOp.DELETE, MsgOpSignal.HIDE -> {
                    if (hit) st.pinned = st.pinned.filterNot { it.convSeq in s.seqs }
                    val my = ++gen
                    launch { delay(RELOAD_DEBOUNCE_MS); if (my == gen) reload() }
                }
            }
        }
    }

    // 进群自动弹一次公告：**页面真的可见时**才记「已看版本」（宿主把群资料页、菜单、选人、查看器、弹窗等都折进 covered），
    // 没看到就不记（下次可见再弹）；我自己发布的只记版本不弹
    val info = gs.info
    LaunchedEffect(info?.announcementAt, info?.announcement, covered) {
        if (!conv.isGroup || covered || info == null) return@LaunchedEffect
        val key = "im_ann_seen_${uid}_${conv.convId}"
        val seen = prefs.getLong(key, 0L)
        if (info.announcementAt > seen) prefs.edit().putLong(key, info.announcementAt).apply()
        if (PinnedBanner.shouldAutoPopAnnouncement(info, uid, seen)) st.annOpen = true
    }

    return remember(st, conv.convId, uid) { ChatBannersHolder(st, prefs, uid, conv, scope, client) }.also {
        it.gs = gs; it.jumpTo = jumpTo; it.onToast = onToast; it.onOpenApproval = onOpenApproval
        it.reload = { scope.launch { reload() } }
    }
}

private const val RELOAD_DEBOUNCE_MS = 300L

/** 动作与派生内容。字段由 [rememberChatBanners] 每次重组刷新（回调拿到的永远是最新的）。 */
class ChatBannersHolder(
    val st: ChatBannersState,
    private val prefs: android.content.SharedPreferences,
    private val uid: String,
    private val conv: ConversationEntity,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val client: IMClient,
) {
    lateinit var gs: ChatGroupState
    var jumpTo: (Long) -> Unit = {}
    var onToast: (String) -> Unit = {}
    var onOpenApproval: () -> Unit = {}
    var reload: () -> Unit = {}

    private val shown: PinnedMessage? get() = st.pinned.getOrNull(PinnedBanner.clampIndex(st.index, st.pinned.size))

    /** 跳到一条置顶：目标已撤回（本地行）就提示并重拉，不跳——否则落在「撤回了一条消息」上闪一下，用户看不出原消息没了。 */
    fun jump(seq: Long) {
        scope.launch {
            val row = client.repo.messages.byConvSeq(uid, conv.convId, seq)
            if (PinnedBanner.targetRecalled(row)) {
                onToast(Str.s(R.string.conv_error_original_recalled))
                reload()
            } else jumpTo(seq)
        }
    }

    fun tapPinned() {
        val cur = shown ?: return
        jump(cur.convSeq)
        st.index = PinnedBanner.nextIndex(PinnedBanner.clampIndex(st.index, st.pinned.size), st.pinned.size) // 连点逐条轮播
    }

    fun unpinCurrent() {
        val cur = shown ?: return
        client.messages.sendMsgOp(conv.convId, MsgOp.PIN, cur.convSeq, pinned = false)
    }

    private fun dismiss(kind: String, apply: () -> Unit, write: (android.content.SharedPreferences.Editor, String) -> Unit) {
        apply()
        prefs.edit().also { write(it, PinnedBanner.dismissKey(kind, uid, conv.convId)) }.apply()
    }
    fun closePinned() {
        val sig = PinnedBanner.pinSignature(st.pinned)
        dismiss("pin", { st.dismissedPin = sig }) { e, k -> e.putString(k, sig) }
    }
    fun closeAnnouncement() {
        val sig = PinnedBanner.announcementSignature(gs.info?.announcement)
        dismiss("ann", { st.dismissedAnn = sig }) { e, k -> e.putString(k, sig) }
    }
    fun closeApproval() {
        val n = PinnedBanner.approvalCount(gs.info)
        dismiss("approval", { st.dismissedApproval = n }) { e, k -> e.putInt(k, n) }
    }

    /** 当前该显的三张横幅（null = 不显）。 */
    @Composable
    fun Stack() {
        val info = gs.info
        val c = IMTheme.colors
        val ic = IMTheme.settingsIcons // 浅/深色各一套 systemBlue/Orange，不要散落 Color(0xFF…)
        val closePinnedLabel = stringResource(R.string.chat_banner_collapse_pinned)
        val closeAnnLabel = stringResource(R.string.chat_banner_collapse_announcement)

        val approval = PinnedBanner.approvalCount(info)
        // 待审清零后收起记忆也清掉：否则之后新来的同样件数的申请被旧记忆吃掉、横幅永远不再出现
        LaunchedEffect(approval) {
            if (info != null && approval == 0 && st.dismissedApproval != null) {
                st.dismissedApproval = null
                prefs.edit().remove(PinnedBanner.dismissKey("approval", uid, conv.convId)).apply()
            }
        }
        val join = if (conv.isGroup && approval > 0 && approval != st.dismissedApproval) {
            BannerContent(
                BannerIcons.Join, ic.blue, stringResource(R.string.chat_banner_join_request),
                androidx.compose.ui.res.pluralStringResource(R.plurals.chat_banner_join_pending, approval, approval),
                closeLabel = stringResource(R.string.chat_banner_collapse_join_request),
            )
        } else null

        val annText = if (conv.isGroup) PinnedBanner.announcementPreview(info) else ""
        val ann = if (annText.isNotEmpty() && PinnedBanner.announcementSignature(info?.announcement) != st.dismissedAnn) {
            BannerContent(
                BannerIcons.Announcement, ic.orange, stringResource(R.string.group_text_announcement),
                annText, closeLabel = closeAnnLabel,
            )
        } else null

        val items = st.pinned
        val cur = shown
        val pinned = if (cur != null && !PinnedBanner.dismissed(PinnedBanner.pinSignature(items), st.dismissedPin)) {
            val label = PinnedBanner.senderLabel(cur, conv.isGroup)
            val idx = PinnedBanner.clampIndex(st.index, items.size)
            val kicker = buildString {
                append(Str.s(R.string.chat_banner_pinned))
                if (items.size > 1) append(" ${idx + 1}/${items.size}")
                if (label.isNotEmpty()) append(" · $label")
            }
            BannerContent(
                BannerIcons.Pinned, c.accent, kicker, PinnedBanner.preview(cur),
                multiple = items.size > 1, showList = items.size > 1, closeLabel = closePinnedLabel,
            )
        } else null

        ChatBannerStack(
            join = join, announcement = ann, pinned = pinned,
            onJoinTap = onOpenApproval, onJoinClose = ::closeApproval,
            onAnnouncementTap = { st.annOpen = true }, onAnnouncementClose = ::closeAnnouncement,
            onPinnedTap = ::tapPinned, onPinnedList = { st.listOpen = true }, onPinnedClose = ::closePinned,
        )
    }

    /** 置顶列表 / 公告全文两个弹层。 */
    @Composable
    fun Dialogs(canPin: Boolean) {
        if (st.listOpen) PinnedListDialog(
            items = st.pinned, isGroup = conv.isGroup, canUnpin = canPin && shown != null,
            onJump = { st.listOpen = false; jump(it) },
            onUnpinCurrent = { st.listOpen = false; unpinCurrent() },
            onDismiss = { st.listOpen = false },
        )
        val info = gs.info
        if (st.annOpen && info != null && info.announcement.isNotBlank()) {
            GroupTextViewDialog(
                title = stringResource(R.string.group_text_announcement),
                content = info.announcement,
                subtitle = announceSubtitle(info.announcementAt),
                onDismiss = { st.annOpen = false },
            )
        }
    }
}

private fun announceSubtitle(at: Long): String {
    if (at <= 0) return ""
    val loc = java.util.Locale.getDefault()
    val f = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "MMMd HHmm"), loc)
    return Str.s(R.string.group_announcement_published_at, f.format(java.util.Date(at)))
}

/**
 * 全部置顶消息：每行「发送者：预览」（单聊不带发送者），点行跳转；有权限者多一行
 * 「取消置顶当前这条」——**取消的是横幅当前显示的那条，不是点的那一行**（对齐 iOS `bannerStackDidTapPinnedList:`）。
 */
@Composable
private fun PinnedListDialog(
    items: List<PinnedMessage>, isGroup: Boolean, canUnpin: Boolean,
    onJump: (Long) -> Unit, onUnpinCurrent: () -> Unit, onDismiss: () -> Unit,
) {
    val c = IMTheme.colors
    // 别的管理员把最后一条也取消置顶了：弹层别留一个空壳
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onDismiss() }
    if (items.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_banner_pinned)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    val sender = PinnedBanner.senderLabel(item, isGroup)
                    val text = if (sender.isNotEmpty()) "$sender：${PinnedBanner.preview(item)}" else PinnedBanner.preview(item)
                    Text(
                        text, color = c.textPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onJump(item.convSeq) }.padding(vertical = 10.dp),
                    )
                }
                if (canUnpin) {
                    Text(
                        stringResource(R.string.chat_banner_unpin_current), color = c.danger,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onUnpinCurrent).padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
