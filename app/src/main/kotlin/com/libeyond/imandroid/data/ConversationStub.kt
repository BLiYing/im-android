package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 本地还没有这个会话时（对方第一次给我发消息）造的**壳行**。标题/头像此时还不知道（空串），
 * 但 **`peerUid` 必须带上**——列表行靠它去好友表取名；两样都空就一路回退成 convId
 * （`u_1000156391_u_1199353701` 露在列表上，2026-10-02 用户报）。纯函数，独立可测。
 */
internal fun newConversationStub(owner: String, convId: String): ConversationEntity {
    val kind = NotificationRoute.resolveKind(convId, owner)
    return ConversationEntity(
        ownerUid = owner,
        convId = convId,
        isGroup = kind is NotificationRoute.Kind.Group,
        peerUid = (kind as? NotificationRoute.Kind.Private)?.peerUid.orEmpty(),
    )
}

/**
 * HTTP 会话快照落库时，若本地这一行**比快照新**（快照发出后又有 new_msg 到达并 bump 过），保留本地的
 * 预览 / 时间 / 序号 / 未读——否则旧快照整行覆盖会把刚到的消息回退（陌生人连发两条时必现：
 * 第一条造壳触发刷新，第二条在响应落库前到达）。标题/头像/备注/置顶/免打扰等仍以快照为准。
 */
internal fun ConversationEntity.keepNewerLocalTail(local: ConversationEntity?): ConversationEntity =
    if (local == null || local.lastConvSeq <= lastConvSeq) this else copy(
        lastContent = local.lastContent,
        lastContentType = local.lastContentType,
        lastFrom = local.lastFrom,
        lastFromNickname = local.lastFromNickname,
        lastRecalled = local.lastRecalled,
        lastSysEvent = local.lastSysEvent,
        lastSysArgs = local.lastSysArgs,
        lastSysSegments = local.lastSysSegments,
        lastTimestamp = local.lastTimestamp,
        lastConvSeq = local.lastConvSeq,
        unread = local.unread,
    )
