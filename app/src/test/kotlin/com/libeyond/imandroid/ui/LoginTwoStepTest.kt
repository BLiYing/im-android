package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.api.TwoStepChallenge
import com.libeyond.imandroid.sdk.http.ApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginTwoStepTest {
    private fun flow() = LoginTwoStep().also { it.begin(TwoStepChallenge("tk"), "twostep01") }

    @Test fun `开始后进二步页 丢弃后 ticket 与用户名都没了`() {
        val f = flow()
        assertTrue(f.active); assertEquals("tk", f.challenge!!.ticket)
        f.drop()
        assertFalse(f.active); assertNull(f.challenge); assertEquals("", f.username)
    }

    @Test fun `码错留在本页 failTick 加一 剩余次数小于等于 2 才提示`() {
        val f = flow()
        val e2 = ApiException(200021, "x", data = buildJsonObject { put("attempts_left", 2) })
        val r = f.onRejected(e2)
        assertTrue(r is LoginTwoStep.Outcome.Stay)
        assertEquals("验证码不正确，还可尝试 2 次", r.message)
        assertEquals(1, f.failTick); assertTrue(f.active)
        val r2 = f.onRejected(ApiException(200021, "x", data = buildJsonObject { put("attempts_left", 4) }))
        assertEquals("验证码不正确", r2.message)
        assertEquals(2, f.failTick)
    }

    @Test fun `ticket 失效回密码页并丢弃 ticket`() {
        val f = flow()
        val r = f.onRejected(ApiException(200022, "x"))
        assertTrue(r is LoginTwoStep.Outcome.BackToPassword)
        assertEquals("已超时，请重新登录", r.message)
        assertFalse(f.active)
    }

    @Test fun `限流给限流文案`() {
        assertEquals("尝试次数过多，请稍后再试", flow().onRejected(ApiException(100002, "x")).message)
    }
}
