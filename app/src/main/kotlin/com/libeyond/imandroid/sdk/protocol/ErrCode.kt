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
    /**
     * 内容命中后台敏感词（「拦截」档，SENSITIVE_WORDS_DESIGN §0）：发消息 / 编辑 / 资料 / 群资料 / 好友申请附言都可能回。
     * 服务端**不告诉是哪个词**；原样重发必然再被拒。
     */
    const val CONTENT_BLOCKED = 100004

    // --- 认证鉴权 1001xx（映射 HTTP 401，NO_PERMISSION 除外映射 403）---
    const val TOKEN_INVALID = 100101
    const val TOKEN_EXPIRED = 100102
    const val NO_PERMISSION = 100103

    // --- 账号 2000xx ---
    const val USER_NOT_FOUND = 200001
    const val WRONG_PASSWORD = 200002
    const val ACCOUNT_BANNED = 200003
    const val USER_ALREADY_EXISTS = 200004
    /**
     * 账号处于注销冷静期（登录返回，附 `delete_at`，不签 token）。
     * 见 PROTOCOL §6.16（`200005 AccountPendingDeletion`，`data.delete_at` 毫秒）。
     */
    const val ACCOUNT_PENDING_DELETION = 200005
    /**
     * 密码登录失败超限、暂时锁定（PROTOCOL §6.16，HTTP 429）；错误响应带 `data.retry_after_sec`。
     * 2026-11-09 前同一场景回的是 [RATE_LIMITED]。
     */
    const val LOGIN_LOCKED = 200006
    /** 管理员暂停了注册（S4）；注册页应先读 `GET /api/v1/public-config` 的 `register_enabled`。 */
    const val REGISTER_CLOSED = 200007
    /** 两步验证（PROTOCOL §7.1）：密码对但需第二步，`data` 带 `ticket / methods / expires_in`。 */
    const val TWO_STEP_REQUIRED = 200020
    /** 动态码 / 恢复码不对、已用过或重放；登录第二步带 `data.attempts_left`。 */
    const val TWO_STEP_CODE_INVALID = 200021
    /** ticket 过期 / 用完 / 错满，或待确认密钥超时。 */
    const val TWO_STEP_TICKET_INVALID = 200022
    const val TWO_STEP_NOT_ENABLED = 200023
    const val TWO_STEP_ALREADY_ENABLED = 200024
    /** 服务端未配置 `IM_TWOSTEP_KEY`。 */
    const val TWO_STEP_UNAVAILABLE = 200025
    /** 已有进行中的数据导出任务（DATA_EXPORT_DESIGN §2）。 */
    const val EXPORT_BUSY = 200010
    /** 成功导出后 24 小时内再次发起；回包 `data.retry_after_sec`。 */
    const val EXPORT_COOLDOWN = 200011

    // --- 邮箱登录 2000(30-37)（PROTOCOL §7.2）---
    const val EMAIL_INVALID = 200030
    const val EMAIL_TAKEN = 200031
    /** 验证码不对；`data.attempts_left`。 */
    const val EMAIL_CODE_INVALID = 200032
    /** 过期 / 尝试耗尽 / 无待验证请求 / 邮箱不符；`data.retry_after_sec`（0 = 现在就能重发）。 */
    const val EMAIL_CODE_EXPIRED = 200033
    const val EMAIL_NOT_BOUND = 200034
    const val EMAIL_UNCHANGED = 200035
    /** 服务端未配置 SMTP / `IM_EMAIL_KEY`。 */
    const val EMAIL_UNAVAILABLE = 200036
    const val EMAIL_SEND_FAILED = 200037

    // --- 手机号与短信验证码 2000(40-48)（PROTOCOL §7.3）---
    /** 未配置通道 / 号码不在测试号码里 / 本人未绑定手机号。 */
    const val SMS_UNAVAILABLE = 200040
    const val PHONE_INVALID = 200041
    const val PHONE_TAKEN = 200042
    /** 验证码不对；`data.attempts_left`。 */
    const val SMS_CODE_INVALID = 200043
    /** 过期 / 尝试耗尽 / 无待验证请求；`data.retry_after_sec`。 */
    const val SMS_CODE_EXPIRED = 200044
    const val SMS_SEND_FAILED = 200045
    const val SMS_DAILY_CAP = 200046
    /** 仅忘记密码：号码没有可用账号。 */
    const val PHONE_NOT_REGISTERED = 200047
    /** `register_ticket` / `restore_ticket` 过期或已用。 */
    const val PHONE_TICKET_INVALID = 200048

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
    const val GROUP_ADMIN_LIMIT = 300213

    // --- 会话文件夹 3003xx（PROTOCOL §6.15）---
    const val FOLDER_LIMIT = 300301
    const val FOLDER_CONV_LIMIT = 300302
    const val FOLDER_NAME_INVALID = 300303
    const val FOLDER_NAME_DUPLICATE = 300304
    const val FOLDER_NOT_FOUND = 300305

    // --- 文件 5000xx ---
    const val FILE_TOO_LARGE = 500001
    const val FILE_TYPE_INVALID = 500002
    /** 管理员在后台「系统设置 ▸ 上传与媒体」暂停了聊天文件上传（头像不受影响；S3a）。 */
    const val UPLOAD_DISABLED = 500003

    /**
     * 上传策略拒绝（S3a）：超限 / 类型禁用 / 总开关关。**确定性的业务错**——换会话从 0 重传也必然再被拒，
     * 分片上传遇到它不重开会话（[com.libeyond.imandroid.data.ChunkedUploader]）。
     */
    fun isUploadPolicyReject(code: Int): Boolean =
        code == FILE_TOO_LARGE || code == FILE_TYPE_INVALID || code == UPLOAD_DISABLED

    // --- 语音转文字 5001xx（VOICE_TRANSCRIBE_DESIGN §3.3）---
    /** 服务端没配识别引擎。重试无意义，要明说。 */
    const val TRANSCRIBE_DISABLED = 500101
    /** 预留码，REST 当前不返回（真失败走 WS `voice_transcript` 的 `status=failed`）。 */
    const val TRANSCRIBE_FAILED = 500102
    /** 识别队列已满，稍后重试。 */
    const val TRANSCRIBE_BUSY = 500103

    /** token 失效的两种码——重连/续期逻辑按这两个判，别 parse 文案。 */
    fun isAuthExpired(code: Int): Boolean = code == TOKEN_INVALID || code == TOKEN_EXPIRED
}
