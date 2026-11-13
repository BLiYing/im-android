package com.libeyond.imandroid.ui

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.LoginMode
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.data.RegisterGate
import com.libeyond.imandroid.data.SmsErrors
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.AccountApi
import com.libeyond.imandroid.sdk.api.PhoneAuthApi
import com.libeyond.imandroid.sdk.api.PublicConfig
import com.libeyond.imandroid.sdk.api.PublicConfigApi
import com.libeyond.imandroid.sdk.api.SmsLoginResult
import com.libeyond.imandroid.sdk.api.SmsScene
import com.libeyond.imandroid.sdk.api.SmsSendResult
import com.libeyond.imandroid.sdk.api.TwoStepChallenge
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.loginSms
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.registerPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 登录页里同页切换的几块内容（PHONE_AUTH_DESIGN §4 / §5）。 */
enum class LoginPane { Main, Register, Complete, Reset }

/** 验证码登录的结局里要由外壳（`AppRoot`）接手的三种。 */
interface LoginHooks {
    /** 登录成功（含完善资料后）：进主界面。 */
    fun loggedIn()
    /** 两步验证第二步：走既有二步页。 */
    fun twoStep(challenge: TwoStepChallenge)
    /** 冷静期：弹既有恢复弹窗（带 `restore_ticket`）。 */
    fun restore(pending: PendingRestore)
}

/**
 * 登录页手机号部分要用的后端能力。生产由 [IMClient] 提供（[LoginPhoneController.of]），单测换假的。
 * 带 `host` 的调用先把 SDK 切到输入框里的地址。
 */
interface LoginPhoneBackend {
    val scope: CoroutineScope
    suspend fun config(host: String): PublicConfig?
    suspend fun sendCode(host: String, scene: SmsScene, countryCode: String, phone: String): SmsSendResult
    suspend fun loginSms(host: String, countryCode: String, phone: String, code: String): SmsLoginResult
    suspend fun registerPhone(host: String, ticket: String, nickname: String)
    suspend fun resetPassword(host: String, countryCode: String, phone: String, code: String, newPassword: String)
    suspend fun restoreByTicket(host: String, ticket: String)
}

/**
 * 登录页的手机号部分：public-config（页签 / 地区 / 注册开关）、验证码登录、完善资料、忘记密码、冷静期凭票恢复
 * （PHONE_AUTH_DESIGN §4 / §5）。密码登录与用户名注册的请求仍在 `AppRoot`（沿用既有路径）。
 *
 * 号码、验证码、票只在内存；**日志里不写号码与验证码**（只写事件名 + 业务码）。
 * 「记住上次成功使用的页签」经 [loadTab] / [saveTab] 写本机。
 */
class LoginPhoneController(
    private val backend: LoginPhoneBackend,
    private val loadTab: () -> String?,
    private val saveTab: (String) -> Unit,
    now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val log = IMLog.tag("IM.LoginSms")

    var config by mutableStateOf<PublicConfig?>(null); private set
    private var configHost: String? = null
    /** 注册开关（[RegisterGate]）：明确关闭了注册的 host。public-config 与提交注册回 200007 两路写这一份。 */
    var registerClosedFor by mutableStateOf<String?>(null)
    var pane by mutableStateOf(LoginPane.Main); private set
    private var tab by mutableStateOf(LoginMode.parse(loadTab()) ?: LoginMode.Password)
    /** 用户本次点过页签或开始输入了：配置晚到时不再替他换页签。 */
    private var tabTouched = false

    /** 验证码登录 / 忘记密码各一份号码 + 验证码 + 倒计时。 */
    val login = PhoneCodeForm(now)
    val reset = PhoneCodeForm(now)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf("")
        private set

    /** 完善资料。 */
    private var ticket: String? = null
    var nickname by mutableStateOf(""); private set

    var newPassword by mutableStateOf(""); private set
    var confirmPassword by mutableStateOf(""); private set

    /** 重设成功后回密码登录页要预填的登录名；登录页取走后置空。 */
    var prefillIdent by mutableStateOf<String?>(null)
    /** 回到登录页时显示的一次性成功提示（「密码已重设…」「账号已恢复…」），accent 色。 */
    var notice by mutableStateOf("")

    val tabs: List<LoginMode> get() = PhoneAuthRules.loginTabs(config)
    /** 实际显示的页签（配置变了、记住的页签不可用时回落）。 */
    val shownTab: LoginMode get() = if (tab in tabs) tab else tabs.first()
    private val defaultCountryCode: String get() = PhoneAuthRules.defaultCountryCode(config)

    /** 当前地址是否已确认关闭注册。 */
    fun registerClosed(host: String): Boolean = RegisterGate.isClosed(registerClosedFor, host)

    /**
     * 按输入框里的地址读 public-config（调用方防抖）。失败保留同一地址上次的值，换了地址按老服务端口径。
     * 同一份配置喂页签、地区、「注册是否开放」（[registerClosedFor]，按发起时的 host 记账）。
     */
    suspend fun loadConfig(host: String) {
        val cfg = backend.config(host)
        if (cfg == null && configHost == host) return
        val hostChanged = configHost != host
        config = cfg; configHost = host
        registerClosedFor = RegisterGate.afterResult(registerClosedFor, host, cfg?.registerEnabled ?: true)
        // 国家码只在换了服务器、或已选的不在新的开放地区里时才回到默认（同一地址重载配置不打断用户选的地区与倒计时）
        val cc = PhoneAuthRules.defaultCountryCode(cfg)
        val regions = cfg?.smsRegions.orEmpty()
        for (f in listOf(login, reset)) {
            if (PhoneAuthRules.shouldResetCountryCode(hostChanged, f.countryCode, regions)) f.onCountryCode(cc)
        }
        tab = PhoneAuthRules.tabAfterConfig(tab, tabTouched, tabs, LoginMode.parse(loadTab()))
    }

    fun selectTab(m: LoginMode) { if (busy) return; tab = m; tabTouched = true; error = "" }

    /** 用户开始在任一页签里输入：钉住当前显示的页签（配置晚到也不切走）。 */
    fun touch() { if (!tabTouched) { tab = shownTab; tabTouched = true } }

    fun open(p: LoginPane) { if (busy) return; pane = p; error = ""; notice = "" }
    fun onLoginPhone(v: String) { touch(); login.onPhone(v); error = "" }
    fun onLoginCode(v: String) { touch(); login.onCode(v); error = "" }
    fun onLoginCountry(v: String) { touch(); login.onCountryCode(v) }
    fun onNickname(v: String) { nickname = v; error = "" }
    fun onResetPhone(v: String) { reset.onPhone(v); error = "" }
    fun onResetCode(v: String) { reset.onCode(v); error = "" }
    fun onResetCountry(v: String) { reset.onCountryCode(v) }
    fun onNewPassword(v: String) { newPassword = v; error = "" }
    fun onConfirmPassword(v: String) { confirmPassword = v; error = "" }

    /** 登录成功后记住这次用的页签（两步验证第二步成功也算验证码 / 密码那一边）。 */
    fun commitTab() { saveTab(shownTab.wire) }

    /**
     * 退出登录 / 被踢 / 注销回到登录页：丢掉上一轮的票与输入，**号码也清**（与密码页签一致：
     * 上一个账号的完整手机号不能留在登录页给下一个人看）。
     */
    fun resetForms() {
        for (f in listOf(login, reset)) { f.onPhone(""); f.clearCode() }
        ticket = null; nickname = ""; newPassword = ""; confirmPassword = ""
        pane = LoginPane.Main; busy = false
    }

    // —— 验证码登录 ——
    fun sendLoginCode(host: String) = sendCode(host, SmsScene.Login, login)

    fun submitSms(host: String, hooks: LoginHooks) {
        val p = login.normalizedPhone
        when {
            p.isEmpty() -> { error = Str.s(R.string.login_error_phone_required); return }
            !PhoneAuthRules.isCodeComplete(login.code) -> { error = Str.s(R.string.login_error_code_required); return }
        }
        if (busy) return
        val (cc, c) = login.countryCode to login.code
        busy = true; error = ""; notice = ""
        backend.scope.launch {
            try {
                when (val r = backend.loginSms(host, cc, p, c)) {
                    is SmsLoginResult.LoggedIn -> { log.i("sms_login_ok"); login.clearCode(); commitTab(); hooks.loggedIn() }
                    is SmsLoginResult.NeedRegister -> {
                        log.i("sms_login_need_register"); ticket = r.ticket; login.clearCode(); nickname = ""; pane = LoginPane.Complete
                    }
                }
            } catch (e: ApiException) {
                log.w("sms_login_failed", "code" to e.code)
                val challenge = TwoStepChallenge.from(e)
                when {
                    challenge != null -> { login.clearCode(); hooks.twoStep(challenge) }
                    e.code == ErrCode.ACCOUNT_PENDING_DELETION -> {
                        login.clearCode() // 这次的码已用掉
                        val t = PhoneAuthApi.restoreTicket(e)
                        if (t != null) hooks.restore(PendingRestore("", "", AccountApi.deleteAtMillis(e.data), t))
                        else error = Str.s(R.string.err_200005)
                    }
                    else -> error = messageFor(e)
                }
            } finally { busy = false }
        }
    }

    /**
     * 冷静期账号凭一次性 `restore_ticket` 恢复。登录用的码已用掉，但**不自动重发**：每号码 60 秒间隔还没过，
     * 自动发只会撞 `100002`；倒计时照旧走，提示用户重新获取验证码登录。
     */
    fun restoreWithTicket(host: String, ticket: String) {
        if (busy) return
        busy = true; error = ""
        backend.scope.launch {
            try {
                backend.restoreByTicket(host, ticket)
                log.i("restore_by_ticket_ok")
                login.clearCode()
                notice = Str.s(R.string.account_restore_sms_done)
            } catch (e: ApiException) {
                log.w("restore_by_ticket_failed", "code" to e.code)
                error = messageFor(e)
            } finally { busy = false }
        }
    }

    // —— 完善资料 ——
    /** 「+86 136****0001 已验证」：按本机输入打码。 */
    val completeVerifiedText: String get() = Str.s(R.string.register_complete_verified, PhoneAuthRules.maskPhone(login.countryCode, login.phone))

    fun completeProfile(host: String, hooks: LoginHooks) {
        val t = ticket ?: run { backToLogin(Str.s(R.string.err_200048)); return }
        val n = nickname.trim()
        if (n.isEmpty() || busy) return
        busy = true; error = ""
        backend.scope.launch {
            try {
                backend.registerPhone(host, t, n)
                log.i("phone_register_ok")
                ticket = null; nickname = ""; pane = LoginPane.Main; tab = LoginMode.Sms; commitTab()
                hooks.loggedIn()
            } catch (e: ApiException) {
                log.w("phone_register_failed", "code" to e.code)
                // 票过期 / 号码被抢注：提示并回登录页重新验证；昵称不合规等可修正的错误留在本页
                if (e.code == ErrCode.PHONE_TICKET_INVALID || e.code == ErrCode.PHONE_TAKEN) { ticket = null; backToLogin(messageFor(e)) }
                else error = messageFor(e)
            } finally { busy = false }
        }
    }

    /** 完善资料页「返回」：丢票回验证码登录。 */
    fun leaveComplete() { if (!busy) { ticket = null; nickname = ""; pane = LoginPane.Main; error = "" } }

    private fun backToLogin(msg: String) { pane = LoginPane.Main; tab = LoginMode.Sms; error = msg }

    // —— 忘记密码 ——
    fun sendResetCode(host: String) = sendCode(host, SmsScene.Reset, reset)

    fun submitReset(host: String) {
        val p = reset.normalizedPhone
        when {
            p.isEmpty() -> { error = Str.s(R.string.login_error_phone_required); return }
            !PhoneAuthRules.isCodeComplete(reset.code) -> { error = Str.s(R.string.login_error_code_required); return }
            newPassword.length < ChangePasswordRules.MIN_LENGTH -> { error = Str.s(R.string.reset_too_short); return }
            newPassword != confirmPassword -> { error = Str.s(R.string.reset_mismatch); return }
        }
        if (busy) return
        val (cc, c, pw) = Triple(reset.countryCode, reset.code, newPassword)
        busy = true; error = ""
        backend.scope.launch {
            try {
                backend.resetPassword(host, cc, p, c, pw)
                log.i("password_reset_ok")
                reset.clearCode(); newPassword = ""; confirmPassword = ""
                // 回密码登录页并预填手机号（登录名解析认 5–15 位纯数字为默认地区手机号，别的地区要带 +国家码）
                prefillIdent = PhoneAuthRules.loginIdentFor(cc, p, defaultCountryCode); notice = Str.s(R.string.reset_done)
                pane = LoginPane.Main; tab = LoginMode.Password; tabTouched = true
            } catch (e: ApiException) {
                log.w("password_reset_failed", "code" to e.code)
                if (e.code == ErrCode.SMS_CODE_EXPIRED) reset.clearCode()
                error = messageFor(e)
            } finally { busy = false }
        }
    }

    // —— 共用 ——
    private fun sendCode(host: String, scene: SmsScene, form: PhoneCodeForm) {
        if (form.normalizedPhone.isEmpty()) { error = Str.s(R.string.login_error_phone_required); return }
        error = ""
        form.send(backend.scope, { cc, p -> backend.sendCode(host, scene, cc, p) }) { e ->
            if (e == null) log.i("sms_code_sent", "scene" to scene.wire)
            else {
                log.w("sms_code_failed", "scene" to scene.wire, "code" to ((e as? ApiException)?.code ?: 0))
                error = (e as? ApiException)?.takeIf { it.isTransport }?.let(::messageFor) ?: sendFailureText(e)
            }
        }
    }

    private fun messageFor(e: ApiException): String =
        if (e.isTransport) LoginError.friendly(e) { id, args -> Str.s(id, *args) }
        // 提交时的 100002 是验证防爆破（不是发码太频繁）：不带秒数，用通用「操作太频繁」
        else SmsErrors.message(e.code, PhoneAuthApi.intField(e, "attempts_left")) ?: LoginError.friendly(e) { id, args -> Str.s(id, *args) }

    companion object {
        private const val KEY_TAB = "last_login_tab"
        const val PREFS = "im_login"

        fun of(client: IMClient, prefs: SharedPreferences) = LoginPhoneController(
            backend = object : LoginPhoneBackend {
                override val scope get() = client.scope
                override suspend fun config(host: String) = PublicConfigApi.anonymous(host, BuildConfig.USE_TLS).fetch()
                override suspend fun sendCode(host: String, scene: SmsScene, countryCode: String, phone: String): SmsSendResult {
                    client.host = host; return client.phoneAuth.sendCode(scene, countryCode, phone, EmailRules.lang())
                }
                override suspend fun loginSms(host: String, countryCode: String, phone: String, code: String): SmsLoginResult {
                    client.host = host; return client.loginSms(countryCode, phone, code)
                }
                override suspend fun registerPhone(host: String, ticket: String, nickname: String) {
                    client.host = host; client.registerPhone(ticket, nickname)
                }
                override suspend fun resetPassword(host: String, countryCode: String, phone: String, code: String, newPassword: String) {
                    client.host = host; client.phoneAuth.resetPassword(countryCode, phone, code, newPassword)
                }
                override suspend fun restoreByTicket(host: String, ticket: String) {
                    client.host = host; client.phoneAuth.restoreByTicket(ticket)
                }
            },
            loadTab = { prefs.getString(KEY_TAB, null) },
            saveTab = { prefs.edit().putString(KEY_TAB, it).apply() },
        )
    }
}
