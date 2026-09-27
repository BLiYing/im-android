package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 多选底栏四个动作（转发 / 举报 / 收藏 / 删除）里**要被测试钉住的判据与编码**（2026-09-10 用户报 #3）。
 *
 * 对端 iOS `IMChatViewController+Selection.m`，Web `useForward.ts` / `messageContent.ts`。
 * 抽成纯函数不是为了好看：这里有三条**一破就是线上事故**、而界面上一点都看不出来的纪律——
 *
 * ① **举报只能对同一个人**：服务端 `Subject()` 只按首条反查处置对象，混着两个人的证据会让管理员的
 *    一键封号落到"第一条那个人"头上；
 * ② **合并转发卡片是发出去的字节**：条目名只能是公开名（不带备注、不写「我」、不落 uid），
 *    发送者键 `u` 只能是卡片内匿名序号（`s1`/`s2`），不能是真 uid——真 uid 发给不在群里的人等于
 *    绕过 `GET /users/{id}` 的不可枚举防线（Web `buildRecordSenderKeys` 注释，2026-08-31）；
 * ③ **失效媒体不转出去**：对端点开必 404，而且要如实告诉用户少了几条。
 */
object SelectionActions {

    // ————————————————— 举报 —————————————————

    /**
     * 这批消息能不能批量举报：能则回**那个唯一发送者的 uid**，否则 null。
     *
     * 三个条件缺一不可（同 iOS `reportableSenderForMessages:`）：非空、不含我自己发的、全部来自同一个人。
     * 另加一条防御：未确认的本地件（`convSeq<=0`）服务端定位不到，整批不可举报。
     */
    fun reportableSender(msgs: List<MessageEntity>, myUid: String): String? {
        if (msgs.isEmpty()) return null
        var sender: String? = null
        for (m in msgs) {
            if (m.convSeq <= 0) return null
            if (m.sender.isBlank() || m.sender == myUid) return null
            if (sender == null) sender = m.sender else if (sender != m.sender) return null
        }
        return sender
    }

    /**
     * 举报钮**灰着被点**时给的原因；可举报或什么都没勾时回 null（0 选中全栏皆灰，不单独解释举报）。
     * 不给原因的话用户只会反复戳一个灰按钮（iOS `reportHintTapped:`）。
     */
    fun reportBlockedHint(msgs: List<MessageEntity>, myUid: String): String? {
        if (msgs.isEmpty() || reportableSender(msgs, myUid) != null) return null
        return if (msgs.any { it.sender == myUid }) {
            Str.s(R.string.chat_select_report_own)
        } else {
            Str.s(R.string.chat_select_report_multi)
        }
    }

    /**
     * 举报确认框标题。[who] 用**本机显示名**（备注优先）——这句话只给我自己看、不随请求发出，
     * 与合并转发"会发出去必须用公开名"刻意分叉（同 iOS）。
     */
    fun reportTitle(count: Int, who: String): String =
        if (count <= 1) Str.s(R.string.chat_report_single_title) else Str.p(R.plurals.chat_report_multi_title, count, who, count)

    // ————————————————— 逐条转发 —————————————————

    /**
     * 是不是**已失效的媒体**（服务端已清理，转出去对端必 404）。只有图片/视频/文件有这回事。
     * @param isExpiredUrl `(url, isVideo) -> 已失效`，由调用方接到下载器的失效登记上。
     */
    fun isExpiredMedia(msg: MessageEntity, isExpiredUrl: (String, Boolean) -> Boolean): Boolean {
        val media = msg.contentType == ContentType.IMAGE ||
            msg.contentType == ContentType.VIDEO ||
            msg.contentType == ContentType.FILE
        return media && msg.content.isNotBlank() && isExpiredUrl(msg.content, msg.contentType == ContentType.VIDEO)
    }

    /**
     * 单条转发撞上失效媒体时的提示（iOS `presentForwardPickerForMessage:` + `expiredNounForMessage:`）。
     * 单条是**拦下不转**，不像多选那样跳过其余照发。
     */
    fun expiredForwardText(contentType: String): String {
        val noun = when (contentType) {
            ContentType.VIDEO -> Str.s(R.string.common_video)
            ContentType.FILE -> Str.s(R.string.common_file)
            else -> Str.s(R.string.common_image)
        }
        return Str.s(R.string.chat_forward_expired_noun, noun)
    }

    /**
     * 相册整体转发：**同一原相册被选了 ≥2 张**的，给它们分一个新的共享 group_id，收端重新聚成宫格；
     * 只选 1 张或不是相册成员的不分（单发）。返回 `conv_seq → 新 group_id`。
     *
     * **每个目标会话调一次**（[newId] 每次给新的）：不能沿用原 ID——原相册里没选的那几张不在新会话里；
     * 也不能多个目标共用——不同会话的转发相册互不相干（同 iOS `forwardMessages:perMessageToConversations:`）。
     */
    fun regroupAlbums(msgs: List<MessageEntity>, newId: () -> String): Map<Long, String> {
        val members = msgs.filter { AlbumLayout.isAlbumMember(it.contentType, it.groupId) }
        val counts = members.groupingBy { it.groupId!! }.eachCount()
        val fresh = HashMap<String, String>()
        return members
            .filter { (counts[it.groupId] ?: 0) >= 2 }
            .associate { it.convSeq to fresh.getOrPut(it.groupId!!, newId) }
    }

    /** 转发回执：「已转发」/「已转发到 N 个会话」，有失效跳过的再补一句（同 iOS 文案）。 */
    fun forwardDoneText(targets: Int, expiredSkipped: Int): String {
        val base = if (targets > 1) {
            Str.p(R.plurals.favorites_forward_success_count, targets, targets)
        } else {
            Str.s(R.string.favorites_forward_success_single)
        }
        return base + expiredSuffix(expiredSkipped)
    }

    fun expiredSuffix(n: Int): String =
        if (n > 0) Str.p(R.plurals.chat_forward_expired_suffix, n, n) else ""

    // ————————————————— 合并转发 —————————————————

    /**
     * 真正入卡的消息：撤回 / 删除 / 系统 / 空内容 / 未确认 / 失效媒体都剔掉（同 iOS `mergedForwardJSONForMessages:`）。
     * 先筛再编号——匿名序号表必须按**入卡的**消息算，否则 s1/s2 会跳号。
     */
    fun mergeable(msgs: List<MessageEntity>, isExpired: (MessageEntity) -> Boolean): List<MessageEntity> =
        msgs.filter {
            (it.recalledAt ?: 0) <= 0 &&
                (it.deletedAt ?: 0) <= 0 &&
                it.contentType != ContentType.SYSTEM &&
                it.contentType != ContentType.CALL &&
                it.content.isNotBlank() &&
                it.convSeq > 0 &&
                !isExpired(it)
        }

    /**
     * 卡片标题。群聊固定「群聊的聊天记录」——**不写真实群名**：收件人往往不在那个群里，
     * 而 `t` 会被冻结进消息、还能被再转发，泄露无从回收。单聊写双方**公开名**。
     * 与 iOS `IMChatRecordTitle` / Web `chatRecordTitle` 逐字对齐。
     */
    fun chatRecordTitle(isGroup: Boolean, peerName: String?, myName: String?): String {
        if (isGroup) return "群聊的聊天记录"
        val peer = peerName.orEmpty().trim()
        val me = myName.orEmpty().trim()
        return when {
            peer.isNotEmpty() && me.isNotEmpty() -> "${peer}和${me}的聊天记录"
            peer.isNotEmpty() || me.isNotEmpty() -> "${peer.ifEmpty { me }}的聊天记录"
            else -> "聊天记录"
        }
    }

    /** 卡片内匿名发送者序号：真 uid → `s1`/`s2`/…，按首次出现顺序，空 uid 跳过（Web `buildRecordSenderKeys`）。 */
    fun recordSenderKeys(uids: List<String>): Map<String, String> {
        val keys = LinkedHashMap<String, String>()
        for (u in uids) {
            if (u.isBlank() || u in keys) continue
            keys[u] = "s${keys.size + 1}"
        }
        return keys
    }

    /**
     * 单聊对方的**公开名**：昵称 → @句柄 → 对方消息上带的昵称快照 → 空串。
     * **绝不回落 uid、绝不用会话标题**（标题是备注优先的）；空串让 [chatRecordTitle] 自己降级。
     */
    fun peerPublicName(nickname: String?, username: String?, fromNickname: String?): String = when {
        !nickname.isNullOrBlank() -> nickname.trim()
        !username.isNullOrBlank() -> "@${username.trim()}"
        !fromNickname.isNullOrBlank() -> fromNickname.trim()
        else -> ""
    }

    /**
     * 合并转发条目的发送者名与头像来源。**全部是公开信息**——这些字符串会烧进发出去的 JSON。
     *
     * @param myName 我的公开名（`IMClient.myPublicName()`），**不是「我」**：那是看的人才成立的称呼
     *   （iOS/Web 2026-09-05 同一处事故：收件人看到一排「我」）。
     * @param memberNames 群成员公开显示名（群昵称 → 昵称 → @句柄），不含任何备注。
     * @param memberAvatars 群成员头像相对路径。
     * @param peerAvatar 单聊对方头像；「我」那一方本页拿不到，留空让读端按名字画首字母色块。
     */
    data class RecordSenders(
        val myUid: String,
        val myName: String,
        val isGroup: Boolean,
        val peerUid: String = "",
        val peerPublic: String = "",
        val peerAvatar: String = "",
        val memberNames: Map<String, String> = emptyMap(),
        val memberAvatars: Map<String, String> = emptyMap(),
    ) {
        fun nameOf(m: MessageEntity): String = when {
            m.sender == myUid -> myName.ifBlank { DisplayName.UNNAMED }
            isGroup -> memberNames[m.sender]?.takeIf { it.isNotBlank() }
                ?: m.fromNickname?.takeIf { it.isNotBlank() }
                ?: DisplayName.UNNAMED
            else -> peerPublic.ifBlank { m.fromNickname?.takeIf { it.isNotBlank() } ?: DisplayName.UNNAMED }
        }

        fun avatarOf(m: MessageEntity): String = when {
            m.sender.isBlank() || m.sender == myUid && !isGroup -> ""
            isGroup -> memberAvatars[m.sender].orEmpty()
            m.sender == peerUid -> peerAvatar
            else -> ""
        }
    }

    /**
     * 合并转发卡片 JSON（`content_type=chat_record`）：
     * `{t, items:[{n, ct, c, ts?, u?, a?, fn?/fs?(文件), d?/w?(语音), cap?}]}`。键与 iOS/Web 同约定，
     * 服务端不参与（PROTOCOL「合并转发卡片的条目结构」）——一端改了另两端必须跟。
     *
     * **空值一律省略**（不发 `"a":""`），读端本来就要能吃缺字段的老记录。
     * @param kept 已经过 [mergeable] 筛过的消息。
     */
    fun encodeRecord(title: String, kept: List<MessageEntity>, senders: RecordSenders): String {
        val keys = recordSenderKeys(kept.map { it.sender })
        return buildJsonObject {
            put("t", title)
            put("items", buildJsonArray {
                kept.forEach { m -> add(encodeItem(m, keys, senders)) }
            })
        }.toString()
    }

    private fun encodeItem(m: MessageEntity, keys: Map<String, String>, senders: RecordSenders) = buildJsonObject {
        put("n", senders.nameOf(m))
        put("ct", m.contentType.ifBlank { ContentType.TEXT })
        put("c", m.content)
        if (m.timestamp > 0) put("ts", m.timestamp)
        keys[m.sender]?.let { put("u", it) }
        senders.avatarOf(m).takeIf { it.isNotBlank() }?.let { put("a", it) }
        if (m.contentType == ContentType.FILE) {
            val fn = m.fileName?.takeIf { it.isNotBlank() } ?: m.content.substringAfterLast('/')
            if (fn.isNotBlank()) put("fn", fn)
            if ((m.fileSize ?: 0) > 0) put("fs", m.fileSize)
        }
        // 语音带时长与波形——缺了收端记录卡只能画一个 0:00 的空播放器。audio = 旧命名兼容
        if (m.contentType == ContentType.VOICE || m.contentType == "audio") {
            if ((m.duration ?: 0) > 0) put("d", m.duration)
            m.waveform?.takeIf { it.isNotBlank() }?.let { put("w", it) }
        }
        m.caption?.takeIf { it.isNotBlank() }?.let { put("cap", it) }
    }

    // ————————————————— 收藏 —————————————————

    /** 能收藏的：已确认、未撤回、非系统、有内容（同 iOS `favoriteSelected`，另加未确认防御）。 */
    fun favoritable(msgs: List<MessageEntity>): List<MessageEntity> =
        msgs.filter {
            it.convSeq > 0 &&
                (it.recalledAt ?: 0) <= 0 &&
                it.contentType != ContentType.SYSTEM &&
                it.contentType != ContentType.CALL &&
                it.content.isNotBlank()
        }

    /** 批量收藏回执。部分失败要说清几条成功——逐条弹 N 个吐司会互相盖掉。 */
    fun favoriteSummary(ok: Int, total: Int): String = when {
        ok <= 0 -> Str.s(R.string.net_fallback_favorite_failed)
        ok >= total -> if (total == 1) {
            Str.s(R.string.chat_favorite_success)
        } else {
            Str.p(R.plurals.chat_favorite_success_count, total, total)
        }
        else -> Str.s(R.string.chat_favorite_partial, ok, total, total - ok)
    }
}
