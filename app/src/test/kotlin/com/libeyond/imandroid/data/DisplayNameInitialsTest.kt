package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头像首字母规则（2026-10-01 改，三端同口径，见 `../IMServer/docs/UI.md`「图标与头像资源」）：
 * 末一个字是汉字就取它（中文名去姓留名）；否则取首字母并转大写（英文名/用户名）。
 */
class DisplayNameInitialsTest {

    @Test
    fun `中文名取末字`() {
        assertEquals("丰", DisplayName.initials("张三丰"))
        assertEquals("甲", DisplayName.initials("甲"))
    }

    @Test
    fun `英文名或用户名取首字母并转大写`() {
        assertEquals("B", DisplayName.initials("bob"))
        assertEquals("L", DisplayName.initials("libeyond"))
    }

    @Test
    fun `中文开头数字结尾 末字不是汉字退回取首字母`() {
        assertEquals("用", DisplayName.initials("用户1001"))
    }

    @Test
    fun `空白与空字符串安全——与iOS Web一致返回空串`() {
        assertEquals("", DisplayName.initials(""))
        assertEquals("", DisplayName.initials("   "))
    }

    /**
     * 结尾是 emoji（辅助平面字符，UTF-16 用一对代理对表示，不是汉字）：退回取首字母，
     * 且取字形簇时不会把代理对拆成半个（拆了会是无效字符甚至抛异常）。
     */
    @Test
    fun `结尾emoji安全退回取首字母`() {
        assertEquals("小", DisplayName.initials("小😀")) // 😀 U+1F600
    }

    /** 扩展区汉字（辅助平面，代理对表示）同样要识别成汉字、取末字整体。 */
    @Test
    fun `扩展区汉字识别为汉字`() {
        assertEquals("𠀀", DisplayName.initials("小𠀀")) // U+20000，CJK 扩展 B 第一个字
    }

    /** 扩展 C 起更冷门的辅助平面汉字同样要识别——范围覆盖到整个辅助表意平面，不只 B。 */
    @Test
    fun `扩展C汉字识别为汉字`() {
        assertEquals("𪜀", DisplayName.initials("小𪜀")) // U+2A700，CJK 扩展 C 第一个字
    }

    /**
     * 大写不能把一个字拆成两个——`String.uppercase()` 的完整 Unicode 大小写折叠会把德语 ß 变成
     * 两个字符 "SS"，画到头像圆里就是挤进两个字母。改用简单大写映射，没有大写形式就原样返回。
     */
    @Test
    fun `大写不把一个字拆成两个`() {
        val result = DisplayName.initials("ßtraße99")
        assertEquals(1, result.length)
        assertEquals("ß", result) // ß 没有「简单大写映射」，原样返回，不是 "SS"
    }
}
