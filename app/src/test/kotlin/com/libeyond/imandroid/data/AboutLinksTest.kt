package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutLinksTest {
    @Test fun legalUrlUsesSchemeHostPathAndLanguage() {
        assertEquals("http://10.0.2.2:8080/legal/privacy?lang=zh-Hans",
            AboutLinks.legalUrl("10.0.2.2:8080", false, AboutLinks.PATH_PRIVACY, ResolvedLanguage.ZH_HANS))
        assertEquals("https://im.example.com/support?lang=en",
            AboutLinks.legalUrl("im.example.com", true, AboutLinks.PATH_SUPPORT, ResolvedLanguage.EN))
        assertEquals("https://h/legal/terms?lang=en",
            AboutLinks.legalUrl(" h ", true, AboutLinks.PATH_TERMS, ResolvedLanguage.EN))
    }

    @Test fun legalUrlRejectsEmptyHostAndUnknownPath() {
        assertNull(AboutLinks.legalUrl("", false, AboutLinks.PATH_PRIVACY, ResolvedLanguage.EN))
        assertNull(AboutLinks.legalUrl("  ", false, AboutLinks.PATH_PRIVACY, ResolvedLanguage.EN))
        assertNull(AboutLinks.legalUrl("h:1", false, "/api/v1/login", ResolvedLanguage.EN))
    }

    @Test fun versionStringCombinesNameAndCode() {
        assertEquals("0.1.0 (1)", AboutLinks.versionString("0.1.0", 1))
        assertEquals("0.1.0", AboutLinks.versionString("0.1.0", null))
        assertEquals("7", AboutLinks.versionString("", 7))
        assertEquals("-", AboutLinks.versionString(null, null))
    }

    @Test fun clipboardTextCarriesPlatformName() {
        assertEquals("Android 0.1.0 (1)", AboutLinks.clipboardText("0.1.0 (1)"))
    }

    @Test fun aboutEntryIsSearchableByItsAliases() {
        val all = SettingsSearchIndex.entries()
        for (kw in listOf("关于", "隐私政策", "服务条款", "技术支持", "版本")) {
            assertTrue("搜「$kw」应命中关于我们", SettingsSearchIndex.hits(all, kw).any { it.id == "about" })
        }
        assertEquals(SettingsRoute(MePage.About), all.first { it.id == "about" }.route)
    }

    // ---- 后台可配置链接：字段值 → 最终 URL ----

    @Test fun resolveKeepsAbsoluteUrlVerbatimWithoutLang() {
        assertEquals("http://192.168.1.12:8091/?ck=ck_abc",
            AboutLinks.resolve("http://192.168.1.12:8091/?ck=ck_abc", "10.0.2.2:8080", false, ResolvedLanguage.EN))
        assertEquals("https://help.example.com/p?x=1#top",
            AboutLinks.resolve("  https://help.example.com/p?x=1#top ", "h", true, ResolvedLanguage.ZH_HANS))
    }

    @Test fun resolveBuildsServerRelativePathFromHostAndLang() {
        assertEquals("http://10.0.2.2:8080/legal/privacy?lang=zh-Hans",
            AboutLinks.resolve("/legal/privacy", "10.0.2.2:8080", false, ResolvedLanguage.ZH_HANS))
        assertEquals("https://im.example.com/support?lang=en",
            AboutLinks.resolve("/support", "im.example.com", true, ResolvedLanguage.EN))
        assertNull(AboutLinks.resolve("/api/v1/login", "h:1", false, ResolvedLanguage.EN))
        assertNull(AboutLinks.resolve("/legal/privacy", "", false, ResolvedLanguage.EN))
    }

    @Test fun resolveReturnsNullForEmptyAndNonHttpSchemes() {
        for (v in listOf(null, "", "   ", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x",
            "intent://x#Intent;end", "ftp://x/", "www.example.com", "example.com/x", "https://", "http://")) {
            assertNull("「$v」应不可打开", AboutLinks.resolve(v, "h:1", false, ResolvedLanguage.EN))
        }
    }

    @Test fun resolveRejectsCleartextHttpWhenTls() {
        assertNull(AboutLinks.resolve("http://cs.example/", "h", true, ResolvedLanguage.EN))
        assertEquals("https://cs.example/", AboutLinks.resolve("https://cs.example/", "h", true, ResolvedLanguage.EN))
    }

    @Test fun sameOriginComparesSchemeHostAndEffectivePort() {
        assertTrue(AboutLinks.sameOrigin("http://192.168.1.12:8091/?ck=a", "http://192.168.1.12:8091/other#x"))
        assertTrue(AboutLinks.sameOrigin("HTTPS://Example.com/a", "https://example.com:443/b"))
        assertTrue(AboutLinks.sameOrigin("http://example.com", "http://example.com:80/"))
        assertFalse(AboutLinks.sameOrigin("http://a.com:8091/", "http://a.com:8092/"))
        assertFalse(AboutLinks.sameOrigin("http://a.com/", "https://a.com/"))
        assertFalse(AboutLinks.sameOrigin("http://a.com/", "http://b.com/"))
        assertFalse(AboutLinks.sameOrigin("not a url", "http://a.com/"))
    }

    @Test fun beaconUserAgentAppendsOnceOnly() {
        val ua = AboutLinks.beaconUserAgent("Mozilla/5.0 (Linux; Android 15) Chrome/130", "0.1.0")
        assertEquals("Mozilla/5.0 (Linux; Android 15) Chrome/130 BeaconApp/0.1.0 (Android)", ua)
        assertEquals(ua, AboutLinks.beaconUserAgent(ua, "0.1.0"))
    }
}
