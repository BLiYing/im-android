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
    fun `标签按空格与中英文逗号切分并去空去重`() {
        assertEquals(listOf("同事", "篮球", "读书"), ProfileEdit.tagsFrom(" 同事, 篮球，读书 "))
        assertEquals(listOf("a", "b"), ProfileEdit.tagsFrom("a\tb"))
        assertEquals(emptyList<String>(), ProfileEdit.tagsFrom("   "))
        assertEquals(listOf("同事"), ProfileEdit.tagsFrom("同事 同事"))
    }

    @Test
    fun `标签串回填是切分的逆操作`() {
        val tags = listOf("同事", "篮球")
        assertEquals(tags, ProfileEdit.tagsFrom(ProfileEdit.tagsText(tags)))
    }
}
