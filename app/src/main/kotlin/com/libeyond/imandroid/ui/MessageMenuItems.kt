package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendMsgOp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import coil.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.CornerUpLeft
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.ArchiveAction
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.data.MessageActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.ChatRowStyle
import com.libeyond.imandroid.ui.screens.ChatRowView
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

// 长按菜单的**拼装与接线**（聊天页气泡 + 详情页归档两处）。从 ChatHost 拆出
// （CODING_STYLE §7②）：那个文件是接线层，菜单怎么摆是另一件事，且它贴着 600 行的体量红线。

/**
 * 「两档删除收进一个『删除』子菜单」这条规则的**唯一实现**。
 *
 * 对齐 iOS `deleteMenuActionForMessage:` 与 `contentMenuConfigForMessage:`——那一侧两处也是同一条：
 * 两档删除是**同一个动作的两种范围**，摊成两个平级项会让人以为是两件不同的事
 * （而且「为所有人删除」这种长文案会把菜单撑宽）。只有一档可用时不套子菜单——
 * 为一个选项造一层菜单是纯粹的多余点击。
 *
 * **抽成泛型是因为它有两个调用方**（气泡菜单 / 归档菜单）。各写一遍的话，
 * 改了一处忘了另一处的表现是"同一条规则在两个页面上不一样"，而不是报错。
 */
private fun <T> buildMenuWithDeleteSubmenu(
    actions: List<T>,
    isDelete: (T) -> Boolean,
    label: (T) -> String,
    destructive: (T) -> Boolean,
    icon: (T) -> ImageVector,
    run: (T) -> Unit,
): List<SheetItem> {
    val deletes = actions.filter(isDelete)
    val head = actions.filterNot { it in deletes }.map { a ->
        SheetItem(label(a), destructive(a), icon = icon(a)) { run(a) }
    }
    val tail = when {
        deletes.isEmpty() -> emptyList()
        deletes.size == 1 -> listOf(
            SheetItem("删除", destructive = true, icon = Lucide.Trash2) { run(deletes.first()) },
        )
        else -> listOf(
            SheetItem(
                "删除", destructive = true, icon = Lucide.Trash2,
                submenu = deletes.map { a ->
                    SheetItem(label(a), destructive = true, icon = icon(a)) { run(a) }
                },
            ),
        )
    }
    return head + tail
}

internal fun buildMessageMenu(
    actions: List<MessageAction>,
    run: (MessageAction) -> Unit,
): List<SheetItem> = buildMenuWithDeleteSubmenu(
    actions = actions,
    isDelete = { it == MessageAction.DeleteForEveryone || it == MessageAction.HideForMe },
    label = { it.label }, destructive = { it.destructive }, icon = ::messageActionIcon, run = run,
)

internal fun buildArchiveMenu(
    actions: List<ArchiveAction>,
    run: (ArchiveAction) -> Unit,
): List<SheetItem> = buildMenuWithDeleteSubmenu(
    actions = actions,
    isDelete = { it == ArchiveAction.DeleteForEveryone || it == ArchiveAction.HideForMe },
    label = { it.label }, destructive = { it.destructive }, icon = ::archiveActionIcon, run = run,
)

/**
 * 消息菜单项图标。**逐项对齐 iOS `messageActionsForMessage:` 里的 SF Symbol**
 * （doc.on.doc / arrowshape.turn.up.left / arrowshape.turn.up.right /
 * arrow.uturn.backward / trash），用 Lucide 里语义最近的一枚。
 */
internal fun messageActionIcon(a: MessageAction) = when (a) {
    MessageAction.Copy -> Lucide.Copy
    MessageAction.Reply -> Lucide.CornerUpLeft
    MessageAction.Forward -> Lucide.Forward
    MessageAction.Recall -> Lucide.Undo2
    MessageAction.DeleteForEveryone -> Lucide.Users
    MessageAction.HideForMe -> Lucide.User
}

/**
 * 归档菜单项图标。同样逐项对齐 iOS `contentMenuConfigForMessage:` 的 SF Symbol
 * （arrowshape.turn.up.right / bubble.left.and.text.bubble.right / xmark.circle / trash）。
 */
internal fun archiveActionIcon(a: ArchiveAction) = when (a) {
    ArchiveAction.Forward -> Lucide.Forward
    ArchiveAction.LocateInChat -> Lucide.MessageSquare
    ArchiveAction.CancelDownload -> Lucide.X
    ArchiveAction.DeleteForEveryone -> Lucide.Users
    ArchiveAction.HideForMe -> Lucide.User
}

/**
 * 聊天页气泡的长按菜单（含原位重绘）。
 *
 * 原位重绘被长按的那一**行**：iOS 是把它光栅化成位图钉回原位（`UITargetedPreview`），
 * 这里直接再画一遍。**必须按行的真实形态画**——宫格要画成宫格：起初这里一律画 Bubble，
 * 长按宫格里的一格会重绘成一张大图，与原位那一行完全对不上（2026-09-08 真机撞见）。
 */
@Composable
internal fun ChatMessageMenu(
    target: MessageEntity,
    anchor: Rect,
    rows: List<ChatRow>,
    rowStyle: ChatRowStyle,
    client: IMClient,
    conv: ConversationEntity,
    iAmManager: Boolean,
    onReply: (MessageEntity) -> Unit,
    onForward: (MessageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val owner = client.uid.orEmpty()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val actions = MessageActions.availableFor(target, owner, conv.isGroup, iAmManager)
    MessageContextMenu(
        anchor = anchor,
        mine = target.sender == owner,
        preview = {
            // **与列表本身共用同一段渲染**（ChatRowView）：预览与原位由两份代码画时，
            // 本端连栽两次——宫格被画成一张大图、带链接的文本少了富预览卡。
            val idx = rows.indexOfFirst { r ->
                when (r) {
                    is ChatRow.Confirmed -> r.msg.convSeq == target.convSeq
                    is ChatRow.Album -> r.sent.any { it.convSeq == target.convSeq }
                    else -> false
                }
            }
            val row = rows.getOrNull(idx)
            if (row is ChatRow.Album) {
                // 宫格浮起的是**手指按住的那一格**（同 iOS）：anchor 就是那一格的矩形，
                // 这里按它铺满即可（格子是正方形）。
                AsyncImage(
                    model = MediaUrl.absolute(
                        target.content, client.host, com.libeyond.imandroid.BuildConfig.USE_TLS,
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius)),
                )
            } else if (idx >= 0) {
                ChatRowView(rows = rows, i = idx, style = rowStyle)
            }
        },
        items = buildMessageMenu(actions) { a ->
            when (a) {
                MessageAction.Copy -> clipboard.setText(AnnotatedString(target.content))
                MessageAction.Reply -> onReply(target)
                MessageAction.Forward -> onForward(target)
                MessageAction.Recall ->
                    client.messages.sendMsgOp(conv.convId, MsgOp.RECALL, target.convSeq)
                MessageAction.DeleteForEveryone ->
                    client.messages.sendMsgOp(conv.convId, MsgOp.DELETE, target.convSeq)
                MessageAction.HideForMe -> scope.launch {
                    // 走 REST，不是 msg_op——「仅为我删除」是每用户私有偏好，
                    // 不进会话事件流、不占 conv_seq、不广播给其他成员（§6.7.1）
                    runCatching { client.conversationsApi.hideMessage(conv.convId, target.convSeq) }
                    client.repo.applyMsgHidden(owner, conv.convId, target.convSeq)
                }
            }
        },
        onDismiss = onDismiss,
    )
}
