package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 修改密码的本地校验与业务码映射（对照 iOS `IMChangePasswordViewController`、服务端 `account.ChangePassword`）。 */
class ChangePasswordRulesTest {

    private fun issue(old: String, new: String, confirm: String) = ChangePasswordRules.validate(old, new, confirm)

    @Test
    fun `三个框都填了按钮才亮`() {
        assertTrue(ChangePasswordRules.canSubmit("a", "b", "c"))
        assertFalse(ChangePasswordRules.canSubmit("", "b", "c"))
        assertFalse(ChangePasswordRules.canSubmit("a", "", "c"))
        assertFalse(ChangePasswordRules.canSubmit("a", "b", ""))
    }

    /** 按钮亮了不代表能提交——没过本地校验时要说出原因（iOS 这几句提示轮不到显示，本端补上）。 */
    @Test
    fun `填满但不合规时仍有一句具体原因`() {
        assertTrue(ChangePasswordRules.canSubmit("oldpw1", "12345", "12345"))
        assertEquals(PasswordField.New, issue("oldpw1", "12345", "12345")?.field)
    }

    @Test
    fun `新密码 6 位是下限，5 位拒`() {
        assertEquals("新密码至少 6 位", issue("oldpw1", "12345", "12345")?.message)
        assertNull(issue("oldpw1", "123456", "123456"))
    }

    /** 服务端按字节卡 72（bcrypt 上限），超了回 100001 且文案是英文——本地先挡。 */
    @Test
    fun `新密码按 UTF-8 字节卡 72`() {
        assertNull(issue("oldpw1", "a".repeat(72), "a".repeat(72)))
        val long = "密".repeat(25) // 25 个字符（过了长度下限）但 75 字节
        assertEquals(PasswordField.New, issue("oldpw1", long, long)?.field)
        assertEquals(PasswordField.New, issue("oldpw1", "a".repeat(73), "a".repeat(73))?.field)
    }

    @Test
    fun `新旧相同拒，指向新密码框`() {
        val r = issue("same12", "same12", "same12")
        assertEquals("新密码不能与旧密码相同", r?.message)
        assertEquals(PasswordField.New, r?.field)
    }

    @Test
    fun `两次不一致拒，指向确认框`() {
        val r = issue("oldpw1", "newpw1", "newpw2")
        assertEquals("两次输入不一致", r?.message)
        assertEquals(PasswordField.Confirm, r?.field)
    }

    /** 顺序同 iOS `submitTapped`：同时有多处不合规时报最前面那条。 */
    @Test
    fun `多处不合规报第一条：长度先于一致性`() {
        assertEquals(PasswordField.New, issue("oldpw1", "123", "456")?.field)
    }

    @Test
    fun `旧密码错指向旧密码框`() {
        val fb = ChangePasswordRules.feedbackFor(ErrCode.WRONG_PASSWORD) as PasswordFeedback.Inline
        assertEquals("旧密码错误", fb.message)
        assertEquals(PasswordField.Old, fb.field)
    }

    /** 参数错是 100001。iOS 写成了 100002（限流码）——这条钉住本端别照抄。 */
    @Test
    fun `参数错按 100001 映射，限流码 100002 不当成强度不足`() {
        assertEquals(100001, ErrCode.PARAM_INVALID)
        val fb = ChangePasswordRules.feedbackFor(ErrCode.PARAM_INVALID) as PasswordFeedback.Inline
        assertEquals(PasswordField.New, fb.field)
        assertNull(ChangePasswordRules.feedbackFor(ErrCode.RATE_LIMITED))
    }

    @Test
    fun `封号是红字但不指向任何框`() {
        val fb = ChangePasswordRules.feedbackFor(ErrCode.ACCOUNT_BANNED) as PasswordFeedback.Inline
        assertNull(fb.field)
    }

    @Test
    fun `会话失效走吐司`() {
        assertTrue(ChangePasswordRules.feedbackFor(ErrCode.TOKEN_INVALID) is PasswordFeedback.Toast)
        assertTrue(ChangePasswordRules.feedbackFor(ErrCode.TOKEN_EXPIRED) is PasswordFeedback.Toast)
    }

    @Test
    fun `没有专门文案的码交给调用方兜底`() {
        assertNull(ChangePasswordRules.feedbackFor(ErrCode.INTERNAL))
    }
}
