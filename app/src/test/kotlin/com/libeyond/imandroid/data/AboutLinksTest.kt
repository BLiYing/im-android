package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
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
}
