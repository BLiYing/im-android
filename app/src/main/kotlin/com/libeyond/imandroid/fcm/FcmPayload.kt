package com.libeyond.imandroid.fcm

/**
 * FCM `data` payload 解析出来的展示内容（M5 批次 2，`../../IMServer/docs/design/PUSH_M5_DESIGN.md` §3.2 +
 * 父任务简报）。服务端 `internal/push/content.go` 的 `Content` 序列化进 FCM message 的 `data` 字段：
 * `title`/`body`/`conv_id`/`conv_seq`（数字的字符串形式，FCM data payload 只能是 string-string map）/
 * `badge`——与 iOS APNs payload 同一套字段语义。
 *
 * @param convSeq 这条通知对应哪条消息。记进通知 extras，撤回/删除时据此收回（[FcmNotifications]）。
 *   **不**用来「点开后跳到那条」：同一会话只留最新一条通知，它指的永远是最新消息，进会话本来就看得到。
 * @param retract 这不是新消息，而是「[convSeq] 那条已被撤回/删除，把通知收回」（服务端 `type=retract`）。
 *   此时 [title]/[body] 是给不认识该类型的旧版本看的替换文案，本版本不展示。
 */
data class FcmNotificationContent(
    val convId: String,
    val title: String,
    val body: String,
    val convSeq: Long?,
    val badge: Int?,
    val retract: Boolean = false,
)

/**
 * 纯函数解析——**不碰 Android/Firebase 类型**，JVM 单测不需要 Robolectric。
 * `RemoteMessage.getData()` 本身就是 `Map<String, String>`，这里直接吃这个形状。
 */
object FcmPayload {
    private const val TYPE_RETRACT = "retract"

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
        )
    }
}
