package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
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
import com.libeyond.imandroid.data.sendMsgOp
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
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
 * 批量删除的档位选择单（三端同口径，2026-09-30）：
 *
 * - **仅删除自己**恒有：走 REST `/messages/hide`——每用户私有偏好，落服务端隐藏表并同步本人其它设备，
 *   不进会话事件流、不占 conv_seq、不广播给其他成员（PROTOCOL §6.7.1）。
 * - **为所有人删除**只在所选**全部**有权时才给（[ChatSelection.allDeletableForEveryone]：全有或全无，
 *   避免"选了 10 条只删掉 3 条"的怪相）：逐条 `msg_op delete`，成功由服务端广播回帧收敛，
 *   被拒（300006）走既有的操作失败提示。
 *
 * 此前这里是一个确认弹窗，只有第一档，正文还写着「只从本机删除，其他设备…仍能看到」——
 * 与 hide 的真实语义（本人多端同步）不符，故换成与单条删除同款的选择单，不再带那句话。
 */
@Composable
internal fun BatchDeleteConfirm(
    sel: ChatSelectionController,
    client: IMClient,
    convId: String,
    isGroup: Boolean,
    iAmManager: Boolean,
    onToast: (String) -> Unit,
) {
    // ⚠️ 作用域必须在早退**之前**取：点下去那一刻 `sel.cancel()` 把 confirmDelete 置回 false，下一次重组就走早退。
    // 作用域若是早退之后才 remember 的，就跟着离开组合被当场取消——第一条 hide 还挂在网络上，
    // 后面的全都发不出去、吐司也不出（同 `MessageMenuItems.kt` 记的那条 LeftCompositionCancellationException）。
    // 本函数由 ChatHost 无条件调用，取在这里它就与聊天页同寿命。
    val scope = rememberCoroutineScope()
    if (!sel.confirmDelete) return
    val picked = sel.picked()
    val myUid = client.uid.orEmpty()
    val items = buildList {
        add(SheetItem(stringResource(R.string.delete_sheet_only_me), destructive = true) {
            sel.cancel()
            scope.launch {
                var failed = 0
                picked.forEach { m ->
                    runCatchingCancellable { client.conversationsApi.hideMessage(convId, m.convSeq) }
                        .onSuccess {
                            // 服务端已隐藏 → 本端立刻移除，不等回推的 msg_hidden（幂等）。本地写失败**不算删除失败**：
                            // 服务端那边已经成了，回推帧或下次同步会把它收敛掉，算成失败只会引用户重复去删。
                            runCatchingCancellable { client.repo.applyMsgHidden(myUid, convId, m.convSeq) }
                        }
                        .onFailure { failed++ }
                }
                onToast(
                    if (failed == 0) Str.p(R.plurals.chat_select_delete_done, picked.size, picked.size)
                    else Str.p(R.plurals.chat_select_delete_failed_count, failed, failed),
                )
            }
        })
        // 破坏性重的放最后（destructive-last，与长按子菜单一致）
        if (ChatSelection.allDeletableForEveryone(picked, myUid, isGroup, iAmManager)) {
            add(SheetItem(stringResource(R.string.delete_sheet_everyone), destructive = true) {
                // 未连接时帧发不出去——先拦住并**留在多选态**，别让用户以为删掉了
                if (client.socket.state.value != ConnState.Connected) {
                    onToast(Str.s(R.string.conv_error_delete_failed_detail, Str.s(R.string.conn_state_disconnected)))
                    return@SheetItem
                }
                sel.cancel()
                picked.forEach { m -> client.messages.sendMsgOp(convId, MsgOp.DELETE, m.convSeq) }
            })
        }
    }
    // 选择单开着时返回键只关它：不拦的话落到聊天页的返回键上，直接退出会话
    BackHandler { sel.confirmDelete = false }
    ActionSheet(
        title = pluralStringResource(R.plurals.chat_select_delete_confirm_title, picked.size, picked.size),
        items = items,
        onDismiss = { sel.confirmDelete = false },
    )
}
