package com.libeyond.imandroid.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 来电横幅上点的「接听 / 拒绝」：等那通来电到了执行一次，别的来电、过期的都不执行。 */
class PendingCallActionTest {

    @Test
    fun `同一通来电取走一次，第二次没有了`() {
        PendingCallAction.request("c1", accept = true, nowMs = 1_000)
        assertEquals(true, PendingCallAction.consume("c1", nowMs = 2_000))
        assertNull(PendingCallAction.consume("c1", nowMs = 2_000))
    }

    @Test
    fun `别的来电不执行，也不把这条吃掉`() {
        PendingCallAction.request("c1", accept = false, nowMs = 1_000)
        assertNull(PendingCallAction.consume("c2", nowMs = 2_000))
        assertEquals(false, PendingCallAction.consume("c1", nowMs = 2_000))
    }

    @Test
    fun `过了振铃时长作废`() {
        PendingCallAction.request("c1", accept = true, nowMs = 0)
        assertNull(PendingCallAction.consume("c1", nowMs = PendingCallAction.TTL_MS + 1))
    }
}

/** 横幅「接听」只在绕过 Kit 接听流程也不出事时直接接（见 autoAcceptAllowed 注释）。 */
class AutoAcceptAllowedTest {
    @Test
    fun `有麦克风权限的语音与群视频可以直接接`() {
        assertEquals(true, autoAcceptAllowed(micGranted = true, mediaType = "audio", isGroup = false))
        assertEquals(true, autoAcceptAllowed(micGranted = true, mediaType = "video", isGroup = true))
    }

    @Test
    fun `没麦克风权限或 1v1 视频交给 Kit 来电界面`() {
        assertEquals(false, autoAcceptAllowed(micGranted = false, mediaType = "audio", isGroup = false))
        assertEquals(false, autoAcceptAllowed(micGranted = true, mediaType = "video", isGroup = false))
    }
}
