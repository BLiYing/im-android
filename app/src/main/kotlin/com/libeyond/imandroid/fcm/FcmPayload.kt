package com.libeyond.imandroid.fcm

/**
 * FCM `data` payload 解析出来的展示内容（M5 批次 2，`../../IMServer/docs/design/PUSH_M5_DESIGN.md` §3.2 +
 * 父任务简报）。服务端 `internal/push/content.go` 的 `Content` 序列化进 FCM message 的 `data` 字段：
 * `title`/`body`/`conv_id`/`conv_seq`（数字的字符串形式，FCM data payload 只能是 string-string map）/
 * `badge`——与 iOS APNs payload 同一套字段语义。
 *
 * @param convSeq 这条通知对应哪条消息。记进通知里那一行，撤回/删除/别处已读时据此去掉（[FcmNotifications]）。
 *   **不**用来「点开后跳到那条」：一个会话一条通知，点开进会话本来就停在首条未读。
 * @param retract 这不是新消息，而是「[convSeq] 那条已被撤回/删除，把通知收回」（服务端 `type=retract`）。
 *   此时 [title]/[body] 是给不认识该类型的旧版本看的替换文案，本版本不展示。
 * @param clear 这不是新消息，而是「本人在别的设备上把这个会话读到了 [convSeq]」（服务端 `type=clear`，
 *   PUSH_M5_DESIGN §3.5）：取消该会话里 seq ≤ 它的通知，不展示。
 * @param call 非 null 表示这是一条通话提醒（服务端 `type=call`，PUSH_M5_DESIGN §3.8），交给 [CallNotifications]。
 */
data class FcmNotificationContent(
    val convId: String,
    val title: String,
    val body: String,
    val convSeq: Long?,
    val badge: Int?,
    val retract: Boolean = false,
    val clear: Boolean = false,
    val senderId: String? = null,
    val senderAvatar: String? = null,
    val groupAvatar: String? = null,
    val senderName: String? = null,
    val bareBody: String? = null,
    val call: FcmCallNotice? = null,
) {
    /**
     * 通知大图标用哪张头像（PUSH_M5_DESIGN §3.6，2026-10-02 改）：**群聊固定用群头像**，不管谁发的消息
     * ——同 App 内会话列表（群头像与发消息的人是谁无关），不再退回发送人头像（那会导致同一个群的通知
     * 忽而显示这个人、忽而显示那个人）。私聊用对方头像。两者都没有时画首字母占位图，见 [FcmAvatarPlaceholder]。
     */
    val iconAvatar: String? get() = if (isGroup) groupAvatar else senderAvatar

    /** 单聊的 conv_id 是 `u_<a>_u_<b>`，其余（`g_…`）都是群。 */
    val isGroup: Boolean get() = !convId.startsWith("u_")

    /** 首字母占位图的种子 + 名字：群用群本身（conv_id / 标题），私聊用对方（sender_id / 对方名）。 */
    val placeholderSeed: String get() = if (isGroup) convId else (senderId?.takeIf { it.isNotBlank() } ?: convId)
    val placeholderName: String get() = if (isGroup) title else senderName?.takeIf { it.isNotBlank() } ?: title

    /**
     * 这条推送在会话通知里是哪一行（§3.7）。群聊：发送人 + 不带「发送人: 」前缀的正文（老服务端没给
     * `bare_body` 时退回带前缀的整句、发送人留空）；单聊：发送人就是标题。没有 seq 的认不出是哪条，不成行。
     */
    fun toLine(nowMs: Long): ConversationLine? {
        val seq = convSeq?.takeIf { it > 0 } ?: return null
        return if (isGroup) {
            val bare = bareBody
            if (bare != null) ConversationLine(seq, senderName.orEmpty(), bare, nowMs)
            else ConversationLine(seq, "", body, nowMs)
        } else {
            ConversationLine(seq, title, body, nowMs)
        }
    }
}

/**
 * 通话提醒（PROTOCOL §6.14「通话提醒」）。[kind]：`incoming` 来电横幅 / `missed` 群通话未接 / `ended` 收回横幅。
 */
data class FcmCallNotice(val callId: String, val kind: String, val media: String) {
    val isVideo: Boolean get() = media == "video"

    companion object {
        const val INCOMING = "incoming"
        const val MISSED = "missed"
        const val ENDED = "ended"
    }
}

/**
 * 纯函数解析——**不碰 Android/Firebase 类型**，JVM 单测不需要 Robolectric。
 * `RemoteMessage.getData()` 本身就是 `Map<String, String>`，这里直接吃这个形状。
 */
object FcmPayload {
    private const val TYPE_RETRACT = "retract"
    private const val TYPE_CLEAR = "clear"
    private const val TYPE_CALL = "call"

    /** `conv_id` 缺失/空白视为不可展示（没有会话可跳转），返回 null——调用方据此静默丢弃，不崩、不弹空通知。 */
    fun parse(data: Map<String, String>): FcmNotificationContent? {
        val convId = data["conv_id"]?.trim().orEmpty()
        if (convId.isEmpty()) return null
        return FcmNotificationContent(
            convId = convId,
            title = data["title"].orEmpty(),
            body = data["body"].orEmpty(),
            convSeq = data["conv_seq"]?.toLongOrNull(),
            badge = data["badge"]?.toIntOrNull(),
            retract = data["type"] == TYPE_RETRACT,
            clear = data["type"] == TYPE_CLEAR,
            senderId = data["sender_id"]?.takeIf { it.isNotBlank() },
            senderAvatar = data["sender_avatar"]?.takeIf { it.isNotBlank() },
            groupAvatar = data["group_avatar"]?.takeIf { it.isNotBlank() },
            senderName = data["sender_name"]?.takeIf { it.isNotBlank() },
            bareBody = data["bare_body"]?.takeIf { it.isNotBlank() },
            call = callOf(data),
        )
    }

    /** `type=call` 且 call_id / call_kind 都在才算通话提醒；缺了哪样都认不出是哪通、要做什么，丢掉。 */
    private fun callOf(data: Map<String, String>): FcmCallNotice? {
        if (data["type"] != TYPE_CALL) return null
        val callId = data["call_id"]?.trim().orEmpty()
        val kind = data["call_kind"]?.trim().orEmpty()
        if (callId.isEmpty() || kind.isEmpty()) return null
        return FcmCallNotice(callId, kind, data["media"].orEmpty())
    }

    /** 联网取头像失败后，这么久之内只用本地缓存（见 `FcmMessagingService.loadAvatar`）。 */
    const val AVATAR_NETWORK_BACKOFF_MS = 60_000L

    fun avatarNetworkAllowed(lastFailureMs: Long, nowMs: Long): Boolean =
        lastFailureMs <= 0 || nowMs - lastFailureMs >= AVATAR_NETWORK_BACKOFF_MS

    /**
     * 头像相对路径补成绝对地址。**只认自家服务器的 `/avatars/`**（同 iOS `IMPushAvatarURL`）：
     * 推送内容里塞一个外站地址 / 别的目录，不该让手机在后台去拉。
     */
    fun avatarUrl(path: String?, host: String, useTls: Boolean): String? {
        if (path.isNullOrBlank() || host.isBlank()) return null
        if (!path.startsWith("/avatars/") || path.contains("..")) return null
        if (host.any { it == '/' || it == '@' || it.isWhitespace() }) return null
        return (if (useTls) "https" else "http") + "://" + host + path
    }
}
