package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页操作排的**可见性判据**。每条对应 iOS `actionPillSpecs` / `moreTapped:` 里
 * 一条写了注释的取舍——那些注释记的都是真账，抄错一条就等于把它们全丢了。
 */
class DetailActionsTest {

    @Test
    fun `单聊非好友只给加好友，不给消息`() {
        // 非好友发消息会被服务端 200103 拒收 —— 摆一个必然失败的入口比不摆更糟
        val pills = DetailActions.pillsFor(
            isGroup = false, isSystemPeer = false, peerIsFriend = false, showsMessagePill = true,
        )
        assertEquals(listOf(DetailAction.AddFriend), pills)
    }

    @Test
    fun `单聊非好友连搜索和更多都不给`() {
        val pills = DetailActions.pillsFor(
            isGroup = false, isSystemPeer = false, peerIsFriend = false, showsMessagePill = false,
        )
        assertFalse(pills.contains(DetailAction.Search))
        assertFalse(pills.contains(DetailAction.More))
    }

    @Test
    fun `系统通知会话只有更多`() {
        val pills = DetailActions.pillsFor(
            isGroup = false, isSystemPeer = true, peerIsFriend = true, showsMessagePill = true,
        )
        assertEquals(listOf(DetailAction.More), pills)
    }

    @Test
    fun `单聊好友从外部进来带消息入口，从聊天页进来不带`() {
        val outside = DetailActions.pillsFor(
            isGroup = false, isSystemPeer = false, peerIsFriend = true, showsMessagePill = true,
        )
        val inChat = DetailActions.pillsFor(
            isGroup = false, isSystemPeer = false, peerIsFriend = true, showsMessagePill = false,
        )
        assertEquals(
            listOf(
                DetailAction.Message, DetailAction.Call, DetailAction.Video,
                DetailAction.Search, DetailAction.More,
            ),
            outside,
        )
        assertEquals(
            listOf(DetailAction.Call, DetailAction.Video, DetailAction.Search, DetailAction.More),
            inChat,
        )
    }

    @Test
    fun `群聊只有搜索和更多`() {
        val pills = DetailActions.pillsFor(
            isGroup = true, isSystemPeer = false, peerIsFriend = false, showsMessagePill = true,
        )
        assertEquals(listOf(DetailAction.Search, DetailAction.More), pills)
    }

    @Test
    fun `群主的更多里才有删除群组`() {
        val owner = DetailActions.moreFor(
            isGroup = true, isSystemPeer = false, iAmOwner = true,
            peerBlocked = false, peerIsFriend = false,
        )
        val member = DetailActions.moreFor(
            isGroup = true, isSystemPeer = false, iAmOwner = false,
            peerBlocked = false, peerIsFriend = false,
        )
        assertTrue(owner.contains(DetailMoreAction.DissolveGroup))
        assertFalse(member.contains(DetailMoreAction.DissolveGroup))
    }

    @Test
    fun `已拉黑给取消拉黑，且它不是红色项`() {
        val blocked = DetailActions.moreFor(
            isGroup = false, isSystemPeer = false, iAmOwner = false,
            peerBlocked = true, peerIsFriend = true,
        )
        assertTrue(blocked.contains(DetailMoreAction.Unblock))
        assertFalse(blocked.contains(DetailMoreAction.Block))
        // 取消拉黑是在**撤销**一个破坏性动作，标红会把"解除"读成"再来一次"
        assertFalse(DetailActions.destructive(DetailMoreAction.Unblock))
        assertTrue(DetailActions.destructive(DetailMoreAction.Block))
    }

    @Test
    fun `非好友的更多里没有删除好友`() {
        val stranger = DetailActions.moreFor(
            isGroup = false, isSystemPeer = false, iAmOwner = false,
            peerBlocked = false, peerIsFriend = false,
        )
        assertFalse(stranger.contains(DetailMoreAction.RemoveFriend))
    }

    @Test
    fun `系统通知会话的更多只留清空聊天记录`() {
        val sys = DetailActions.moreFor(
            isGroup = false, isSystemPeer = true, iAmOwner = false,
            peerBlocked = false, peerIsFriend = true,
        )
        assertEquals(listOf(DetailMoreAction.ClearHistory), sys)
    }

    @Test
    fun `系统账号 uid 判定`() {
        assertTrue(DetailActions.isSystemPeer("777000"))
        assertFalse(DetailActions.isSystemPeer("7770001"))
        assertFalse(DetailActions.isSystemPeer(""))
    }

    @Test
    fun `破坏性项排在末位`() {
        val full = DetailActions.moreFor(
            isGroup = false, isSystemPeer = false, iAmOwner = false,
            peerBlocked = false, peerIsFriend = true,
        )
        assertEquals(DetailMoreAction.RemoveFriend, full.last())
    }

    @Test
    fun `群聊默认不出通话入口，接入后只出一个群通话，在搜索前面`() {
        val off = DetailActions.pillsFor(
            isGroup = true, isSystemPeer = false, peerIsFriend = false, showsMessagePill = false,
        )
        assertEquals(listOf(DetailAction.Search, DetailAction.More), off)
        val on = DetailActions.pillsFor(
            isGroup = true, isSystemPeer = false, peerIsFriend = false, showsMessagePill = false,
            groupCallEnabled = true,
        )
        assertEquals(listOf(DetailAction.GroupCall, DetailAction.Search, DetailAction.More), on)
    }
}
