package com.libeyond.imandroid.ui

import com.libeyond.imandroid.ui.ChatOverlays.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 返回键的分层关闭。
 *
 * `ChatHost` 原本只有一个无条件的 `BackHandler(onBack)`：任何覆盖层开着时按返回
 * 都会**直接退出整个聊天页**。覆盖层是逐个加上去的（转发 → 选图 → 选联系人 → 媒体查看器），
 * 每加一个都没人想起返回键——所以把层序钉在这里，加新层时会被这组测试提醒。
 */
class ChatOverlaysTest {

    @Test
    fun `没有覆盖层时才退出本页`() {
        assertNull(ChatOverlays.topmost(emptySet()))
    }

    @Test
    fun `关最上面那一层`() {
        assertEquals(Layer.Viewer, ChatOverlays.topmost(setOf(Layer.Viewer)))
        // 查看器可以从选图页的预览之外的地方开；两层都在时先关查看器
        assertEquals(Layer.Viewer, ChatOverlays.topmost(setOf(Layer.MediaPicker, Layer.Viewer)))
        assertEquals(Layer.MediaPicker, ChatOverlays.topmost(setOf(Layer.Forward, Layer.MediaPicker)))
        assertEquals(Layer.ContextMenu, ChatOverlays.topmost(setOf(Layer.ContextMenu)))
    }

    @Test
    fun `层序就是渲染顺序`() {
        // 改这个顺序 = 改返回键行为，必须同时改 ChatHost 里的渲染顺序。
        // 2026-09-08 加 UserProfile（点系统消息里的名字进资料页）时这条如期变红——
        // 加一层就得说清它排在哪，这正是这条测试存在的意义。
        // 它排在 Viewer 之下：查看器是从资料页也可能开出来的最临时的一层。
        assertEquals(
            listOf(
                Layer.Viewer, Layer.UserProfile, Layer.FriendPicker,
                Layer.MediaPicker, Layer.Forward, Layer.ContextMenu,
            ),
            Layer.entries,
        )
    }

    @Test
    fun `每一层单独开着都能被关掉——不会漏掉任何一层`() {
        // 新加一层却忘了在 ChatHost 的 BackHandler 里接，这条抓不到（那要 UI 测试）；
        // 但它至少保证 topmost 对每一层都有定义，不会返回 null 而误退出整页
        Layer.entries.forEach { assertEquals(it, ChatOverlays.topmost(setOf(it))) }
    }
}
