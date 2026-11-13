package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/** 修改 / 设置密码页的输入框（`Code` = 用短信验证码证明身份时替代 `Old` 的那一格）。 */
enum class PasswordField { Old, Code, New, Confirm }

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

    val TITLE: String get() = Str.s(R.string.settings_change_password)
    val FOOTER: String get() = Str.s(R.string.password_footer_security_note)
    val HELPER: String get() = Str.s(R.string.password_helper_min_length_hint)
    val SUCCESS_TOAST: String get() = Str.s(R.string.password_success_toast)
    val FALLBACK: String get() = Str.s(R.string.password_error_generic_failed)

    /**
     * 三个框都填了按钮才亮（**Web 口径，不是 iOS 的**）。iOS 要求本地校验全过才亮，
     * 于是「新密码至少 6 位」「两次输入不一致」那几句提示永远轮不到显示——按钮只是灰着，不说为什么。
     */
    fun canSubmit(old: String, new: String, confirm: String): Boolean =
        old.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty()

    /** 提交前的本地校验；null = 通过。先后顺序同 iOS `submitTapped`：长度 → 与旧相同 → 两次一致。 */
    fun validate(old: String, new: String, confirm: String): PasswordFeedback.Inline? = when {
        old.isEmpty() -> PasswordFeedback.Inline(Str.s(R.string.password_error_empty_old), PasswordField.Old)
        new.length < MIN_LENGTH -> PasswordFeedback.Inline(Str.s(R.string.password_error_min_length), PasswordField.New)
        new.toByteArray(Charsets.UTF_8).size > MAX_BYTES ->
            PasswordFeedback.Inline(Str.s(R.string.password_error_too_long), PasswordField.New)
        new == old -> PasswordFeedback.Inline(Str.s(R.string.password_error_same_as_old), PasswordField.New)
        confirm != new -> PasswordFeedback.Inline(Str.s(R.string.password_error_mismatch), PasswordField.Confirm)
        else -> null
    }

    /**
     * 用短信验证码证明身份（设置密码 / 忘记旧密码改密）时的本地校验：验证码 6 位 → 新密码长度 → 两次一致。
     * 没有「与旧相同」这一条（不知道旧密码）。
     */
    fun validateWithCode(code: String, new: String, confirm: String): PasswordFeedback.Inline? = when {
        !PhoneAuthRules.isCodeComplete(code) -> PasswordFeedback.Inline(Str.s(R.string.login_error_code_required), PasswordField.Code)
        new.length < MIN_LENGTH -> PasswordFeedback.Inline(Str.s(R.string.password_error_min_length), PasswordField.New)
        new.toByteArray(Charsets.UTF_8).size > MAX_BYTES ->
            PasswordFeedback.Inline(Str.s(R.string.password_error_too_long), PasswordField.New)
        confirm != new -> PasswordFeedback.Inline(Str.s(R.string.password_error_mismatch), PasswordField.Confirm)
        else -> null
    }

    /**
     * 新密码组下的说明：设置密码 →「设置后可以用手机号和密码登录」；用验证码改密 → 只说长度（不知道旧密码，
     * 不提「与旧密码不同」）；用旧密码改密 → [HELPER]。
     */
    fun helper(mode: PasswordPageMode, proof: PasswordProofMode): String = when {
        mode == PasswordPageMode.Set -> Str.s(R.string.security_password_set_footer)
        proof == PasswordProofMode.Sms -> Str.s(R.string.password_error_min_length)
        else -> HELPER
    }

    /** 第一组下「其它设备将下线」的说明：修改密码（两种证明方式都）会下线其它设备，设置密码不会。 */
    fun showLogoutOthersNote(mode: PasswordPageMode): Boolean = mode == PasswordPageMode.Change

    /** 验证码方式下按钮可点：三格都有内容（同 [canSubmit] 的 Web 口径，提示留给 [validateWithCode]）。 */
    fun canSubmitWithCode(code: String, new: String, confirm: String): Boolean =
        code.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty()

    /**
     * 服务端业务码 → 反馈；null = 没有专门文案，调用方用服务端 / 网络文案兜底成吐司。
     *
     * 参数错是 **100001**。iOS 这里硬编码的是 100002——那是限流码，所以 iOS 的
     * 「密码强度不足」一支实际走不到（2026-09-11 对照服务端 `errcode.go` 发现，iOS 未改）。
     */
    fun feedbackFor(code: Int, attemptsLeft: Int? = null): PasswordFeedback? = when (code) {
        ErrCode.WRONG_PASSWORD -> PasswordFeedback.Inline(Str.s(R.string.password_error_wrong_old), PasswordField.Old)
        // 短信验证码方式（PROTOCOL §7.3）：验证码错 / 过期 / 本人没绑手机，红字指向验证码那一格
        ErrCode.SMS_CODE_INVALID, ErrCode.SMS_CODE_EXPIRED, ErrCode.SMS_UNAVAILABLE ->
            PasswordFeedback.Inline(SmsErrors.message(code, attemptsLeft).orEmpty(), PasswordField.Code)
        ErrCode.PARAM_INVALID -> PasswordFeedback.Inline(Str.s(R.string.password_error_weak), PasswordField.New)
        ErrCode.ACCOUNT_BANNED -> PasswordFeedback.Inline(Str.s(R.string.password_error_account_banned), null)
        ErrCode.TOKEN_INVALID, ErrCode.TOKEN_EXPIRED -> PasswordFeedback.Toast(Str.s(R.string.password_error_session_expired_retry))
        else -> null
    }
}
