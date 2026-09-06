package com.libeyond.imandroid.data

/** 服务端下发的粗化档位（PROTOCOL §5.5）。 */
object PresenceStatus {
    const val ONLINE = "online"
    const val RECENTLY = "recently"       // 3 天内
    const val LAST_WEEK = "last_week"     // 7 天内
    const val LAST_MONTH = "last_month"   // 30 天内
    const val LONG_AGO = "long_ago"       // 更早或从未上线
}

/** 端上渲染出来的在线态。 */
sealed interface PresenceDisplay {
    data object Online : PresenceDisplay
    /** 有精确的最后在线时间。 */
    data class LastSeen(val atMillis: Long) : PresenceDisplay
    /** 只有粗档位，没有精确时间（服务端隐藏了 last_seen，或从未上线）。 */
    data class Coarse(val status: String) : PresenceDisplay
    /** 不显示（群聊 / 无权查看 / 数据缺失）。 */
    data object Hidden : PresenceDisplay
}

/**
 * 在线态渲染判据（PROTOCOL §5.5 租约模型）。
 *
 * ## 这不是「把 status 翻译成中文」那么简单
 * 服务端**只推上线、不推下线**——下线靠客户端的租约到期自然收敛。于是：
 *
 * 1. **客户端必须自己敲心跳重算**（iOS `startPresenceTick` / Web 30s `setInterval`）。
 *    「租约到期」是纯粹的时间流逝，**不触发任何回调、不改变任何状态**。不主动重算的话，
 *    用户静止不动时界面会**永远**停在「在线」——比有下线帧时更糟。
 *    这是本模型成立的前提，不是可选优化。
 *
 * 2. **`status=online` 但 `online_until` 缺失或已过期时，绝不能渲染成「在线」**。
 *    没有租约就没有到期时刻，那个「在线」再也不会被时间推翻。两端一律回落「最近在线」。
 */
object Presence {

    /** 客户端重算心跳间隔。与 iOS/Web 同为 30s。 */
    const val TICK_MS = 30_000L

    /**
     * 对端不在线时，每隔多久重拉一次 HTTP 快照。
     *
     * 为什么需要：上线广播的收件人取自会话成员，而单聊 topic 随首条消息才建立，
     * 故「刚加好友但从未聊过」的对端**收不到上线事件**，只靠帧永远升不回「在线」
     * （租约模型只会降级）。已在线时不拉——有租约又有帧，够用了。
     */
    const val SNAPSHOT_REFRESH_MS = 120_000L

    /**
     * 算出该显示成什么。
     *
     * @param status 服务端档位；空串表示没有数据
     * @param onlineUntil 在线租约到期毫秒；仅 status=online 时服务端下发
     * @param lastSeen 最后在线毫秒；0=未知/不可见
     * @param now 当前毫秒
     */
    fun display(status: String, onlineUntil: Long, lastSeen: Long, now: Long): PresenceDisplay {
        if (status.isEmpty()) return PresenceDisplay.Hidden

        if (status == PresenceStatus.ONLINE) {
            // 有效租约才算在线。缺租约 / 已过期 → 回落，**不得显示在线**。
            if (onlineUntil > 0 && now < onlineUntil) return PresenceDisplay.Online
            return if (lastSeen > 0) PresenceDisplay.LastSeen(lastSeen)
            else PresenceDisplay.Coarse(PresenceStatus.RECENTLY)
        }

        return if (lastSeen > 0) PresenceDisplay.LastSeen(lastSeen) else PresenceDisplay.Coarse(status)
    }

    /** 是否该去重拉快照（不在线时才拉）。 */
    fun needsSnapshotRefresh(display: PresenceDisplay): Boolean = display !is PresenceDisplay.Online

    /** 渲染成聊天页副标题的文案。与 iOS/Web 同一档位划分。 */
    fun label(display: PresenceDisplay, now: Long): String = when (display) {
        is PresenceDisplay.Online -> "在线"
        is PresenceDisplay.Hidden -> ""
        is PresenceDisplay.Coarse -> when (display.status) {
            PresenceStatus.RECENTLY -> "最近在线"
            PresenceStatus.LAST_WEEK -> "一周内在线"
            PresenceStatus.LAST_MONTH -> "一月内在线"
            else -> "很久以前在线"
        }
        is PresenceDisplay.LastSeen -> lastSeenLabel(display.atMillis, now)
    }

    private fun lastSeenLabel(at: Long, now: Long): String {
        val diff = now - at
        return when {
            diff < 60_000 -> "刚刚在线"
            diff < 60 * 60_000 -> "${diff / 60_000} 分钟前在线"
            diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)} 小时前在线"
            diff < 7L * 24 * 60 * 60_000 -> "${diff / (24 * 60 * 60_000)} 天前在线"
            else -> "很久以前在线"
        }
    }
}
