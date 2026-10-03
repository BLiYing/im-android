package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 服务端**明确拒收**一条 `send_msg` 后，气泡下方那行小灰字（对齐 iOS `IMRejectNoteView` / `IMNoteCodeIsActionable`）。
 *
 * 这些消息**原样重发必然再被拒**，所以不给红 ❗ 重发入口，恢复入口是这行字本身：
 * 目前只有非好友（200103）可自助恢复——「发送好友申请」。
 *
 * **被拉黑（200102）刻意不给动作**：服务端对拉黑与非好友返回同样的模糊文案就是为了不泄露拉黑，
 * 给了入口反而会因申请被 200102 拒而暴露。
 */
object SendRejection {
    /** 码 → 提示文案；不在表里的（超时、网络、未知）返回 null，仍走红 ❗ 重发。 */
    @StringRes
    fun noteRes(code: Int): Int? = when (code) {
        ErrCode.FRIEND_BLOCKED -> R.string.err_200102
        ErrCode.NOT_FRIEND -> R.string.err_200103
        ErrCode.ACCOUNT_MUTED -> R.string.err_300004
        ErrCode.NOT_GROUP_MEMBER -> R.string.err_300203
        ErrCode.GROUP_MUTED -> R.string.chat_input_disabled_mute_all
        ErrCode.GROUP_MEMBER_MUTED -> R.string.chat_input_disabled_muted
        else -> null
    }

    /** 这条拒收是否带「发送好友申请」动作。 */
    fun isActionable(code: Int): Boolean = code == ErrCode.NOT_FRIEND
}
