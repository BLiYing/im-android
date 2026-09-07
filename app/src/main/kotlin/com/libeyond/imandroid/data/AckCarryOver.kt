package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity

/**
 * ack **不回带**的那些字段，必须从待发行里补回来。
 *
 * ### 为什么值得单独抽出来
 * 这个坑本仓踩了**三次**，每次都是同一个形状：
 *
 * | 时间 | 漏掉的字段 | 表现 |
 * |---|---|---|
 * | M4-3 | `forward_from` | 自己转发的消息，**自己这侧**看不到「转发自 X」，对端看得到 |
 * | M4+ | `group_id` | 一组图收到 ack 后从宫格**散回单张**，对端仍是宫格 |
 * | 2026-09-07 | `media_w/h`·`duration`·`poster` | 自己发的视频**自己这侧**没封面没时长，对端正常 |
 *
 * 三次的共同点：**只在发送者自己那一侧坏**，对端一切正常——所以自测时极难发现，
 * 除非专门去看自己发出去的那条。而且每次都是"新加一个随消息走的字段"时忘了这一步。
 *
 * 所以把它变成一个有测试守着的显式清单：[CARRIED]。
 * `AckCarryOverTest` 用反射比对两个实体的同名字段，**新加字段却没在这里做决定就会红**。
 */
internal object AckCarryOver {

    /**
     * 从待发行取的字段名。改这里就是改契约——
     * 加字段前先问：ack 回带它吗？回带就不用进来，不回带就必须进来。
     */
    val CARRIED = setOf(
        "contentType", "content", "caption", "fileName", "fileSize", "replyToConvSeq",
        "forwardFrom", "groupId", "mediaW", "mediaH", "duration", "poster",
    )

    /** 把待发行里的字段补进 ack 生成的行。[cached] 为 null（罕见）时原样返回。 */
    fun enrich(row: MessageEntity, cached: PendingMessageEntity?): MessageEntity {
        if (cached == null) return row
        return row.copy(
            contentType = cached.contentType,
            content = cached.content,
            caption = cached.caption,
            fileName = cached.fileName,
            fileSize = cached.fileSize,
            replyToConvSeq = cached.replyToConvSeq,
            forwardFrom = cached.forwardFrom,
            groupId = cached.groupId,
            mediaW = cached.mediaW,
            mediaH = cached.mediaH,
            duration = cached.duration,
            poster = cached.poster,
        )
    }
}
