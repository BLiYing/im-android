package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity

/**
 * 转发溯源名（M4-3，PROTOCOL §4.3 `forward_from`）。
 *
 * 抽成纯函数是因为这里有**两条一破就是线上事故**的纪律，必须能被测试钉住：
 *
 * ① **只能用公开名，绝不能带备注。** 这个字符串**会原样发给收件人**——带备注就是
 *    把「我给他起的外号」发出去。im-web 与 iOS 都各为此出过一次事故
 *    （见 IMServer `docs/UI.md` 隐私红线：显示名会被写进要发出去的字节的地方，一律走公开名）。
 *    本端目前没有备注层，但**接备注时会经过这里**，所以纪律先立在这。
 *
 * ② **转发链保留最初作者。** 转发一条已被转发的消息，写的是最初作者而不是中间人
 *    ——否则转三手之后「转发自」变成一串跟内容毫无关系的人。
 *    对端口径逐字一致：`m.forwardFrom || m.fromNickname || m.from`（im-web `useForward.ts`）。
 *
 * ③ **末级不落 uid 就只能落空**——但 uid 是 10 位随机内部 ID，摆在「转发自」后面
 *    既难看也无意义。所以末级取 uid 是**刻意的**（与 Web 同）：有总比没有强，
 *    且这条路只在昵称缺失时才走到。
 */
object Forward {

    /** 服务端对 `forward_from` 限长 40（§4.3）；超长在端上先截，别等服务端拒。 */
    const val MAX_LEN = 40

    /** 一次最多选几个转发目标会话。与 iOS/Web 同为 9。 */
    const val MAX_TARGETS = 9

    /** 一次多选最多几条消息。三端同为 100（与举报、收藏共用这个上限）。 */
    const val MAX_SELECTION = 100

    /**
     * 算这条消息转发出去时该写的「转发自」。
     *
     * @param myUid 我自己的 uid——**自己发的消息被自己转发**时，溯源名是我的公开名，
     *   不能写「我」：那是**看的人**才成立的称呼，而这串字会烧进发出去的内容。
     *   im-web 正为此出过事（2026-09-05 用户实测：收件人看到一排「我」）。
     * @param myPublicName 我的公开显示名（昵称 → @username → 未命名用户，由调用方算好）。
     */
    fun originOf(msg: MessageEntity, myUid: String, myPublicName: String): String {
        val raw = when {
            !msg.forwardFrom.isNullOrBlank() -> msg.forwardFrom!!   // ② 转发链保留最初作者
            msg.sender == myUid -> myPublicName                      // 自己发的
            !msg.fromNickname.isNullOrBlank() -> msg.fromNickname!!
            else -> msg.sender                                       // ③ 末级兜底
        }
        return raw.take(MAX_LEN)
    }

    /** 多选加一条消息：到上限就拒（返回 null），由调用方吐司。 */
    fun toggleCapped(selected: Set<Long>, seq: Long, max: Int = MAX_SELECTION): Set<Long>? {
        if (seq in selected) return selected - seq
        if (selected.size >= max) return null
        return selected + seq
    }

    /** 多选一个转发目标会话：到上限就拒（返回 null）。**取消选择永远允许**——
     *  已经选满时若连取消都拒，用户就被卡死在选满了又改不了。 */
    fun toggleTarget(selected: Set<String>, convId: String, max: Int = MAX_TARGETS): Set<String>? {
        if (convId in selected) return selected - convId
        if (selected.size >= max) return null
        return selected + convId
    }

    /**
     * 能不能转发这一条。撤回/删除/系统消息不可转发（与 iOS/Web 同）；
     * **未确认的消息（convSeq<=0）也不行**——它在服务端还不存在，转出去的是个幻影。
     */
    fun canForward(msg: MessageEntity): Boolean =
        msg.convSeq > 0 &&
            msg.content.isNotBlank() &&
            (msg.recalledAt ?: 0) <= 0 &&
            (msg.deletedAt ?: 0) <= 0 &&
            msg.contentType != com.libeyond.imandroid.sdk.protocol.ContentType.SYSTEM
}
