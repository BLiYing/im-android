package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FavoriteDraft
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.IMTextPrompt
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 多选底栏的**转发 / 举报 / 收藏**三件事的接线（2026-09-10 用户报 #3；删除仍在 `BatchDeleteConfirm`）。
 *
 * 判据与编码全在 `data/SelectionActions.kt`（有测试钉着），这里只管"弹什么、调哪个接口、吐哪句话"。
 * 从 ChatHost 拆出来是因为那个文件贴着 600 行红线；套路同 `ChatSelectionState.kt`。
 * 对端 iOS `IMChatViewController+Selection.m`（`forwardSelected` / `reportSelected` / `favoriteSelected`）。
 */

/**
 * 一次转发任务（转发目标选择页开着时非 null）。
 *
 * @param record 非 null = **合并转发**：整批消息打成的那一张 `chat_record` 卡片 JSON（选目标前就打好）。
 * @param fromSelection 从多选发起：**选好目标发出去才退出多选**——在选择页点取消要回到原来勾好的状态，
 *   不能让用户重新勾一遍（iOS 同）。
 * @param expiredSkipped 逐条转发时已剔掉的失效媒体条数，回执里如实说。
 */
internal data class ForwardJob(
    val msgs: List<MessageEntity>,
    val record: String? = null,
    val fromSelection: Boolean = false,
    val expiredSkipped: Int = 0,
)

/** 待填理由的一次举报。 */
internal data class ReportDraft(val seqs: List<Long>, val title: String)

/** 合并转发 / 举报要用到的"人"的信息。随成员表、好友表异步到位，所以按最新值取。 */
internal data class SelectionPeople(
    val friends: Map<String, FriendEntry> = emptyMap(),
    /** 群成员**公开**显示名（群昵称 → 昵称 → @句柄），不含备注。 */
    val memberNames: Map<String, String> = emptyMap(),
    val memberAvatars: Map<String, String> = emptyMap(),
)

internal class SelectionActionsController(
    private val client: IMClient,
    private val conv: () -> ConversationEntity,
    private val sel: ChatSelectionController,
    private val scope: CoroutineScope,
    private val people: () -> SelectionPeople,
    private val toast: (String) -> Unit,
    private val openForward: (ForwardJob) -> Unit,
) {
    /** 「逐条转发 / 合并转发」选择单开着。 */
    var askingMode by mutableStateOf(false)

    /** 举报理由框（null = 没开）。 */
    var reporting by mutableStateOf<ReportDraft?>(null)

    private val myUid get() = client.uid.orEmpty()

    /** 失效 = 下载器登记过 404/410（本会话内）。与气泡上画「已失效」的是同一个判据。 */
    private fun expired(m: MessageEntity) = SelectionActions.isExpiredMedia(m) { url, isVideo ->
        client.downloads.stateOf(url, isVideo).phase == DownloadPhase.Expired
    }

    fun forward() {
        if (sel.picked().isNotEmpty()) askingMode = true
    }

    /**
     * 单条转发（长按菜单 / 查看器）的**唯一入口**：失效媒体直接拦下，不打开选择页。
     * 转发透传的是 URL 不是字节，本机有缓存也救不了对端（iOS `presentForwardPickerForMessage:` 一处拦三入口）。
     */
    fun forwardOne(m: MessageEntity) {
        if (expired(m)) toast(SelectionActions.expiredForwardText(m.contentType))
        else openForward(ForwardJob(listOf(m)))
    }

    /** 逐条转发：先滤转不出去的，再滤失效媒体；两类都要如实说少了几条。 */
    fun forwardEach() {
        askingMode = false
        val r = sel.forwardPick()
        if (r.msgs.isEmpty()) {
            r.notice?.let(toast)
            return
        }
        val live = r.msgs.filterNot(::expired)
        if (live.isEmpty()) {
            toast("所选均已失效，未转发")
            return
        }
        r.notice?.let(toast)
        openForward(ForwardJob(live, fromSelection = true, expiredSkipped = r.msgs.size - live.size))
    }

    /**
     * 合并转发：**选目标之前**就把卡片打好（同 iOS）。卡片里每一个名字都是公开名——
     * 单聊对方的名字不取 `conv.title`（那是备注优先的），见 [SelectionActions.peerPublicName]。
     */
    fun forwardMerged() {
        askingMode = false
        val c = conv()
        val picked = sel.picked()
        val kept = SelectionActions.mergeable(picked, ::expired)
        if (kept.isEmpty()) {
            toast(if (picked.any(::expired)) "所选均已失效，无法合并转发" else "所选消息都无法转发")
            return
        }
        val p = people()
        val peer = if (c.isGroup) null else p.friends[c.peerUid]
        val peerPublic = if (c.isGroup) "" else SelectionActions.peerPublicName(
            peer?.nickname, peer?.username, picked.firstOrNull { it.sender == c.peerUid }?.fromNickname,
        )
        val myName = client.myPublicName()
        val senders = SelectionActions.RecordSenders(
            myUid = myUid, myName = myName, isGroup = c.isGroup,
            peerUid = c.peerUid, peerPublic = peerPublic, peerAvatar = if (c.isGroup) "" else c.avatarUrl,
            memberNames = p.memberNames, memberAvatars = p.memberAvatars,
        )
        val title = SelectionActions.chatRecordTitle(c.isGroup, peerPublic, myName)
        openForward(ForwardJob(kept, record = SelectionActions.encodeRecord(title, kept, senders), fromSelection = true))
    }

    fun report() {
        val picked = sel.picked()
        val sender = SelectionActions.reportableSender(picked, myUid) ?: return reportBlocked()
        // 确认框里的名字只给我自己看、不随请求发出 → 本机显示名（备注优先）
        val p = people()
        val who = p.friends[sender]?.let { DisplayName.ofFriend(it) }
            ?: p.memberNames[sender]
            ?: picked.first().fromNickname?.takeIf { it.isNotBlank() }
            ?: DisplayName.UNNAMED
        reporting = ReportDraft(picked.map { it.convSeq }, SelectionActions.reportTitle(picked.size, who))
    }

    fun reportBlocked() {
        SelectionActions.reportBlockedHint(sel.picked(), myUid)?.let(toast)
    }

    /** 一次 POST 合成**一张**工单（勾 N 条不给管理员刷出 N 张讲同一件事的单）。失败留在多选里让用户重试。 */
    fun submitReport(d: ReportDraft, reason: String) {
        reporting = null
        val convId = conv().convId
        scope.launch {
            runCatchingCancellable { client.contacts.reportMessages(convId, d.seqs, reason) }
                .onSuccess {
                    toast("举报已提交，感谢反馈。")
                    sel.cancel()
                }
                .onFailure { toast(it.userMessage("举报失败")) }
        }
    }

    /** 批量收藏：点下去就退出多选（同 iOS），逐条串行加，最后吐一句汇总。 */
    fun favorite() {
        val ok = SelectionActions.favoritable(sel.picked())
        if (ok.isEmpty()) {
            toast("所选消息都无法收藏")
            return
        }
        sel.cancel()
        scope.launch {
            var done = 0
            for (m in ok) {
                runCatchingCancellable { client.favorites.add(FavoriteDraft.of(m)) }.onSuccess { done++ }
            }
            toast(SelectionActions.favoriteSummary(done, ok.size))
        }
    }
}

@Composable
internal fun rememberSelectionActions(
    client: IMClient,
    conv: ConversationEntity,
    sel: ChatSelectionController,
    people: SelectionPeople,
    onToast: (String) -> Unit,
    onOpenForward: (ForwardJob) -> Unit,
): SelectionActionsController {
    val scope = rememberCoroutineScope()
    // 控制器按会话记住，但成员表/好友表/会话行都会在之后更新——一律读最新值
    val convNow by rememberUpdatedState(conv)
    val peopleNow by rememberUpdatedState(people)
    val toastNow by rememberUpdatedState(onToast)
    val openNow by rememberUpdatedState(onOpenForward)
    return remember(conv.convId) {
        SelectionActionsController(
            client = client, conv = { convNow }, sel = sel, scope = scope, people = { peopleNow },
            toast = { toastNow(it) }, openForward = { openNow(it) },
        )
    }
}

/** 「逐条 / 合并」选择单 + 举报理由框。 */
@Composable
internal fun SelectionActionLayers(ctrl: SelectionActionsController) {
    if (ctrl.askingMode) {
        ActionSheet(
            title = "",
            items = listOf(
                SheetItem("逐条转发", onClick = { ctrl.forwardEach() }),
                SheetItem("合并转发", onClick = { ctrl.forwardMerged() }),
            ),
            onDismiss = { ctrl.askingMode = false },
        )
    }
    ctrl.reporting?.let { d ->
        IMTextPrompt(
            title = d.title,
            initial = "",
            maxLen = REPORT_REASON_MAX,
            hint = "请填写举报理由（可空）",
            multiline = true,
            confirmText = "提交举报",
            onConfirm = { ctrl.submitReport(d, it) },
            onDismiss = { ctrl.reporting = null },
        )
    }
}

/** 举报理由上限，与服务端 `internal/report` 的 `maxReasonLen` 同为 500。 */
private const val REPORT_REASON_MAX = 500

/**
 * 转发目标选择页。逐条转发走 [forwardMessages]；合并转发每个目标发一张卡片。
 * 发送都挂在 [scope] 上串行做，选择页先关（发 100 条要一会儿，不该让用户盯着选择页等）。
 */
@Composable
internal fun ForwardPickerLayer(
    client: IMClient,
    job: ForwardJob,
    sel: ChatSelectionController,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val convs by client.repo.observeConversations(client.uid.orEmpty()).collectAsState(initial = emptyList())
    ForwardPickerScreen(
        conversations = convs,
        onCancel = onClose,
        onToast = onToast,
        onConfirm = { targets ->
            onClose()
            if (job.fromSelection) sel.cancel()
            scope.launch {
                val record = job.record
                if (record == null) {
                    onToast(forwardMessages(client, job.msgs, targets, job.expiredSkipped))
                } else {
                    for (t in targets) {
                        client.messages.sendCard(t.convId, if (t.isGroup) "" else t.peerUid, ContentType.CHAT_RECORD, record)
                    }
                    onToast(if (targets.size > 1) "已合并转发到 ${targets.size} 个会话" else "已合并转发")
                }
            }
        },
    )
}
