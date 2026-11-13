package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.LoginResult
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.api.decode
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本机存的公开用户名只认服务端响应（A2 审查：手机号 + 密码登录后转发，「转发自 @13800138000」把完整手机号发给了别人）。
 * `IMClient.myPublicName()` = "@" + 这里存的值，经 `Forward.originOf` 写进转发消息。
 */
class SessionUsernameTest {

    private fun store() = SessionStore(FakePrefs())

    @Test fun `所有登录成功响应都解出 username 缺省为 null`() {
        val r = decode(ProtocolJson.parseToJsonElement("""{"token":"t","uid":"1","username":"alice_01"}"""), LoginResult.serializer())
        assertEquals("alice_01", r.username)
        val old = decode(ProtocolJson.parseToJsonElement("""{"token":"t","uid":"1"}"""), LoginResult.serializer())
        assertNull(old.username)
    }

    @Test fun `登录存响应里的用户名 不是输入的登录名`() {
        val s = store()
        s.saveLogin("t", "1", "r", "alice_01")
        assertEquals("alice_01", s.username)
    }

    /** 老服务端不带 username：清掉旧值（宁可「未命名用户」），不能沿用上一个账号的名字。 */
    @Test fun `响应没带用户名就清掉旧值`() {
        val s = store()
        s.saveLogin("t", "1", "r", "alice_01")
        s.saveLogin("t2", "2", null, null)
        assertNull(s.username)
        s.saveLogin("t3", "3", null, "  ")
        assertNull(s.username)
    }

    @Test fun `续期不动用户名 也不抹掉已有的续期凭据`() {
        val s = store()
        s.saveLogin("t", "1", "r1", "alice_01")
        s.saveRefreshed("t2", "1", null)
        assertEquals("alice_01", s.username)
        assertEquals("r1", s.refreshToken)
        assertEquals("t2", s.token)
    }

    /**
     * 老版本存的是用户输入的登录名（邮箱登录早已上线）：冷启动恢复 / 续期不经过登录响应，
     * 读取时就得把不像句柄的存量值当空并清掉，转发溯源才不会写出「@alice@example.com」。
     */
    @Test fun `存量是邮箱或手机号 读出为空并清掉 转发溯源不含它`() {
        for (legacy in listOf("alice@example.com", "+8613622220211", "13622220211", "Alice_01")) {
            val prefs = FakePrefs().apply { map["username"] = legacy }
            val s = SessionStore(prefs)
            assertNull(legacy, s.username)
            assertFalse(legacy, "username" in prefs.map)
            val name = SessionStore.publicName(s.username)
            val origin = Forward.originOf(MessageEntity(ownerUid = "me", convId = "c", convSeq = 1, sender = "me", contentType = ContentType.TEXT, content = "x"), "me", name)
            assertFalse(origin, origin.contains("@example") || origin.contains("1362222"))
            assertEquals("未命名用户", origin)
        }
        val ok = SessionStore(FakePrefs().apply { map["username"] = "alice_01" })
        assertEquals("@alice_01", SessionStore.publicName(ok.username))
        // 服务端给的纯数字句柄是合法的：带「来自服务端」标记就不当手机号挡掉
        val digits = SessionStore(FakePrefs()).apply { saveLogin("t", "1", null, "12345678") }
        assertEquals("12345678", digits.username)
    }
}
