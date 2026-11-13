package com.libeyond.imandroid.data

import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 短信验证码错误码映射与提示落点（PROTOCOL §7.3 / §8）。 */
class SmsErrorsTest {

    @Before fun zh() = Str.install(XmlStringResolver())
    @After fun restore() = Str.install(XmlStringResolver())

    @Test fun `200040-200048 走 err 键 200043 带剩余次数`() {
        assertEquals("短信验证暂不可用", SmsErrors.message(ErrCode.SMS_UNAVAILABLE))
        assertEquals("验证码不正确", SmsErrors.message(ErrCode.SMS_CODE_INVALID))
        assertEquals("验证码不正确，还可尝试 3 次", SmsErrors.message(ErrCode.SMS_CODE_INVALID, attemptsLeft = 3))
        assertEquals("该手机号未注册，可直接用验证码登录完成注册", SmsErrors.message(ErrCode.PHONE_NOT_REGISTERED))
        assertEquals("验证已过期，请重新验证手机号", SmsErrors.message(ErrCode.PHONE_TICKET_INVALID))
        assertNull(SmsErrors.message(ErrCode.EMAIL_INVALID))
    }

    @Test fun `限流 发码带秒数才说秒数 其余走 ErrorText 的通用文案`() {
        assertEquals("发送过于频繁，请 42 秒后再试", SmsErrors.message(ErrCode.RATE_LIMITED, retryAfterSec = 42))
        assertEquals("操作过于频繁，请稍后再试", SmsErrors.message(ErrCode.RATE_LIMITED))
        assertEquals("操作过于频繁，请稍后再试", SmsErrors.message(ErrCode.RATE_LIMITED, retryAfterSec = 0))
    }

    /** 码 → 文案只有一张表（ErrorText.friendlyRes）：SmsErrors 不带参数时与它逐码一致。 */
    @Test fun `不带参数的码与 ErrorText 同源`() {
        for (code in listOf(ErrCode.SMS_UNAVAILABLE, ErrCode.PHONE_INVALID, ErrCode.PHONE_TAKEN, ErrCode.SMS_CODE_INVALID, ErrCode.SMS_CODE_EXPIRED,
            ErrCode.SMS_SEND_FAILED, ErrCode.SMS_DAILY_CAP, ErrCode.PHONE_NOT_REGISTERED, ErrCode.PHONE_TICKET_INVALID, ErrCode.WRONG_PASSWORD)) {
            assertEquals(ErrorText.friendlyRes(code)?.let { Str.s(it) }, SmsErrors.message(code))
        }
    }

    @Test fun `倒计时从 retry_after_sec 开始`() {
        assertEquals(42, SmsErrors.countdownAfterFailure(ErrCode.RATE_LIMITED, 42))
        assertEquals(30, SmsErrors.countdownAfterFailure(ErrCode.SMS_CODE_EXPIRED, 30))
        assertNull(SmsErrors.countdownAfterFailure(ErrCode.SMS_CODE_EXPIRED, 0))
        assertNull(SmsErrors.countdownAfterFailure(ErrCode.SMS_CODE_INVALID, 30))
    }

    @Test fun `提示落点 data_field 优先 其余按码`() {
        assertFalse(SmsErrors.isProofError(ErrCode.SMS_CODE_INVALID, "code")) // 新号码的码错：页面
        assertTrue(SmsErrors.isProofError(ErrCode.SMS_CODE_INVALID, "proof"))
        assertTrue(SmsErrors.isProofError(ErrCode.WRONG_PASSWORD, null))
        assertTrue(SmsErrors.isProofError(ErrCode.SMS_CODE_EXPIRED, null))
        assertFalse(SmsErrors.isProofError(ErrCode.NO_PERMISSION, null)) // 群主没转让
        assertFalse(SmsErrors.isProofError(ErrCode.PHONE_TAKEN, null))
    }

    /** 100002 一律交页面（与 Web 对齐）：邮箱每日 / 每邮箱 / 每 IP 限额回的 100002 不带秒数，也不是「证明错了」。 */
    @Test fun `100002 带不带秒数都交页面`() {
        assertFalse(SmsErrors.isProofError(ErrCode.RATE_LIMITED, null))
    }
}
