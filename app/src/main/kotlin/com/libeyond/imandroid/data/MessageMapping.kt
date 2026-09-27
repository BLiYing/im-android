package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.SysSegment

/**
 * 下行 [MessageData] → 落库行 [MessageEntity] 的字段映射。
 *
 * 从 [MessageRepository] 平移出来（2026-09-16，那个文件贴着 600 行硬闸），**行为未改**。
 * 单独一个文件也更好找：**「加一个随消息走的新字段」要动的七处里，这是第一处**
 * （其余六处见 `AckCarryOver` 的注释）——漏在这里的表现是"收到了但重进会话就没了"。
 */
internal fun MessageData.toEntity(owner: String) = MessageEntity(
    ownerUid = owner,
    convId = convId,
    convSeq = convSeq,
    serverMsgId = serverMsgId,
    sender = from,
    fromNickname = fromNickname,
    fromRole = fromRole,
    contentType = contentType,
    content = content,
    caption = caption,
    timestamp = timestamp,
    fileName = fileName,
    fileSize = fileSize,
    mediaW = mediaW,
    mediaH = mediaH,
    duration = duration,
    poster = poster,
    thumb = thumb,
    waveform = waveform,
    replyToConvSeq = replyToConvSeq,
    replySnapshot = replySnapshot,
    replyToFrom = replyToFrom,
    // 快照结构化标记落库：不落的话重进会话后引用条切了语言也还是中文
    replySnapshotKind = replySnapshotKind?.takeIf { it.isNotEmpty() },
    replySnapshotArgs = SysEvents.encodeArgs(replySnapshotArgs),
    recalledAt = recalledAt,
    deletedAt = deletedAt,
    editedAt = editedAt,
    pinnedAt = pinnedAt,
    forwardFrom = forwardFrom,
    groupId = groupId,
    // 分段落库：不落的话刷新/重进会话后系统消息退回"显真实昵称、不可点"
    sysSegments = sysSegments?.takeIf { it.isNotEmpty() }?.let {
        ProtocolJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SysSegment.serializer()), it)
    },
    // 结构化事件落库：不落的话重进会话后系统消息切了语言也还是中文
    sysEvent = sysEvent?.takeIf { it.isNotEmpty() },
    sysArgs = SysEvents.encodeArgs(sysArgs),
    // @提及片段落库：不落的话重进会话后 @ 不再高亮、也点不动（同上一条的坑）
    mentionSpans = Mention.encodeSpans(mentionSpans.orEmpty()),
)
