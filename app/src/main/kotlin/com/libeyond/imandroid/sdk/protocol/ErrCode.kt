package com.libeyond.imandroid.sdk.protocol

/**
 * 业务错误码——**逐条抄自后端 `internal/errcode/errcode.go`**（6 位 AABBCC 分段码）。
 *
 * 后端约定：**错误码一经发布永不复用、永不改含义**；`message` 只给开发看，
 * 面向用户的提示由客户端按 code 做本地映射（见 [friendlyMessageKey]）。
 *
 * ## 别把 HTTP 状态码混进来
 * 401/403/404 是**传输层状态**，不是业务码。后端有独立映射：
 * `100103` → 403、认证段 `1001xx` → 401、`100003` → 500、限流 → 429、其余业务错误一律 400
 * ——**语义在 code 里，不在 HTTP 状态里**。按 HTTP 状态分支会把几十种业务错误糊成一个 400。
 */
object ErrCode {
    const val SUCCESS = 0

    // --- 通用 / 系统 1000xx ---
    const val PARAM_INVALID = 100001
    const val RATE_LIMITED = 100002
    const val INTERNAL = 100003

    // --- 认证鉴权 1001xx（映射 HTTP 401，NO_PERMISSION 除外映射 403）---
    const val TOKEN_INVALID = 100101
    const val TOKEN_EXPIRED = 100102
    const val NO_PERMISSION = 100103

    // --- 账号 2000xx ---
    const val USER_NOT_FOUND = 200001
    const val WRONG_PASSWORD = 200002
    const val ACCOUNT_BANNED = 200003
    const val USER_ALREADY_EXISTS = 200004

    // --- 好友 2001xx ---
    const val FRIEND_ALREADY_FRIENDS = 200101
    const val FRIEND_BLOCKED = 200102
    /** 不是好友——单聊好友准入（微信式），收件方须仍视发件方为好友。 */
    const val NOT_FRIEND = 200103
    const val FRIEND_SELF = 200104
    const val FRIEND_REQUEST_PENDING = 200105
    const val NO_FRIEND_REQUEST = 200106

    /** 二维码已失效（过期/被重置/不存在——**刻意不区分**，避免给爆破者反馈）。 */
    const val QR_EXPIRED = 200110

    // --- 消息 3000xx ---
    const val MSG_TOO_LONG = 300001
    const val SEND_RATE_LIMITED = 300002
    const val RECIPIENT_NOT_FOUND = 300003
    /** 发送方被禁言（后台处置）。端上表现为发送失败 + 系统行「你已被禁言」。 */
    const val ACCOUNT_MUTED = 300004
    const val MSG_NOT_FOUND = 300005
    const val MSG_OP_FORBIDDEN = 300006
    const val MSG_NOT_EDITABLE = 300007
    const val RECALL_WINDOW_PAST = 300008

    // --- 群 3002xx ---
    const val GROUP_NOT_FOUND = 300201
    const val GROUP_NAME_INVALID = 300202
    const val NOT_GROUP_MEMBER = 300203
    const val NO_GROUP_PERMISSION = 300204
    const val GROUP_MEMBER_LIMIT = 300205
    /** 全员禁言（群主/管理员不受限）。 */
    const val GROUP_MUTED = 300206
    /**
     * 已被移出该群、暂时不可加入。
     * 注意端上文案分两种人称：**自己加群**用第二人称「你已被移出该群」，
     * **邀请别人**用第三人称「该成员已被移出本群」——iOS/Web 都踩过混用（2026-08-13）。
     */
    const val GROUP_BANNED = 300207
    /** 被管理员单独禁言，区别于 300206 全员禁言。 */
    const val GROUP_MEMBER_MUTED = 300208
    const val GROUP_JOIN_PENDING = 300210
    const val GROUP_JOIN_COOLDOWN = 300211
    const val GROUP_INVITE_REVOKED = 300212

    // --- 文件 5000xx ---
    const val FILE_TOO_LARGE = 500001
    const val FILE_TYPE_INVALID = 500002

    /** token 失效的两种码——重连/续期逻辑按这两个判，别 parse 文案。 */
    fun isAuthExpired(code: Int): Boolean = code == TOKEN_INVALID || code == TOKEN_EXPIRED
}
