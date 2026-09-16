package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聊天页查看器「更多」的外部动作（iOS `mediaViewerMoreActionsForMessage:`）。 */
class ViewerActionsTest {

    private fun actions(convSeq: Long = 42, recalled: Boolean = false, isVideo: Boolean = false) =
        ViewerActions.chatMoreActions(convSeq, recalled, isVideo)

    @Test
    fun `已确认的图片给齐定位、收藏、复制、转发、删除，顺序与文案同 iOS`() {
        val a = actions()
        assertEquals(
            listOf(
                ViewerAction.Locate, ViewerAction.Favorite, ViewerAction.Copy,
                ViewerAction.Forward, ViewerAction.Delete,
            ),
            a,
        )
        assertEquals(listOf("定位到聊天位置", "收藏", "复制", "转发", "删除"), a.map { it.label })
    }

    @Test
    fun `视频没有复制——无复制字节的语义，复制链接意义不大（iOS 同）`() {
        assertFalse(ViewerAction.Copy in actions(isVideo = true))
        assertTrue(ViewerAction.Copy in actions(isVideo = false))
    }

    @Test
    fun `未确认的那条只剩复制——定位、收藏、转发、删除都要 conv_seq`() {
        // 刚发出还没 ack 的图，本地就有字节，复制得了；其余四项在服务端还不存在这条消息
        assertEquals(listOf(ViewerAction.Copy), actions(convSeq = 0))
    }

    @Test
    fun `未确认的视频什么都不给——连复制也没有`() {
        assertEquals(emptyList<ViewerAction>(), actions(convSeq = 0, isVideo = true))
    }

    @Test
    fun `已撤回的不给收藏、复制与转发——内容已被服务端脱敏`() {
        assertEquals(
            listOf(ViewerAction.Locate, ViewerAction.Delete),
            actions(recalled = true),
        )
    }

    @Test
    fun `删除恒在最后且是危险项`() {
        val a = actions()
        assertEquals(ViewerAction.Delete, a.last())
        assertTrue(a.last().destructive)
    }
}
