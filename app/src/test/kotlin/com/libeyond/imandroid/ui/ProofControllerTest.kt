package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.ProofTab
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.api.Proof
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.api.VerifyCodeResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 确认身份弹层的状态机（[ProofController]）：打开时取状态、统一入口里的 reject / dismiss / 保留验证码、离开页面清码、100002 的落点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProofControllerTest {
    private val both = SecurityStatus(phoneBound = true, phoneMasked = "+86 136****0201", passwordSet = true, smsAvailable = true)
    private val smsOnly = both.copy(passwordSet = false)

    @Before fun setUp() { IMLog.useSinksForTest(); Str.install(XmlStringResolver()) }

    private var clock = 1_000_000L

    private fun TestScope.ctl(fetch: suspend () -> SecurityStatus): Pair<ProofController, SecurityState> {
        val sec = SecurityState(fetch, now = { clock })
        return ProofController(this, sec) { VerifyCodeResult(resendAfter = 60, phoneMasked = "+86 136****0201") } to sec
    }

    private fun api(code: Int, data: JsonObject? = null) = ApiException(code, "x", data = data)

    // —— 打开 ——

    @Test fun `没有旧值且取不到状态 不弹层 给可重试提示 打开期间是忙态`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (p, _) = ctl { gate.await(); throw ApiException(ApiException.TRANSPORT, "down") }
        p.open { }
        advanceUntilIdle()
        assertTrue("取状态期间宿主按钮要显示忙态", p.busy)
        assertFalse(p.visible)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse("取不到不能退化成只有密码页签", p.visible)
        assertFalse(p.busy)
        assertNotNull(p.openFailure)
    }

    @Test fun `没设密码的账号只有验证码页签`() = runTest {
        val (p, _) = ctl { smsOnly }
        p.open { }
        advanceUntilIdle()
        assertTrue(p.visible)
        assertEquals(listOf(ProofTab.Sms), p.tabs)
        assertEquals(ProofTab.Sms, p.tab)
    }

    @Test fun `有旧值立即用旧值弹出 后台重取校正页签`() = runTest {
        var current = both
        val (p, sec) = ctl { current }
        sec.refresh(); current = smsOnly // 旧值：有密码；服务端此刻：没密码（例如刚在别处…不可能，但页签要跟服务端）
        p.open { }
        assertTrue("有旧值不等网络", p.visible)
        assertEquals(listOf(ProofTab.Password, ProofTab.Sms), p.tabs)
        advanceUntilIdle()
        assertEquals(listOf(ProofTab.Sms), p.tabs)
        assertEquals(ProofTab.Sms, p.tab)
    }

    @Test fun `取状态途中离开页面 回来后不再弹出`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (p, _) = ctl { gate.await(); both }
        p.open { }
        advanceUntilIdle()
        p.clear()
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(p.visible)
    }

    // —— 统一入口 ——

    private fun openedWithCode(p: ProofController) {
        p.selectTab(ProofTab.Sms); p.onCode("123456")
    }

    @Test fun `成功 先收起清码 再回调`() = runTest {
        val (p, _) = ctl { both }
        var visibleAtOk: Boolean? = null
        p.submit(call = { "ok" }, onOk = { visibleAtOk = p.visible }, onPageError = { throw AssertionError() })
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertEquals(false, visibleAtOk)
        assertEquals("", p.code)
    }

    @Test fun `证明错 留在弹层 不交页面`() = runTest {
        val (p, _) = ctl { both }
        var page = 0
        p.submit(call = { throw api(ErrCode.SMS_CODE_INVALID, buildJsonObject { put("attempts_left", 3) }) }, onOk = {}, onPageError = { page++ })
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertTrue(p.visible)
        assertEquals(0, page)
        assertEquals("验证码不正确，还可尝试 3 次", p.error)
        assertEquals("123456", p.code)
    }

    @Test fun `页面错误 先收起再交页面 验证码保留 下次打开可直接重交`() = runTest {
        val (p, _) = ctl { both }
        var visibleAtPage: Boolean? = null
        var sent: Proof? = null
        var fail = true
        val call: suspend (Proof) -> Unit = { pr -> sent = pr; if (fail) throw api(ErrCode.NO_PERMISSION) } // 群主没转让
        p.submit(call, onOk = {}, onPageError = { visibleAtPage = p.visible })
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertEquals(false, visibleAtPage)
        assertEquals("123456", p.code)
        fail = false
        p.submit(call, onOk = {}, onPageError = {})
        advanceUntilIdle()
        assertEquals(ProofTab.Sms, p.tab)
        p.confirm(); advanceUntilIdle()
        assertEquals(Proof.SmsCode("123456"), sent)
    }

    @Test fun `离开页面清掉保留的验证码`() = runTest {
        val (p, _) = ctl { both }
        p.submit(call = { throw api(ErrCode.NO_PERMISSION) }, onOk = {}, onPageError = {})
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertEquals("123456", p.code)
        p.clear()
        assertEquals("", p.code)
    }

    @Test fun `100002 带不带 retry_after_sec 都交页面`() = runTest {
        val (p, _) = ctl { both }
        for (e in listOf(api(ErrCode.RATE_LIMITED, buildJsonObject { put("retry_after_sec", 42) }), api(ErrCode.RATE_LIMITED))) {
            var page: Throwable? = null
            p.submit(call = { throw e }, onOk = {}, onPageError = { page = it })
            advanceUntilIdle(); p.onPassword("pw")
            p.confirm(); advanceUntilIdle()
            assertEquals(e, page)
            assertFalse(p.visible)
        }
    }

    /** 提交中下滑 / 点外部：忽略。否则结果回来时弹层已关，证明错会被当成页面错误、过期码也不清。 */
    @Test fun `提交中关不掉弹层 证明错仍落在弹层里`() = runTest {
        val (p, _) = ctl { both }
        val gate = CompletableDeferred<Unit>()
        var page = 0
        p.submit(call = { gate.await(); throw api(ErrCode.SMS_CODE_EXPIRED) }, onOk = {}, onPageError = { page++ })
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertTrue(p.submitting)
        p.cancel()
        assertTrue(p.visible)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(p.visible)
        assertEquals(0, page)
        assertEquals("", p.code)
        p.cancel()
        assertFalse("不在提交中可以关", p.visible)
    }

    @Test fun `验证码过期 清掉码留在弹层`() = runTest {
        val (p, _) = ctl { both }
        p.submit(call = { throw api(ErrCode.SMS_CODE_EXPIRED) }, onOk = {}, onPageError = {})
        advanceUntilIdle(); openedWithCode(p)
        p.confirm(); advanceUntilIdle()
        assertTrue(p.visible)
        assertEquals("", p.code)
    }

    @Test fun `发码倒计时与打码号码`() = runTest {
        val (p, sec) = ctl { both }
        p.open { }; advanceUntilIdle()
        p.sendCode(); advanceUntilIdle()
        assertTrue(sec.verifyTimer.sentOnce)
        assertEquals("+86 136****0201", p.phoneMasked)
    }

    // —— 最近验证过免确认（PHONE_AUTH_DESIGN §15.2）——

    @Test fun `免确认还剩 15 秒以上 不弹层 直接发空证明`() = runTest {
        val (p, _) = ctl { both.copy(recentAuthSec = 15) }
        var sent: Proof? = null
        var ok: String? = null
        p.submit(call = { pr -> sent = pr; "done" }, onOk = { ok = it }, onPageError = { throw AssertionError() })
        advanceUntilIdle()
        assertFalse(p.visible)
        assertEquals(Proof.None, sent)
        assertEquals("done", ok)
    }

    @Test fun `免确认只剩 14 秒 照常弹层`() = runTest {
        val (p, _) = ctl { both.copy(recentAuthSec = 14) }
        var calls = 0
        p.submit(call = { calls++ }, onOk = {}, onPageError = {})
        advanceUntilIdle()
        assertTrue(p.visible)
        assertEquals(0, calls)
    }

    @Test fun `旧值的免确认秒数扣掉流逝的时间`() = runTest {
        val (p, sec) = ctl { both.copy(recentAuthSec = 60) }
        sec.refresh()
        clock += 46_000 // 还剩 14 秒
        assertEquals(14, sec.recentAuthLeft())
        var calls = 0
        p.submit(call = { calls++ }, onOk = {}, onPageError = {})
        assertTrue(p.visible)
        assertEquals(0, calls)
    }

    @Test fun `空证明回 200049 退回弹层 用用户给的证明重发`() = runTest {
        var server = 300 // 本地以为还剩 300 秒，服务端已重启清零（回 200049，之后重取也是 0）
        val (p, sec) = ctl { both.copy(recentAuthSec = server) }
        val sent = mutableListOf<Proof>()
        var ok = 0
        var page = 0
        p.submit(
            call = { pr -> sent += pr; if (pr == Proof.None) { server = 0; throw api(ErrCode.PROOF_REQUIRED) } else "ok" },
            onOk = { ok++ }, onPageError = { page++ },
        )
        advanceUntilIdle()
        assertTrue("200049 要弹层", p.visible)
        assertEquals(0, page)
        assertEquals(0, sec.recentAuthLeft())
        p.onPassword("pw"); p.confirm(); advanceUntilIdle()
        assertEquals(listOf(Proof.None, Proof.Password("pw")), sent)
        assertEquals(1, ok)
        assertFalse(p.visible)
        // 证明通过后本地记上免确认，下一次敏感操作不再弹层
        assertTrue(sec.recentAuthLeft() >= 15)
    }

    /** 服务端窗口从校验证明时起算：本地按发请求时刻记，请求耗时不算进窗口。 */
    @Test fun `证明通过后本地窗口从发请求时起算`() = runTest {
        val (p, sec) = ctl { both }
        p.submit(call = { clock += 20_000; "ok" }, onOk = {}, onPageError = {})
        advanceUntilIdle(); p.onPassword("pw"); p.confirm(); advanceUntilIdle()
        assertEquals(580, sec.recentAuthLeft())
    }

    @Test fun `空证明的其它错误直接交页面 不弹层`() = runTest {
        val (p, _) = ctl { both.copy(recentAuthSec = 300) }
        var page: Throwable? = null
        val e = api(ErrCode.NO_PERMISSION)
        p.submit(call = { throw e }, onOk = {}, onPageError = { page = it })
        advanceUntilIdle()
        assertFalse(p.visible)
        assertEquals(e, page)
    }

    @Test fun `空证明在途时离开页面 回 200049 不再弹层`() = runTest {
        val (p, _) = ctl { both.copy(recentAuthSec = 300) }
        val gate = CompletableDeferred<Unit>()
        p.submit(call = { gate.await(); throw api(ErrCode.PROOF_REQUIRED) }, onOk = {}, onPageError = {})
        advanceUntilIdle()
        assertTrue(p.busy)
        p.clear()
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(p.visible)
    }
}
