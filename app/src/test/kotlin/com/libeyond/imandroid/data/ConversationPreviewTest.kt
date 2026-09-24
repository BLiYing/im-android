package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话列表副标题（[ConversationPreview.of]）：群聊"昵称: "前缀 + 撤回态现算。
 *
 * 2026-09-22 用户报：安卓群聊列表没有"昵称: "前缀，与 iOS `lastPreviewTextForSelfUID:` +
 * 列表 cell、Web `convPreview()` 都不对齐。这组测试钉住修复后的行为，
 * 也钉住"系统消息不加前缀""撤回态覆盖正文"这两条容易漏的分支。
 */
class ConversationPreviewTest {

    private val base = ConversationEntity(
        ownerUid = "me", convId = "c1", isGroup = true,
        lastContent = "在吗", lastContentType = ContentType.TEXT,
        lastFrom = "u9", lastFromNickname = "老王", lastConvSeq = 5,
    )

    @Test
    fun `群聊别人发的文本带昵称前缀`() {
        assertEquals("老王: 在吗", ConversationPreview.of(base, "me") { null })
    }

    @Test
    fun `本机备注优先于服务端昵称快照`() {
        assertEquals("大王: 在吗", ConversationPreview.of(base, "me") { uid -> if (uid == "u9") "大王" else null })
    }

    @Test
    fun `自己发的显示我`() {
        val mine = base.copy(lastFrom = "me")
        assertEquals("我: 在吗", ConversationPreview.of(mine, "me") { null })
    }

    @Test
    fun `单聊不加前缀`() {
        val p2p = base.copy(isGroup = false)
        assertEquals("在吗", ConversationPreview.of(p2p, "me") { null })
    }

    @Test
    fun `系统消息不加前缀`() {
        val sys = base.copy(lastContentType = ContentType.SYSTEM, lastContent = "老王加入了群聊")
        assertEquals("老王加入了群聊", ConversationPreview.of(sys, "me") { null })
    }

    @Test
    fun `lastFrom 为空时不加前缀——同 iOS「who 解析不出来就不包前缀」`() {
        val noSender = base.copy(lastFrom = "")
        assertEquals("在吗", ConversationPreview.of(noSender, "me") { null })
    }

    @Test
    fun `自己撤回显你撤回了一条消息`() {
        val recalled = base.copy(lastFrom = "me", lastRecalled = true)
        assertEquals("你撤回了一条消息", ConversationPreview.of(recalled, "me") { null })
    }

    @Test
    fun `群里别人撤回带名字`() {
        val recalled = base.copy(lastRecalled = true)
        assertEquals("老王撤回了一条消息", ConversationPreview.of(recalled, "me") { null })
    }

    @Test
    fun `单聊对方撤回`() {
        val recalled = base.copy(isGroup = false, lastRecalled = true)
        assertEquals("对方撤回了一条消息", ConversationPreview.of(recalled, "me") { null })
    }

    @Test
    fun `没有消息时显示无消息占位`() {
        val empty = base.copy(lastContent = "", lastFrom = "")
        assertEquals("（无消息）", ConversationPreview.of(empty, "me") { null })
    }
}
