package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.sendMsgOp
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ReadBy
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.IMTextPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 聊天页「对某条消息做点什么」的状态与动作：已读详情 / 翻译 / 编辑 / 举报。
 * 从 `ChatHost` 拆出（那份文件贴着 600 行硬闸）；长按菜单只拿这一个对象，不再一个动作一个回调。
 */
class ChatMessageOps(
    private val client: IMClient,
    private val conv: ConversationEntity,
    private val scope: CoroutineScope,
) {
    /** 已读详情（群聊我发的消息，长按「N 人已读」）。 */
    var readReceipts by mutableStateOf<ReadBy?>(null)

    /**
     * 译文：convSeq → 文本。**只在内存**（对齐 iOS `IMMessageModel.translation`、Web state）：
     * 退出聊天页就没了，不落库。目标语言恒为 zh（跨端契约，`/translate` 缺省也是 zh）。
     */
    val translations = mutableStateMapOf<Long, String>()

    /** 正在编辑的消息（与回复条互斥，共用同一条）。 */
    var editing by mutableStateOf<MessageEntity?>(null)

    /** 正在举报的那条（弹理由框）。 */
    var reporting by mutableStateOf<MessageEntity?>(null)

    /** 宿主每次重组刷新：弹提示、把文字填进输入框（并清掉回复态）。 */
    var toast: (String) -> Unit = {}
    var prefill: (String) -> Unit = {}

    fun translate(m: MessageEntity) {
        scope.launch {
            runCatchingCancellable { client.conversationsApi.translate(m.content) }
                .onSuccess { translations[m.convSeq] = it }
                .onFailure {
                    toast(Str.s(R.string.chat_error_translate_failed, it.userMessage(Str.s(R.string.net_fallback_translate_failed))))
                }
        }
    }

    /** 进编辑态：输入框填入原文（iOS `beginEditMessage:`）。 */
    fun beginEdit(m: MessageEntity) {
        editing = m
        prefill(m.content)
    }

    /** 取消编辑：**输入框一并清空**（iOS `cancelEdit`）。 */
    fun cancelEdit() {
        editing = null
        prefill("")
    }

    /**
     * 编辑态点发送：发 `msg_op edit`，**不发新消息**。空文本什么都不做（返回 false，保持编辑态）；
     * 文本没改也照发（iOS 没做「未变化」判断）。不做本地乐观更新，等服务端广播回来再变（同其它 msg_op）。
     */
    fun commitEdit(text: String): Boolean {
        val m = editing ?: return false
        if (text.isBlank()) return false
        client.messages.sendMsgOp(conv.convId, MsgOp.EDIT, m.convSeq, content = text)
        editing = null
        return true
    }

    private fun submitReport(m: MessageEntity, reason: String) {
        scope.launch {
            runCatchingCancellable { client.contacts.reportMessages(conv.convId, listOf(m.convSeq), reason) }
                .onSuccess { toast(Str.s(R.string.chat_detail_report_submitted)) }
                .onFailure { toast(it.userMessage(Str.s(R.string.net_fallback_report_failed))) }
        }
    }

    /** 已读详情卡片 + 举报理由框。 */
    @Composable
    fun Layers(nameOf: (String) -> String, avatarOf: (String) -> String, roleOf: (String) -> String?, onOpenUser: (String) -> Unit) {
        readReceipts?.let { rb ->
            ReadReceiptsSheet(rb, nameOf, avatarOf, roleOf, onOpenUser, onDismissed = { readReceipts = null })
        }
        reporting?.let { m ->
            IMTextPrompt(
                title = stringResource(R.string.chat_report_single_title),
                initial = "",
                maxLen = REPORT_REASON_MAX,
                hint = stringResource(R.string.chat_detail_report_reason_prompt),
                label = stringResource(R.string.chat_detail_report_reason_placeholder),
                multiline = true,
                confirmText = stringResource(R.string.chat_detail_report_submit),
                onConfirm = { reason -> reporting = null; submitReport(m, reason) },
                onDismiss = { reporting = null },
            )
        }
    }
}

/** 举报理由上限（服务端同，500 字）。 */
private const val REPORT_REASON_MAX = 500
