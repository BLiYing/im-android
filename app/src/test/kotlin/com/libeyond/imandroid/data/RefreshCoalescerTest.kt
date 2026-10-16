package com.libeyond.imandroid.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 群帧连发（一批邀请）时，会话列表重拉合并成一次；在飞期间又来请求则补一轮。 */
class RefreshCoalescerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    @Test fun burstRunsOnce() = runBlocking {
        val runs = AtomicInteger()
        val c = RefreshCoalescer(scope, 50) { runs.incrementAndGet() }
        repeat(100) { c.request() }
        delay(300)
        assertEquals(1, runs.get())
    }

    @Test fun requestDuringFlightRerunsOnce() = runBlocking {
        val runs = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val c = RefreshCoalescer(scope, 10) {
            if (runs.incrementAndGet() == 1) { started.complete(Unit); gate.await() }
        }
        c.request()
        started.await()
        repeat(50) { c.request() } // 在飞期间连发
        gate.complete(Unit)
        delay(300)
        assertEquals(2, runs.get())
    }

    @Test fun canRunAgainAfterFinish() = runBlocking {
        val runs = AtomicInteger()
        val c = RefreshCoalescer(scope, 10) { runs.incrementAndGet() }
        c.request(); delay(150)
        c.request(); delay(150)
        assertEquals(2, runs.get())
    }
}
