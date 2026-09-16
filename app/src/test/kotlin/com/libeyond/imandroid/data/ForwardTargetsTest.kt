package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 转发选择页列哪些会话、按什么顺序发（对齐 iOS `IMForwardPickerViewController`）。 */
class ForwardTargetsTest {

    private fun conv(id: String, title: String = "", peer: String = "", group: Boolean = false) =
        ConversationEntity(ownerUid = "me", convId = id, isGroup = group, peerUid = peer, title = title)

    private val system = conv("p_sys", title = "系统通知", peer = DetailActions.SYSTEM_UID)
    private val alice = conv("p_a", title = "Alice", peer = "1000000001")
    private val team = conv("g_1", title = "项目群", group = true)

    @Test
    fun `系统通知单聊不出现——服务端拒收发往 system 的消息，列出来只会点了报错`() {
        assertEquals(listOf(alice, team), Forward.pickable(listOf(system, alice, team), ""))
    }

    @Test
    fun `按显示名或对端 uid 过滤，大小写不敏感`() {
        val all = listOf(system, alice, team)
        assertEquals(listOf(alice), Forward.pickable(all, " alice"))
        assertEquals(listOf(alice), Forward.pickable(all, "1000000001"))
        assertEquals(listOf(team), Forward.pickable(all, "项目"))
        // 搜「系统」也搜不出系统通知：它是被剔除的，不是被过滤掉的
        assertEquals(emptyList<ConversationEntity>(), Forward.pickable(all, "系统"))
    }

    @Test
    fun `发送顺序是勾选顺序，已不在列表里的目标跳过`() {
        val all = listOf(alice, team)
        assertEquals(listOf(team, alice), Forward.targetsInOrder(listOf("g_1", "gone", "p_a"), all))
    }

    @Test
    fun `没有标题时不落内部 ID`() {
        assertEquals("未命名群聊", Forward.titleOf(conv("g_2", group = true)))
        assertEquals(DisplayName.UNNAMED, Forward.titleOf(conv("p_2", peer = "1000000002")))
    }
}
