package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ConvBumpData
import com.libeyond.imandroid.sdk.protocol.ConvBumpItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `conv_bump`（超级群「有新消息」信号，OFFLINE_BACKLOG_DESIGN §4.8 C4）。
 *
 * **只记位点、刷列表行，不补拉正文**：2 万人群正文逐个推下来出网带宽算术上不成立，所以服务端只推信号；
 * 正文在打开会话时才取（`window_req`）。此前 Android 把这帧落进忽略分支——超级群的列表行收到信号
 * 既不刷新预览也不记最新位点。对称兄弟：iOS `IMSocketManager.m` 的 `kIMTypeConvBump` 分支、Web `imSdk` 的 `catchUpOnBump`。
 */
internal suspend fun MessageService.applyConvBump(owner: String, data: ConvBumpData) {
    data.items.forEach { repo.applyBumpSignal(owner, it) }
    if (data.items.isEmpty()) return
    // 未读数以服务端为准：整表刷一次（合并成一次，别每帧一次——服务端本就把每连接每秒的 bump 合并过一道）
    if (bumpRefreshScheduled) return
    bumpRefreshScheduled = true
    scope.launch {
        delay(BUMP_REFRESH_DELAY_MS)
        bumpRefreshScheduled = false
        refreshConversations()
    }
}

/** 与会话列表节流同量级（iOS 列表侧 0.4s）。 */
private const val BUMP_REFRESH_DELAY_MS = 400L

/**
 * 一条信号落到本地：记 head（只增不减），并把列表行的预览/最新位点刷成信号带的那一条。
 * 不碰未读（走整表刷新）。会话行还没建就只记 head 的尝试落空——整表刷新会建它。
 */
internal suspend fun MessageRepository.applyBumpSignal(owner: String, item: ConvBumpItem) {
    if (item.convId.isEmpty() || item.latestSeq <= 0) return
    noteHead(owner, item.convId, item.latestSeq)
    val c = conversations.byId(owner, item.convId) ?: return
    if (item.latestSeq <= c.lastConvSeq) return // 不比行里已有的新：别把预览刷回旧的（乱序到达的旧信号）
    conversations.upsert(
        c.copy(
            lastContent = item.preview,
            lastContentType = "text",
            lastFrom = item.from,
            lastFromNickname = item.fromNickname,
            lastRecalled = false,
            lastSysEvent = "", lastSysArgs = "", lastSysSegments = "",
            lastTimestamp = maxOf(c.lastTimestamp, System.currentTimeMillis()),
            lastConvSeq = item.latestSeq,
        ),
    )
}
