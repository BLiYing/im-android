package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「跟随系统」解析规则（纯函数，对齐 iOS `IMLocalization.resolveLanguageForPreference:`）：
 * 只认 `zh` 前缀归简体，其余一律回落英文——**不假设中文**。
 */
class LanguageStoreTest {

    @Test
    fun explicitPrefsIgnoreSystemLanguage() {
        assertEquals(ResolvedLanguage.ZH_HANS, LanguageStore.resolve(LanguagePref.ZH_HANS, "en"))
        assertEquals(ResolvedLanguage.EN, LanguageStore.resolve(LanguagePref.EN, "zh"))
    }

    @Test
    fun systemZhResolvesToZhHans() {
        assertEquals(ResolvedLanguage.ZH_HANS, LanguageStore.resolve(LanguagePref.SYSTEM, "zh"))
    }

    @Test
    fun systemEnglishResolvesToEn() {
        assertEquals(ResolvedLanguage.EN, LanguageStore.resolve(LanguagePref.SYSTEM, "en"))
    }

    /** 既不是中文也不是英文（如日语）：回落英文，不悄悄变成中文。 */
    @Test
    fun systemUnknownLanguageFallsBackToEnglishNotChinese() {
        assertEquals(ResolvedLanguage.EN, LanguageStore.resolve(LanguagePref.SYSTEM, "ja"))
    }

    @Test
    fun fromWireRoundTripsKnownValuesAndDefaultsToSystem() {
        assertEquals(LanguagePref.ZH_HANS, LanguagePref.fromWire("zh-Hans"))
        assertEquals(LanguagePref.EN, LanguagePref.fromWire("en"))
        assertEquals(LanguagePref.SYSTEM, LanguagePref.fromWire("system"))
        assertEquals(LanguagePref.SYSTEM, LanguagePref.fromWire(null))
        assertEquals(LanguagePref.SYSTEM, LanguagePref.fromWire("bogus"))
    }
}
