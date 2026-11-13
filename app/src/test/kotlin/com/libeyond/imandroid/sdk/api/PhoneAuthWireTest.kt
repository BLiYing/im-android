package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 手机号与短信验证码的线上形状（PROTOCOL §7.3）。 */
class PhoneAuthWireTest {

    private fun json(s: String) = ProtocolJson.parseToJsonElement(s)

    @Test fun `public-config 新服务端 全字段`() {
        val c = PublicConfigApi.configOf(json("""{"register_enabled":true,"sms_available":true,"sms_regions":[{"code":"86","iso":"CN"},{"code":"60","iso":"MY"}],"register_methods":["phone"]}"""))
        assertTrue(c.smsAvailable)
        assertEquals(listOf(SmsRegion("86", "CN"), SmsRegion("60", "MY")), c.smsRegions)
        assertEquals(listOf("phone"), c.registerMethods)
    }

    @Test fun `public-config 老服务端缺字段 = 短信不可用 可用户名注册`() {
        val c = PublicConfigApi.configOf(json("""{"register_enabled":false}"""))
        assertFalse(c.smsAvailable)
        assertTrue(c.smsRegions.isEmpty())
        assertEquals(listOf("username"), c.registerMethods)
        assertFalse(c.registerEnabled)
        // data 为空 / 不是对象也按老服务端
        assertFalse(PublicConfigApi.configOf(null).smsAvailable)
    }

    @Test fun `public-config register_methods 空数组 = 两种都不行`() {
        assertEquals(emptyList<String>(), PublicConfigApi.configOf(json("""{"sms_available":false,"register_methods":[]}""")).registerMethods)
    }

    @Test fun `login-sms 需要注册 与 直接登录`() {
        val nr = PhoneAuthApi.loginSmsResultOf(json("""{"need_register":true,"register_ticket":"T1","expires_in":600}"""))
        assertEquals(SmsLoginResult.NeedRegister("T1", 600), nr)
        val ok = PhoneAuthApi.loginSmsResultOf(json("""{"token":"jwt","uid":"1234567890","expires_in":7200,"refresh_token":"rt"}"""))
        assertEquals("1234567890", (ok as SmsLoginResult.LoggedIn).result.uid)
    }

    @Test fun `login-sms need_register 却没票 当结构不符`() {
        try {
            PhoneAuthApi.loginSmsResultOf(json("""{"need_register":true}"""))
            fail("应抛")
        } catch (e: ApiException) {
            assertTrue(e.isTransport)
        }
    }

    @Test fun `冷静期 restore_ticket 与 绑定错误 field`() {
        val e = ApiException(200005, "x", data = json("""{"delete_at":1,"restore_ticket":"R","restore_expires_in":600}"""))
        assertEquals("R", PhoneAuthApi.restoreTicket(e))
        assertNull(PhoneAuthApi.restoreTicket(ApiException(200005, "x", data = json("""{"delete_at":1}"""))))
        val f = ApiException(200043, "x", data = json("""{"field":"code","attempts_left":3}"""))
        assertEquals("code", PhoneAuthApi.errorField(f))
        assertEquals(3, PhoneAuthApi.intField(f, "attempts_left"))
        assertNull(PhoneAuthApi.errorField(ApiException(200043, "x")))
    }

    @Test fun `请求体字段名`() {
        val s = PhoneAuthApi.sendCodeBody(SmsScene.Reset, "86", "13622220001", "zh-Hans")
        assertEquals(setOf("scene", "country_code", "phone", "lang"), s.keys)
        assertEquals("reset", s.getValue("scene").jsonPrimitive.content)
        assertEquals(setOf("country_code", "phone", "code", "new_password"), PhoneAuthApi.resetBody("86", "1", "000000", "pw").keys)
        val b = SecurityApi.bindPhoneBody(Proof.SmsCode("111111"), "86", "13622220002", "000000")
        assertEquals(setOf("proof", "country_code", "phone", "code"), b.keys)
        assertEquals("111111", b.getValue("proof").jsonObject.getValue("sms_code").jsonPrimitive.content)
    }

    @Test fun `security 状态 缺字段按老服务端`() {
        val st = decode(json("""{"phone_bound":true,"phone_masked":"+86 136****0001","password_set":false,"sms_available":true}"""), SecurityStatus.serializer())
        assertTrue(st.phoneBound); assertFalse(st.passwordSet); assertEquals("+86 136****0001", st.phoneMasked)
        val old = decode(json("{}"), SecurityStatus.serializer())
        assertTrue(old.passwordSet); assertFalse(old.phoneBound); assertFalse(old.smsAvailable)
    }

    /** PHONE_AUTH_DESIGN §15：recent_auth_sec / legacy_phone；老服务端缺省 = 0 / null。 */
    @Test fun `security 状态 免确认秒数与旧号码`() {
        val st = decode(json("""{"phone_bound":false,"password_set":true,"sms_available":true,"recent_auth_sec":540,"legacy_phone":{"country_code":"86","phone":"13712340002","masked":"+86 137****0002"}}"""), SecurityStatus.serializer())
        assertEquals(540, st.recentAuthSec)
        assertEquals(LegacyPhone("86", "13712340002", "+86 137****0002"), st.legacyPhone)
        assertFalse(st.legacyPhone.toString().contains("13712340002"))
        val old = decode(json("{}"), SecurityStatus.serializer())
        assertEquals(0, old.recentAuthSec); assertNull(old.legacyPhone)
    }

    /** 空证明：既不带 proof 也不带旧的顶层 password（服务端只认「没有」为空证明）。七个接口共用 putProof。 */
    @Test fun `空证明 请求体不带 proof 与 password`() {
        val bodies = listOf(
            SecurityApi.bindPhoneBody(Proof.None, "86", "13622220002", "000000"),
            EmailApi.unbindBody(Proof.None),
            EmailApi.sendBody("a@b.c", Proof.None, "zh-Hans"),
            TwoStepApi.setupBody(Proof.None),
            TwoStepApi.verifyBody(Proof.None, "123456"),
            ExportApi.startBody(Proof.None, false, listOf("json")),
        )
        for (b in bodies) {
            assertFalse(b.toString(), "proof" in b.keys)
            assertFalse(b.toString(), "password" in b.keys)
        }
        assertEquals(setOf("country_code", "phone", "code"), bodies.first().keys)
        assertTrue("proof" in TwoStepApi.setupBody(Proof.Password("p")).keys)
    }

    /** 资料编辑不再发 phone（服务端忽略，资料卡 phone = 登录手机号，PHONE_AUTH_DESIGN §15.1）。 */
    @Test fun `资料更新请求体不带 phone`() {
        assertEquals(setOf("nickname", "avatar_url", "bio"), ProfileApi.updateBody("n", "u", "b").keys)
    }

    @Test fun `proof 打码`() {
        assertFalse(Proof.Password("secret").toString().contains("secret"))
        assertFalse(Proof.SmsCode("123456").toString().contains("123456"))
    }
}
