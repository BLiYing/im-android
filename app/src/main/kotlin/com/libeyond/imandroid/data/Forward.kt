package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MentionSpan

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

    /**
     * 一次多选最多几条消息。三端同为 100（与举报、收藏共用这个上限）。
     * 勾选的判据与写入口在 [ChatSelection]——**按 conv_seq 记且连消息一起存**，理由见那里。
     */
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

    /** 多选一个转发目标会话：到上限就拒（返回 null）。**取消选择永远允许**——
     *  已经选满时若连取消都拒，用户就被卡死在选满了又改不了。 */
    fun toggleTarget(selected: Set<String>, convId: String, max: Int = MAX_TARGETS): Set<String>? {
        if (convId in selected) return selected - convId
        if (selected.size >= max) return null
        return selected + convId
    }

    /**
     * 转发选择页里列哪些会话（iOS `IMForwardPickerViewController` 的 `loadConversations` + `applyFilter`）。
     *
     * ① **剔除系统通知单聊**：那是只读会话，服务端直接拒发往 system 的消息
     *    （IMServer `docs/design/SYSTEM_NOTICE_SESSION_DESIGN.md` §2.2），列出来只会点了报错。
     *    群聊不看 peer——群会话的 peer 无意义，所以先判 isGroup。
     * ② 按搜索词过滤：匹配显示名与单聊对端 uid（uid 只参与匹配、不展示，同 iOS）。
     */
    fun pickable(convs: List<ConversationEntity>, query: String): List<ConversationEntity> =
        convs.filter { c ->
            (c.isGroup || !DetailActions.isSystemPeer(c.peerUid)) &&
                ListSearch.matches(query, listOf(titleOf(c), c.peerUid))
        }

    /** 行上显示的名字。末级**不落内部 ID**（10 位随机数字对人没有意义，见 [DisplayName]）。 */
    fun titleOf(c: ConversationEntity): String =
        c.title.ifBlank { if (c.isGroup) DisplayName.UNNAMED_GROUP else DisplayName.UNNAMED }

    /**
     * 按**勾选顺序**取回目标会话（iOS `_selected` 是有序数组，发送顺序即勾选顺序）。
     * 搜索只影响看得见哪些行、不影响已选——所以从全量里找，不从当前可见的行里找。
     */
    fun targetsInOrder(selectedIds: List<String>, convs: List<ConversationEntity>): List<ConversationEntity> {
        val byId = convs.associateBy { it.convId }
        return selectedIds.mapNotNull { byId[it] }
    }

    /**
     * 转发时必须跟着走的媒体元数据（对端 iOS `IMMediaAttributes`、im-web `useForward.ts` 的
     * `poster/thumb/mediaW/mediaH/duration`）。
     *
     * ### 为什么这也要是纯函数
     * **漏带是静默的**：编译过、测试过、消息也确实发出去了，只有收件人那一侧看得出来——
     * 视频没有 `poster` 就没有封面（本端 `VideoContent` 只剩一块磨砂），没有 `media_w/media_h`
     * 就按「像素未知」走 [MediaDisplaySize] 的方块兜底，于是一条 16:9 的视频变成 180×180 的方块。
     * **而且事后补不回来**：这几个字段是随消息落库的，收端不会再去问一遍。
     *
     * im-web 已经为此改过一轮（`useForward.ts` 里写着同一句理由），iOS 的 `forwardEchoContent:`
     * 也一直带着 attributes——**本端是这条对称链上唯一没跟的一端**（IMServer `docs/SYMMETRY.md`）。
     */
    data class Attributes(
        val mediaW: Int? = null,
        val mediaH: Int? = null,
        val duration: Int? = null,
        val poster: String? = null,
        val thumb: String? = null,
        /**
         * 语音振幅指纹（仅 voice）。**不带就是转发出去的语音在收端只有等高条纹**——
         * 服务端一直收这个字段（`protocol.SanitizeVoiceWaveform`，`gateway/voice_flow_test.go` 钉着），
         * iOS 也一直带，2026-09-16 本端补上（`SendMsgData` 早有这一列，不是协议限制）。
         */
        val waveform: String? = null,
        /**
         * 图说里的 @ 提及（对齐 iOS `forwardAttributesForMessage:stripCaption:` 的 `mentions`/`mentionSpans`）。
         * **只在图片/视频这两种类型上带**——文本消息 iOS 走的是另一条不带 attrs 的路径，
         * 本来就不转发提及，本端不该多做（`attributesOf` 里按 contentType 收窄）。
         * 只带 [mentions]（谁收到强提醒），**不带 mentionAll**：@所有人需要目标群的群主/管理员权限，
         * 转发不该在新会话里再次触发全员强提醒（与 iOS 同一条取舍）。
         */
        val mentions: List<String>? = null,
        /** 与 [mentions] 同一份数据的位置信息，喂给收端做高亮（caption 原样转走，偏移仍对得上）。 */
        val mentionSpans: List<MentionSpan>? = null,
    )

    /**
     * 这条消息转发出去时要带上的元数据。
     *
     * 空串一律归一成 `null`：协议按 `omitempty` 读，空串与缺省等价但会白占字节，
     * 而端上 `poster.isNullOrBlank()` 与 `poster == null` 两种判法并存时容易写岔。
     */
    fun attributesOf(msg: MessageEntity): Attributes {
        val spans = if (msg.contentType == ContentType.IMAGE || msg.contentType == ContentType.VIDEO) {
            Mention.parseSpans(msg.mentionSpans).takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return Attributes(
            mediaW = msg.mediaW?.takeIf { it > 0 },
            mediaH = msg.mediaH?.takeIf { it > 0 },
            duration = msg.duration?.takeIf { it > 0 },
            poster = msg.poster?.takeIf { it.isNotBlank() },
            thumb = msg.thumb?.takeIf { it.isNotBlank() },
            waveform = msg.waveform?.takeIf { it.isNotBlank() },
            mentionSpans = spans,
            mentions = spans?.map { it.uid }?.distinct()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
        )
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
            msg.contentType != ContentType.SYSTEM &&
            msg.contentType != ContentType.CALL
}
