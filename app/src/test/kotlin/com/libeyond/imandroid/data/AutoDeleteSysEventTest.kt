package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话系统消息 `sys_event=auto_delete`（PROTOCOL §6.6 / AUTO_DELETE_DESIGN §7）：按 `sys_args.sec` 选 on/off 模板。 */
class AutoDeleteSysEventTest {
    @After
    fun restoreChinese() = Str.install(XmlStringResolver())

    private val segs = """[{"uid":"1001","text":"张三"},{"text":" 开启了自动删除，消息将在1 天后自动删除"}]"""
    private fun args(sec: String) = SysEvents.encodeArgs(mapOf("sec" to sec))

    @Test
    fun `开启 按 sec 折算时长`() {
        assertEquals("张三 开启了自动删除，消息将在1 天后自动删除", SysEvents.groupText("auto_delete", args("86400"), segs))
        assertEquals("张三 开启了自动删除，消息将在1 周后自动删除", SysEvents.groupText("auto_delete", args("604800"), segs))
        assertEquals("张三 开启了自动删除，消息将在2 分钟后自动删除", SysEvents.groupText("auto_delete", args("120"), segs))
        assertEquals("张三 开启了自动删除，消息将在1 个月后自动删除", SysEvents.groupText("auto_delete", args("2592000"), segs))
    }

    @Test
    fun `关闭 sec 为 0`() {
        assertEquals("张三 关闭了自动删除", SysEvents.groupText("auto_delete", args("0"), segs))
    }

    @Test
    fun `人名段仍可点 保留 uid`() {
        val out = SysEvents.groupSegments("auto_delete", args("86400"), segs)!!
        assertEquals(listOf("1001"), out.filter(SysSegments::isName).map { it.uid })
    }

    @Test
    fun `英文模板`() {
        Str.install(XmlStringResolver("en"))
        assertEquals("张三 turned on auto-delete. Messages will be deleted 3 days after they are sent", SysEvents.groupText("auto_delete", args("259200"), segs))
        assertEquals("张三 turned off auto-delete", SysEvents.groupText("auto_delete", args("0"), segs))
    }

    @Test
    fun `缺字段或坏数据回退 content`() {
        assertNull(SysEvents.groupSegments("auto_delete", null, segs))
        assertNull(SysEvents.groupSegments("auto_delete", args(""), segs))
        assertNull(SysEvents.groupSegments("auto_delete", args("abc"), segs))
        assertNull(SysEvents.groupSegments("auto_delete", args("-5"), segs))
        assertNull(SysEvents.groupSegments("auto_delete", args("86400"), """[{"text":"无人名"}]"""))
        assertNull(SysEvents.groupSegments("auto_delete", args("86400"), null))
    }

    @Test
    fun `会话列表副标题同源 系统消息不加昵称前缀`() {
        val conv = ConversationEntity(
            ownerUid = "me", convId = "g1", isGroup = true, lastContent = "张三 开启了自动删除，消息将在1 天后自动删除",
            lastContentType = ContentType.SYSTEM, lastSysEvent = "auto_delete", lastSysArgs = args("86400").orEmpty(), lastSysSegments = segs,
        )
        assertEquals("张三 开启了自动删除，消息将在1 天后自动删除", ConversationPreview.of(conv, "me") { null })
        Str.install(XmlStringResolver("en"))
        assertEquals("张三 turned on auto-delete. Messages will be deleted 1 day after they are sent", ConversationPreview.of(conv, "me") { null })
    }
}
