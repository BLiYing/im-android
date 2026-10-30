package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 两步验证的线上形状（PROTOCOL §7.1）。登录 `200020` 是 **HTTP 400 + 标准信封**，
 * `data.ticket` 必须从错误里取得到——所以这里用真 socket 走一遍 [HttpClient]，而不只测 JSON 解析。
 */
class TwoStepWireTest {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    @Volatile private var reply = ""
    @Volatile private var seenRequest = ""

    @Before fun setUp() {
        IMLog.useSinksForTest()
        Thread {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { s ->
                        val buf = ByteArray(8192)
                        val n = s.getInputStream().read(buf)
                        seenRequest = String(buf, 0, maxOf(n, 0))
                        val body = reply.toByteArray()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() { server.close() }

    private fun http() = HttpClient("127.0.0.1:${server.localPort}")

    private fun loginFailure(): ApiException = runBlocking {
        try {
            http().call("POST", "/api/v1/login", TwoStepApi.loginBody("t", "c"), authenticated = false)
            error("应当抛 ApiException")
        } catch (e: ApiException) { e }
    }

    @Test fun `登录 200020 HTTP 400 的错误体里 data 保留 能取到 ticket`() {
        reply = """{"code":200020,"message":"two-step required","request_id":"r1","data":{"ticket":"tk-abc","methods":["totp","recovery"],"expires_in":300}}"""
        val e = loginFailure()
        assertEquals(200020, e.code)
        assertEquals(400, e.httpStatus)
        val ch = TwoStepChallenge.from(e)
        assertNotNull(ch)
        assertEquals("tk-abc", ch!!.ticket)
        assertEquals(listOf("totp", "recovery"), ch.methods)
        assertEquals(300, ch.expiresIn)
    }

    @Test fun `不是 200020 或 data 缺失 取不到挑战`() {
        reply = """{"code":200002,"message":"wrong password","request_id":"r1"}"""
        assertNull(TwoStepChallenge.from(loginFailure()))
        reply = """{"code":200020,"message":"x","request_id":"r1"}"""
        assertNull(TwoStepChallenge.from(loginFailure()))
    }

    @Test fun `200021 带 attempts_left`() {
        reply = """{"code":200021,"message":"bad","request_id":"r1","data":{"attempts_left":2}}"""
        assertEquals(2, TwoStepChallenge.attemptsLeft(loginFailure()))
        reply = """{"code":200021,"message":"bad","request_id":"r1"}"""
        assertNull(TwoStepChallenge.attemptsLeft(loginFailure()))
    }

    @Test fun `请求体字段名与服务端一致`() {
        assertEquals(setOf("password"), TwoStepApi.setupBody("p").keys)
        assertEquals(setOf("code"), TwoStepApi.enableBody("123456").keys)
        val v = TwoStepApi.verifyBody("p", "123456")
        assertEquals(setOf("password", "code"), v.keys)
        assertEquals("123456", v.getValue("code").jsonPrimitive.content)
        // 第二步不带设备字段（设备取自第一步）
        assertEquals(setOf("ticket", "code"), TwoStepApi.loginBody("t", "c").keys)
    }

    @Test fun `状态 setup enable 应答解得出来`() {
        val st = decode(ProtocolJson.parseToJsonElement("""{"enabled":true,"enabled_at":1790000000000,"recovery_remaining":8}"""), TwoStepStatus.serializer())
        assertTrue(st.enabled); assertEquals(8, st.recoveryRemaining); assertEquals(1790000000000L, st.enabledAt)
        assertFalse(decode(ProtocolJson.parseToJsonElement("""{"enabled":false}"""), TwoStepStatus.serializer()).enabled)
        val su = decode(ProtocolJson.parseToJsonElement("""{"secret":"JBSWY3DPEHPK3PXP","otpauth_uri":"otpauth://totp/IM:a?secret=JBSWY3DPEHPK3PXP","expires_in":1800}"""), TwoStepSetup.serializer())
        assertEquals("JBSWY3DPEHPK3PXP", su.secret)
        val rc = decode(ProtocolJson.parseToJsonElement("""{"recovery_codes":["aaaaa-bbbbb","ccccc-ddddd"]}"""), RecoveryCodes.serializer())
        assertEquals(2, rc.recoveryCodes.size)
    }

    @Test fun `机密类型的 toString 不带内容`() {
        val su = TwoStepSetup("SECRETSECRET", "otpauth://x?secret=SECRETSECRET")
        assertFalse(su.toString().contains("SECRET"))
        assertFalse(RecoveryCodes(listOf("aaaaa-bbbbb")).toString().contains("aaaaa"))
        assertFalse(TwoStepChallenge("tk-xyz").toString().contains("tk-xyz"))
    }
}
