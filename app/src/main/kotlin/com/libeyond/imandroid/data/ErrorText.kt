package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil

/**
 * 业务错误码 → 本地化文案（对齐 iOS `IMFriendlyMessageForCode`）。服务端 `message` 是英文
 * （如 "friend request already pending"），直接弹给用户就是英文漏出来；未收录的码返回 null，回退服务端原文。
 *
 * 刻意**不映射**的（与 iOS 同）：
 * - 300204 无权限：服务端会带具体原因（如「群主需先转让群主再退群」），透传更有用；
 * - 300210 入群申请已提交：UI 走「待审批」分支而非错误提示。
 * 登录页有自己的一套（`LoginError`，用户名/密码错误刻意合并成一句防枚举），不走这里。
 */
object ErrorText {
    @StringRes
    fun friendlyRes(code: Int): Int? = when (code) {
        ErrCode.TOKEN_INVALID, ErrCode.TOKEN_EXPIRED -> R.string.common_login_expired
        ErrCode.USER_NOT_FOUND -> R.string.err_200001
        ErrCode.WRONG_PASSWORD -> R.string.err_200002
        ErrCode.ACCOUNT_BANNED -> R.string.err_200003
        ErrCode.EXPORT_BUSY -> R.string.err_200010
        ErrCode.EXPORT_COOLDOWN -> R.string.err_200011
        ErrCode.USER_ALREADY_EXISTS -> R.string.err_200004
        ErrCode.REGISTER_CLOSED -> R.string.err_200007
        // 200006 LOGIN_LOCKED 不在这里：文案带分钟数参数，走 [friendlyText]
        ErrCode.RECIPIENT_NOT_FOUND -> R.string.chat_reject_recipient_deleted
        ErrCode.ACCOUNT_MUTED -> R.string.err_300004
        ErrCode.GROUP_MUTED -> R.string.chat_input_disabled_mute_all
        ErrCode.GROUP_MEMBER_MUTED -> R.string.chat_input_disabled_muted
        ErrCode.FRIEND_ALREADY_FRIENDS -> R.string.err_200101
        ErrCode.FRIEND_BLOCKED -> R.string.err_200102 // 模糊文案，不暴露「被拉黑」
        ErrCode.NOT_FRIEND -> R.string.err_200103
        ErrCode.FRIEND_SELF -> R.string.err_200104
        ErrCode.FRIEND_REQUEST_PENDING -> R.string.err_200105
        ErrCode.NO_FRIEND_REQUEST -> R.string.friend_requests_empty
        ErrCode.QR_EXPIRED -> R.string.err_200110
        ErrCode.GROUP_NOT_FOUND -> R.string.err_300201
        ErrCode.GROUP_NAME_INVALID -> R.string.err_300202
        ErrCode.NOT_GROUP_MEMBER -> R.string.err_300203
        ErrCode.GROUP_MEMBER_LIMIT -> R.string.err_300205
        ErrCode.GROUP_BANNED -> R.string.qr_action_banned_note
        ErrCode.GROUP_JOIN_COOLDOWN -> R.string.err_300211
        ErrCode.GROUP_ADMIN_LIMIT -> R.string.err_300213
        ErrCode.GROUP_INVITE_REVOKED -> R.string.qr_action_admin_only_note
        ErrCode.FOLDER_LIMIT -> R.string.err_300301
        ErrCode.FOLDER_CONV_LIMIT -> R.string.err_300302
        ErrCode.FOLDER_NAME_INVALID -> R.string.err_300303
        ErrCode.FOLDER_NAME_DUPLICATE -> R.string.err_300304
        ErrCode.FOLDER_NOT_FOUND -> R.string.err_300305
        // 500001/500002 刻意不映射：头像接口也回这两个码（>2MB / 非 jpg·png），聊天上传的「安装包」文案放到头像上是错的；
        // 聊天消息的拒收说明走 [SendRejection]。500003 只有聊天上传会回。
        ErrCode.UPLOAD_DISABLED -> R.string.err_500003
        ErrCode.TRANSCRIBE_DISABLED -> R.string.err_500101
        ErrCode.TRANSCRIBE_FAILED -> R.string.err_500102
        ErrCode.TRANSCRIBE_BUSY -> R.string.err_500103
        // 短信验证码（带参数的 attempts_left / retry_after_sec 版本在 [SmsErrors]）
        ErrCode.SMS_UNAVAILABLE -> R.string.err_200040
        ErrCode.PHONE_INVALID -> R.string.err_200041
        ErrCode.PHONE_TAKEN -> R.string.err_200042
        ErrCode.SMS_CODE_INVALID -> R.string.err_200043
        ErrCode.SMS_CODE_EXPIRED -> R.string.err_200044
        ErrCode.SMS_SEND_FAILED -> R.string.err_200045
        ErrCode.SMS_DAILY_CAP -> R.string.err_200046
        ErrCode.PHONE_NOT_REGISTERED -> R.string.err_200047
        ErrCode.PHONE_TICKET_INVALID -> R.string.err_200048
        // 200049 一般由确认身份弹层兜住（ProofController 退回弹层）；漏到页面上时（如请求途中已离开页面）给「确认是你本人」，
        // 不露服务端英文。还没有专门的 err_200049 文案键。
        ErrCode.PROOF_REQUIRED -> R.string.proof_title
        ErrCode.RATE_LIMITED -> R.string.err_100002
        ErrCode.CONTENT_BLOCKED -> R.string.err_100004
        else -> null
    }

    /**
     * 带参数的码也能处理的版本：[ErrCode.LOGIN_LOCKED] 走 [loginLockedText]（永不为 null）；其余同 [friendlyRes]。
     */
    fun friendlyText(e: ApiException): String? =
        if (e.code == ErrCode.LOGIN_LOCKED) loginLockedText(e.data) { id, args -> Str.s(id, *args) }
        else friendlyRes(e.code)?.let { Str.s(it) }

    /**
     * 200006 文案的**唯一实现**（`LoginError` 与 [friendlyText] 都调它；三端规则一致）：
     * 带合法 `retry_after_sec` → err_200006（N 分钟）；缺失 / 非数字 → 通用 err_100002，**不**回退服务端英文。
     * [get] 负责按 resId + 参数取文案（生产用 Context / Str，单测可注入）。
     */
    fun loginLockedText(data: JsonElement?, get: (resId: Int, args: Array<out Any>) -> String): String =
        loginLockMinutes(data)?.let { get(R.string.err_200006, arrayOf(it)) }
            ?: get(R.string.err_100002, emptyArray())

    /**
     * 200006 错误响应 `data.retry_after_sec` → 提示里的分钟数：`max(1, ceil(秒/60))`（PROTOCOL §8；
     * 59 秒说「0 分钟」是错的）。接受整数或小数且 ≥ 0；缺失 / 非数字（含字符串形式的数字）/ 负数 / 非有限值返回 null。
     */
    fun loginLockMinutes(data: JsonElement?): Long? {
        val p = (data as? JsonObject)?.get("retry_after_sec") as? JsonPrimitive ?: return null
        if (p.isString) return null
        val sec = p.doubleOrNull ?: return null
        if (!sec.isFinite() || sec < 0) return null
        return maxOf(1L, ceil(sec / 60).toLong())
    }
}
