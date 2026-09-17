package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 消息长按菜单矩阵（CHAT_UX §13/§14）。条件互相牵扯，散在 UI 里必然漂移。 */
class MessageActionsTest {

    private val NOW = 1_700_000_000_000L
    private val ME = "1001"
    private val OTHER = "1002"

    private fun msg(
        sender: String = ME,
        type: String = ContentType.TEXT,
        content: String = "hi",
        ts: Long = NOW,
        seq: Long = 10,
        recalledAt: Long? = null,
    ) = MessageEntity(
        ownerUid = ME, convId = "u_1001_u_1002", convSeq = seq,
        sender = sender, contentType = type, content = content,
        timestamp = ts, recalledAt = recalledAt,
    )

    private fun actions(m: MessageEntity, isGroup: Boolean = false, manager: Boolean = false) =
        MessageActions.availableFor(m, ME, isGroup, manager, NOW)

    /** 撤回墓碑上什么都不给——正文已被服务端脱敏，复制/引用都没意义。 */
    @Test
    fun `撤回的消息没有任何菜单项`() {
        assertTrue(actions(msg(recalledAt = NOW - 1000)).isEmpty())
    }

    /** 待确认消息（没有 conv_seq）不能做任何服务端操作。 */
    @Test
    fun `未确认消息没有菜单项`() {
        assertTrue(actions(msg(seq = 0)).isEmpty())
    }

    /**
     * 复制**按矩阵给**（2026-09-16 起，对齐 iOS `copyMessageToPasteboard:`）。
     *
     * 这条原先钉的是「复制只给文本」，理由写的是"给图片一个复制却什么都没进剪贴板，比没有更糟"
     * ——那在**没有复制图片能力**的时候是对的；2026-09-16 补了图片复制之后，这个前提就不成立了，
     * 于是放开（用户报的对齐项）。**复制什么**（图说压过一切 / 图 / 链接 / 文本）归
     * [copyKindOf]，逐档在 `MessageCopyKindTest` 里钉着，本条只管"给不给这一项"。
     */
    @Test
    fun `复制按矩阵给`() {
        assertTrue(MessageAction.Copy in actions(msg()))
        assertTrue(MessageAction.Copy in actions(msg(type = ContentType.IMAGE, content = "/uploads/x.jpg")))
        assertTrue(MessageAction.Copy in actions(msg(type = ContentType.VIDEO, content = "/uploads/x.mp4")))
        assertTrue(MessageAction.Copy in actions(msg(type = ContentType.FILE, content = "/uploads/x.pdf")))
        // 语音刻意不给：iOS 那支走兜底分支复制 `message.content`，也就是一段相对路径，对用户没意义
        assertFalse(MessageAction.Copy in actions(msg(type = ContentType.VOICE, content = "/uploads/x.m4a")))
        assertFalse(MessageAction.Copy in actions(msg(content = "")))
    }

    /** 撤回仅本人、且在 2 分钟窗内（服务端超窗回 300008，端上先挡一道）。 */
    /**
     * 长按「收藏」（2026-09-17 用户报：本端长按菜单没有这一项）。**逐类对齐 iOS `messageActionsForMessage:`**：
     * `convSeq > 0 && content 非空 && 未撤回 && 非 system`——文本/图片/视频/文件/语音/名片/聊天记录都给，
     * 与多选底栏「收藏」同一份判据（`SelectionActions.favoritable`）。
     */
    @Test
    fun `收藏对已发出的各类内容都给，排在转发之后`() {
        val kinds = listOf(
            ContentType.TEXT to "hi",
            ContentType.IMAGE to "/uploads/x.jpg",
            ContentType.VIDEO to "/uploads/x.mp4",
            ContentType.FILE to "/uploads/x.pdf",
            ContentType.VOICE to "/uploads/x.m4a",
            ContentType.CONTACT to """{"u":"1003","n":"小王"}""",
            ContentType.CHAT_RECORD to """{"t":"聊天记录","items":[]}""",
        )
        for ((type, content) in kinds) {
            for (sender in listOf(ME, OTHER)) {
                val a = actions(msg(sender = sender, type = type, content = content))
                assertTrue("$type/$sender 应能收藏", MessageAction.Favorite in a)
                assertEquals("$type：收藏紧跟在转发之后（iOS 顺序）", a.indexOf(MessageAction.Forward) + 1, a.indexOf(MessageAction.Favorite))
            }
        }
    }

    @Test
    fun `系统消息与空内容不给收藏`() {
        assertFalse(MessageAction.Favorite in actions(msg(type = ContentType.SYSTEM, content = "xx 加入了群聊")))
        // 空内容收藏下来是一条点不开的空快照
        assertFalse(MessageAction.Favorite in actions(msg(content = "")))
    }

    @Test
    fun `撤回仅本人且在时间窗内`() {
        assertTrue(MessageAction.Recall in actions(msg(ts = NOW - 60_000)))
        assertFalse("超窗", MessageAction.Recall in actions(msg(ts = NOW - 200_000)))
        assertFalse("别人的消息", MessageAction.Recall in actions(msg(sender = OTHER)))
    }

    /** 为所有人删除：本人恒可（**无时间窗**，区别于 recall）。 */
    @Test
    fun `为所有人删除本人无时间窗`() {
        assertTrue(MessageAction.DeleteForEveryone in actions(msg(ts = NOW - 86_400_000)))
    }

    /** 群主/管理员可删他人；普通成员不行。 */
    @Test
    fun `群管理员可删他人`() {
        val other = msg(sender = OTHER)
        assertTrue(MessageAction.DeleteForEveryone in actions(other, isGroup = true, manager = true))
        assertFalse(MessageAction.DeleteForEveryone in actions(other, isGroup = true, manager = false))
        // 单聊里即使 manager=true 也不能删对方的
        assertFalse(MessageAction.DeleteForEveryone in actions(other, isGroup = false, manager = true))
    }

    /** 仅删除自己：任何消息都可以。 */
    @Test
    fun `仅删除自己恒可用`() {
        assertTrue(MessageAction.HideForMe in actions(msg(sender = OTHER)))
        assertTrue(MessageAction.HideForMe in actions(msg(type = ContentType.SYSTEM)))
    }

    /** 系统消息不可引用——它没有发送者，引用条显示不出来源。 */
    @Test
    fun `系统消息不可引用`() {
        assertFalse(MessageAction.Reply in actions(msg(type = ContentType.SYSTEM)))
        assertTrue(MessageAction.Reply in actions(msg()))
    }
}

/** 会话长按菜单。 */
class ConversationActionsTest {

    @Test
    fun `置顶与取消置顶互斥`() {
        val notPinned = ConversationActions.availableFor(0, false, false, 0)
        assertTrue(ConversationAction.Pin in notPinned)
        assertFalse(ConversationAction.Unpin in notPinned)

        val pinned = ConversationActions.availableFor(123, false, false, 0)
        assertTrue(ConversationAction.Unpin in pinned)
        assertFalse(ConversationAction.Pin in pinned)
    }

    /** 有未读给「标为已读」，没未读给「标为未读」——反过来等于给一个无操作项。 */
    @Test
    fun `标记项随未读状态切换`() {
        assertTrue(ConversationAction.MarkRead in ConversationActions.availableFor(0, false, false, 5))
        assertTrue(ConversationAction.MarkUnread in ConversationActions.availableFor(0, false, false, 0))
        // 手动标未读时也该给「标为已读」
        assertTrue(ConversationAction.MarkRead in ConversationActions.availableFor(0, false, true, 0))
    }

    @Test
    fun `删除恒在且为危险项`() {
        val a = ConversationActions.availableFor(0, false, false, 0)
        assertTrue(ConversationAction.Delete in a)
        assertTrue(ConversationAction.Delete.destructive)
        // destructive 项应排最后（避免误点）
        assertEquals(ConversationAction.Delete, a.last())
    }
}
