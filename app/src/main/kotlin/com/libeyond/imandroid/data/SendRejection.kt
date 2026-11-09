package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 服务端**明确拒收**一条 `send_msg` 后，气泡下方那行小灰字（对齐 iOS `IMRejectNoteView` / `IMNoteCodeIsActionable`）。
 *
 * 这些消息**原样重发必然再被拒**：红 ❗ 照显（微信式、对齐 iOS）但**不可点**，恢复入口是这行字本身：
 * 目前只有非好友（200103）可自助恢复——「发送好友申请」。
 *
 * 文案是微信式「消息已发出，但被对方拒收了」（不用 `err_2001xx`：那是加好友场景的措辞），与 iOS / Web 同。
 *
 * **被拉黑（200102）刻意不给动作**：服务端对拉黑与非好友返回同样的模糊文案就是为了不泄露拉黑，
 * 给了入口反而会因申请被 200102 拒而暴露。
 */
object SendRejection {
    /** 码 → 提示文案；不在表里的（超时、网络、未知）返回 null，仍走红 ❗ 重发。 */
    @StringRes
    fun noteRes(code: Int): Int? = when (code) {
        ErrCode.FRIEND_BLOCKED -> R.string.chat_reject_blocked
        ErrCode.NOT_FRIEND -> R.string.chat_reject_not_friend
        ErrCode.RECIPIENT_NOT_FOUND -> R.string.chat_reject_recipient_deleted // 对端已注销（§6.16.3）：重发必然再失败，不可点
        ErrCode.ACCOUNT_MUTED -> R.string.err_300004
        ErrCode.MSG_TOO_LONG -> R.string.chat_reject_too_large
        ErrCode.CONTENT_BLOCKED -> R.string.chat_reject_sensitive // 敏感词拦截：重发必再被拒，文本留在气泡里可复制改了再发
        ErrCode.NOT_GROUP_MEMBER -> R.string.err_300203
        ErrCode.GROUP_MUTED -> R.string.chat_input_disabled_mute_all
        ErrCode.GROUP_MEMBER_MUTED -> R.string.chat_input_disabled_muted
        // 上传策略（S3a）：媒体在上传阶段被拒（发送前预检之后管理员又改了策略，或分片 complete 复检）。
        // 原样重发必然再被拒，与禁言同理不给重发。超限是**带参文案**（上限值），显示一律走 [noteText]
        ErrCode.FILE_TOO_LARGE -> R.string.upload_precheck_too_large
        ErrCode.FILE_TYPE_INVALID -> R.string.upload_precheck_blocked_type
        ErrCode.UPLOAD_DISABLED -> R.string.err_500003
        else -> null
    }

    /**
     * 说明行的**显示文案**（调用时刻取，跟随当前语言）；null = 不是明确拒收。
     * 500001 填**当前**单文件上限（拒的那一刻的值客户端不可知，当前值是最好的近似，与发送前预检同一文案同一格式）——
     * 不能用 `chat_reject_too_large`（「消息内容过大」是文本超长 300xxx 的措辞，放在文件上是错的）。
     */
    fun noteText(code: Int, limits: ServerLimits = ServerConfigStore.current): String? {
        val res = noteRes(code) ?: return null
        return if (code == ErrCode.FILE_TOO_LARGE) {
            Str.s(res, UploadPrecheck.sizeLabel(limits.uploadMaxFileBytes))
        } else {
            Str.s(res)
        }
    }

    /** 这条拒收是否带「发送好友申请」动作。 */
    fun isActionable(code: Int): Boolean = code == ErrCode.NOT_FRIEND
}

/**
 * 客户端本地产生的发送失败码（**不来自服务端**，所以不放 `ErrCode`——那里逐条抄自后端 errcode.go）。
 * 不在 [SendRejection] 表里 → 红点可点重发。
 */
object LocalSendError {
    /** ack 重发耗尽仍无回应（断网 / 丢包）。同 iOS `5002`。 */
    const val ACK_TIMEOUT = 5002
}
