package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendMsgOp
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ArchiveAction
import com.libeyond.imandroid.data.ArchiveActions
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.MessageContextMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 归档长按菜单的**接线层**：菜单本身 + 四个动作的执行。
 *
 * 单聊详情的内联页签与群资料的独立归档页**共用这一份**——归档这件事在两种会话里完全一样，
 * 分两份的代价不是重复代码，是判据会分叉（"某个入口的菜单少一项/删错档位"）。
 * iOS 那一侧同样只有一个 `contentMenuConfigForMessage:`，四个页签与媒体宫格全汇到它。
 *
 * @param target       被长按的那一项；`null` = 菜单没开。
 * @param anchor       它在窗口坐标系里的矩形（菜单贴着它弹，同气泡那套）。
 * @param onLocateInChat 请求「定位到聊天」。**本页做不了这件事**——聊天页在本页之下、
 *   已被整个移出组合，所以只能把 conv_seq 交上去，由导航层关掉本页再让聊天页接手。
 * @param onChanged    删除成功后回调，宿主据此刷新归档列表（它是服务端来的，删完要重拉）。
 */
@Composable
internal fun ArchiveActionsHost(
    client: IMClient,
    convId: String,
    isGroup: Boolean,
    iAmManager: Boolean,
    target: ArchiveTarget?,
    anchor: Rect,
    onLocateInChat: (Long) -> Unit,
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
    /**
     * 请求打开转发选择页。**状态挂在宿主上、不在本层**——归档查看器的「更多 → 转发」
     * 与这里的长按菜单是同一件事，两处各留一份选择页状态的话，两条路的行为迟早分叉。
     */
    onForwardPicker: (ArchiveTarget) -> Unit,
    /**
     * **宿主的作用域，本层绝不自建**（同本包 `ArchiveViewer.kt` 文件头那条 ⚠️）。
     *
     * 菜单点完就 `onDismiss()` 把自己关掉，`rememberCoroutineScope()` 绑的是本 composable，
     * 于是「仅删除自己」那次 REST 请求会随组合一起被取消——**点了像没反应**。
     * 2026-09-17 在聊天页长按菜单上抓到了同一个形状（`LeftCompositionCancellationException`），
     * 顺手把归档这一侧一并收了。
     */
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val owner = client.uid.orEmpty()

    if (target == null) return

    val actions = ArchiveActions.availableFor(
        convSeq = target.convSeq,
        downloading = archiveItemDownloading(client, target),
        mine = target.sender == owner,
        isGroup = isGroup,
        iAmManager = iAmManager,
    )
    // convSeq <= 0 时 availableFor 回空表（同 iOS 的前置门槛）——不弹一个全是死项的菜单
    if (actions.isEmpty()) {
        onDismiss()
        return
    }

    MessageContextMenu(
        anchor = anchor,
        // 归档行是整行/整格的，菜单一律靠左——这里没有"谁发的"这个方向感
        mine = false,
        // 归档项不做原位重绘：宫格那一格是张远端图，重绘要再拉一次；
        // 行则本来就在原位看得见。压暗背景 + 贴着它弹菜单已经说清"操作的是这一项"。
        preview = null,
        items = buildArchiveMenu(actions) { a ->
            runArchiveAction(
                action = a, client = client, convId = convId, owner = owner, target = target,
                scope = scope, onForwardPicker = onForwardPicker,
                onLocateInChat = onLocateInChat, onChanged = onChanged, onToast = onToast,
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * 这一项是不是正在下载 / 已暂停——决定给不给「取消下载」（同 iOS：phase ∈ {Downloading, Paused}）。
 * 语音/链接不参与门控，恒为 false。
 */
internal fun archiveItemDownloading(client: IMClient, target: ArchiveTarget): Boolean {
    val phase = client.downloads
        .stateOf(target.content, target.contentType == ContentType.VIDEO).phase
    return phase == DownloadPhase.Downloading || phase == DownloadPhase.Paused
}

/**
 * 归档动作的**执行**。长按菜单与归档查看器的「更多」共用——
 * 各写一遍的话，哪天「仅为我删除」换了接口，查看器那一侧会悄悄还走老路
 * （同 `runMessageDelete` 在聊天页那两处的理由）。
 */
internal fun runArchiveAction(
    action: ArchiveAction,
    client: IMClient,
    convId: String,
    owner: String,
    target: ArchiveTarget,
    scope: CoroutineScope,
    onForwardPicker: (ArchiveTarget) -> Unit,
    onLocateInChat: (Long) -> Unit,
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
) {
    when (action) {
        // 失效媒体拦下不转，与聊天页 `SelectionActionsController.forwardOne` 同一判据（iOS 一处拦三入口）
        ArchiveAction.Forward -> {
            val msg = target.toMessageEntity(owner, convId)
            val gone = SelectionActions.isExpiredMedia(msg) { url, isVideo ->
                client.downloads.stateOf(url, isVideo).phase == DownloadPhase.Expired
            }
            if (gone) onToast(SelectionActions.expiredForwardText(msg.contentType)) else onForwardPicker(target)
        }
        ArchiveAction.LocateInChat -> onLocateInChat(target.convSeq)
        ArchiveAction.CancelDownload ->
            client.downloads.pause(target.content, target.contentType == ContentType.VIDEO)
        ArchiveAction.HideForMe -> scope.launch {
            // 走 REST，不是 msg_op——「仅为我删除」是每用户私有偏好，
            // 不进会话事件流、不占 conv_seq、不广播给其他成员（§6.7.1）
            runCatching { client.conversationsApi.hideMessage(convId, target.convSeq) }
                .onSuccess {
                    client.repo.applyMsgHidden(owner, convId, target.convSeq)
                    onChanged()
                }
                .onFailure {
                    onToast(Str.s(R.string.chat_archive_delete_failed_retry))
                    IMLog.tag("IM.Detail").w("archive_hide_failed", "seq" to target.convSeq)
                }
        }
        ArchiveAction.DeleteForEveryone -> {
            client.messages.sendMsgOp(convId, MsgOp.DELETE, target.convSeq)
            // msg_op 是**发出去就走**、成功与否靠回帧收敛；这里先把列表刷掉，
            // 服务端拒绝时那一条会随下一次拉取回来（而不是留一个假的"已删"）
            onChanged()
        }
    }
}
