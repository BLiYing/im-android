package com.libeyond.imandroid.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.sendText
import com.libeyond.imandroid.sdk.IMClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 输入栏点「发送」（从 `ChatHost` 拆出，那份文件贴着 600 行硬闸）。
 *
 * - **编辑态**：发 `msg_op edit`，**不发新消息**；空文本什么都不做、保持编辑态（[ChatMessageOps.commitEdit]）。
 * - 粘贴条上挂着的图**随这一次发送一起走**（iOS pasteBar 同）；先发图再发文字——两条消息，顺序按用户看到的先后。
 * - 文字：按**文本现状**复核 @ 收件人（点过又把 token 删掉的人不该收到强提醒），带上被引用的那条。
 *   回到最新不在这里做，收口在 `onOutgoingEcho`（发图/文件/名片/转发也要回来）。
 */
internal fun sendComposerInput(
    client: IMClient,
    conv: ConversationEntity,
    scope: CoroutineScope,
    ops: ChatMessageOps,
    mediaSend: MediaSendFlow,
    paste: PasteImages,
    mention: MentionComposer,
    input: TextFieldValue,
    replyTo: MessageEntity?,
    onToast: (String) -> Unit,
    clearInput: () -> Unit,
    clearReply: () -> Unit,
) {
    if (ops.editing != null) {
        if (ops.commitEdit(input.text.trim())) clearInput()
        return
    }
    if (!paste.isEmpty) {
        val pastedImages = paste.items
        paste.clear()
        scope.launch { mediaSend.send(pastedImages, sendOriginal = false) { onToast(it) } }
    }
    val text = input.text.trim()
    if (text.isEmpty()) return
    val at = mention.resolve(text)
    clearInput()
    mention.clear()
    clearReply()
    scope.launch {
        client.messages.sendText(
            convId = conv.convId,
            to = if (conv.isGroup) conv.convId else conv.peerUid,
            text = text,
            replyToConvSeq = replyTo?.convSeq,
            mentions = at.mentions,
            mentionAll = at.mentionAll,
            mentionSpans = at.spans,
        )
    }
}
