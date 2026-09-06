package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 某人的在线态原始数据（档位 + 租约 + 最后在线）。 */
data class PresenceSnapshot(
    val status: String = "",
    val onlineUntil: Long = 0,
    val lastSeen: Long = 0,
)

/**
 * 在线态与「正在输入」的内存态。
 *
 * **刻意不落库**：两者都是 best-effort 临时态（PROTOCOL §5.5「不持久化、不进离线同步」），
 * 落库只会让重启后显示一份过期的「在线」。
 */
class PresenceStore {

    private val log = IMLog.tag("IM.Presence")

    private val _presence = MutableStateFlow<Map<String, PresenceSnapshot>>(emptyMap())
    val presence: StateFlow<Map<String, PresenceSnapshot>> = _presence.asStateFlow()

    /** convId → (uid, 到期毫秒)。「正在输入」显示 5 秒后自动消失。 */
    private val _typing = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    val typing: StateFlow<Map<String, Pair<String, Long>>> = _typing.asStateFlow()

    /** 当前界面要显示在线态的 uid 全集——**全量替换语义**，watch 帧直接发它。 */
    private var watchSet: Set<String> = emptySet()

    fun applyPresence(uid: String, snap: PresenceSnapshot) {
        if (uid.isEmpty()) return
        _presence.value = _presence.value + (uid to snap)
    }

    /** 会话列表/资料页的 HTTP 快照——**初始值来源**，presence 帧只做其后的增量更新。 */
    fun seed(uid: String, status: String, onlineUntil: Long, lastSeen: Long) {
        if (uid.isEmpty() || status.isEmpty()) return
        applyPresence(uid, PresenceSnapshot(status, onlineUntil, lastSeen))
    }

    fun snapshotOf(uid: String): PresenceSnapshot = _presence.value[uid] ?: PresenceSnapshot()

    fun onTyping(convId: String, from: String, now: Long = System.currentTimeMillis()) {
        if (convId.isEmpty() || from.isEmpty()) return
        _typing.value = _typing.value + (convId to (from to now + TYPING_TTL_MS))
    }

    /** 取当前仍有效的「正在输入」者；过期自动清。 */
    fun typingIn(convId: String, now: Long = System.currentTimeMillis()): String? {
        val e = _typing.value[convId] ?: return null
        if (now >= e.second) {
            _typing.value = _typing.value - convId
            return null
        }
        return e.first
    }

    /**
     * 更新要 watch 的集合。返回 true 表示集合变了、需要发帧。
     *
     * **注意即使集合没变也可能要重发**：服务端对每次 watch（含集合不变的重发）都回快照，
     * 客户端进入/返回聊天页正靠这个刷新，故调用方在「进入界面」「重连后」两个时机
     * 应强制发一次（`force=true`），不要因为集合相同就跳过。
     */
    fun updateWatch(set: Set<String>, force: Boolean = false): Boolean {
        val changed = set != watchSet
        watchSet = set
        return changed || force
    }

    fun currentWatchSet(): List<String> = watchSet.toList().take(WATCH_LIMIT)

    companion object {
        /** 「正在输入」显示时长。 */
        const val TYPING_TTL_MS = 5_000L
        /** 单连接 watch 上限（PROTOCOL §5.5）。 */
        const val WATCH_LIMIT = 512
    }
}
