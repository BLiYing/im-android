package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.PublicConfig
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.api.SmsRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机号与短信验证码的可见性判据（PHONE_AUTH_DESIGN §4.1 / §6 / §7.2）。 */
class PhoneAuthRulesTest {

    private val cn = listOf(SmsRegion("86", "CN"))
    private val full = PublicConfig(smsAvailable = true, smsRegions = cn, registerMethods = listOf("username", "phone"))
    private val noSms = PublicConfig(smsAvailable = false, registerMethods = listOf("username"))

    @Test fun `登录页签 短信可用两个 否则只有密码`() {
        assertEquals(listOf(LoginMode.Sms, LoginMode.Password), PhoneAuthRules.loginTabs(full))
        assertEquals(listOf(LoginMode.Password), PhoneAuthRules.loginTabs(noSms))
        assertEquals(listOf(LoginMode.Password), PhoneAuthRules.loginTabs(null))
        assertTrue(PhoneAuthRules.showLoginTabBar(PhoneAuthRules.loginTabs(full)))
        assertFalse(PhoneAuthRules.showLoginTabBar(PhoneAuthRules.loginTabs(null)))
    }

    @Test fun `默认页签 记住的仍可用就用它 否则第一个`() {
        val tabs = PhoneAuthRules.loginTabs(full)
        assertEquals(LoginMode.Sms, PhoneAuthRules.initialTab(tabs, null))
        assertEquals(LoginMode.Password, PhoneAuthRules.initialTab(tabs, LoginMode.Password))
        assertEquals(LoginMode.Password, PhoneAuthRules.initialTab(listOf(LoginMode.Password), LoginMode.Sms))
        assertEquals(LoginMode.Sms, LoginMode.parse("sms"))
        assertEquals(null, LoginMode.parse("x"))
    }

    @Test fun `忘记密码 用户名注册 自动注册提示 地区选择器`() {
        assertTrue(PhoneAuthRules.showForgotPassword(full))
        assertFalse(PhoneAuthRules.showForgotPassword(noSms))
        assertFalse(PhoneAuthRules.showForgotPassword(null))
        assertTrue(PhoneAuthRules.showUsernameRegister(full))
        assertTrue(PhoneAuthRules.showUsernameRegister(null)) // 老服务端 / 取不到：可用户名注册
        assertFalse(PhoneAuthRules.showUsernameRegister(full.copy(registerMethods = listOf("phone"))))
        assertTrue(PhoneAuthRules.showAutoRegisterHint(full))
        assertFalse(PhoneAuthRules.showAutoRegisterHint(full.copy(registerMethods = listOf("username"))))
        assertFalse(PhoneAuthRules.showRegionPicker(full.smsRegions))
        assertTrue(PhoneAuthRules.showRegionPicker(cn + SmsRegion("60", "MY")))
        assertEquals("86", PhoneAuthRules.defaultCountryCode(null))
        assertEquals("60", PhoneAuthRules.defaultCountryCode(full.copy(smsRegions = listOf(SmsRegion("60", "MY")))))
    }

    @Test fun `号码与验证码输入`() {
        assertEquals("13622220001", PhoneAuthRules.normalizePhone("136 2222-0001"))
        assertEquals("+86 136****0001", PhoneAuthRules.maskPhone("86", "136 2222 0001"))
        assertEquals("+86 12345", PhoneAuthRules.maskPhone("86", "12345"))
        assertFalse(PhoneAuthRules.canSendCode(" - ", 0, false))
        assertFalse(PhoneAuthRules.canSendCode("136", 5, false))
        assertFalse(PhoneAuthRules.canSendCode("136", 0, true))
        assertTrue(PhoneAuthRules.canSendCode("136", 0, false))
        assertEquals("123456", PhoneAuthRules.sanitizeCode("12a34 5678"))
        assertTrue(PhoneAuthRules.isCodeComplete("000000"))
        assertFalse(PhoneAuthRules.isCodeComplete("00000"))
    }

    @Test fun `确认身份弹层页签`() {
        val both = SecurityStatus(phoneBound = true, passwordSet = true, smsAvailable = true)
        assertEquals(listOf(ProofTab.Password, ProofTab.Sms), PhoneAuthRules.proofTabs(both))
        assertEquals(listOf(ProofTab.Sms), PhoneAuthRules.proofTabs(both.copy(passwordSet = false)))
        assertEquals(listOf(ProofTab.Password), PhoneAuthRules.proofTabs(both.copy(phoneBound = false)))
        assertEquals(listOf(ProofTab.Password), PhoneAuthRules.proofTabs(both.copy(smsAvailable = false)))
        // 没设密码 + 短信不可用：没有可用方式（不给假密码框）
        assertEquals(emptyList<ProofTab>(), PhoneAuthRules.proofTabs(both.copy(passwordSet = false, smsAvailable = false)))
        assertTrue(PhoneAuthRules.showProofSegments(PhoneAuthRules.proofTabs(both)))
        assertFalse(PhoneAuthRules.showProofSegments(listOf(ProofTab.Sms)))
        assertTrue(PhoneAuthRules.canConfirmProof(ProofTab.Password, "x", "", false))
        assertFalse(PhoneAuthRules.canConfirmProof(ProofTab.Sms, "x", "12345", false))
        assertTrue(PhoneAuthRules.canConfirmProof(ProofTab.Sms, "", "123456", false))
        assertFalse(PhoneAuthRules.canConfirmProof(ProofTab.Sms, "", "123456", true))
    }

    @Test fun `隐私与安全 登录手机号行与登录密码行`() {
        val unbound = SecurityStatus(phoneBound = false, passwordSet = true, smsAvailable = true)
        assertTrue(PhoneAuthRules.showPhoneRow(unbound))
        assertFalse(PhoneAuthRules.showPhoneRow(unbound.copy(smsAvailable = false))) // 没法绑
        assertTrue(PhoneAuthRules.showPhoneRow(unbound.copy(smsAvailable = false, phoneBound = true)))
        assertFalse(PhoneAuthRules.showPhoneRow(null))
        assertEquals(PhoneRowTap.Bind, PhoneAuthRules.phoneRowTap(unbound))
        assertEquals(PhoneRowTap.Change, PhoneAuthRules.phoneRowTap(unbound.copy(phoneBound = true)))
        assertEquals(PhoneRowTap.SmsUnavailable, PhoneAuthRules.phoneRowTap(unbound.copy(phoneBound = true, smsAvailable = false)))

        assertEquals(PasswordPageMode.Set, PhoneAuthRules.passwordPageMode(unbound.copy(passwordSet = false)))
        assertEquals(PasswordPageMode.Change, PhoneAuthRules.passwordPageMode(unbound))
        assertEquals(PasswordPageMode.Change, PhoneAuthRules.passwordPageMode(null))
        assertFalse(PhoneAuthRules.canChangePasswordBySms(unbound))
        assertTrue(PhoneAuthRules.canChangePasswordBySms(unbound.copy(phoneBound = true)))
    }

    @Test fun `密码页证明方式`() {
        val bound = SecurityStatus(phoneBound = true, passwordSet = true, smsAvailable = true)
        assertEquals(PasswordProofMode.Sms, PhoneAuthRules.passwordProofMode(PasswordPageMode.Set, false, bound))
        assertEquals(PasswordProofMode.OldPassword, PhoneAuthRules.passwordProofMode(PasswordPageMode.Change, false, bound))
        assertEquals(PasswordProofMode.Sms, PhoneAuthRules.passwordProofMode(PasswordPageMode.Change, true, bound))
        // 切到短信后服务端说短信不可用了：退回旧密码
        assertEquals(PasswordProofMode.OldPassword, PhoneAuthRules.passwordProofMode(PasswordPageMode.Change, true, bound.copy(smsAvailable = false)))
    }

    @Test fun `配置到达后的页签 动过就不切`() {
        val two = listOf(LoginMode.Sms, LoginMode.Password)
        assertEquals(LoginMode.Sms, PhoneAuthRules.tabAfterConfig(LoginMode.Password, touched = false, tabs = two, remembered = null))
        assertEquals(LoginMode.Password, PhoneAuthRules.tabAfterConfig(LoginMode.Password, touched = false, tabs = two, remembered = LoginMode.Password))
        assertEquals(LoginMode.Password, PhoneAuthRules.tabAfterConfig(LoginMode.Password, touched = true, tabs = two, remembered = LoginMode.Sms))
    }

    @Test fun `重载配置时国家码只在换地址或地区下线时重置`() {
        assertFalse(PhoneAuthRules.shouldResetCountryCode(false, "852", cn + SmsRegion("852", "HK")))
        assertTrue(PhoneAuthRules.shouldResetCountryCode(false, "852", cn))
        assertTrue(PhoneAuthRules.shouldResetCountryCode(true, "86", cn))
        assertFalse(PhoneAuthRules.shouldResetCountryCode(false, "852", emptyList()))
    }

    @Test fun `重设后预填的登录名 默认地区不带国家码 其余带加号`() {
        assertEquals("13622220201", PhoneAuthRules.loginIdentFor("86", "136 2222-0201", "86"))
        assertEquals("+85251234567", PhoneAuthRules.loginIdentFor("852", "5123 4567", "86"))
    }
}
