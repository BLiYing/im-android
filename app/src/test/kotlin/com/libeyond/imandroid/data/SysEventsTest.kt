package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.SysSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统消息结构化事件（P3 i18n，PROTOCOL §6.6）按 App 语言渲染：[SysEvents]。
 * 对齐 iOS `IMSysEventFormatterTests`、Web `sysEventRender.test.ts` 的覆盖面。
 */
class SysEventsTest {

    @After
    fun restoreChinese() = Str.install(XmlStringResolver())

    private fun english() = Str.install(XmlStringResolver("en"))

    private fun segs(vararg s: Pair<String, String>) =
        s.joinToString(",", "[", "]") { (uid, text) -> """{"uid":"$uid","text":"$text"}""" }

    private val removeSegs = segs("1001" to "张三", "" to " 将 ", "1002" to "李四", "" to " 移出群聊")

    @Test
    fun `人名段保留 uid 与服务端文本，固定文案换成本语言`() {
        english()
        val out = SysEvents.groupSegments("member_remove", null, removeSegs)!!
        assertEquals(
            listOf(
                SysSegment("1001", "张三"), SysSegment(text = " removed "),
                SysSegment("1002", "李四"), SysSegment(text = " from the group"),
            ),
            out,
        )
    }

    @Test
    fun `中文下与服务端原句一致`() {
        assertEquals("张三 将 李四 移出群聊", SysEvents.groupText("member_remove", null, removeSegs))
    }

    @Test
    fun `邀请多人逐个出段且各自可点，分隔符随语言`() {
        val json = segs("1" to "甲", "" to " 邀请 ", "2" to "乙", "" to "、", "3" to "丙", "" to " 加入群聊")
        assertEquals("甲 邀请 乙、丙 加入群聊", SysEvents.groupText("member_invite", null, json))
        english()
        val out = SysEvents.groupSegments("member_invite", null, json)!!
        assertEquals("甲 invited 乙, 丙 to the group", out.joinToString("") { it.text })
        assertEquals(listOf("1", "2", "3"), out.filter(SysSegments::isName).map { it.uid })
    }

    @Test
    fun `非人名参数取 sys_args`() {
        val args = SysEvents.encodeArgs(mapOf("name" to "周末爬山"))
        assertEquals("群名已改为「周末爬山」", SysEvents.groupText("group_rename", args, null))
        english()
        assertEquals(
            "张三 created the group \"周末爬山\"",
            SysEvents.groupText("group_create", args, segs("9" to "张三", "" to " 创建了群聊「周末爬山」")),
        )
    }

    @Test
    fun `事件为空或不认识返回 null，调用方回退 content`() {
        assertNull(SysEvents.groupSegments(null, null, removeSegs))
        assertNull(SysEvents.groupSegments("", null, removeSegs))
        assertNull(SysEvents.groupSegments("future_event", null, removeSegs))
        assertNull(SysEvents.noticeText("member_join", null))
    }

    @Test
    fun `人名段不足或参数坏数据不崩，缺的占位符留空`() {
        assertEquals(" 将  移出群聊", SysEvents.groupText("member_remove", "{坏", null))
        assertEquals("群名已改为「」", SysEvents.groupText("group_rename", null, null))
    }

    @Test
    fun `新设备登录异地通知拼多行`() {
        english()
        val args = SysEvents.encodeArgs(
            mapOf(
                "at" to "not-a-time", "device" to "Pixel 8", "platform" to "Android",
                "ip" to "1.2.3.4", "unusual" to "1", "province" to "广东", "familiars" to "北京, 上海",
            )
        )
        assertEquals(
            listOf(
                "⚠ Your account signed in from [广东] at not-a-time:",
                "· Pixel 8 · Android",
                "· IP 1.2.3.4",
                "· Usual locations: 北京, 上海",
                "If this wasn't you, go to Settings → Logged-in Devices to sign out this device and change your password.",
            ).joinToString("\n"),
            SysEvents.noticeText("new_device_login", args),
        )
    }

    @Test
    fun `被踢下线缺目标设备用兜底名，改密不带设备行`() {
        val kicked = SysEvents.noticeText("device_kicked", SysEvents.encodeArgs(mapOf("at" to "x")))!!
        assertEquals("「某台设备」已被其他设备下线。", kicked.lines().first())
        assertEquals("· 时间：x", kicked.lines()[1])
        val pwd = SysEvents.noticeText("password_changed", SysEvents.encodeArgs(mapOf("at" to "x")))!!
        assertEquals(2, pwd.lines().size)
    }

    @Test
    fun `RFC3339 时间按本地日期格式化`() {
        val text = SysEvents.noticeText("password_changed", SysEvents.encodeArgs(mapOf("at" to "2026-09-27T08:30:00Z")))!!
        // 时区随机器，只钉年份与"已格式化"（不再是原串）
        assertEquals(true, text.contains("2026年") && !text.contains("T08:30"))
    }

    @Test
    fun `气泡正文只对系统通知账号生效`() {
        val args = SysEvents.encodeArgs(mapOf("at" to "x"))
        val notice = MessageEntity(ownerUid = "me", convId = "c", convSeq = 1, sender = DetailActions.SYSTEM_UID, sysEvent = "password_changed", sysArgs = args)
        assertEquals(true, SysEvents.noticeTextOf(notice)!!.startsWith("你的账号密码已于 x 修改成功。"))
        assertNull(SysEvents.noticeTextOf(notice.copy(sender = "u9")))
    }

    @Test
    fun `下行字段落库`() {
        val row = MessageData(
            convId = "g_1", convSeq = 3, contentType = ContentType.SYSTEM,
            sysEvent = "group_rename", sysArgs = mapOf("name" to "新名"),
        ).toEntity("me")
        assertEquals("group_rename", row.sysEvent)
        assertEquals(mapOf("name" to "新名"), SysEvents.parseArgs(row.sysArgs))
        val bare = MessageData(convId = "g_1", convSeq = 4, sysEvent = "", sysArgs = emptyMap()).toEntity("me")
        assertNull(bare.sysEvent)
        assertNull(bare.sysArgs)
    }

    @Test
    fun `会话列表预览按当前语言现算`() {
        val group = ConversationEntity(
            ownerUid = "me", convId = "g_1", isGroup = true,
            lastContent = "张三 将 李四 移出群聊", lastContentType = ContentType.SYSTEM,
            lastSysEvent = "member_remove", lastSysSegments = removeSegs,
        )
        val notice = ConversationEntity(
            ownerUid = "me", convId = "c2", lastContent = "你的账号密码已于 …", lastFrom = DetailActions.SYSTEM_UID,
            lastSysEvent = "password_changed", lastSysArgs = SysEvents.encodeArgs(mapOf("at" to "x"))!!,
        )
        english()
        assertEquals("张三 removed 李四 from the group", ConversationPreview.of(group, "me") { null })
        assertEquals(true, ConversationPreview.of(notice, "me") { null }.startsWith("Your account password was changed"))
        // 不认识的事件回退烤好的中文
        assertEquals("张三 将 李四 移出群聊", ConversationPreview.of(group.copy(lastSysEvent = "future"), "me") { null })
    }
}
