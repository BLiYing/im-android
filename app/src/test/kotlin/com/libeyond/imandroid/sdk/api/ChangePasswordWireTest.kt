package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `POST /api/v1/users/me/password` 的线上形状（服务端 `handleChangePassword`）。
 *
 * 应答里那枚 `refresh_token` 是**续期凭据唯一的轮换点**：服务端这一刻已作废本机旧凭据，
 * 新的只出现在这次应答里。字段名解错一个字母，本机就会在 access token 下次过期时被登出
 * ——而且是在用户刚改完密码之后，最难联想到原因的时候。
 */
class ChangePasswordWireTest {

    @Test
    fun `请求体字段名与服务端一致`() {
        val body = AuthApi.changePasswordBody("old-pw", "new-pw")
        assertEquals(setOf("old_password", "new_password"), body.keys)
        assertEquals("old-pw", body.getValue("old_password").jsonPrimitive.content)
        assertEquals("new-pw", body.getValue("new_password").jsonPrimitive.content)
    }

    @Test
    fun `应答带轮换出的新凭据时解得出来`() {
        val data = ProtocolJson.parseToJsonElement(
            """{"ok":true,"refresh_token":"rt-new","refresh_expires_in":15552000}""",
        )
        val r = decode(data, ChangePasswordResult.serializer())
        assertEquals("rt-new", r.refreshToken)
        assertEquals(15552000, r.refreshExpiresIn)
    }

    /** 会话登记降级的老会话、或服务端轮换失败改为作废时，应答里没有这个字段——不能抛。 */
    @Test
    fun `应答不带凭据时为 null`() {
        val r = decode(ProtocolJson.parseToJsonElement("""{"ok":true}"""), ChangePasswordResult.serializer())
        assertNull(r.refreshToken)
    }
}
