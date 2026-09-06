package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同步游标推进（PROTOCOL §6.2）。
 * 每条用例对应一次真实事故或一条明文契约，不是凑覆盖率。
 */
class SyncCursorRuleTest {

    @Test
    fun `全部成功则推进到 covered`() {
        assertEquals(60L, SyncCursorRule.advance(current = 57, covered = 60))
    }

    /**
     * 核心：covered 大于本页实际下发的最大序号时，游标要跨过去。
     * 场景：(57,60] 里 58、59 因 history_visible 对我不可见，只下发了 60。
     * 若按 latest 推进就会卡在空洞前反复重拉——iOS 2026-08-13 连刷一天多的根因。
     */
    @Test
    fun `跨过对我不可见的空洞`() {
        assertEquals(60L, SyncCursorRule.advance(current = 57, covered = 60))
    }

    /** 老服务端不带 covered（=0）时保持原位，绝不倒退。 */
    @Test
    fun `老服务端 covered 为 0 时不倒退`() {
        assertEquals(57L, SyncCursorRule.advance(current = 57, covered = 0))
    }

    /** covered 小于当前游标（乱序响应 / 重复页）时同样不倒退。 */
    @Test
    fun `covered 落后于当前游标时不倒退`() {
        assertEquals(100L, SyncCursorRule.advance(current = 100, covered = 60))
    }

    /** 落库中途失败：只推进到首个失败序号之前，其余靠重拉幂等收敛。 */
    @Test
    fun `落库失败只推进到失败点之前`() {
        assertEquals(59L, SyncCursorRule.advance(current = 57, covered = 70, firstFailedSeq = 60))
    }

    /** 失败点就在游标处或更早时原地不动，不能算出比当前更小的值。 */
    @Test
    fun `失败点在游标处则原地不动`() {
        assertEquals(57L, SyncCursorRule.advance(current = 57, covered = 70, firstFailedSeq = 57))
        assertEquals(57L, SyncCursorRule.advance(current = 57, covered = 70, firstFailedSeq = 50))
    }

    @Test
    fun `从零开始`() {
        assertEquals(200L, SyncCursorRule.advance(current = 0, covered = 200))
    }

    /** has_more 时下一页的 since 用新游标，不是 latest。 */
    @Test
    fun `续拉用新游标作 since`() {
        val c = SyncCursorRule.advance(current = 0, covered = 200)
        assertEquals(200L, SyncCursorRule.nextSince(c))
    }
}
