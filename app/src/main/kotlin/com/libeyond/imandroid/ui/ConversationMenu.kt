package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Trash2
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ConversationAction
import com.libeyond.imandroid.data.ConversationActions
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.SheetItem
import kotlinx.coroutines.launch

// 会话列表长按菜单（从 MainScreen.kt 拆出：MainScreen 逼近 600 行红线，菜单是自成一体的一段）。

/**
 * 会话长按菜单（CHAT_UX §12/§14）。
 *
 * **不是底部弹窗**：iOS 会话列表长按走的是 UIContextMenu（原位、贴着那一行）。
 * 底部弹窗把「操作哪一条」这个信息丢了——手指在屏幕上半部长按，眼睛却要跑到底部找菜单。
 * 与消息长按共用同一个组件，两处交互才一致。
 */
@Composable
internal fun ConversationMenu(
    client: IMClient,
    target: ConversationEntity,
    anchor: androidx.compose.ui.geometry.Rect,
    scope: kotlinx.coroutines.CoroutineScope,
    /** 判「是否免打扰」的当前时刻——决定菜单给「免打扰」还是「取消免打扰」（§4.1/§4.2）。 */
    nowMs: Long,
    /** 点了「免打扰」（且当前未免打扰）：交给调用方弹时长菜单，不在这里直接置 muted=true
     *  （§4.1：未免打扰时这一项要先选时长，已免打扰时这一项本身就是「取消免打扰」，直接生效）。 */
    onRequestMuteSheet: (ConversationEntity) -> Unit,
    /** 操作失败的提示（离线时尤其需要：不然点了没反应）。 */
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 列表当前所在的文件夹 Tab（null = 「全部」）：决定「从「X」移出」排第一的判据。 */
    currentFolderId: String? = null,
    /** 点了「新建文件夹…」：交给调用方弹输入框（与时长菜单同理，不占用这层的 anchor 坐标系）。 */
    onNewFolder: (ConversationEntity) -> Unit = {},
) {
    val mutedNow = MuteState.isMutedNow(target.muted, target.muteUntil, nowMs)
    val folderSnap by client.chatFolderStore.state.collectAsState()
    // 文件夹项放菜单第一位（设计 §4.4）：未入夹 =「添加到文件夹」；已入夹 =「从「X」移出」排第一
    val folderItems = conversationFolderItems(
        snapshot = folderSnap,
        convId = target.convId,
        currentFolderId = currentFolderId,
        onToggle = { f, member ->
            scope.launch {
                runCatchingCancellable {
                    client.chatFolderStore.changeConvs(
                        f.id, add = if (member) listOf(target.convId) else emptyList(),
                        remove = if (member) emptyList() else listOf(target.convId),
                    )
                }.onSuccess {
                    onToast(com.libeyond.imandroid.i18n.Str.s(if (member) R.string.folder_toast_added else R.string.folder_toast_removed, f.name))
                }.onFailure { onToast(it.userMessage(com.libeyond.imandroid.i18n.Str.s(R.string.folder_toast_failed))) }
            }
        },
        onNew = { onNewFolder(target) },
        onToast = onToast,
    )
    val lift = com.libeyond.imandroid.ui.components.LocalMenuLift.current
    MessageContextMenu(
        anchor = anchor,
        // 会话行是整行全宽的，菜单靠左（跟着行的起始边，与 iOS 的 preview 锚点同侧）
        mine = false,
        preview = lift?.let { { com.libeyond.imandroid.ui.components.LiftPreview(it) } },
        items = folderItems + ConversationActions
            .availableFor(target.pinnedAt, mutedNow, target.markedUnread, target.unread)
            .map { a ->
                SheetItem(a.label, a.destructive, icon = convActionIcon(a)) {
                    if (a == ConversationAction.Mute) {
                        onRequestMuteSheet(target)
                    } else {
                        val failRes = when (a) {
                            ConversationAction.Pin, ConversationAction.Unpin -> R.string.conv_error_pin_failed
                            ConversationAction.Delete -> R.string.conv_error_delete_failed
                            else -> R.string.conv_error_settings_failed
                        }
                        scope.launch {
                            runCatchingCancellable {
                                when (a) {
                                    ConversationAction.Pin -> settings(client, target, pinnedAt = System.currentTimeMillis())
                                    ConversationAction.Unpin -> settings(client, target, pinnedAt = 0)
                                    ConversationAction.Unmute -> settings(client, target, muted = false)
                                    ConversationAction.MarkUnread -> settings(client, target, markedUnread = true)
                                    ConversationAction.MarkRead -> settings(client, target, markedUnread = false)
                                    ConversationAction.Delete -> client.messages.deleteConversation(target.convId)
                                    ConversationAction.Mute -> Unit // 上面已分流，走不到这里
                                }
                            }.onFailure { onToast(it.userMessage(com.libeyond.imandroid.i18n.Str.s(failRes))) }
                            client.messages.refreshConversations()
                        }
                    }
                }
            },
        onDismiss = onDismiss,
    )
}

/**
 * 会话菜单项的图标。**逐项对齐 iOS `conversationActionsFor:` 里的 SF Symbol**
 * （pin / pin.slash / bell / bell.slash / checkmark.circle / circle / trash），
 * 用 Lucide 里语义最近的一枚——SF Symbol 在 Android 上不存在，
 * 要对齐的是「每项都有图标且认得出」，不是同一张图（docs/UI_PARITY_IOS.md §3）。
 */
private fun convActionIcon(a: ConversationAction) = when (a) {
    ConversationAction.Pin -> Lucide.Pin
    ConversationAction.Unpin -> Lucide.PinOff
    ConversationAction.Mute -> Lucide.BellOff
    ConversationAction.Unmute -> Lucide.Bell
    ConversationAction.MarkRead -> Lucide.CircleCheck
    ConversationAction.MarkUnread -> Lucide.Circle
    ConversationAction.Delete -> Lucide.Trash2
}

/**
 * 改会话设置。**整体替换三项**（§6.8），所以未指定的项要用当前值填回去——
 * 漏传一项等于把它清零，「置顶一下顺手把免打扰关了」就是这么来的。
 */
internal suspend fun settings(
    client: IMClient,
    conv: ConversationEntity,
    pinnedAt: Long = conv.pinnedAt,
    muted: Boolean = conv.muted,
    markedUnread: Boolean = conv.markedUnread,
    /** 定时免打扰到期毫秒——**只有选了时长菜单的调用点才传**，其余调用点留 `null`（省略），
     *  让服务端按 PROTOCOL §6.10 的缺省规则保留原到期时间（不然置顶一下会把定时免打扰变成永久）。 */
    muteUntil: Long? = null,
) {
    client.conversationsApi.updateSettings(conv.convId, pinnedAt, muted, markedUnread, muteUntil)
}

