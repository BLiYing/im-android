package com.libeyond.imandroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatArm.forConv]：「换会话并顺带做件事」的待办只交给目标会话那一页。
 *
 * 资料页「搜索」pill（2026-10-07 用户报「点了没反应」）是**换到与此人的单聊**再开搜索；换会话有一段 push 转场，
 * 期间旧聊天页仍在组合里——不带归属的话它会先把待办吃掉并复位，新开的那页什么都收不到。
 */
class ChatArmTest {
    @Test fun `不带归属的待办交给当前那一页（详情页关掉回原聊天页的老路径）`() {
        val arm = ChatArm(openSearch = true)
        assertEquals(arm, arm.forConv("g_1"))
        assertEquals(arm, arm.forConv("u_1_2"))
    }

    @Test fun `带归属的待办只交给目标会话`() {
        val arm = ChatArm(openSearch = true, convId = "u_1_2")
        assertEquals(arm, arm.forConv("u_1_2"))
        // 转场中还在组合里的旧页（群聊）看到的是空待办，不会把它吃掉
        assertTrue(arm.forConv("g_1").isEmpty)
    }

    @Test fun `定位待办同样按归属分发`() {
        val arm = ChatArm(locateSeq = 42, convId = "g_9")
        assertEquals(42L, arm.forConv("g_9").locateSeq)
        assertEquals(0L, arm.forConv("g_8").locateSeq)
    }
}
