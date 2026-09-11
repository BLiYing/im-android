package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ErrCode

/** 修改密码页的三个输入框。 */
enum class PasswordField { Old, New, Confirm }

/** 改密的一条反馈。 */
sealed interface PasswordFeedback {
    val message: String

    /** 红字显在输入卡下方、[field] 那个框描红；**输入不清空**（iOS 同）。null = 不指向具体框。 */
    data class Inline(override val message: String, val field: PasswordField?) : PasswordFeedback

    /** 与输入内容无关的失败（会话过期 / 网络 / 未知）——吐司。 */
    data class Toast(override val message: String) : PasswordFeedback
}

/**
 * 修改密码的判据（对齐 iOS `IMChangePasswordViewController` 与 Web `changePassword.ts`）。
 *
 * 能在提交前挡下的都本地挡（空 / 长度 / 与旧相同 / 两次一致），只把「旧密码对不对」留给服务端。
 */
object ChangePasswordRules {
    /** 服务端 `account.ChangePassword`：少于 6 拒。三端都按字符数判，比服务端的字节数严一点，无害。 */
    const val MIN_LENGTH = 6

    /** bcrypt 硬上限 72 **字节**（服务端 `len(newPassword) > 72` 回 100001，文案是英文）。 */
    const val MAX_BYTES = 72

    const val TITLE = "修改密码"
    const val FOOTER = "为保护账号安全，修改密码后你在其它设备上的登录会被自动下线，需用新密码重新登录；当前设备保持登录。"
    const val HELPER = "新密码至少 6 位，与旧密码不同。"
    const val SUCCESS_TOAST = "✓ 密码已修改，其它设备已下线"
    const val FALLBACK = "修改密码失败，请稍后再试"

    /**
     * 三个框都填了按钮才亮（**Web 口径，不是 iOS 的**）。iOS 要求本地校验全过才亮，
     * 于是「新密码至少 6 位」「两次输入不一致」那几句提示永远轮不到显示——按钮只是灰着，不说为什么。
     */
    fun canSubmit(old: String, new: String, confirm: String): Boolean =
        old.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty()

    /** 提交前的本地校验；null = 通过。先后顺序同 iOS `submitTapped`：长度 → 与旧相同 → 两次一致。 */
    fun validate(old: String, new: String, confirm: String): PasswordFeedback.Inline? = when {
        old.isEmpty() -> PasswordFeedback.Inline("请输入旧密码", PasswordField.Old)
        new.length < MIN_LENGTH -> PasswordFeedback.Inline("新密码至少 6 位", PasswordField.New)
        new.toByteArray(Charsets.UTF_8).size > MAX_BYTES ->
            PasswordFeedback.Inline("新密码太长（最多 72 个字节）", PasswordField.New)
        new == old -> PasswordFeedback.Inline("新密码不能与旧密码相同", PasswordField.New)
        confirm != new -> PasswordFeedback.Inline("两次输入不一致", PasswordField.Confirm)
        else -> null
    }

    /**
     * 服务端业务码 → 反馈；null = 没有专门文案，调用方用服务端 / 网络文案兜底成吐司。
     *
     * 参数错是 **100001**。iOS 这里硬编码的是 100002——那是限流码，所以 iOS 的
     * 「密码强度不足」一支实际走不到（2026-09-11 对照服务端 `errcode.go` 发现，iOS 未改）。
     */
    fun feedbackFor(code: Int): PasswordFeedback? = when (code) {
        ErrCode.WRONG_PASSWORD -> PasswordFeedback.Inline("旧密码错误", PasswordField.Old)
        ErrCode.PARAM_INVALID -> PasswordFeedback.Inline("密码强度不足（至少 6 位）", PasswordField.New)
        ErrCode.ACCOUNT_BANNED -> PasswordFeedback.Inline("账号已被封禁，无法修改密码", null)
        ErrCode.TOKEN_INVALID, ErrCode.TOKEN_EXPIRED -> PasswordFeedback.Toast("会话已过期，请重新登录")
        else -> null
    }
}
