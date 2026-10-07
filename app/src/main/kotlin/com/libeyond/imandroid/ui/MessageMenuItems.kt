package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendMsgOp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Box
import com.libeyond.imandroid.ui.screens.AlbumTile
import com.libeyond.imandroid.ui.screens.AlbumTilePreview
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.CornerUpLeft
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ArchiveAction
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.data.MessageActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
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
            SheetItem(Str.s(R.string.common_delete), destructive = true, icon = Lucide.Trash2) { run(deletes.first()) },
        )
        else -> listOf(
            SheetItem(
                Str.s(R.string.common_delete), destructive = true, icon = Lucide.Trash2,
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
 * （doc.on.doc / arrowshape.turn.up.left / arrowshape.turn.up.right / bookmark /
 * arrow.uturn.backward / trash），用 Lucide 里语义最近的一枚。
 */
internal fun messageActionIcon(a: MessageAction) = when (a) {
    MessageAction.Copy -> Lucide.Copy
    MessageAction.Reply -> Lucide.CornerUpLeft
    MessageAction.Forward -> Lucide.Forward
    MessageAction.Favorite -> Lucide.Bookmark
    MessageAction.Transcribe, MessageAction.TranscribeOff -> Lucide.FileText
    MessageAction.MultiSelect -> Lucide.ListChecks
    MessageAction.Edit -> Lucide.Pencil
    MessageAction.Translate -> Lucide.Languages
    MessageAction.Report -> Lucide.Flag
    MessageAction.Pin -> Lucide.Pin
    MessageAction.Unpin -> Lucide.PinOff
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
    /** 聊天页的模糊状态：菜单登记在它上面，聊天页才会糊（见 MenuBackdrop.kt）。 */
    backdrop: com.libeyond.imandroid.ui.components.MenuBackdropState,
    target: MessageEntity,
    anchor: com.libeyond.imandroid.ui.components.MenuAnchor,
    rows: List<ChatRow>,
    rowStyle: ChatRowStyle,
    client: IMClient,
    conv: ConversationEntity,
    iAmManager: Boolean,
    /** 我能不能置顶（[com.libeyond.imandroid.data.PinnedBanner.canPin]）。 */
    canPin: Boolean,
    /** 已读详情 / 翻译 / 编辑 / 举报的状态与动作（本层不持有成员表/资料页/输入框）。 */
    ops: ChatMessageOps,
    onReply: (MessageEntity) -> Unit,
    onForward: (MessageEntity) -> Unit,
    /** 进多选态，并默认勾上这一条。 */
    onMultiSelect: (MessageEntity) -> Unit,
    /** 「复制」要说一句（iOS 三档文案：已复制 / 已复制图片 / 已复制链接）。 */
    onToast: (String) -> Unit,
    /**
     * **宿主的作用域，本层绝不自建**（同 `ArchiveViewer.kt` 文件头那条 ⚠️）。
     *
     * 菜单是"点一下就把自己关掉"的浮层：`rememberCoroutineScope()` 绑的是本 composable，
     * 点完菜单项 `onDismiss()` 一走，本层离开组合、作用域当场取消，**挂在上面的活全废**。
     * 2026-09-17 真机抓到的就是这个：点「复制」后日志里是
     * `image_copy_failed {err=LeftCompositionCancellationException}`——图片压根没进剪贴板，
     * 用户看到的现象是"复制完回输入框长按，没有粘贴"。同一条线上还有「仅删除自己」
     * （[runMessageDelete] 的 HideForMe 也是 launch）。
     */
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val owner = client.uid.orEmpty()
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val transcriber = com.libeyond.imandroid.ui.voice.LocalVoiceTranscriber.current
    val actions = MessageActions.availableFor(
        target, owner, conv.isGroup, iAmManager,
        hasTranscript = transcriber?.isExpanded(target.convSeq) == true,
        canPin = canPin,
    )
    // 我发的群消息：菜单一开就去问已读名单（只有发送者能查，服务端同判）；
    // 超过 2000 人的群服务端回 enabled=false、出错同理——整行直接不出现（iOS 的 deferred element 同口径）
    val readBy by androidx.compose.runtime.produceState<com.libeyond.imandroid.sdk.api.ReadBy?>(null, target.convSeq) {
        if (conv.isGroup && target.sender == owner && target.convSeq > 0) {
            value = runCatchingCancellable { client.conversationsApi.readBy(conv.convId, target.convSeq) }
                .getOrNull()?.takeIf { it.enabled }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(com.libeyond.imandroid.ui.components.LocalMenuBackdrop provides backdrop) {
    MessageContextMenu(
        anchor = anchor.area,
        focus = anchor.focus,
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
                val tileSize = with(androidx.compose.ui.platform.LocalDensity.current) { anchor.area.width.toDp() }
                Box(
                    Modifier
                        .shadow(12.dp, RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                        .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius)),
                ) {
                    // 与宫格原位同一份渲染（门控磨砂等）；点图片 = 关菜单（见 AlbumTilePreview）
                    AlbumTilePreview(
                        tile = AlbumTile(
                            target.content, target.contentType, target.duration,
                            thumb = target.thumb, sizeBytes = target.fileSize ?: 0L,
                        ),
                        size = tileSize, host = client.host, useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
                        isGroup = conv.isGroup, mine = target.sender == owner, onDismiss = onDismiss,
                    )
                }
            } else if (idx >= 0) {
                // 预览里的链接**只高亮不可点**：预览层的约定是点哪儿都穿透到背景把菜单关掉，
                // 链接可点的话会在菜单还开着时再盖一层浏览器（2026-09-16 code-reviewer 抓出）
                androidx.compose.runtime.CompositionLocalProvider(
                    com.libeyond.imandroid.ui.components.LocalOpenLink provides null,
                ) {
                    // 同理，预览里点图片/宫格格子只关菜单：`onOpenMedia` 不传时吃的是
                    // ChatRowView 的空实现默认值，点了什么都不发生（看起来像卡住了）。
                    ChatRowView(
                        rows = rows, i = idx, style = rowStyle,
                        onOpenMedia = { onDismiss() },
                    )
                }
            }
        },
        items = readReceiptsItem(readBy) { ops.readReceipts = it } + buildMessageMenu(actions) { a ->
            when (a) {
                // 复制什么由矩阵定（`copyKindOf`，对齐 iOS `copyMessageToPasteboard:`）：
                // **caption 压过一切**——带图说的图片复制的是那段文字，不是图。
                MessageAction.Copy -> when (com.libeyond.imandroid.data.copyKindOf(target)) {
                    com.libeyond.imandroid.data.CopyKind.Caption -> {
                        clipboard.setText(AnnotatedString(target.caption.orEmpty()))
                        onToast(Str.s(R.string.common_copied))
                    }
                    com.libeyond.imandroid.data.CopyKind.Text -> {
                        clipboard.setText(AnnotatedString(target.content))
                        onToast(Str.s(R.string.common_copied))
                    }
                    // 图片走与查看器「更多 → 复制」同一条（本地原件优先），别另搓一份
                    com.libeyond.imandroid.data.CopyKind.Image -> scope.launch {
                        val local = client.downloads.localFile(target.content)
                        val url = local?.let { android.net.Uri.fromFile(it).toString() }
                            ?: MediaUrl.absolute(
                                target.content, client.host, com.libeyond.imandroid.BuildConfig.USE_TLS,
                            )
                        onToast(CopyImage.copy(context, url))
                    }
                    null -> Unit
                }
                // 转文字 / 取消转文字：同一个入口，VoiceTranscriber.toggle 按当前展开态分派
                // （对齐 iOS `im_transcribeVoiceMessage:`、Web `transcribeMessage`）。
                MessageAction.Transcribe, MessageAction.TranscribeOff ->
                    transcriber?.toggle(conv.convId, target.convSeq, target.content)
                MessageAction.Reply -> onReply(target)
                MessageAction.Forward -> onForward(target)
                // 挂宿主作用域（本函数 KDoc 的 scope 那条）：菜单一关，挂菜单自己身上的请求会被取消。
                // 快照字段与多选底栏同一份 FavoriteDraft.of（宽高/时长/波形/文件名大小都带上）
                MessageAction.Favorite -> scope.launch {
                    runCatchingCancellable { client.favorites.add(com.libeyond.imandroid.sdk.api.FavoriteDraft.of(target)) }
                        .onSuccess { onToast(Str.s(R.string.chat_favorite_success)) }
                        .onFailure { onToast(it.userMessage(Str.s(R.string.net_fallback_favorite_failed))) }
                }
                // 进多选态：**默认把触发的那条勾上**（同 iOS enterSelectionWithMessage:）
                MessageAction.MultiSelect -> onMultiSelect(target)
                MessageAction.Recall ->
                    client.messages.sendMsgOp(conv.convId, MsgOp.RECALL, target.convSeq)
                // 置顶 / 取消置顶：**不做本地乐观更新**，等服务端广播回来再变（横幅与 pinnedAt 同一条帧收敛，见 sendMsgOp 注释）
                MessageAction.Edit -> ops.beginEdit(target)
                MessageAction.Translate -> ops.translate(target)
                MessageAction.Report -> ops.reporting = target
                MessageAction.Pin -> client.messages.sendMsgOp(conv.convId, MsgOp.PIN, target.convSeq, pinned = true)
                MessageAction.Unpin -> client.messages.sendMsgOp(conv.convId, MsgOp.PIN, target.convSeq, pinned = false)
                MessageAction.DeleteForEveryone, MessageAction.HideForMe ->
                    runMessageDelete(client, conv.convId, a, target.convSeq, scope)
            }
        },
        onDismiss = onDismiss,
    )
    }
}

/**
 * 两档删除的**执行**。气泡长按菜单与查看器「更多」（`ChatViewerLayer`）共用——各写一遍的话，
 * 哪天「仅为我删除」换了接口，查看器那一侧会悄悄还走老路。其余动作传进来不做事。
 */
internal fun runMessageDelete(
    client: IMClient,
    convId: String,
    action: MessageAction,
    convSeq: Long,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (action) {
        MessageAction.DeleteForEveryone -> client.messages.sendMsgOp(convId, MsgOp.DELETE, convSeq)
        MessageAction.HideForMe -> scope.launch {
            // 走 REST，不是 msg_op——「仅为我删除」是每用户私有偏好，
            // 不进会话事件流、不占 conv_seq、不广播给其他成员（§6.7.1）
            runCatching { client.conversationsApi.hideMessage(convId, convSeq) }
            client.repo.applyMsgHidden(client.uid.orEmpty(), convId, convSeq)
        }
        else -> Unit
    }
}

/** 菜单最上面那一行：有人读过 =「N 人已读」可点开名单；没人读过 =「暂无人已读」（灰、点了只关菜单）。 */
internal fun readReceiptsItem(
    readBy: com.libeyond.imandroid.sdk.api.ReadBy?,
    onOpen: (com.libeyond.imandroid.sdk.api.ReadBy) -> Unit,
): List<SheetItem> {
    readBy ?: return emptyList()
    if (readBy.read.isEmpty()) return listOf(SheetItem(Str.s(R.string.chat_menu_no_reads), icon = Lucide.Eye))
    return listOf(
        SheetItem(Str.p(R.plurals.chat_menu_read_count, readBy.read.size, readBy.read.size), icon = Lucide.Eye) { onOpen(readBy) },
    )
}
