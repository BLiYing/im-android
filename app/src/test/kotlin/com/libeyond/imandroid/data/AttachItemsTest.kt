package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ➕ 面板的清单与顺序是**跨端契约**：用户靠位置记住「文件在右下角」，
 * 两端顺序不同就是两套肌肉记忆。基准是 iOS `IMChatViewController+Media.m` 的 `attachItems`。
 */
class AttachItemsTest {

    @Test
    fun `五项且顺序与 iOS 一致`() {
        // 音视频入口已删除（六条用户报告第 5 项，2026-09-29）：呼叫/视频早已在聊天详情页真正接通，
        // 面板这颗是打不通的占位，留着反而误导。
        assertEquals(
            listOf("照片", "拍摄", "收藏", "个人名片", "文件"),
            AttachItems.ALL.map { it.title },
        )
    }

    @Test
    fun `排两行，末行不必填满`() {
        assertEquals(2, (AttachItems.ALL.size + AttachItems.COLUMNS - 1) / AttachItems.COLUMNS)
    }

    @Test
    fun `未实现的项照样列出来`() {
        // 删掉会让三端面板长得不一样：用户在另一端找得到、在这端找不到，比点进去看到「还没做」更困惑
        val notDone = AttachItems.ALL.filter { !it.implemented }.map { it.title }
        assertEquals(listOf("收藏"), notDone)
        assertTrue(AttachItems.ALL.any { it.implemented })
    }

    @Test
    fun `面板几何与 iOS 同值`() {
        assertEquals(236, AttachItems.PANEL_HEIGHT)
        assertEquals(56, AttachItems.ITEM_SIZE)
    }
}
