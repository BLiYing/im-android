package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.data.ChatSelection
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import kotlinx.coroutines.launch

/**
 * 聊天页多选态的**状态持有者**（M4-3）。判据全在 `data/ChatSelection.kt`，这里只管"记着谁被勾了"。
 *
 * 从 ChatHost 拆出来（2026-09-09，那个文件触了 600 行红线），套路与同文件的
 * `rememberChatSearch` / `rememberChatLocator` 一致。
 *
 * 对端 iOS 是 `IMChatSelectionState`（同样是「只装状态、无行为」的袋子），
 * 它的类注释里那条最贵的教训本端照抄了：**按 `conv_seq` 记，且连消息一起存**——
 * 本端列表是窗口化的，勾过的消息会被裁出内存，只留 seq 会让「已选 N 条」与真能操作的条数对不上。
 */
internal class ChatSelectionController {

    /** `conv_seq → 消息`；**null = 不在多选态**。 */
    var selected by mutableStateOf<Map<Long, MessageEntity>?>(null)
        private set

    /** 批量删除待确认。 */
    var confirmDelete by mutableStateOf(false)

    val active: Boolean get() = selected != null

    /** 进多选并**默认勾上触发的那条**（同 iOS `enterSelectionWithMessage:`）。 */
    fun enter(msg: MessageEntity) {
        selected = if (ChatSelection.selectable(msg)) mapOf(msg.convSeq to msg) else emptyMap()
    }

    fun cancel() {
        selected = null
        confirmDelete = false
    }

    /**
     * 勾 / 取消勾。**返回非 null 表示要吐司的话**（到上限了）——
     * 绝不静默吞掉这一下点击（同 iOS `allowSelectingMore:`）。
     */
    fun toggle(msg: MessageEntity): String? {
        val cur = selected ?: return null
        val next = ChatSelection.toggle(cur, msg)
        if (next == null) return ChatSelection.overflowNotice()
        selected = next
        return null
    }

    /** 已勾选的消息，按 `conv_seq` 升序（= 会话时序）。 */
    fun picked(): List<MessageEntity> = ChatSelection.ordered(selected.orEmpty())

    /**
     * 批量转发前的复核：滤掉转不出去的（撤回/空内容/系统/未确认）。
     *
     * **少发的那几条要如实说**，不静默跳过（同 iOS：先数一次再发）——
     * 勾选判据比转发判据宽一档，用户勾完点转发才发现少了几条会莫名其妙。
     */
    fun forwardPick(): ForwardPick {
        val all = picked()
        val ok = ChatSelection.forwardable(all)
        return when {
            ok.isEmpty() -> ForwardPick(emptyList(), Str.s(R.string.chat_forward_none_forwardable))
            ok.size < all.size -> ForwardPick(
                ok,
                Str.p(R.plurals.chat_select_forward_partial_skip, all.size - ok.size, all.size - ok.size),
            )
            else -> ForwardPick(ok, null)
        }
    }

    /** [forwardPick] 的结果：真能转的那几条 + 要不要吐一句。 */
    data class ForwardPick(val msgs: List<MessageEntity>, val notice: String?)
}

@Composable
internal fun rememberChatSelection(convId: String): ChatSelectionController =
    remember(convId) { ChatSelectionController() }

/**
 * 批量删除的二次确认。
 *
 * **只删本机**——批量「为所有人删除」的权限判定逐条不同（自己的恒可、别人的要管理员），
 * 混在一起做会出现"选了 10 条只删掉 3 条"的怪相；iOS 那侧的多选删除同样只给「仅为我删除」。
 *
 * 走 REST `/messages/hide` 而不是 `msg_op`：仅为我删除是每用户私有偏好，
 * 不进会话事件流、不占 conv_seq、不广播给其他成员（PROTOCOL §6.7.1）。
 */
@Composable
internal fun BatchDeleteConfirm(
    sel: ChatSelectionController,
    client: IMClient,
    convId: String,
    onToast: (String) -> Unit,
) {
    if (!sel.confirmDelete) return
    val scope = rememberCoroutineScope()
    val picked = sel.picked()
    IMConfirmDialog(
        title = pluralStringResource(R.plurals.chat_select_delete_confirm_title, picked.size, picked.size),
        message = stringResource(R.string.chat_select_delete_confirm_message),
        confirmText = stringResource(R.string.common_delete),
        destructive = true,
        onDismiss = { sel.confirmDelete = false },
        onConfirm = {
            sel.cancel()
            scope.launch {
                var failed = 0
                picked.forEach { m ->
                    runCatchingCancellable {
                        client.conversationsApi.hideMessage(convId, m.convSeq)
                    }.onFailure { failed++ }
                }
                onToast(
                    if (failed == 0) Str.p(R.plurals.chat_select_delete_done, picked.size, picked.size)
                    else Str.p(R.plurals.chat_select_delete_failed_count, failed, failed),
                )
            }
        },
    )
}
