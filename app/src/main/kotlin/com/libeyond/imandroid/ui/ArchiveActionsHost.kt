package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendMsgOp
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ArchiveAction
import com.libeyond.imandroid.data.ArchiveActions
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import kotlinx.coroutines.launch

/**
 * 归档长按菜单的**接线层**：菜单本身 + 转发选择页 + 四个动作的执行。
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
    onDismiss: () -> Unit,
) {
    val owner = client.uid.orEmpty()
    val scope = rememberCoroutineScope()
    var forwarding by remember(convId) { mutableStateOf<ArchiveTarget?>(null) }

    // —— 转发选择页（盖在菜单之上）——
    val fwd = forwarding
    if (fwd != null) {
        // **本页自己拦返回键**：BackHandler 后注册者优先，本组合体渲染在宿主页面之后，
        // 所以这一个会先吃到返回键，宿主那套页面栈判定（ChatDetailNav / GroupInfoNav）
        // 不必为一个临时浮层新增一层。
        BackHandler { forwarding = null }
        val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
        ForwardPickerScreen(
            conversations = convs,
            count = 1,
            onCancel = { forwarding = null },
            onToast = onToast,
            onConfirm = { targets ->
                forwarding = null
                scope.launch {
                    val msg = fwd.toMessageEntity(owner, convId)
                    onToast(forwardMessages(client, listOf(msg), targets))
                }
            },
        )
        return
    }

    if (target == null) return

    // 下载中/已暂停才给「取消下载」（同 iOS：phase ∈ {Downloading, Paused}）。
    // 语音/链接不参与门控，恒为 false。
    val phase = client.downloads
        .stateOf(target.content, target.contentType == ContentType.VIDEO).phase
    val downloading = phase == DownloadPhase.Downloading || phase == DownloadPhase.Paused

    val actions = ArchiveActions.availableFor(
        convSeq = target.convSeq,
        downloading = downloading,
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
            when (a) {
                ArchiveAction.Forward -> forwarding = target
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
                            onToast("删除失败，请重试")
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
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
