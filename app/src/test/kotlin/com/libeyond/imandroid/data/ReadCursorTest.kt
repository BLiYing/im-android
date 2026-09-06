package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已读位点的单调性。三条都对应 2026-09-07 自审查出的真问题。
 *
 * 这里测的是**判据**（该不该上报、位点该变成多少），DAO 的 SQL 由 Room 保证；
 * 两者用同一条规则：位点只增不减。
 */
class ReadCursorTest {

    /** 复刻 MessageService.markRead 的闸门判据。 */
    private fun shouldReport(lastReported: Long, upTo: Long) = upTo > lastReported

    /** 复刻 DAO 的 MAX 语义。 */
    private fun newReadSeq(current: Long, upTo: Long) = maxOf(current, upTo)

    /**
     * 往回翻历史时可见的最大 seq 变小。
     * 无条件赋值会把已读位点写回去 →「翻了下历史，未读又冒出来了」，
     * 还会向服务端发一个倒退的 read 回执。
     */
    @Test
    fun `位点不因回翻而倒退`() {
        assertEquals(100L, newReadSeq(current = 100, upTo = 40))
        assertFalse(shouldReport(lastReported = 100, upTo = 40))
    }

    @Test
    fun `位点前进时上报并推进`() {
        assertTrue(shouldReport(lastReported = 100, upTo = 130))
        assertEquals(130L, newReadSeq(current = 100, upTo = 130))
    }

    /** 静止不动时重组会反复触发可见即读——同一位点不该重复发帧。 */
    @Test
    fun `同一位点不重复上报`() {
        assertFalse(shouldReport(lastReported = 130, upTo = 130))
    }

    @Test
    fun `首次上报`() {
        assertTrue(shouldReport(lastReported = 0, upTo = 7))
        assertEquals(7L, newReadSeq(current = 0, upTo = 7))
    }
}
