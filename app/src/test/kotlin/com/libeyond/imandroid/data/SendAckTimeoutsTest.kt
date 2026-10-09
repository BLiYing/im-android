package com.libeyond.imandroid.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** ack 超时计时：每 5s 同 id 重发、3 次后判失败（对齐 iOS）；arm 顶掉旧计时；cancel 后静默。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendAckTimeoutsTest {
    private class Log {
        val resent = mutableListOf<String>()
        val failed = mutableListOf<String>()
    }

    private fun TestScope.make(log: Log) =
        SendAckTimeouts(this, intervalMs = 1000, maxResend = 3, resend = { log.resent += it }, fail = { log.failed += it })

    @Test fun `前三个间隔各重发一次，第四个间隔判失败`() = runTest {
        val log = Log()
        make(log).arm("a")
        advanceTimeBy(999); assertEquals(0, log.resent.size)
        advanceTimeBy(2); assertEquals(1, log.resent.size)
        advanceTimeBy(1000); assertEquals(2, log.resent.size)
        advanceTimeBy(1000); assertEquals(3, log.resent.size)
        assertEquals(emptyList<String>(), log.failed)
        advanceTimeBy(1000); assertEquals(listOf("a"), log.failed)
        advanceTimeBy(5000); assertEquals(3, log.resent.size) // 判失败后不再动
    }

    @Test fun `再次arm顶掉旧计时，重发次数清零`() = runTest {
        val log = Log()
        val t = make(log)
        t.arm("a")
        advanceTimeBy(2500) // 已重发 2 次
        t.arm("a")
        advanceTimeBy(2500) // 距补发 2.5s：旧计时若还在，第 3 次已发、第 4 间隔会判失败
        assertEquals(2 + 2, log.resent.size)
        assertEquals(emptyList<String>(), log.failed)
    }

    @Test fun `cancel后不再重发也不判失败（ack已到）`() = runTest {
        val log = Log()
        val t = make(log)
        t.arm("a"); t.arm("b")
        advanceTimeBy(500)
        t.cancel("a")
        advanceTimeBy(5000)
        assertEquals(listOf("b", "b", "b"), log.resent)
        assertEquals(listOf("b"), log.failed)
    }
}
