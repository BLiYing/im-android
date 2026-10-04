package com.libeyond.imandroid.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDisconnectTest {

    @Test
    fun pushReachableNeedsServerFlagAndReportedToken() {
        assertTrue(BackgroundDisconnect.pushReachable(true, true))
        assertFalse(BackgroundDisconnect.pushReachable(true, false))
        assertFalse(BackgroundDisconnect.pushReachable(false, true))
        assertFalse(BackgroundDisconnect.pushReachable(null, true))
    }

    @Test
    fun appliesOnlyWhenKeepOffAndPushReachable() {
        assertTrue(BackgroundDisconnect.applies(keepConnectionEffective = false, pushReachable = true))
        assertFalse(BackgroundDisconnect.applies(true, true))
        assertFalse(BackgroundDisconnect.applies(false, false))
    }

    @Test
    fun noDisconnectDuringCallOrTransfers() {
        assertTrue(BackgroundDisconnect.canDisconnectNow(false, 0))
        assertFalse(BackgroundDisconnect.canDisconnectNow(true, 0))
        assertFalse(BackgroundDisconnect.canDisconnectNow(false, 1))
    }

    private class Env(val scope: kotlinx.coroutines.CoroutineScope) {
        var applies = true
        var busy = false
        var disconnects = 0
        var reconnects = 0
        val keeper = BackgroundConnectionKeeper(scope, { applies }, { busy }, { disconnects++ }, { reconnects++ })
    }

    @Test
    fun disconnectsAfter60sInBackgroundThenReconnectsOnStart() = runTest {
        val e = Env(backgroundScope)
        e.keeper.onStop()
        advanceTimeBy(59_999); runCurrent()
        assertEquals(0, e.disconnects)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, e.disconnects)
        e.keeper.onStart()
        assertEquals(1, e.reconnects)
        // onStart 无条件转发 reconnect（Activity 重建后 keeper 不记得停靠态也不会卡死；socket.unpark 自己幂等）
        e.keeper.onStart()
        assertEquals(2, e.reconnects)
    }

    @Test
    fun returningBefore60sCancelsTimerWithoutReconnect() = runTest {
        val e = Env(backgroundScope)
        e.keeper.onStop()
        advanceTimeBy(30_000)
        e.keeper.onStart()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, e.disconnects); assertEquals(1, e.reconnects) // onStart 的无条件 reconnect
    }

    @Test
    fun busyDefersAndRestartsTimer() = runTest {
        val e = Env(backgroundScope)
        e.busy = true
        e.keeper.onStop()
        advanceTimeBy(60_001); runCurrent()
        assertEquals(0, e.disconnects)
        e.busy = false
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, e.disconnects)
    }

    @Test
    fun notApplyingNeverDisconnects() = runTest {
        val e = Env(backgroundScope)
        e.applies = false
        e.keeper.onStop()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(0, e.disconnects)
    }

    @Test
    fun conditionBecomingTrueAfter60sParksOnReevaluate() = runTest {
        val e = Env(backgroundScope)
        e.applies = false
        e.keeper.onStop()
        advanceTimeBy(61_000); runCurrent()
        assertEquals(0, e.disconnects)
        e.applies = true // 例如电量穿过阈值、令牌上报成功
        e.keeper.reevaluate(); runCurrent()
        assertEquals(1, e.disconnects)
        e.keeper.reevaluate(); runCurrent() // 已停靠不重复断
        assertEquals(1, e.disconnects)
    }

    @Test
    fun reevaluateBefore60sOrInForegroundDoesNothing() = runTest {
        val e = Env(backgroundScope)
        e.keeper.reevaluate(); runCurrent() // 前台
        e.keeper.onStop()
        advanceTimeBy(10_000)
        e.keeper.reevaluate(); runCurrent() // 未满 60s：等计时器
        assertEquals(0, e.disconnects)
        advanceTimeBy(51_000); runCurrent()
        assertEquals(1, e.disconnects)
    }

    @Test
    fun startingForegroundAgainAllowsParkingNextTime() = runTest {
        val e = Env(backgroundScope)
        e.keeper.onStop(); advanceTimeBy(61_000); runCurrent()
        e.keeper.onStart()
        e.keeper.onStop(); advanceTimeBy(61_000); runCurrent()
        assertEquals(2, e.disconnects)
    }
}
