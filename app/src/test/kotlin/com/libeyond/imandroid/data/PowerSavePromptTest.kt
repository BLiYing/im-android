package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavePromptTest {
    private val bat = PowerSaveReason.BATTERY
    private val min = 60_000L

    @Test
    fun foregroundRisingEdgeShowsOnceUntilCharging() {
        var s = PowerSavePromptState()
        var st = PowerSavePrompt.onStatus(s, true, bat, false, true, 0)
        assertTrue(st.showNow); s = st.state
        // 电量继续掉、状态再算一遍：不重弹
        st = PowerSavePrompt.onStatus(s, true, bat, false, true, min); s = st.state
        assertFalse(st.showNow)
        // 开始充电：重置（active 变 false）
        st = PowerSavePrompt.onStatus(s, false, null, true, true, 2 * min); s = st.state
        assertFalse(s.shown)
        // 下一个放电周期再弹
        st = PowerSavePrompt.onStatus(s, false, null, false, true, 3 * min); s = st.state
        st = PowerSavePrompt.onStatus(s, true, bat, false, true, 4 * min)
        assertTrue(st.showNow)
    }

    @Test
    fun manualAlwaysAndSystemNeverPrompt() {
        for (r in listOf(PowerSaveReason.ALWAYS, PowerSaveReason.SYSTEM)) {
            assertFalse(PowerSavePrompt.onStatus(PowerSavePromptState(), true, r, false, true, 0).showNow)
        }
    }

    @Test
    fun backgroundTriggerShowsOnReturnWithinTenMinutes() {
        val st = PowerSavePrompt.onStatus(PowerSavePromptState(), true, bat, false, false, 0)
        assertFalse(st.showNow)
        val back = PowerSavePrompt.onForeground(st.state, true, 10 * min)
        assertTrue(back.showNow)
        // 只补一次
        assertFalse(PowerSavePrompt.onForeground(back.state, true, 10 * min).showNow)
    }

    @Test
    fun backgroundTriggerExpiresAfterTenMinutesOrWhenNoLongerActive() {
        val st = PowerSavePrompt.onStatus(PowerSavePromptState(), true, bat, false, false, 0).state
        assertFalse(PowerSavePrompt.onForeground(st, true, 10 * min + 1).showNow)
        assertFalse(PowerSavePrompt.onForeground(st, false, min).showNow)
    }

    @Test
    fun chargingBeforeReturnCancelsPending() {
        var s = PowerSavePrompt.onStatus(PowerSavePromptState(), true, bat, false, false, 0).state
        s = PowerSavePrompt.onStatus(s, false, null, true, false, min).state
        assertFalse(PowerSavePrompt.onForeground(s, true, 2 * min).showNow)
    }

    @Test
    fun alwaysToAutoWhileAlreadyActiveIsNotRisingEdge() {
        val s = PowerSavePrompt.onStatus(PowerSavePromptState(), true, PowerSaveReason.ALWAYS, false, true, 0).state
        assertFalse(PowerSavePrompt.onStatus(s, true, bat, false, true, min).showNow)
    }

    @Test
    fun startupShownSurvivesOnlyWhileStillBatteryTriggered() {
        assertTrue(PowerSavePrompt.startupShown(true, bat))
        assertFalse(PowerSavePrompt.startupShown(true, null))                       // 已充电 / 回到阈值以上
        assertFalse(PowerSavePrompt.startupShown(true, PowerSaveReason.ALWAYS))
        assertFalse(PowerSavePrompt.startupShown(false, bat))
    }

    @Test
    fun restartedInSameCycleDoesNotPromptAgain() {
        val s = PowerSavePromptState(shown = PowerSavePrompt.startupShown(true, bat))
        assertFalse(PowerSavePrompt.onStatus(s, true, bat, false, true, 0).showNow)
    }
}
