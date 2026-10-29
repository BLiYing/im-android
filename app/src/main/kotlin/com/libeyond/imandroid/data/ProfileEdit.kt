package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 「我的资料」编辑表单的**纯校验/规整逻辑**。
 *
 * 抽出来是为了能变异验证：这几条规则改错了，界面上只表现为「保存后服务端拒了」，
 * 靠肉眼很难发现少了哪一条（CODING_STYLE §7③）。
 *
 * 规则**逐条对齐服务端**（`internal/account/userid.go`），客户端只是前置提示，
 * 服务端仍会再校验一遍——前置不是为了省服务端那道，是为了别让用户点了保存才知道。
 */
object ProfileEdit {

    /** 昵称长度上限（服务端 `maxNicknameRunes` = 32 rune）。 */
    const val MAX_NICKNAME_RUNES = 32

    /** 公开句柄形态：仅小写字母 / 数字 / 下划线，5–32 位（服务端 `usernameRe`）。 */
    private val USERNAME_RE = Regex("^[a-z0-9_]{5,32}$")

    /**
     * 昵称的前置校验。返回 null = 通过，否则是给用户看的原因。
     *
     * **必填**：它是全端显示名回退链的终点，清空会让各处露出 10 位数字内部 ID。
     */
    fun nicknameError(nickname: String): String? {
        val n = nickname.trim()
        return when {
            n.isEmpty() -> Str.s(R.string.profile_nickname_required)
            n.length > MAX_NICKNAME_RUNES -> Str.s(R.string.profile_nickname_too_long, MAX_NICKNAME_RUNES)
            else -> null
        }
    }

    /**
     * 用户名的前置校验。返回 null = 通过。
     *
     * **不做 toLowerCase**：与服务端同一取舍——静默把「Alice」改成「alice」落库，
     * 会与用户看到的输入不一致。显式拒绝更诚实。
     */
    fun usernameError(username: String): String? {
        val u = username.trim()
        return when {
            u.isEmpty() -> Str.s(R.string.profile_username_required)
            !USERNAME_RE.matches(u) -> Str.s(R.string.profile_username_invalid_format)
            else -> null
        }
    }

    /**
     * 这次保存要不要发改名请求。
     *
     * **只在真改了才发**：每次保存都发，会把「用户名已被占用」抛给一个压根没动
     * 用户名的人（iOS `saveUsernameIfChangedThenExitEditing` 的原话）。
     */
    fun shouldChangeUsername(input: String, loaded: String): Boolean {
        val u = input.trim()
        return u.isNotEmpty() && u != loaded.trim()
    }

    /** 个性签名上限（Unicode 码点，服务端 `profile.MaxBioRunes`）。 */
    const val MAX_BIO_RUNES = 70

    /** 计数达到此值开始变 danger 色（设计稿 §2.2）。 */
    const val BIO_WARN_RUNES = 60

    /** 按码点计长（emoji 代理对算一个，与服务端同口径）。 */
    fun bioRunes(text: String): Int = text.codePointCount(0, text.length)

    /**
     * 输入框里的实时规整：只把换行折成空格（签名是单段文字）。**不在输入时截断**：
     * 字符串版 TextField 在输入法组词期间改写文本会打断组词（拼音字母还会被算进上限），
     * 所以超长留到保存前由 [bioError] 拦下，计数在 ≥60 时已变红提醒。
     */
    fun flattenBio(text: String): String = text.replace(Regex("[\\r\\n]+"), " ")

    /** 保存前的签名校验：按码点超过 [MAX_BIO_RUNES] 返回提示，否则 null。 */
    fun bioError(text: String): String? =
        if (bioRunes(text.trim()) > MAX_BIO_RUNES) Str.s(R.string.profile_bio_too_long) else null
}
