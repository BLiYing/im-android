package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode

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
        ErrCode.USER_ALREADY_EXISTS -> R.string.err_200004
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
        ErrCode.GROUP_INVITE_REVOKED -> R.string.qr_action_admin_only_note
        ErrCode.RATE_LIMITED -> R.string.err_100002
        else -> null
    }
}
