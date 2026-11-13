package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.PublicConfig
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.api.SmsRegion

/** 登录页的页签（PHONE_AUTH_DESIGN §4.1）。 */
enum class LoginMode(val wire: String) {
    Sms("sms"), Password("password");

    companion object {
        fun parse(v: String?): LoginMode? = entries.firstOrNull { it.wire == v }
    }
}

/** 确认身份弹层的页签（§7.2）。 */
enum class ProofTab { Password, Sms }

/** 「登录密码」行点进去是哪一页（§6.1 / §6.2）。 */
enum class PasswordPageMode { Set, Change }

/** 修改密码页上用哪种方式证明身份（§6.2：默认旧密码，可切短信验证码）。 */
enum class PasswordProofMode { OldPassword, Sms }

/** 点「登录手机号」行的结果（§6）。 */
enum class PhoneRowTap { Bind, Change, SmsUnavailable }

/**
 * 手机号与短信验证码的**可见性 / 去向判据**（页签、链接、行、弹层页签）。
 * 纯函数，不碰 Compose / Android；三端同一张表，改了要对照 Web / iOS 的同名判据。
 *
 * 入参为 null = 还没取到（或取不到）：登录页按老服务端口径（只有密码登录、可用户名注册），
 * 安全页按「先不显示新行」。
 */
object PhoneAuthRules {

    // —— 登录页 ——

    /** 短信可用时「验证码登录 / 密码登录」，否则只有密码登录（不画页签条）。 */
    fun loginTabs(cfg: PublicConfig?): List<LoginMode> =
        if (cfg?.smsAvailable == true) listOf(LoginMode.Sms, LoginMode.Password) else listOf(LoginMode.Password)

    /** 只有一个页签时不画页签条。 */
    fun showLoginTabBar(tabs: List<LoginMode>): Boolean = tabs.size > 1

    /** 默认页签：记住的那个（仍可用时）；否则第一个（短信可用时即验证码登录）。 */
    fun initialTab(tabs: List<LoginMode>, remembered: LoginMode?): LoginMode =
        remembered?.takeIf { it in tabs } ?: tabs.first()

    /**
     * public-config 到了（或换了地址）之后的页签：用户已经动过（点过页签 / 开始输入）就**不再自动切**，
     * 否则按 [initialTab]。避免配置晚到时把正在输密码的人翻到验证码页签。
     */
    fun tabAfterConfig(current: LoginMode, touched: Boolean, tabs: List<LoginMode>, remembered: LoginMode?): LoginMode =
        if (touched) current else initialTab(tabs, remembered)

    /**
     * 重设密码成功后回密码登录页预填的登录名：默认地区填本地号码（服务端按第一个开放地区补国家码）；
     * 其余地区带 `+国家码`，否则会按默认地区解析成另一个号码。
     */
    fun loginIdentFor(countryCode: String, phone: String, defaultCountryCode: String): String {
        val p = normalizePhone(phone)
        return if (countryCode == defaultCountryCode) p else "+$countryCode$p"
    }

    /** 「忘记密码？」只在短信可用时显示。 */
    fun showForgotPassword(cfg: PublicConfig?): Boolean = cfg?.smsAvailable == true

    /** 「用户名注册」链接：`register_methods` 含 `username`；取不到配置按可注册。 */
    fun showUsernameRegister(cfg: PublicConfig?): Boolean =
        cfg == null || PublicConfig.METHOD_USERNAME in cfg.registerMethods

    /** 「未注册的手机号验证后将自动注册」提示：只有允许手机号注册时才说这句。 */
    fun showAutoRegisterHint(cfg: PublicConfig?): Boolean = cfg?.registerMethods?.contains(PublicConfig.METHOD_PHONE) == true

    /** 地区只有一个时显示固定的 `+86`、不可点；多于一个才出选择器。 */
    fun showRegionPicker(regions: List<*>): Boolean = regions.size > 1

    /**
     * 重载 public-config 后是否把已选国家码重置成默认：换了服务器地址，或已选的不在新的开放地区里
     * （开放地区为空 = 短信不可用 / 老服务端，不动）。
     */
    fun shouldResetCountryCode(hostChanged: Boolean, current: String, regions: List<SmsRegion>): Boolean =
        hostChanged || (regions.isNotEmpty() && regions.none { it.code == current })

    /** 默认国家码（第一个开放地区；没有时 86）。 */
    fun defaultCountryCode(cfg: PublicConfig?): String = cfg?.smsRegions?.firstOrNull()?.code ?: DEFAULT_COUNTRY_CODE

    /** 号码输入去掉空格与短横线（服务端也能吃，但端上判空 / 判长按去掉之后的算）。 */
    fun normalizePhone(raw: String): String = raw.filterNot { it.isWhitespace() || it == '-' }

    /** 完善资料页「+86 136****0001 已验证」用的打码号码（与服务端 `phone_masked` 同款：前 3 后 4）。 */
    fun maskPhone(countryCode: String, raw: String): String {
        val p = normalizePhone(raw)
        return if (p.length < MASK_MIN_LEN) "+$countryCode $p" else "+$countryCode ${p.take(3)}****${p.takeLast(4)}"
    }

    /** 发码按钮可点：号码非空、不在倒计时、不在请求中。 */
    fun canSendCode(phone: String, secondsLeft: Int, busy: Boolean): Boolean =
        normalizePhone(phone).isNotEmpty() && secondsLeft <= 0 && !busy

    /** 验证码 6 位数字。 */
    fun isCodeComplete(code: String): Boolean = code.length == CODE_LEN && code.all { it in '0'..'9' }

    /** 验证码输入：只留数字、最多 6 位。 */
    fun sanitizeCode(raw: String): String = raw.filter { it in '0'..'9' }.take(CODE_LEN)

    // —— 确认身份弹层 ——

    /**
     * 弹层页签：没设密码只有验证码；没绑手机或短信不可用只有密码；都有时「密码 / 短信验证码」。
     * 没设密码、短信又不可用（短信通道关了 / 未绑手机）→ 空：没有可用的验证方式，弹层只提示 `proof_sms_unavailable`、不可确认
     * ——不能退回只给密码框，用户根本没有密码。状态未知时**不弹层**（`ProofController.open`），理由同上。
     */
    fun proofTabs(st: SecurityStatus): List<ProofTab> {
        val sms = st.phoneBound && st.smsAvailable
        return when {
            !st.passwordSet && sms -> listOf(ProofTab.Sms)
            st.passwordSet && sms -> listOf(ProofTab.Password, ProofTab.Sms)
            st.passwordSet -> listOf(ProofTab.Password)
            else -> emptyList()
        }
    }

    /** 只有一种方式时不显示分段控件。 */
    fun showProofSegments(tabs: List<ProofTab>): Boolean = tabs.size > 1

    /** 弹层能否确认：密码非空；验证码 6 位。 */
    fun canConfirmProof(tab: ProofTab, password: String, code: String, busy: Boolean): Boolean =
        !busy && when (tab) {
            ProofTab.Password -> password.isNotEmpty()
            ProofTab.Sms -> isCodeComplete(code)
        }

    // —— 隐私与安全 ——

    /** 「登录手机号」行：短信不可用且没绑定时不显示（没法绑）；状态未知不显示。 */
    fun showPhoneRow(st: SecurityStatus?): Boolean = st != null && (st.smsAvailable || st.phoneBound)

    /** 点「登录手机号」：未绑定 → 绑定；已绑定 → 更换；已绑定但短信不可用 → 提示（换不了，新号码收不到码）。 */
    fun phoneRowTap(st: SecurityStatus): PhoneRowTap = when {
        !st.smsAvailable -> PhoneRowTap.SmsUnavailable
        st.phoneBound -> PhoneRowTap.Change
        else -> PhoneRowTap.Bind
    }

    /** 「登录密码」行点进去：没设密码 → 设置；有 → 修改（状态未知按修改，即改造前的行为）。 */
    fun passwordPageMode(st: SecurityStatus?): PasswordPageMode =
        if (st?.passwordSet == false) PasswordPageMode.Set else PasswordPageMode.Change

    /** 修改密码页下方「用短信验证码修改」链接：已绑定手机号且短信可用。 */
    fun canChangePasswordBySms(st: SecurityStatus?): Boolean = st != null && st.phoneBound && st.smsAvailable

    /** 修改 / 设置密码页实际用哪种证明：设置只能验证码；修改按页面上的切换。 */
    fun passwordProofMode(mode: PasswordPageMode, switchedToSms: Boolean, st: SecurityStatus?): PasswordProofMode = when {
        mode == PasswordPageMode.Set -> PasswordProofMode.Sms
        switchedToSms && canChangePasswordBySms(st) -> PasswordProofMode.Sms
        else -> PasswordProofMode.OldPassword
    }

    const val CODE_LEN = 6
    private const val MASK_MIN_LEN = 7
    const val DEFAULT_COUNTRY_CODE = "86"
}
