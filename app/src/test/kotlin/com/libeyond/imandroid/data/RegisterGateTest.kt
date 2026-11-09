package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Test

/** 登录页注册开关（S4）：结果按发起时的 host 记账，改地址后旧回包不串台。 */
class RegisterGateTest {
    @Test fun `关闭的结果只对发起时的 host 生效`() {
        val closed = RegisterGate.afterResult(null, "a:8080", enabled = false)
        assertTrue(RegisterGate.isClosed(closed, "a:8080"))
        assertFalse("改了地址后旧地址的关闭不能挂到新地址上", RegisterGate.isClosed(closed, "b:8080"))
    }

    @Test fun `旧地址迟到的开放回包不能解禁新地址`() {
        val closedB = RegisterGate.afterResult(null, "b:8080", enabled = false)
        val after = RegisterGate.afterResult(closedB, "a:8080", enabled = true)
        assertEquals("b:8080", after)
        assertTrue(RegisterGate.isClosed(after, "b:8080"))
    }

    @Test fun `同一地址重新开放即解禁`() =
        assertNull(RegisterGate.afterResult("a:8080", "a:8080", enabled = true))

    @Test fun `不知道时当开放`() = assertFalse(RegisterGate.isClosed(null, "a:8080"))

    @Test fun `提交注册回 200007 等同该 host 的 register_enabled=false`() {
        val closed = RegisterGate.afterSubmitRejected(null, "a:8080", ErrCode.REGISTER_CLOSED)
        assertEquals("a:8080", closed)
        assertTrue(RegisterGate.isClosed(closed, "a:8080"))
        assertFalse(RegisterGate.isClosed(closed, "b:8080"))
        assertNull("该 host 下一次 public-config 回开放即解禁", RegisterGate.afterResult(closed, "a:8080", enabled = true))
    }

    @Test fun `其他注册错误不动开关（调用方照常显示错误行）`() {
        assertNull(RegisterGate.afterSubmitRejected(null, "a:8080", ErrCode.USER_ALREADY_EXISTS))
        assertNull(RegisterGate.afterSubmitRejected("b:8080", "a:8080", ErrCode.RATE_LIMITED))
    }
}
