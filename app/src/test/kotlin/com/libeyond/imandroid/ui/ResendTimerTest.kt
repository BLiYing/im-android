package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.api.DEFAULT_RESEND_SEC
import com.libeyond.imandroid.sdk.api.SmsSendResult
import com.libeyond.imandroid.sdk.api.VerifyCodeResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 发码倒计时（四处共用）：回包秒数 / 缺省 60 / 失败按 retry_after_sec 重建 / 按号码计。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendTimerTest {
    private var clock = 1_000_000L

    @Before fun setUp() = IMLog.useSinksForTest()

    @Test fun `回包秒数的派生属性 非正数回落 60`() {
        assertEquals(45, SmsSendResult(resendAfter = 45).resendAfterSec)
        assertEquals(DEFAULT_RESEND_SEC, SmsSendResult(resendAfter = 0).resendAfterSec)
        assertEquals(DEFAULT_RESEND_SEC, VerifyCodeResult(resendAfter = -1).resendAfterSec)
    }

    @Test fun `成功按回包起倒计时 倒计时内不再发`() = runTest {
        val t = ResendTimer { clock }
        var calls = 0
        assertTrue(t.send(this, { calls++; SmsSendResult(resendAfter = 30) }))
        assertFalse("在途不能连发", t.send(this, { calls++; SmsSendResult() }))
        advanceUntilIdle()
        assertEquals(30, t.secondsLeft()); assertTrue(t.sentOnce)
        assertFalse(t.send(this, { calls++; SmsSendResult() }))
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun `失败带 retry_after_sec 时按它重建 并把错误交回`() = runTest {
        val t = ResendTimer { clock }
        var got: Throwable? = null
        t.send(this, { throw ApiException(ErrCode.RATE_LIMITED, "x", data = buildJsonObject { put("retry_after_sec", 42) }) }) { got = it }
        advanceUntilIdle()
        assertEquals(42, t.secondsLeft())
        assertEquals(ErrCode.RATE_LIMITED, (got as ApiException).code)
        assertFalse(t.sending)
    }

    @Test fun `倒计时按号码计 换号清掉 同号保留`() = runTest {
        val f = PhoneCodeForm { clock }
        f.onPhone("136 2222 0201")
        f.send(this, { _, _ -> SmsSendResult(resendAfter = 60) }) {}
        advanceUntilIdle()
        assertEquals(60, f.timer.secondsLeft())
        f.onPhone("13622220201") // 只是去掉空格：同一个号码
        assertEquals(60, f.timer.secondsLeft())
        f.onPhone("13622220202")
        assertEquals(0, f.timer.secondsLeft())
        assertFalse(f.timer.sentOnce)
    }

    @Test fun `换国家码也算换号`() = runTest {
        val f = PhoneCodeForm { clock }
        f.onPhone("13622220201")
        f.send(this, { _, _ -> SmsSendResult() }) {}
        advanceUntilIdle()
        f.onCountryCode("852")
        assertEquals(0, f.timer.secondsLeft())
    }
}
