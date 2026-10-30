package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStepRulesTest {

    @Test fun `6 位清洗 过滤非数字 截 6 位 粘贴带空格`() {
        assertEquals("123456", TwoStepRules.sanitizeCode("123 456"))
        assertEquals("123456", TwoStepRules.sanitizeCode("1234567890"))
        assertEquals("12", TwoStepRules.sanitizeCode("a1-b2"))
        assertEquals("", TwoStepRules.sanitizeCode("abc"))
        assertEquals("", TwoStepRules.sanitizeCode("１２３")) // 全角数字不算
    }

    @Test fun `恢复码归一化 大小写 连字符 空格不敏感`() {
        assertEquals("k7m2q9xw3p", TwoStepRules.normalizeRecovery(" K7M2Q-9xw3P "))
        assertTrue(TwoStepRules.isRecoveryComplete("k7m2q-9xw3p"))
        assertFalse(TwoStepRules.isRecoveryComplete("k7m2q-9xw3"))
    }

    @Test fun `恢复码输入过滤只限长 超长整次忽略 不改写已有文本`() {
        assertEquals("k7m2q-9xw3p", TwoStepRules.acceptRecoveryInput("k7m2q-9xw3", "k7m2q-9xw3p"))
        assertEquals("k7m2q-9xw3p", TwoStepRules.acceptRecoveryInput("k7m2q-9xw3p", "k7m2q-9xw3p-zzzz"))
        // 大写原样保留（归一化只在提交时做）
        assertEquals("K7M", TwoStepRules.acceptRecoveryInput("K7", "K7M"))
    }

    @Test fun `提取恢复码 带前缀 换行 整份清单 大小写`() {
        assertEquals("y6wa3-pj29r", TwoStepRules.extractRecoveryCode("y6wa3-pj29r"))
        assertEquals("y6wa3-pj29r", TwoStepRules.extractRecoveryCode("  Y6WA3 PJ29R \n"))
        assertEquals("y6wa3-pj29r", TwoStepRules.extractRecoveryCode("y6wa3pj29r"))
        assertEquals("y6wa3-pj29r", TwoStepRules.extractRecoveryCode("1. y6wa3-pj29r\n2. jzev7-wbws5"))
        assertEquals("jzev7-wbws5", TwoStepRules.extractRecoveryCode("IM 恢复码（每个只能使用一次）\n\njzev7-wbws5\nku729-kh5qp\n"))
        assertNull(TwoStepRules.extractRecoveryCode("hello world"))
        assertNull(TwoStepRules.extractRecoveryCode("y6wa3-pj29"))
        assertNull(TwoStepRules.extractRecoveryCode("0000000000")) // 0/1/i/l/o 不在字符集
    }

    @Test fun `恢复码输入 粘贴多字符或含换行或超长时提取 逐字输入不改写`() {
        val list = (1..10).joinToString("\n") { "y6wa3-pj29r" }
        assertEquals("y6wa3-pj29r", TwoStepRules.acceptRecoveryInput("", list))
        assertEquals("y6wa3-pj29r", TwoStepRules.acceptRecoveryInput("", "1. y6wa3-pj29r"))
        assertEquals("y6wa3-pj29r", TwoStepRules.acceptRecoveryInput("abc", "abc\ny6wa3-pj29r\n"))
        // 提取不到：仍按原规则（超长忽略、合规照收）
        assertEquals("ab", TwoStepRules.acceptRecoveryInput("ab", "ab" + "x".repeat(20)))
        // 逐字输入（大写 / 连字符）一律原样，不被提取改写
        assertEquals("Y6WA3-PJ29", TwoStepRules.acceptRecoveryInput("Y6WA3-PJ2", "Y6WA3-PJ29"))
        assertEquals("Y6WA3-", TwoStepRules.acceptRecoveryInput("Y6WA3", "Y6WA3-"))
    }

    @Test fun `满 6 位且不忙才自动提交`() {
        assertTrue(TwoStepRules.shouldAutoSubmit("123456", busy = false))
        assertFalse(TwoStepRules.shouldAutoSubmit("12345", busy = false))
        assertFalse(TwoStepRules.shouldAutoSubmit("123456", busy = true))
        assertFalse(TwoStepRules.shouldAutoSubmit("abcdef", busy = false)) // 恢复码前缀不是动态码
    }

    @Test fun `提交码 恢复码模式归一化 动态码原样`() {
        assertEquals("k7m2q9xw3p", TwoStepRules.submitCode("K7M2Q-9XW3P", recoveryMode = true))
        assertEquals("123456", TwoStepRules.submitCode("123456", recoveryMode = false))
        assertTrue(TwoStepRules.canSubmitVerify("pw", "123456", false))
        assertFalse(TwoStepRules.canSubmitVerify("", "123456", false))
        assertFalse(TwoStepRules.canSubmitVerify("pw", "12345", false))
        assertTrue(TwoStepRules.canSubmitVerify("pw", "K7M2Q-9XW3P", true))
        assertFalse(TwoStepRules.canSubmitVerify("pw", "123456", true))
    }

    @Test fun `密钥 4 位一组 复制时无分隔`() {
        assertEquals("JBSW Y3DP EHPK 3PXP", TwoStepRules.groupSecret("jbswy3dpehpk3pxp"))
        assertEquals("ABCD EF", TwoStepRules.groupSecret("ABCDEF"))
        assertEquals("JBSWY3DP", TwoStepRules.copySecret("JBSW Y3DP"))
    }

    @Test fun `恢复码两列 复制全部 文件文本`() {
        val codes = (1..10).map { "c$it" }
        val rows = TwoStepRules.codeRows(codes)
        assertEquals(5, rows.size)
        assertEquals(listOf("c1", "c2"), rows.first())
        assertEquals(codes.joinToString("\n"), TwoStepRules.copyAllText(codes))
        assertEquals("H\n\nc1\nc2\n", TwoStepRules.fileText("H", listOf("c1", "c2")))
    }

    @Test fun `剩余 2 及以下算快用完 footer 优先级`() {
        assertTrue(TwoStepRules.isLow(2))
        assertFalse(TwoStepRules.isLow(3))
        assertEquals("恢复码快用完了，建议重新生成。", TwoStepRules.statusFooter(2, unsaved = true))
        assertEquals("建议重新生成并妥善保存恢复码。", TwoStepRules.statusFooter(8, unsaved = true))
        assertEquals("", TwoStepRules.statusFooter(8, unsaved = false))
        assertEquals("剩余 8 个", TwoStepRules.remainingLabel(8))
    }

    @Test fun `隐私页行右值 未取到留空`() {
        assertEquals("", TwoStepRules.rowLabel(null))
        assertEquals("开启", TwoStepRules.rowLabel(true))
        assertEquals("关闭", TwoStepRules.rowLabel(false))
    }

    @Test fun `返回去向与 leave 拦截只在恢复码页`() {
        assertNull(TwoStepRules.backTarget(TwoStepStep.Intro))
        assertNull(TwoStepRules.backTarget(TwoStepStep.Status))
        assertEquals(TwoStepStep.Intro, TwoStepRules.backTarget(TwoStepStep.Bind))
        assertEquals(TwoStepStep.Status, TwoStepRules.backTarget(TwoStepStep.Disable))
        assertEquals(TwoStepStep.Status, TwoStepRules.backTarget(TwoStepStep.Regen))
        TwoStepStep.entries.forEach { assertEquals(it == TwoStepStep.Codes, TwoStepRules.needsLeaveConfirm(it)) }
    }

    @Test fun `错误码到分类与文案`() {
        assertEquals(TwoStepErrorKind.WrongPassword, TwoStepRules.kindOf(200002))
        assertEquals(TwoStepErrorKind.CodeInvalid, TwoStepRules.kindOf(200021))
        assertEquals(TwoStepErrorKind.Expired, TwoStepRules.kindOf(200022))
        assertEquals(TwoStepErrorKind.NotEnabled, TwoStepRules.kindOf(200023))
        assertEquals(TwoStepErrorKind.AlreadyEnabled, TwoStepRules.kindOf(200024))
        assertEquals(TwoStepErrorKind.NotEnabled, TwoStepRules.kindOf(200023))
        assertEquals(TwoStepErrorKind.Unavailable, TwoStepRules.kindOf(200025))
        assertEquals(TwoStepErrorKind.RateLimited, TwoStepRules.kindOf(100002))
        assertEquals(TwoStepErrorKind.Other, TwoStepRules.kindOf(999999))
        assertEquals("密码不正确", TwoStepRules.message(TwoStepErrorKind.WrongPassword))
        assertEquals("验证码不正确，请检查认证器里的最新一个", TwoStepRules.message(TwoStepErrorKind.CodeInvalid))
        assertEquals("已超时，请重新开始", TwoStepRules.message(TwoStepErrorKind.Expired))
        assertNull(TwoStepRules.message(TwoStepErrorKind.Other))
    }

    @Test fun `登录第二步 剩余次数 小于等于 2 才提示`() {
        val k = TwoStepErrorKind.CodeInvalid
        assertEquals("验证码不正确，还可尝试 2 次", TwoStepRules.message(k, 2, login = true))
        assertEquals("验证码不正确，还可尝试 1 次", TwoStepRules.message(k, 1, login = true))
        assertEquals("验证码不正确", TwoStepRules.message(k, 3, login = true))
        assertEquals("验证码不正确", TwoStepRules.message(k, null, login = true))
        assertEquals("已超时，请重新登录", TwoStepRules.message(TwoStepErrorKind.Expired, login = true))
    }
}
