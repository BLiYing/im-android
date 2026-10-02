package com.libeyond.imandroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapRuleTest {
    @Test
    fun `紧接游标的下一条不算跳号`() {
        assertFalse(GapRule.needsCatchUp(prevSynced = 10, seq = 11))
        assertTrue(GapRule.isNextContiguous(10, 11))
    }

    @Test
    fun `跳过了一条以上算跳号 要补`() {
        assertTrue(GapRule.needsCatchUp(10, 12))
        assertFalse(GapRule.isNextContiguous(10, 12))
    }

    @Test
    fun `重复投递与旧消息不算跳号`() {
        assertFalse(GapRule.needsCatchUp(10, 10))
        assertFalse(GapRule.needsCatchUp(10, 3))
        assertFalse(GapRule.isNextContiguous(10, 10))
    }

    @Test
    fun `游标为 0 而 seq 大于 1 要补 seq 为 1 不用`() {
        assertTrue(GapRule.needsCatchUp(0, 5)) // 新会话第一次就收到第 5 条：前面有历史
        assertFalse(GapRule.needsCatchUp(0, 1))
        assertTrue(GapRule.isNextContiguous(0, 1))
    }

    @Test
    fun `非法 seq 不触发`() {
        assertFalse(GapRule.needsCatchUp(10, 0))
        assertFalse(GapRule.needsCatchUp(10, -4))
    }
}
