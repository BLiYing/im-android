package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上传状态：控制钮取值与进度状态机（文案走 Str，不在 JVM 单测里断言）。 */
class UploadStateTest {
    private val q = UploadState(UploadState.Phase.Queued)
    private fun up(pausable: Boolean, paused: Boolean = false) = UploadState(UploadState.Phase.Uploading, 10, 100, pausable, paused)

    @Test fun `排队中给取消钮，可暂停的传输中给暂停钮，暂停后给继续钮，整包上传不给钮`() {
        assertEquals(UploadState.Control.Cancel, q.control)
        assertEquals(UploadState.Control.Pause, up(true).control)
        assertEquals(UploadState.Control.Resume, up(true, paused = true).control)
        assertEquals(UploadState.Control.None, up(false).control)
    }

    @Test fun `进度比例向下取整，环形进度夹在 0 到 1`() {
        assertEquals(9, UploadState(UploadState.Phase.Uploading, 99, 1000).percent)
        assertEquals(0f, UploadState(UploadState.Phase.Uploading, 0, 0).fraction, 0f)
        assertEquals(1f, UploadState(UploadState.Phase.Uploading, 5, 3).fraction, 0f)
    }

    @Test fun `UploadProgress 同步完整状态并保留 pausable 与 paused`() {
        val p = UploadProgress()
        p.uploading("c", 0, 100, pausable = true)
        p.setPaused("c", true)
        p.report("c", 40, 100) // 进度回调不能把暂停标志冲掉
        val s = p.states.value.getValue("c")
        assertEquals(40L, s.sent)
        assertTrue(s.paused)
        assertTrue(s.pausable)
        assertEquals(40, p.state.value["c"])
        p.clear("c")
        assertTrue(p.states.value.isEmpty() && p.state.value.isEmpty())
    }

    @Test fun `排队态只进完整状态，不写百分比（否则整包上传的图片会一直挂 0% 环）`() {
        val p = UploadProgress()
        p.queued("c", 100)
        assertEquals(UploadState.Phase.Queued, p.states.value.getValue("c").phase)
        assertEquals(null, p.state.value["c"])
    }
}
