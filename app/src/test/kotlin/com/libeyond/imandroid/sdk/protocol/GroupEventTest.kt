package com.libeyond.imandroid.sdk.protocol

import com.libeyond.imandroid.sdk.api.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `group` 帧（PROTOCOL §6.6）的解析与「本群对我已不可用」判据。 */
class GroupEventTest {
    private fun parse(json: String) = ProtocolJson.decodeFromString(GroupEventData.serializer(), json)

    @Test
    fun `被移出的是自己才算本群不可用`() {
        val e = parse("""{"event":"remove","conv_id":"g_1","from":"1001","target":"1002"}""")
        assertTrue(e.goneForMe("1002"))
        assertFalse(e.goneForMe("1003")) // 别人被移出：只是成员表变了
        assertFalse(e.goneForMe(null))
    }

    @Test
    fun `自己退群也推给退群者，其它设备据此移除该群`() {
        val e = parse("""{"event":"leave","conv_id":"g_1","from":"1002","target":"1002"}""")
        assertTrue(e.goneForMe("1002"))
        assertTrue(e.leftMe("1002"))
        assertFalse(e.goneForMe("1003")) // 别人退群：只是成员表变了
    }

    @Test
    fun `解散对全体生效`() {
        val e = parse("""{"event":"dissolve","conv_id":"g_1"}""")
        assertTrue(e.dissolved)
        assertTrue(e.goneForMe("1002"))
    }

    @Test
    fun `入群审批结果带 result 且不算不可用`() {
        val e = parse("""{"event":"join_result","conv_id":"g_1","target":"1002","result":"approved"}""")
        assertEquals(GroupEventData.JOIN_RESULT, e.event)
        assertEquals(GroupEventData.APPROVED, e.result)
        assertFalse(e.goneForMe("1002"))
    }

    @Test
    fun `缺字段与未知字段不崩`() {
        val e = parse("""{"event":"profile","extra":1}""")
        assertEquals("", e.convId)
        assertFalse(e.goneForMe("1"))
    }

    @Test
    fun `会话摘要带群待审数`() {
        val s = ProtocolJson.decodeFromString(
            ConversationSummary.serializer(), """{"conv_id":"g_1","is_group":true,"pending_count":3}""",
        )
        assertEquals(3, s.pendingCount)
        assertEquals(0, ProtocolJson.decodeFromString(ConversationSummary.serializer(), """{"conv_id":"g_2"}""").pendingCount)
    }

    @Test
    fun `只有影响我入群位点或开关的帧才丢可见下界`() {
        // 群设置 / 解散 / 未知事件：一律丢
        assertTrue(parse("""{"event":"settings","conv_id":"g_1","from":"1001"}""").changesMyHistoryFloor("1002"))
        assertTrue(parse("""{"event":"dissolve","conv_id":"g_1"}""").changesMyHistoryFloor("1002"))
        assertTrue(parse("""{"event":"brand_new","conv_id":"g_1"}""").changesMyHistoryFloor("1002"))
        // 轮到我被邀请 / 退群 / 移出：丢；别人的：不丢
        for (ev in listOf("invite", "leave", "remove")) {
            assertTrue(parse("""{"event":"$ev","conv_id":"g_1","target":"1002"}""").changesMyHistoryFloor("1002"))
            assertFalse(parse("""{"event":"$ev","conv_id":"g_1","target":"1003"}""").changesMyHistoryFloor("1002"))
        }
        // 资料 / 公告 / 角色 / 转让 / 单人禁言：不影响
        for (ev in listOf("profile", "announcement", "role", "transfer", "member_mute")) {
            assertFalse(parse("""{"event":"$ev","conv_id":"g_1","target":"1002"}""").changesMyHistoryFloor("1002"))
        }
    }
}
