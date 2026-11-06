package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AboutLinkValuesTest {
    // 失败路径会写 warn 日志；单测里 android.util.Log 是桩、一调就抛，换掉落点。
    @Before fun silenceLog() { IMLog.useSinksForTest() }

    @Test fun blankLegalFieldsFallBackToBuiltinPathsAndBlankCustomerServiceStaysHidden() {
        val v = AboutLinkValues.of("", "  ", null, "")
        assertEquals(AboutLinkValues.FALLBACK, v)
        assertEquals("", v.customerService)
    }

    @Test fun configuredValuesAreKeptAndTrimmed() {
        val v = AboutLinkValues.of(" https://a.example/p ", "/legal/terms", "https://a.example/s", " http://cs/?ck=1 ")
        assertEquals(AboutLinkValues("https://a.example/p", "/legal/terms", "https://a.example/s", "http://cs/?ck=1"), v)
    }

    @Test fun loadFailureReturnsFallbackThenLastGoodCache() = runBlocking {
        val key = AboutLinksStore.key("test-host-${System.nanoTime()}:1", authenticated = true)
        val boom: suspend () -> AboutLinkValues = { throw java.io.IOException("offline") }

        assertSame("没有缓存时拉取失败退回内置路径", AboutLinkValues.FALLBACK, AboutLinksStore.load(key, boom))

        val good = AboutLinkValues("/legal/privacy", "/legal/terms", "/support", "http://cs.example:8091/?ck=z")
        assertSame(good, AboutLinksStore.load(key) { good })
        assertSame("失败不得冲掉上次成功的值", good, AboutLinksStore.load(key, boom))
        assertSame(good, AboutLinksStore.cached(key))
    }

    @Test fun anonymousAndAuthenticatedCachesDoNotOverwriteEachOther() = runBlocking {
        val host = "iso-${System.nanoTime()}:1"
        val anon = AboutLinkValues("/legal/privacy", "/legal/terms", "/support", "")
        val authed = anon.copy(customerService = "http://iso.cs:8091/")
        AboutLinksStore.load(AboutLinksStore.key(host, false)) { anon }
        AboutLinksStore.load(AboutLinksStore.key(host, true)) { authed }
        assertEquals("", AboutLinksStore.cached(AboutLinksStore.key(host, false))?.customerService)
        assertEquals("http://iso.cs:8091/", AboutLinksStore.cached(AboutLinksStore.key(host, true))?.customerService)
    }

    @Test fun customerServiceOriginIsRecognisedFromCache() = runBlocking {
        val host = "ua-${System.nanoTime()}:1"
        AboutLinksStore.load(AboutLinksStore.key(host, true)) {
            AboutLinkValues.FALLBACK.copy(customerService = "http://192.168.77.5:8091/?ck=k")
        }
        assertTrue(AboutLinksStore.isCustomerServiceUrl("http://192.168.77.5:8091/chat?x=1", false))
        assertFalse("别的主机不得被当成客服页", AboutLinksStore.isCustomerServiceUrl("http://evil.example/", false))
    }

    @Test fun relativeCustomerServiceMatchesServerOrigin() = runBlocking {
        val host = "rel-${System.nanoTime()}:9"
        AboutLinksStore.load(AboutLinksStore.key(host, true)) { AboutLinkValues.FALLBACK.copy(customerService = "/cs") }
        assertTrue(AboutLinksStore.isCustomerServiceUrl("http://$host/cs?lang=en", false))
        assertFalse(AboutLinksStore.isCustomerServiceUrl("https://$host/cs", false))
    }
}
