package com.libeyond.imandroid.data

import com.libeyond.imandroid.ui.theme.IMThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外观偏好的读写与兜底（对齐 iOS `IMAppearanceTests`）。 */
class AppearancePrefsTest {

    private fun decode(vararg kv: Pair<String, String>): AppearancePrefs {
        val m = kv.toMap()
        return AppearancePrefs.decode { m[it] }
    }

    @Test
    fun `空存储读出出厂值`() {
        val p = decode()
        assertEquals(AppearancePrefs(), p)
        assertEquals(IMThemeMode.System, p.mode)
        assertEquals(ChatThemeId.CLASSIC, p.theme)
        assertEquals(ChatWallpaper.DOODLE, p.wallpaper)
        assertEquals(15, p.chatFontSize)
        assertEquals(18, p.bubbleRadius)
        assertTrue(p.animationsEnabled)
        assertTrue(p.isDefault)
    }

    @Test
    fun `数值越界夹到合法区间`() {
        val p = decode(AppearancePrefs.KEY_FONT to "100", AppearancePrefs.KEY_RADIUS to "-10")
        assertEquals(22, p.chatFontSize)
        assertEquals(6, p.bubbleRadius)
        assertEquals(14, AppearancePrefs(chatFontSize = 3).clamped().chatFontSize)
        assertEquals(24, AppearancePrefs(bubbleRadius = 99).clamped().bubbleRadius)
    }

    @Test
    fun `未知标识回落默认，模式按 iOS 夹紧`() {
        val p = decode(
            AppearancePrefs.KEY_MODE to "99",
            AppearancePrefs.KEY_THEME to "unknown",
            AppearancePrefs.KEY_WALLPAPER to "unknown",
            AppearancePrefs.KEY_ANIMATIONS to "maybe",
        )
        assertEquals(IMThemeMode.Dark, p.mode)
        assertEquals(ChatThemeId.CLASSIC, p.theme)
        assertEquals(ChatWallpaper.DOODLE, p.wallpaper)
        assertTrue(p.animationsEnabled)
        assertEquals(IMThemeMode.System, decode(AppearancePrefs.KEY_MODE to "-3").mode)
    }

    @Test
    fun `一个键坏了不连累其它键`() {
        val p = decode(AppearancePrefs.KEY_FONT to "abc", AppearancePrefs.KEY_THEME to "tiffany")
        assertEquals(15, p.chatFontSize)
        assertEquals(ChatThemeId.TIFFANY, p.theme)
    }

    @Test
    fun `小数字号四舍五入`() {
        assertEquals(17, decode(AppearancePrefs.KEY_FONT to "16.6").chatFontSize)
    }

    @Test
    fun `编码后再解码原样往返`() {
        val p = AppearancePrefs(IMThemeMode.Light, ChatThemeId.HERMES_ORANGE, ChatWallpaper.PLAIN, 20, 9, false)
        val m = p.encode()
        assertEquals(p, AppearancePrefs.decode { m[it] })
        assertFalse(p.isDefault)
    }

    @Test
    fun `主题与壁纸 wire 值与 iOS 一致且顺序固定`() {
        assertEquals(
            listOf(
                "classic", "ocean", "violet", "midnight", "lime", "titian", "mars-green", "klein-blue",
                "burgundy", "schonbrunn", "tiffany", "china-red", "hermes-orange", "prussian-blue",
            ),
            ChatThemeId.entries.map { it.wire },
        )
        assertEquals(listOf("doodle", "gradient", "plain"), ChatWallpaper.entries.map { it.wire })
    }
}
