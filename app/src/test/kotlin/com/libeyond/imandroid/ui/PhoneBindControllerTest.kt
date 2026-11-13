package com.libeyond.imandroid.ui

import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.api.LegacyPhone
import com.libeyond.imandroid.sdk.api.Proof
import com.libeyond.imandroid.sdk.api.PublicConfig
import com.libeyond.imandroid.sdk.api.ResendHint
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.api.SmsRegion
import com.libeyond.imandroid.sdk.api.SmsSendResult
import com.libeyond.imandroid.sdk.api.VerifyCodeResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 「登录手机号」绑定页（[PhoneBindController]）：绑定成功通知重拉「我」资料卡、旧号码预填与离开还原。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneBindControllerTest {
    @Before fun setUp() { IMLog.useSinksForTest(); Str.install(XmlStringResolver()) }

    private class Fake(override val scope: CoroutineScope) : PhoneBindBackend {
        var cfg: PublicConfig? = PublicConfig(smsAvailable = true, smsRegions = listOf(SmsRegion("86", "CN")))
        var bindError: ApiException? = null
        val bound = mutableListOf<Proof>()
        override suspend fun config() = cfg
        override suspend fun sendBindCode(countryCode: String, phone: String): ResendHint = SmsSendResult()
        override suspend fun bindPhone(proof: Proof, countryCode: String, phone: String, code: String): String {
            bindError?.let { throw it }
            bound += proof; return "+$countryCode 136****0009"
        }
        override suspend fun verifyCode() = VerifyCodeResult()
        override suspend fun dismissLegacyPhone() {}
    }

    private val legacy = LegacyPhone("852", "51234567", "+852 5123****")
    private val unbound = SecurityStatus(phoneBound = false, passwordSet = true, smsAvailable = true, legacyPhone = legacy, recentAuthSec = 300)

    private suspend fun TestScope.ctl(st: SecurityStatus = unbound, onBound: () -> Unit = {}): Pair<PhoneBindController, Fake> {
        val fake = Fake(this)
        val sec = SecurityState({ st }, now = { 0L })
        sec.refresh()
        return PhoneBindController(fake, sec, onBound, now = { 0L }) to fake
    }

    private fun fill(c: PhoneBindController) { c.onPhone("13600000009"); c.onCode("000000") }

    /** 资料卡 phone = 登录手机号（§15.1）：绑定成功要通知宿主重拉「我」。 */
    @Test fun `绑定成功 通知重拉我的资料卡 且清掉旧号码`() = runTest {
        var refreshed = 0
        val (c, fake) = ctl { refreshed++ }
        c.open(); advanceUntilIdle()
        fill(c)
        var done: Boolean? = null
        c.next { done = it }
        advanceUntilIdle()
        assertEquals(listOf<Proof>(Proof.None), fake.bound) // 免确认窗口内不弹层
        assertEquals(false, done)
        assertEquals(1, refreshed)
        assertNull(c.legacy.masked.ifEmpty { null })
    }

    @Test fun `绑定失败 不重拉`() = runTest {
        var refreshed = 0
        val (c, fake) = ctl { refreshed++ }
        fake.bindError = ApiException(ErrCode.PHONE_TAKEN, "taken")
        c.open(); advanceUntilIdle()
        fill(c)
        c.next { }
        advanceUntilIdle()
        assertEquals(0, refreshed)
        assertTrue(c.error.isNotEmpty())
    }

    @Test fun `旧号码绑定 预填并清红字 晚到的默认国家码不覆盖 离开还原`() = runTest {
        val (c, fake) = ctl()
        fake.bindError = ApiException(ErrCode.PHONE_TAKEN, "taken")
        fill(c); c.next { }; advanceUntilIdle()
        assertTrue(c.error.isNotEmpty())
        c.prefillLegacy()
        assertEquals("", c.error)
        assertFalse(c.codeError)
        assertEquals("852", c.form.countryCode)
        assertEquals("51234567", c.form.phone)
        c.open() // open 会先 reset：预填只在本次进页内有效
        assertEquals("86", c.form.countryCode)
        c.prefillLegacy(); advanceUntilIdle() // public-config 晚到
        assertEquals("852", c.form.countryCode)
        fake.cfg = null
        c.reset()
        assertEquals("86", c.form.countryCode)
        assertTrue(c.legacy.visible)
    }
}
