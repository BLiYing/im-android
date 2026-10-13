package com.libeyond.imandroid.fcm

/**
 * 一个会话那条通知里的一行：哪条消息、谁发的、说了什么、什么时候。
 * [albumId] 非空表示这是相册（宫格）的成员：同相册后到的成员**替换**这一行而不是另起一行（[ConversationLines.append]），
 * 文案是服务端算好的「[N 张图片]」。
 */
data class ConversationLine(
    val seq: Long, val sender: String, val text: String, val time: Long, val albumId: String = "",
)

/**
 * 一个会话那条通知的内容（PUSH_M5_DESIGN §3.7）：同主流 Android IM（WhatsApp / Telegram），
 * **一个会话一条通知**，展开能看到最近几条；锁屏隐藏内容时显示「N 条新消息」。
 *
 * [lines] 只留最近 [MAX_LINES] 条（系统展开后也就显示这么多）；[seqs] 是这条通知代表的**全部**消息序号，
 * 包括已被挤出 lines 的那些——条数 [total] 就是它的个数，所以撤回一条早被挤出去的消息、或别处读到一半，
 * 条数都扣得准（只记显示的几行的话，扣不到挤出去的那些）。
 *
 * 纯数据、不碰 Android 类型：撤回 / 别处已读后哪几行该去掉、条数怎么算，都在这里，有 JVM 单测。
 * 状态本身存在通知的 extras 里（[FcmNotifications]，读写走 [fromArrays] / 下面几个数组属性），
 * 进程被杀、被 FCM 重新拉起后照样接得上。
 */
data class ConversationLines(val lines: List<ConversationLine>, val seqs: List<Long>) {

    val isEmpty: Boolean get() = seqs.isEmpty()
    val total: Int get() = seqs.size

    /**
     * 新来一条。同一条（seq 相同，FCM 偶有重投）不重复计。同发送人、同相册的成员合成一行：
     * 九宫格逐条转发 9 张，通知里只有一行「[9张图片]」，不会排满 [MAX_LINES] 行「[图片]」；条数照样按 9 条计。
     */
    fun append(line: ConversationLine): ConversationLines {
        if (line.seq in seqs) return this
        val same = if (line.albumId.isEmpty()) -1
        else lines.indexOfFirst { it.albumId == line.albumId && it.sender == line.sender }
        val merged = if (same >= 0) lines.toMutableList().also { it[same] = line } else lines + line
        return ConversationLines(
            merged.sortedBy { it.seq }.takeLast(MAX_LINES),
            (seqs + line.seq).sorted().takeLast(MAX_TRACKED),
        )
    }

    /** 那条被撤回 / 为所有人删除：去掉那一行并少计一条。这条通知里本来没有它就不动。 */
    fun withoutSeq(seq: Long): ConversationLines {
        if (seq !in seqs) return this
        return ConversationLines(lines.filterNot { it.seq == seq }, seqs - seq)
    }

    /** 读到了 [upTo]：去掉 seq ≤ 它的行与计数。 */
    fun readThrough(upTo: Long): ConversationLines {
        if (seqs.none { it <= upTo }) return this
        return ConversationLines(lines.filter { it.seq > upTo }, seqs.filter { it > upTo })
    }

    // —— 存进通知 extras 的形状（Bundle 只认数组）——
    val lineSeqs: LongArray get() = lines.map { it.seq }.toLongArray()
    val lineSenders: Array<String> get() = lines.map { it.sender }.toTypedArray()
    val lineTexts: Array<String> get() = lines.map { it.text }.toTypedArray()
    val lineTimes: LongArray get() = lines.map { it.time }.toLongArray()
    val lineAlbums: Array<String> get() = lines.map { it.albumId }.toTypedArray()
    val allSeqs: LongArray get() = seqs.toLongArray()

    companion object {
        const val MAX_LINES = 6

        /** 计数最多记这么多条——一个会话攒到上千条未读通知时，「999+」与精确数已经没区别。 */
        const val MAX_TRACKED = 999

        val EMPTY = ConversationLines(emptyList(), emptyList())

        /**
         * 从通知 extras 里读回来。任一行数组缺失或长度对不上就当认不出（返回 null，调用方宁可不动那条通知）；
         * 没有全部序号（本功能早一版写的通知）时退回只算显示的那几行。
         */
        fun fromArrays(
            lineSeqs: LongArray?, senders: Array<String>?, texts: Array<String>?, times: LongArray?, allSeqs: LongArray?,
            albums: Array<String>? = null,
        ): ConversationLines? {
            if (lineSeqs == null || senders == null || texts == null || times == null) return null
            if (senders.size != lineSeqs.size || texts.size != lineSeqs.size || times.size != lineSeqs.size) return null
            // 相册 id 数组是后加的：缺失 / 长度对不上（旧版本发的通知）当都不是相册成员，不影响其余几行。
            val albumIds = albums?.takeIf { it.size == lineSeqs.size }
            val lines = lineSeqs.indices.map {
                ConversationLine(lineSeqs[it], senders[it], texts[it], times[it], albumIds?.get(it).orEmpty())
            }
            val seqs = (allSeqs?.toList() ?: emptyList()).union(lineSeqs.toList()).sorted()
            return ConversationLines(lines, seqs)
        }
    }
}
