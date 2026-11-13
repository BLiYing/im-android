package com.libeyond.imandroid.ui

import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.api.LegacyPhone
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 「登录手机号」页的旧号码提示（PHONE_AUTH_DESIGN §15.1）：显示条件、「绑定」预填、「不再提示」成功 / 失败。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyPhonePromptTest {
    private val legacy = LegacyPhone("60", "123456789", "+60 12****789")
    private val unbound = SecurityStatus(phoneBound = false, passwordSet = true, smsAvailable = true, legacyPhone = legacy)

    @Before fun setUp() { IMLog.useSinksForTest(); Str.install(XmlStringResolver()) }

    private suspend fun TestScope.prompt(st: SecurityStatus, dismiss: suspend () -> Unit = {}): Pair<LegacyPhonePrompt, SecurityState> {
        val sec = SecurityState({ st }, now = { 0L })
        sec.refresh()
        return LegacyPhonePrompt(sec, this, dismiss) to sec
    }

    @Test fun `有旧号码且未绑定 显示打码号码`() = runTest {
        val (p, _) = prompt(unbound)
        assertTrue(p.visible)
        assertEquals("+60 12****789", p.masked)
    }

    @Test fun `已绑定 没有旧号码 短信不可用 都不显示`() = runTest {
        assertFalse(prompt(unbound.copy(phoneBound = true)).first.visible)
        assertFalse(prompt(unbound.copy(legacyPhone = null)).first.visible)
        assertFalse(prompt(unbound.copy(smsAvailable = false)).first.visible)
    }

    @Test fun `绑定 预填国家码与号码 提示收起 重进页再出现`() = runTest {
        val (p, _) = prompt(unbound)
        val form = PhoneCodeForm { 0L }
        p.bind(form)
        assertEquals("60", form.countryCode)
        assertEquals("123456789", form.phone)
        assertTrue(p.prefilled)
        assertFalse(p.visible)
        p.reset()
        assertTrue(p.visible)
    }

    @Test fun `不再提示 成功后本地去掉旧号码`() = runTest {
        var calls = 0
        val (p, sec) = prompt(unbound) { calls++ }
        var toast: String? = null
        p.dismiss { toast = it }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(toast)
        assertNull(sec.status?.legacyPhone)
        assertFalse(p.visible)
        assertFalse(p.dismissing)
    }

    @Test fun `不再提示 失败吐司 提示保留`() = runTest {
        val (p, sec) = prompt(unbound) { throw ApiException(ApiException.TRANSPORT, "down") }
        var toast: String? = null
        p.dismiss { toast = it }
        advanceUntilIdle()
        assertNotNull(toast)
        assertEquals(legacy, sec.status?.legacyPhone)
        assertTrue(p.visible)
        assertFalse(p.dismissing)
    }
}
