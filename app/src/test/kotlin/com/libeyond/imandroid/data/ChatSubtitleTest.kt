package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.ws.ConnState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 聊天页副标题 / 标题的选择规则，逐条对齐 iOS `im_navigationSubtitle` / `updateTitle`。 */
class ChatSubtitleTest {

    private fun group(
        typing: String? = null,
        conn: ConnState = ConnState.Connected,
        count: Int = 0,
        loaded: Int = 0,
        isSuper: Boolean = false,
    ) = ChatSubtitle.resolve(true, typing, conn, "", count, loaded, isSuper)

    private fun single(
        typing: String? = null,
        conn: ConnState = ConnState.Connected,
        presence: String = "在线",
    ) = ChatSubtitle.resolve(false, typing, conn, presence, 0, 0, false)

    @Test fun `群聊常态显示成员数`() =
        assertEquals(ChatSubtitleSpec.Members(5, false), group(count = 5))

    @Test fun `超级群人数优先取 memberCount 而不是已加载成员数`() =
        assertEquals(ChatSubtitleSpec.Members(20000, true), group(count = 20000, loaded = 1, isSuper = true))

    @Test fun `memberCount 缺失时回退已加载成员数`() =
        assertEquals(ChatSubtitleSpec.Members(3, false), group(count = 0, loaded = 3))

    @Test fun `超级群人数未知只显示大群`() =
        assertEquals(ChatSubtitleSpec.SuperOnly, group(isSuper = true))

    @Test fun `普通群人数未知不显示副标题`() =
        assertEquals(ChatSubtitleSpec.None, group())

    @Test fun `群里有人输入显示带名字的输入态且优先于成员数`() =
        assertEquals(ChatSubtitleSpec.TypingNamed("u2"), group(typing = "u2", count = 5))

    @Test fun `单聊输入态不带名字`() =
        assertEquals(ChatSubtitleSpec.Typing, single(typing = "u2"))

    @Test fun `输入态优先于连接状态`() =
        assertEquals(ChatSubtitleSpec.TypingNamed("u2"), group(typing = "u2", conn = ConnState.Idle))

    @Test fun `连接中覆盖群成员数`() =
        assertEquals(ChatSubtitleSpec.Connecting, group(conn = ConnState.Connecting, count = 5))

    @Test fun `未连接覆盖群成员数`() =
        assertEquals(ChatSubtitleSpec.Disconnected, group(conn = ConnState.Idle, count = 5))

    @Test fun `连接态覆盖单聊在线态`() {
        assertEquals(ChatSubtitleSpec.Connecting, single(conn = ConnState.Connecting))
        assertEquals(ChatSubtitleSpec.Disconnected, single(conn = ConnState.Idle))
    }

    @Test fun `单聊已连接显示在线态文案`() =
        assertEquals(ChatSubtitleSpec.PeerPresence("在线"), single())

    @Test fun `单聊在线态为空不显示副标题`() =
        assertEquals(ChatSubtitleSpec.None, single(presence = ""))

    // —— 标题（对齐 iOS updateTitle；快照标题已含进页时的备注，页面开着时不会跟着变）——

    @Test fun `群备注非空白用备注`() =
        assertEquals("项目组", ChatSubtitle.title("快照名", true, "项目组", "真实群名"))

    @Test fun `备注被清除回退真实群名而不是含旧备注的快照`() {
        assertEquals("真实群名", ChatSubtitle.title("旧备注", true, "", "真实群名"))
        assertEquals("真实群名", ChatSubtitle.title("旧备注", true, "  ", "真实群名"))
    }

    @Test fun `备注被清除但群资料没拉回时才回退快照`() {
        assertEquals("快照名", ChatSubtitle.title("快照名", true, "", null))
        assertEquals("快照名", ChatSubtitle.title("快照名", true, "", " "))
    }

    @Test fun `备注还没拉回或拉取失败时用快照`() =
        assertEquals("快照名", ChatSubtitle.title("快照名", true, null, "真实群名"))

    @Test fun `单聊不使用会话备注`() =
        assertEquals("老王", ChatSubtitle.title("老王", false, "不该出现", "不该出现"))
}
