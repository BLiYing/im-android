package com.libeyond.imandroid.data

import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.api.EmailStatus
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 邮箱登录的纯规则（设计 EMAIL_LOGIN_DESIGN §2 / §3.4 / §3.9 / §5）。 */
class EmailRulesTest {
    @Before fun chinese() = Str.install(XmlStringResolver())
    @After fun restore() = Str.install(XmlStringResolver())

    @Test fun `预检 合规邮箱通过 含大小写 加号 点号 首尾空白`() {
        assertTrue(EmailRules.isPlausible("a@b.com"))
        assertTrue(EmailRules.isPlausible("  Alice.Smith+tag@Example.COM  "))
    }

    @Test fun `预检 缺 @ 多 @ 无点 空白 空串 local 为空 都拦`() {
        assertFalse(EmailRules.isPlausible(""))
        assertFalse(EmailRules.isPlausible("   "))
        assertFalse(EmailRules.isPlausible("alice.example.com"))
        assertFalse(EmailRules.isPlausible("a@@b.com"))
        assertFalse(EmailRules.isPlausible("a@b@c.com"))
        assertFalse(EmailRules.isPlausible("alice@example")) // @ 后没有 .
        assertFalse(EmailRules.isPlausible("@example.com"))
        assertFalse(EmailRules.isPlausible("a b@c.com"))
        assertFalse(EmailRules.isPlausible("a@b .com"))
        assertFalse(EmailRules.isPlausible("a\tb@c.com"))
    }

    @Test fun `预检 长度边界 254 通过 255 拦`() {
        val tail = "@example.com"
        assertTrue(EmailRules.isPlausible("a".repeat(254 - tail.length) + tail))
        assertFalse(EmailRules.isPlausible("a".repeat(255 - tail.length) + tail))
    }

    @Test fun `发送按钮 两格有内容才可点 格式错不置灰`() {
        assertTrue(EmailRules.canSend("alice@example", "pw", busy = false)) // 格式错照样可点，点后红字
        assertFalse(EmailRules.canSend("", "pw", false))
        assertFalse(EmailRules.canSend("a@b.com", "", false))
        assertFalse(EmailRules.canSend("a@b.com", "pw", true))
    }

    @Test fun `业务码映射`() {
        assertEquals("密码不正确", msg(ErrCode.WRONG_PASSWORD))
        assertEquals("邮箱格式不正确", msg(200030))
        assertEquals("该邮箱已被其他账号使用", msg(200031))
        assertEquals("验证码不正确", msg(200032))
        assertEquals("验证码已失效，请重新发送", msg(200033))
        assertEquals("当前未绑定邮箱", msg(200034))
        assertEquals("新邮箱与当前邮箱相同", msg(200035))
        assertEquals("暂不支持邮箱绑定", msg(200036))
        assertEquals("验证码发送失败，请稍后重试", msg(200037))
        assertEquals("发送过于频繁，请稍后再试", msg(ErrCode.RATE_LIMITED))
        assertNull(msg(999999)) // 未收录：调用方走通用兜底
    }

    @Test fun `验证码错 剩余次数 小于等于 2 才提示`() {
        assertEquals("验证码不正确，还可尝试 2 次", msg(200032, left = 2))
        assertEquals("验证码不正确，还可尝试 1 次", msg(200032, left = 1))
        assertEquals("验证码不正确", msg(200032, left = 3))
        assertEquals("验证码不正确", msg(200032, left = null))
        assertEquals("验证码不正确，还可尝试 0 次", msg(200032, left = 0))
    }

    @Test fun `限流 带 retry_after_sec 显示秒数 否则通用`() {
        assertEquals("发送过于频繁，请 42 秒后再试", msg(ErrCode.RATE_LIMITED, retry = 42))
        assertEquals("发送过于频繁，请稍后再试", msg(ErrCode.RATE_LIMITED, retry = 0))
        assertEquals("发送过于频繁，请稍后再试", msg(ErrCode.RATE_LIMITED, retry = null))
    }

    @Test fun `倒计时 向上取整 到点为 0 不为负`() {
        val d = EmailRules.resendDeadline(10_000, 60)
        assertEquals(70_000L, d)
        assertEquals(60, EmailRules.secondsLeft(d, 10_000))
        assertEquals(60, EmailRules.secondsLeft(d, 10_001)) // 59.999s 仍显示 60
        assertEquals(59, EmailRules.secondsLeft(d, 11_000))
        assertEquals(1, EmailRules.secondsLeft(d, 69_999))
        assertEquals(0, EmailRules.secondsLeft(d, 70_000))
        assertEquals(0, EmailRules.secondsLeft(d, 99_999))
        // 服务端 retry_after_sec = 0：现在就能重发；负数同
        assertEquals(0, EmailRules.secondsLeft(EmailRules.resendDeadline(10_000, 0), 10_000))
        assertEquals(10_000L, EmailRules.resendDeadline(10_000, -5))
    }

    @Test fun `重发文案 倒计时中带秒数`() {
        assertEquals("重新发送（59s）", EmailRules.resendLabel(59))
        assertEquals("重新发送", EmailRules.resendLabel(0))
    }

    @Test fun `返回去向 验证码回填表页 填表页回说明或已绑定页`() {
        assertNull(EmailRules.backTarget(EmailStep.Intro, false))
        assertNull(EmailRules.backTarget(EmailStep.Bound, true))
        assertEquals(EmailStep.Intro, EmailRules.backTarget(EmailStep.Form, false))
        assertEquals(EmailStep.Bound, EmailRules.backTarget(EmailStep.Form, true))
        assertEquals(EmailStep.Form, EmailRules.backTarget(EmailStep.Code, true))
        assertEquals(EmailStep.Bound, EmailRules.backTarget(EmailStep.Unbind, true))
    }

    @Test fun `落页与行右值 未取到留空 未绑定 邮箱`() {
        assertEquals(EmailStep.Intro, EmailRules.homeStep(null))
        assertEquals(EmailStep.Intro, EmailRules.homeStep(EmailStatus(bound = false)))
        assertEquals(EmailStep.Bound, EmailRules.homeStep(EmailStatus(bound = true, email = "a@b.com")))
        assertEquals("", EmailRules.rowLabel(null))
        assertEquals("未绑定", EmailRules.rowLabel(EmailStatus(bound = false)))
        assertEquals("a@b.com", EmailRules.rowLabel(EmailStatus(bound = true, email = "a@b.com")))
    }

    @Test fun `available false 才置灰主按钮 没取到当可用`() {
        assertFalse(EmailRules.introDisabled(null))
        assertFalse(EmailRules.introDisabled(EmailStatus(available = true)))
        assertTrue(EmailRules.introDisabled(EmailStatus(available = false)))
    }

    @Test fun `日志脱敏 邮箱只留首字符和域名`() {
        assertEquals("a***@gmail.com", EmailRules.maskForLog("alice@gmail.com"))
        assertEquals("*@x.io", EmailRules.maskForLog("a@x.io"))
        assertEquals("***", EmailRules.maskForLog("not-an-email"))
        assertNotNull(EmailRules.lang())
    }

    @Test fun `请求语言 中文 zh-Hans 英文 en`() {
        assertEquals("zh-Hans", EmailRules.lang())
        Str.install(XmlStringResolver("en"))
        assertEquals("en", EmailRules.lang())
    }

    private fun msg(code: Int, left: Int? = null, retry: Int? = null) =
        EmailRules.message(EmailRules.kindOf(code), left, retry)
}
