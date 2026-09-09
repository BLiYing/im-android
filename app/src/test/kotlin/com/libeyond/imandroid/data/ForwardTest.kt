package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转发溯源与多选上限（M4-3，IMServer PROTOCOL §4.3）。
 *
 * 这组测试钉的两条纪律都**出过线上事故**（在另外两个端上），所以逐条写死：
 * 公开名不可带备注 / 不可写死「我」；转发链保留最初作者。
 */
class ForwardTest {

    private fun msg(
        sender: String = "u_other",
        nick: String? = "张三",
        fwd: String? = null,
        seq: Long = 10,
        type: String = ContentType.TEXT,
        recalled: Long? = null,
        content: String = "hi",
    ) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = seq, sender = sender,
        fromNickname = nick, forwardFrom = fwd, contentType = type,
        content = content, recalledAt = recalled,
    )

    // ——— 溯源名 ———

    @Test
    fun `转发别人的消息，溯源名取发送者昵称`() {
        assertEquals("张三", Forward.originOf(msg(), myUid = "me", myPublicName = "@myhandle"))
    }

    @Test
    fun `转发链保留最初作者——转三手仍写最初那个人`() {
        // 一条已带 forwardFrom 的消息再被转发：写的是最初作者，不是把它转给我的中间人。
        val relayed = msg(sender = "u_middle", nick = "中间人", fwd = "最初作者")
        assertEquals("最初作者", Forward.originOf(relayed, "me", "@myhandle"))
    }

    @Test
    fun `转发自己的消息，取我的公开名而不是「我」`() {
        // 「我」是**看的人**才成立的称呼，而这串字会烧进发出去的内容。
        // im-web 2026-09-05 实测：收件人打开合并转发卡片，看到一排「我」。
        val mine = msg(sender = "me", nick = "我的昵称")
        val origin = Forward.originOf(mine, myUid = "me", myPublicName = "@myhandle")
        assertEquals("@myhandle", origin)
        assertFalse("溯源名不得是「我」", origin == "我")
    }

    @Test
    fun `昵称缺失时末级落 uid——有总比空强`() {
        assertEquals("u_other", Forward.originOf(msg(nick = null), "me", "@myhandle"))
        assertEquals("u_other", Forward.originOf(msg(nick = "  "), "me", "@myhandle"))
    }

    @Test
    fun `溯源名按服务端上限 40 截断，不等服务端拒`() {
        val long = "名".repeat(80)
        assertEquals(Forward.MAX_LEN, Forward.originOf(msg(nick = long), "me", "@m").length)
    }

    // ——— 可转发判据 ———

    @Test
    fun `撤回、系统消息、未确认的消息都不可转发`() {
        assertFalse("撤回墓碑", Forward.canForward(msg(recalled = 1L)))
        assertFalse("系统消息", Forward.canForward(msg(type = ContentType.SYSTEM)))
        // convSeq<=0 = 服务端还不存在这条，转出去的是幻影
        assertFalse("未确认", Forward.canForward(msg(seq = 0)))
        assertFalse("空正文", Forward.canForward(msg(content = "")))
        assertTrue("正常消息可转发", Forward.canForward(msg()))
    }

    // ——— 上限 ———

    @Test
    fun `消息多选上限 100 与 ChatSelection 同源`() {
        // 勾选的写入口搬到了 ChatSelection.toggle（按 conv_seq 记且连消息一起存，
        // 理由见那里）；这里只钉住"两处不许各写一个 100"
        assertEquals(100, Forward.MAX_SELECTION)
        assertEquals(Forward.MAX_SELECTION, ChatSelection.MAX)
    }

    @Test
    fun `转发目标上限 9，与 iOS Web 同值`() {
        val full = (1..9).map { "c$it" }.toSet()
        assertEquals(9, Forward.MAX_TARGETS)
        assertNull(Forward.toggleTarget(full, "c10"))
        assertEquals(8, Forward.toggleTarget(full, "c3")?.size)
        assertEquals(1, Forward.toggleTarget(emptySet(), "c1")?.size)
    }
}
