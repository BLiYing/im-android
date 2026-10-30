package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/** 两步验证页的步骤（隐私页 ▸ 两步验证 内部导航）。 */
enum class TwoStepStep { Intro, Password, Bind, Codes, Status, Disable, Regen }

/** 错误分类：按业务码分，**不 parse 服务端文案**。 */
enum class TwoStepErrorKind { WrongPassword, CodeInvalid, Expired, RateLimited, Unavailable, AlreadyEnabled, NotEnabled, Other }

/**
 * 两步验证的纯规则（可单测；对齐 iOS / Web 同名口径）。
 * 6 位清洗、恢复码归一化、4 位分组、错误码→文案、状态页文案、返回去向、leave 拦截都在这里，
 * 界面与 Host 只调用，不另写一份。
 */
object TwoStepRules {
    const val CODE_LEN = 6
    /** 恢复码输入框最长（`xxxxx-xxxxx` 11 + 容一个空格）。 */
    const val RECOVERY_MAX_LEN = 12
    /** 去掉连字符 / 空格后的恢复码长度。 */
    const val RECOVERY_CORE_LEN = 10
    /** 恢复码剩余 ≤ 此值时橙色 + footer 提示。 */
    const val LOW_THRESHOLD = 2
    /** 登录第二步剩余次数 ≤ 此值才提示。 */
    const val ATTEMPTS_HINT_MAX = 2
    const val FILE_NAME = "im-recovery-codes.txt"

    /** 6 位框：只留 ASCII 数字、截 6 位。粘贴「123 456」得 `123456`。 */
    fun sanitizeCode(raw: String): String = raw.filter { it in '0'..'9' }.take(CODE_LEN)

    private const val RECOVERY_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    private val RECOVERY_RE = Regex("(?<![a-z0-9])([$RECOVERY_CHARS]{5})[-\\s]?([$RECOVERY_CHARS]{5})(?![a-z0-9])")

    /**
     * 从粘贴文本里取第一个恢复码（10 位、服务端字符集），原文可带连字符 / 空格 / 换行 / 「1.」前缀 / 整份清单，
     * 大小写不敏感；返回 `xxxxx-xxxxx`，取不到 null。
     */
    fun extractRecoveryCode(text: String): String? =
        RECOVERY_RE.find(text.lowercase())?.let { it.groupValues[1] + "-" + it.groupValues[2] }

    /**
     * 恢复码框的输入过滤。逐字输入（一次最多增 1 个字符）原样接收，**不改写**（IME 安全）；
     * 一次增多个字符（粘贴）、含换行或超长时先 [extractRecoveryCode] 提取替换，提取不到再按限长（超长整次忽略）。
     */
    fun acceptRecoveryInput(old: String, new: String): String {
        val pasted = new.length - old.length > 1 || '\n' in new || '\r' in new || new.length > RECOVERY_MAX_LEN
        if (pasted) extractRecoveryCode(new)?.let { return it }
        return if (new.length <= RECOVERY_MAX_LEN) new else old
    }

    /** 提交时的归一化：小写、去连字符与空白。 */
    fun normalizeRecovery(raw: String): String = raw.lowercase().filterNot { it == '-' || it.isWhitespace() }

    /** 6 位**数字**输满且不在提交中才自动提交（长度够但含字母的是别的模式残留，不能当动态码发）。 */
    fun shouldAutoSubmit(code: String, busy: Boolean): Boolean = !busy && isTotpComplete(code)

    fun isTotpComplete(code: String): Boolean = code.length == CODE_LEN && code.all { it in '0'..'9' }

    fun isRecoveryComplete(raw: String): Boolean = normalizeRecovery(raw).length == RECOVERY_CORE_LEN

    /** 提交给服务端的 `code`：恢复码模式给归一化后的串，否则给 6 位。 */
    fun submitCode(input: String, recoveryMode: Boolean): String = if (recoveryMode) normalizeRecovery(input) else input

    fun canSubmitVerify(password: String, input: String, recoveryMode: Boolean): Boolean =
        password.isNotEmpty() && if (recoveryMode) isRecoveryComplete(input) else isTotpComplete(input)

    /** Base32 密钥按 4 位一组、空格分隔、大写展示。 */
    fun groupSecret(secret: String): String = secret.filterNot { it.isWhitespace() }.uppercase().chunked(4).joinToString(" ")

    /** 复制到剪贴板的密钥：无分隔（认证器都认）。 */
    fun copySecret(secret: String): String = secret.filterNot { it.isWhitespace() }

    /** 10 个码排两列。 */
    fun codeRows(codes: List<String>): List<List<String>> = codes.chunked(2)

    fun copyAllText(codes: List<String>): String = codes.joinToString("\n")

    fun fileText(header: String, codes: List<String>): String = header + "\n\n" + copyAllText(codes) + "\n"

    fun isLow(remaining: Int): Boolean = remaining <= LOW_THRESHOLD

    /** 状态页 footer：快用完优先；其次「离开时没保存」；否则无。 */
    fun statusFooter(remaining: Int, unsaved: Boolean): String = when {
        isLow(remaining) -> Str.s(R.string.twostep_recovery_low_footer)
        unsaved -> Str.s(R.string.twostep_recovery_unsaved_footer)
        else -> ""
    }

    fun remainingLabel(remaining: Int): String = Str.s(R.string.twostep_recovery_remaining, remaining)

    /** 隐私页那一行的右值：未取到（null）留空，取到 开启 / 关闭。 */
    fun rowLabel(enabled: Boolean?): String = when (enabled) {
        null -> ""
        true -> Str.s(R.string.twostep_status_on)
        false -> Str.s(R.string.twostep_status_off)
    }

    /** 返回键去向；null = 退出两步验证（回隐私页）。Codes 另走确认，不在此表。 */
    fun backTarget(step: TwoStepStep): TwoStepStep? = when (step) {
        TwoStepStep.Password, TwoStepStep.Bind -> TwoStepStep.Intro
        TwoStepStep.Disable, TwoStepStep.Regen -> TwoStepStep.Status
        TwoStepStep.Codes -> TwoStepStep.Status
        TwoStepStep.Intro, TwoStepStep.Status -> null
    }

    /** 返回 / 关闭是否要先二次确认：恢复码只显示这一次，在该页一律拦。 */
    fun needsLeaveConfirm(step: TwoStepStep): Boolean = step == TwoStepStep.Codes

    fun kindOf(code: Int): TwoStepErrorKind = when (code) {
        ErrCode.WRONG_PASSWORD -> TwoStepErrorKind.WrongPassword
        ErrCode.TWO_STEP_CODE_INVALID -> TwoStepErrorKind.CodeInvalid
        ErrCode.TWO_STEP_TICKET_INVALID -> TwoStepErrorKind.Expired
        ErrCode.RATE_LIMITED -> TwoStepErrorKind.RateLimited
        ErrCode.TWO_STEP_UNAVAILABLE -> TwoStepErrorKind.Unavailable
        ErrCode.TWO_STEP_ALREADY_ENABLED -> TwoStepErrorKind.AlreadyEnabled
        ErrCode.TWO_STEP_NOT_ENABLED -> TwoStepErrorKind.NotEnabled
        else -> TwoStepErrorKind.Other
    }

    /**
     * 文案；null = 没有专门文案，调用方用通用兜底。
     * [login] = 登录第二步（码错不带「检查认证器」长句，剩余次数 ≤2 时提示；ticket 失效是「请重新登录」）。
     */
    fun message(kind: TwoStepErrorKind, attemptsLeft: Int? = null, login: Boolean = false): String? = when (kind) {
        TwoStepErrorKind.WrongPassword -> Str.s(R.string.twostep_error_password_wrong)
        TwoStepErrorKind.CodeInvalid -> when {
            login && attemptsLeft != null && attemptsLeft in 1..ATTEMPTS_HINT_MAX -> Str.s(R.string.twostep_error_attempts_left, attemptsLeft)
            login -> Str.s(R.string.twostep_error_code_invalid_short)
            else -> Str.s(R.string.twostep_error_code_invalid)
        }
        TwoStepErrorKind.Expired -> Str.s(if (login) R.string.twostep_error_login_expired else R.string.twostep_error_expired)
        TwoStepErrorKind.RateLimited -> Str.s(R.string.twostep_error_rate_limited)
        TwoStepErrorKind.Unavailable -> Str.s(R.string.twostep_error_unavailable)
        TwoStepErrorKind.AlreadyEnabled -> Str.s(R.string.twostep_error_already_enabled)
        TwoStepErrorKind.NotEnabled -> Str.s(R.string.twostep_error_not_enabled)
        TwoStepErrorKind.Other -> null
    }

    /** 码刚被用过（重放，例如刚用它开启又马上拿去关闭 / 重新生成）会回 200021；提示等认证器刷出下一个码。用于绑定页以外的验证页。 */
    fun waitNextHint(): String = Str.s(R.string.twostep_bind_wait_next)
}
