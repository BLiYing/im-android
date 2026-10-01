package com.libeyond.imandroid.fcm

/** 一个会话那条通知里的一行：哪条消息、谁发的、说了什么、什么时候。 */
data class ConversationLine(val seq: Long, val sender: String, val text: String, val time: Long)

/**
 * 一个会话那条通知的内容（PUSH_M5_DESIGN §3.7）：同主流 Android IM（WhatsApp / Telegram），
 * **一个会话一条通知**，展开能看到最近几条；锁屏隐藏内容时显示「N 条新消息」。
 *
 * [lines] 只留最近 [MAX_LINES] 条（系统展开后也就显示这么多）；[total] 是这条通知累计代表的消息数，
 * 可以大于 lines.size——锁屏的「N 条」与桌面角标（`setNumber`）用它。
 *
 * 纯数据、不碰 Android 类型：撤回 / 别处已读后哪几行该去掉、条数怎么算，都在这里，有 JVM 单测。
 * 状态本身存在通知的 extras 里（[FcmNotifications]），进程被杀、被 FCM 重新拉起后照样接得上。
 */
data class ConversationLines(val lines: List<ConversationLine>, val total: Int) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 新来一条。同一条（seq 相同，FCM 偶有重投）不重复计。 */
    fun append(line: ConversationLine): ConversationLines {
        if (lines.any { it.seq == line.seq }) return this
        val next = (lines + line).sortedBy { it.seq }.takeLast(MAX_LINES)
        return ConversationLines(next, maxOf(total + 1, next.size))
    }

    /** 那条被撤回 / 为所有人删除：去掉那一行。不在列表里（太早、早被挤出去了）就不动。 */
    fun withoutSeq(seq: Long): ConversationLines {
        if (lines.none { it.seq == seq }) return this
        val next = lines.filterNot { it.seq == seq }
        return ConversationLines(next, maxOf(total - 1, next.size))
    }

    /**
     * 读到了 [upTo]：去掉 seq ≤ 它的行。被挤出列表的都比留着的更早，所以只要留着的里面有被读掉的，
     * 挤出去的那些也一定读过了，条数就等于剩下的行数；一行都没读掉则条数不变。
     */
    fun readThrough(upTo: Long): ConversationLines {
        if (lines.none { it.seq <= upTo }) return this
        val next = lines.filter { it.seq > upTo }
        return ConversationLines(next, next.size)
    }

    companion object {
        const val MAX_LINES = 6
        val EMPTY = ConversationLines(emptyList(), 0)
    }
}
