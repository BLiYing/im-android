package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料编辑的前置校验（规则逐条对齐服务端 `internal/account/userid.go`）。 */
class ProfileEditTest {

    @Test
    fun `昵称必填`() {
        // 昵称是全端显示名回退链的终点，空了各处就会露出 10 位内部 ID
        assertNotNull(ProfileEdit.nicknameError(""))
        assertNotNull(ProfileEdit.nicknameError("   "))
        assertNull(ProfileEdit.nicknameError("李默"))
    }

    @Test
    fun `昵称超 32 字被拦下`() {
        assertNull(ProfileEdit.nicknameError("字".repeat(32)))
        assertNotNull(ProfileEdit.nicknameError("字".repeat(33)))
    }

    @Test
    fun `用户名只收小写字母数字下划线且至少五位`() {
        assertNull(ProfileEdit.usernameError("user_1001"))
        assertNotNull(ProfileEdit.usernameError("abcd"))          // 少于 5 位
        assertNotNull(ProfileEdit.usernameError("Alice1"))        // 大写
        assertNotNull(ProfileEdit.usernameError("user-1001"))     // 连字符
        assertNotNull(ProfileEdit.usernameError("用户名abc"))      // 非 ASCII
        assertNotNull(ProfileEdit.usernameError("a".repeat(33)))  // 超 32 位
        assertNotNull(ProfileEdit.usernameError(""))
    }

    @Test
    fun `没改用户名就不发改名请求`() {
        // 每次保存都发，会把「用户名已被占用」抛给一个压根没动用户名的人
        assertFalse(ProfileEdit.shouldChangeUsername("user1001", "user1001"))
        assertFalse(ProfileEdit.shouldChangeUsername("  user1001  ", "user1001"))
        assertFalse(ProfileEdit.shouldChangeUsername("", "user1001"))
        assertTrue(ProfileEdit.shouldChangeUsername("user1002", "user1001"))
    }

    @Test
    fun `签名按码点计长且换行折成空格`() {
        assertEquals(70, ProfileEdit.MAX_BIO_RUNES)
        assertEquals(2, ProfileEdit.bioRunes("😀😀")) // 代理对算一个，与服务端 rune 口径一致
        assertEquals("a b", ProfileEdit.flattenBio("a\nb"))
        assertEquals("a b", ProfileEdit.flattenBio("a\r\n\r\nb"))
    }

    @Test
    fun `签名超长留到保存前校验而不是输入时截断`() {
        // 输入时截断会打断输入法组词（拼音字母被算进上限），所以只折换行
        val long = "😀".repeat(80)
        assertEquals(long, ProfileEdit.flattenBio(long))
        assertNull(ProfileEdit.bioError("字".repeat(70)))
        assertNull(ProfileEdit.bioError("  " + "字".repeat(70) + "  ")) // 保存前先 trim，首尾空白不占额度
        assertNotNull(ProfileEdit.bioError("字".repeat(71)))
        assertNotNull(ProfileEdit.bioError("😀".repeat(71))) // 按码点计，不是 UTF-16 单元
        assertNull(ProfileEdit.bioError("😀".repeat(70)))
    }
}
