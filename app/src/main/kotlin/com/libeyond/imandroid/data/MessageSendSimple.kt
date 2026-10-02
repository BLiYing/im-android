package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MentionSpan

// 文本 / 卡片 / 通话记录三种「先落库再发帧」的发送入口，从 MessageService 搬出来只为控体量（那边贴 600 行硬闸）。
// 写成扩展函数：只用 MessageService 的 `internal` 成员（ownerProvider / repo / transmit），调用点只多一行 import。

/**
 * 发一条文本。先落库再发帧，杀进程也不会凭空消失。
 *
 * [mentions] / [mentionAll] / [mentionSpans] 是群 @提及（PROTOCOL §4.1，**仅群聊**；
 * 单聊带上会被服务端忽略）。三者由输入栏在**发送那一刻**按文本复核算出
 * （`data/Mention.kt` 的 resolveMentions / resolveMentionAll / resolveSpans），
 * 不是记着"用户点过谁"就发谁——用户手动删掉 token 就该自动不再 @ 他。
 */
suspend fun MessageService.sendText(
    convId: String,
    to: String,
    text: String,
    replyToConvSeq: Long? = null,
    mentions: List<String> = emptyList(),
    mentionAll: Boolean = false,
    mentionSpans: List<MentionSpan> = emptyList(),
) {
    val owner = ownerProvider() ?: return
    val p = repo.createPending(
        owner, convId, to, text, ContentType.TEXT, replyToConvSeq,
        mentionSpans = Mention.encodeSpans(mentionSpans),
        mentions = Mention.encodeMentions(mentions),
    )
    transmit(
        p.clientMsgId, convId, to, ContentType.TEXT, text, replyToConvSeq,
        mentions = mentions, mentionAll = mentionAll, mentionSpans = mentionSpans,
    )
}

/**
 * 发一条卡片消息（`contact` 个人名片 / `chat_record` 合并转发）。
 *
 * 与 [sendText] 只差 `contentType`——**内容就是那段 JSON 字符串**，服务端只透传。
 * 单独开一个方法而不是给 sendText 加参数：卡片的 `content` 不是给人读的文本，
 * 混在一起早晚会有人给它接引用/@提及那套文本逻辑。
 */
suspend fun MessageService.sendCard(convId: String, to: String, contentType: String, json: String) {
    val owner = ownerProvider() ?: return
    val p = repo.createPending(owner, convId, to, json, contentType)
    transmit(p.clientMsgId, convId, to, contentType, json, null)
}

/**
 * 发一条通话记录（`call`）。**`client_msg_id` 固定为 `call-<call_id>`**：主叫两台设备都收到结束事件、
 * 断线重发，服务端都凭它去重只落一条（设计 §2）。发送失败不影响通话，只留待发行 / 日志。
 */
suspend fun MessageService.sendCallRecord(convId: String, to: String, callId: String, json: String) {
    val owner = ownerProvider() ?: return
    val p = repo.createPending(owner, convId, to, json, ContentType.CALL, clientMsgId = CALL_CID_PREFIX + callId)
    transmit(p.clientMsgId, convId, to, ContentType.CALL, json, null)
}
