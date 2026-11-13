package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.LoginMode
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.api.PublicConfig
import com.libeyond.imandroid.sdk.api.SmsLoginResult
import com.libeyond.imandroid.sdk.api.SmsRegion
import com.libeyond.imandroid.sdk.api.SmsScene
import com.libeyond.imandroid.sdk.api.SmsSendResult
import com.libeyond.imandroid.sdk.api.TwoStepChallenge
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 登录页手机号部分（[LoginPhoneController]）：冷静期凭票恢复、页签记忆、重设后预填、注册开关。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginPhoneControllerTest {
    private val cn = listOf(SmsRegion("86", "CN"), SmsRegion("852", "HK"))
    private val smsCfg = PublicConfig(smsAvailable = true, smsRegions = cn, registerMethods = listOf("username", "phone"))

    @Before fun setUp() { IMLog.useSinksForTest(); Str.install(XmlStringResolver()) }

    private class Fake(override val scope: CoroutineScope) : LoginPhoneBackend {
        var cfg: PublicConfig? = null
        val sent = mutableListOf<SmsScene>()
        var loginError: ApiException? = null
        var restored: String? = null
        override suspend fun config(host: String) = cfg
        override suspend fun sendCode(host: String, scene: SmsScene, countryCode: String, phone: String): SmsSendResult {
            sent += scene; return SmsSendResult(resendAfter = 60)
        }
        override suspend fun loginSms(host: String, countryCode: String, phone: String, code: String): SmsLoginResult =
            throw loginError ?: AssertionError("unexpected")
        override suspend fun registerPhone(host: String, ticket: String, nickname: String) = Unit
        override suspend fun resetPassword(host: String, countryCode: String, phone: String, code: String, newPassword: String) = Unit
        override suspend fun restoreByTicket(host: String, ticket: String) { restored = ticket }
    }

    private class Hooks : LoginHooks {
        var pending: PendingRestore? = null
        override fun loggedIn() = Unit
        override fun twoStep(challenge: TwoStepChallenge) = Unit
        override fun restore(pending: PendingRestore) { this.pending = pending }
    }

    private fun TestScope.ctl(remembered: String? = null): Pair<LoginPhoneController, Fake> {
        val f = Fake(this)
        var saved = remembered
        return LoginPhoneController(f, loadTab = { saved }, saveTab = { saved = it }, now = { 1_000_000L }) to f
    }

    @Test fun `冷静期凭票恢复后不自动重发 倒计时保留 提示重新获取`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg; c.loadConfig("h")
        c.onLoginPhone("13622220201")
        c.sendLoginCode("h"); advanceUntilIdle()
        val left = c.login.timer.secondsLeft()
        assertTrue(left > 0)
        c.onLoginCode("000000")
        f.loginError = ApiException(ErrCode.ACCOUNT_PENDING_DELETION, "x", data = buildJsonObject { put("restore_ticket", "RT"); put("delete_at", 1L) })
        val hooks = Hooks()
        c.submitSms("h", hooks); advanceUntilIdle()
        val p = requireNotNull(hooks.pending)
        assertEquals("RT", p.ticket)
        c.restoreWithTicket("h", "RT"); advanceUntilIdle()
        assertEquals("RT", f.restored)
        assertEquals("恢复后不能自动发码（会撞每号码 60 秒间隔）", listOf(SmsScene.Login), f.sent)
        assertEquals(left, c.login.timer.secondsLeft())
        assertEquals("", c.login.code)
        assertEquals("账号已恢复，请重新获取验证码登录", c.notice)
    }

    @Test fun `配置晚到 没动过就按默认切到验证码登录`() = runTest {
        val (c, f) = ctl()
        assertEquals(LoginMode.Password, c.shownTab)
        f.cfg = smsCfg; c.loadConfig("h")
        assertEquals(LoginMode.Sms, c.shownTab)
    }

    @Test fun `配置晚到 已开始输入密码就不再翻到验证码页签`() = runTest {
        val (c, f) = ctl(remembered = "sms") // 记住的是验证码登录，但配置没到前显示的是密码登录
        assertEquals(LoginMode.Password, c.shownTab)
        c.touch() // 在密码框里打了字
        f.cfg = smsCfg; c.loadConfig("h")
        assertEquals(LoginMode.Password, c.shownTab)
    }

    @Test fun `配置晚到 点过页签也不再切 记住的页签可用时用它`() = runTest {
        val (c, f) = ctl(remembered = "password")
        f.cfg = smsCfg; c.loadConfig("h")
        assertEquals(LoginMode.Password, c.shownTab)
        c.selectTab(LoginMode.Sms)
        c.loadConfig("h2")
        assertEquals(LoginMode.Sms, c.shownTab)
    }

    @Test fun `重设密码后预填登录名 非默认地区带加号国家码`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg; c.loadConfig("h")
        c.open(LoginPane.Reset)
        c.onResetCountry("852"); c.onResetPhone("5123 4567"); c.onResetCode("000000")
        c.onNewPassword("abcdef"); c.onConfirmPassword("abcdef")
        c.submitReset("h"); advanceUntilIdle()
        assertEquals("+85251234567", c.prefillIdent)
        assertEquals(LoginMode.Password, c.shownTab)

        c.open(LoginPane.Reset)
        c.onResetCountry("86"); c.onResetPhone("136 2222 0201"); c.onResetCode("000000")
        c.onNewPassword("abcdef"); c.onConfirmPassword("abcdef")
        c.submitReset("h"); advanceUntilIdle()
        assertEquals("13622220201", c.prefillIdent)
    }

    @Test fun `注册开关从同一份配置推导 按地址记账`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg.copy(registerEnabled = false); c.loadConfig("h")
        assertTrue(c.registerClosed("h"))
        assertTrue(!c.registerClosed("other"))
        f.cfg = smsCfg; c.loadConfig("h")
        assertTrue(!c.registerClosed("h"))
    }

    @Test fun `完善资料页的已验证号码按本机输入打码`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg; c.loadConfig("h")
        c.onLoginPhone("136 2222 0201")
        assertEquals("+86 136****0201 已验证", c.completeVerifiedText)
    }

    /** 同一地址重载配置（如进用户名注册页）不打断用户选的地区与倒计时；换地址或地区下线才回默认。 */
    @Test fun `重载配置不重置已选国家码与倒计时`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg; c.loadConfig("h")
        c.onLoginCountry("852"); c.onLoginPhone("51234567")
        c.sendLoginCode("h"); advanceUntilIdle()
        c.loadConfig("h")
        assertEquals("852", c.login.countryCode)
        assertTrue(c.login.timer.secondsLeft() > 0)
        f.cfg = smsCfg.copy(smsRegions = listOf(SmsRegion("86", "CN"))); c.loadConfig("h")
        assertEquals("852 已不开放：回默认", "86", c.login.countryCode)
        c.onLoginCountry("86"); f.cfg = smsCfg
        c.loadConfig("h2")
        assertEquals("86", c.login.countryCode)
    }

    @Test fun `退出 被踢 注销回登录页 两张表的号码都清掉`() = runTest {
        val (c, f) = ctl()
        f.cfg = smsCfg; c.loadConfig("h")
        c.onLoginPhone("13622220211"); c.onLoginCode("123456")
        c.onResetPhone("13622220212")
        c.resetForms()
        assertEquals("", c.login.phone)
        assertEquals("", c.login.code)
        assertEquals("", c.reset.phone)
    }
}
