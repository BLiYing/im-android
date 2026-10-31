package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 邮箱登录的线上形状（PROTOCOL §7.2）。 */
class EmailWireTest {

    @Test fun `请求体字段名与服务端一致`() {
        val s = EmailApi.sendBody("a@b.com", "pw", "zh-Hans")
        assertEquals(setOf("email", "password", "lang"), s.keys)
        assertEquals("zh-Hans", s.getValue("lang").jsonPrimitive.content)
        val v = EmailApi.verifyBody("a@b.com", "123456", "en")
        assertEquals(setOf("email", "code", "lang"), v.keys)
        assertEquals("123456", v.getValue("code").jsonPrimitive.content)
        assertEquals(setOf("password"), EmailApi.unbindBody("pw").keys)
    }

    @Test fun `状态应答 已绑定 未绑定 不可用`() {
        val on = decode(ProtocolJson.parseToJsonElement("""{"available":true,"bound":true,"email":"a@b.com","verified_at":1790000000000}"""), EmailStatus.serializer())
        assertTrue(on.bound); assertEquals("a@b.com", on.email); assertEquals(1790000000000L, on.verifiedAt)
        val off = decode(ProtocolJson.parseToJsonElement("""{"available":false,"bound":false}"""), EmailStatus.serializer())
        assertFalse(off.available); assertFalse(off.bound); assertEquals("", off.email)
    }

    @Test fun `发送与验证应答`() {
        val r = decode(ProtocolJson.parseToJsonElement("""{"expires_in":600,"resend_after":45}"""), EmailSendResult.serializer())
        assertEquals(45, r.resendAfter)
        val b = decode(ProtocolJson.parseToJsonElement("""{"email":"a@b.com","verified_at":1790000000000}"""), EmailBound.serializer())
        assertEquals("a@b.com", b.email)
    }

    @Test fun `失败 data 里的 attempts_left 与 retry_after_sec`() {
        val e = ApiException(200032, "bad", data = ProtocolJson.parseToJsonElement("""{"attempts_left":2}"""))
        assertEquals(2, EmailApi.intField(e, "attempts_left"))
        assertNull(EmailApi.intField(e, "retry_after_sec"))
        assertNull(EmailApi.intField(ApiException(200033, "x"), "retry_after_sec"))
        val t = ApiException(100002, "x", data = ProtocolJson.parseToJsonElement("""{"retry_after_sec":42}"""))
        assertEquals(42, EmailApi.intField(t, "retry_after_sec"))
        // data 不是对象 / 字段不是数字：不崩，当没有
        assertNull(EmailApi.intField(ApiException(1, "x", data = ProtocolJson.parseToJsonElement("[1]")), "attempts_left"))
        assertNull(EmailApi.intField(ApiException(1, "x", data = ProtocolJson.parseToJsonElement("""{"attempts_left":"n"}""")), "attempts_left"))
    }
}
