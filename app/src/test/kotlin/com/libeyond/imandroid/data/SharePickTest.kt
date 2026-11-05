package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享选择页的选择模型（SHARE_PICKER_UX_SKETCH ⑦ 规则表）：
 * 三处列表按 convId 共用一份已选、合计最多 9 个、好友 convId 本地推导、移除同步。
 */
class SharePickTest {

    private val me = "5205766476"

    private fun friend(uid: String, nick: String = "n$uid", status: String = FriendEntry.ACCEPTED, blocked: Boolean = false, deleted: Boolean = false) =
        FriendEntry(userId = uid, nickname = nick, username = "u$uid", status = status, blocked = blocked, deleted = deleted)

    private fun recent(convId: String, title: String, isGroup: Boolean = false, peer: String = "") =
        ConversationEntity(ownerUid = me, convId = convId, isGroup = isGroup, peerUid = peer, title = title)

    // ——— 好友 convId 推导 ———

    @Test
    fun `单聊 convId 按字符串字典序，不按数值序`() {
        // 777000（系统账号，6 位）在数值上更小，但字典序 "5205766476" < "777000"
        assertEquals("u_5205766476_u_777000", ConvIds.privateOf("5205766476", "777000"))
        assertEquals("u_5205766476_u_777000", ConvIds.privateOf("777000", "5205766476"))
        assertEquals("u_1000000001_u_1999999999", ConvIds.privateOf("1999999999", "1000000001"))
    }

    @Test
    fun `好友候选的 convId 与最近聊天里同一个人对得上`() {
        val e = SharePick.friendEntries(me, listOf(friend("1199353701"))).single()
        assertEquals("u_1199353701_u_5205766476", e.conv.convId)
        assertFalse(e.conv.isGroup)
        assertEquals("1199353701", e.conv.peerUid)
    }

    @Test
    fun `好友列表剔除拉黑、已注销、非 accepted、系统账号与自己`() {
        val list = listOf(
            friend("1000000001"),
            friend("1000000002", blocked = true),
            friend("1000000003", deleted = true),
            friend("1000000004", status = FriendEntry.PENDING),
            friend(DetailActions.SYSTEM_UID),
            friend(me),
        )
        assertEquals(listOf("1000000001"), SharePick.friendEntries(me, list).map { it.conv.peerUid })
    }

    @Test
    fun `群候选直接用服务端 convId`() {
        val e = SharePick.groupEntries(me, listOf(GroupInfo(convId = "g_abc", name = "家庭群"))).single()
        assertEquals("g_abc", e.conv.convId)
        assertTrue(e.conv.isGroup)
        assertEquals("家庭群", e.conv.title)
    }

    // ——— 跨来源共用一份已选（按 convId 去重）———

    @Test
    fun `在好友里勾的人，最近聊天里同一会话也算已勾；再点一次是取消`() {
        val fromFriends = SharePick.friendEntries(me, listOf(friend("1199353701"))).single().conv
        val inRecent = recent("u_1199353701_u_5205766476", "张三", peer = "1199353701")
        val sel = requireNotNull(SharePickSelection().toggle(fromFriends))
        assertTrue(inRecent.convId in sel)
        val after = requireNotNull(sel.toggle(inRecent))
        assertEquals(0, after.size)
    }

    @Test
    fun `发送目标按勾选顺序；本地有会话用会话行，没聊过的用勾选时的那份`() {
        val chatted = recent("u_1000000001_u_5205766476", "最近里的名字", peer = "1000000001")
        val friends = SharePick.friendEntries(me, listOf(friend("1000000001", nick = "好友里的名字"), friend("1000000009", nick = "没聊过"))).map { it.conv }
        val group = SharePick.groupEntries(me, listOf(GroupInfo(convId = "g_x", name = "会话已删的群"))).single().conv
        var sel = SharePickSelection()
        sel = requireNotNull(sel.toggle(friends[1]))
        sel = requireNotNull(sel.toggle(group))
        sel = requireNotNull(sel.toggle(friends[0]))
        val out = sel.targets(listOf(chatted))
        assertEquals(listOf("u_1000000009_u_5205766476", "g_x", "u_1000000001_u_5205766476"), out.map { it.convId })
        assertEquals("1000000009", out[0].peerUid)
        assertEquals("最近里的名字", out[2].title)
    }

    // ——— 合计上限 9 ———

    @Test
    fun `三处合计最多 9 个，第 10 个被拒且原集合不变`() {
        var sel = SharePickSelection()
        repeat(4) { i -> sel = requireNotNull(sel.toggle(recent("u_r$i", "r$i"))) }
        SharePick.friendEntries(me, (1..3).map { friend("100000000$it") }).forEach { sel = requireNotNull(sel.toggle(it.conv)) }
        SharePick.groupEntries(me, (1..2).map { GroupInfo(convId = "g_$it") }).forEach { sel = requireNotNull(sel.toggle(it.conv)) }
        assertEquals(Forward.MAX_TARGETS, sel.size)
        assertNull(sel.toggle(recent("g_tenth", "第十个", isGroup = true)))
        assertEquals(9, sel.size)
    }

    @Test
    fun `选满后仍可取消勾选`() {
        var sel = SharePickSelection()
        repeat(9) { i -> sel = requireNotNull(sel.toggle(recent("c$i", "c$i"))) }
        val after = requireNotNull(sel.toggle(recent("c3", "c3")))
        assertEquals(8, after.size)
        assertFalse("c3" in after)
    }

    // ——— 移除 ———

    @Test
    fun `底栏或确认面板里移除，勾选态同步去掉、其余顺序不变`() {
        var sel = SharePickSelection()
        listOf("a", "b", "c").forEach { sel = requireNotNull(sel.toggle(recent(it, it))) }
        val after = sel.remove("b")
        assertEquals(listOf("a", "c"), after.ids)
        assertFalse("b" in after)
        assertEquals(listOf("a", "c"), after.targets(emptyList()).map { it.convId })
    }

    @Test
    fun `移除不存在的 convId 原样返回`() {
        val sel = requireNotNull(SharePickSelection().toggle(recent("a", "a")))
        assertSame(sel, sel.remove("zzz"))
    }

    // ——— 主页搜索：三处分组、按 convId 去重 ———

    @Test
    fun `主页搜索三处分组，已在最近聊天出现的不在好友群聊里重复`() {
        val zhang = friend("1000000001", nick = "产品经理小陈")
        val sources = SharePickSources(
            friends = SharePick.friendEntries(me, listOf(zhang, friend("1000000002", nick = "产品张三"))),
            groups = SharePick.groupEntries(me, listOf(GroupInfo(convId = "g_1", name = "产品讨论群"), GroupInfo(convId = "g_2", name = "产品周会"))),
        )
        val recentMatched = listOf(
            recent("g_1", "产品讨论群", isGroup = true),
            recent("u_1000000002_u_5205766476", "产品张三", peer = "1000000002"),
        )
        val s = SharePick.searchSections(recentMatched, sources, "产品")
        assertEquals(listOf("g_1", "u_1000000002_u_5205766476"), s.recent.map { it.convId })
        assertEquals(listOf("u_1000000001_u_5205766476"), s.friends.map { it.convId })
        assertEquals(listOf("g_2"), s.groups.map { it.convId })
    }

    @Test
    fun `好友可按 @用户名 搜到，排除集合生效`() {
        val sources = SharePickSources(friends = SharePick.friendEntries(me, listOf(friend("1000000001", nick = "阿明"))))
        assertEquals(1, SharePick.searchSections(emptyList(), sources, "u1000000001").friends.size)
        assertTrue(SharePick.searchSections(emptyList(), sources, "阿明", exclude = setOf("u_1000000001_u_5205766476")).friends.isEmpty())
    }
}
