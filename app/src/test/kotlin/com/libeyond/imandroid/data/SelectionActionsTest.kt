package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选底栏四个动作的判据与合并转发编码（2026-09-10 用户报 #3）。
 * 对端 iOS `IMChatViewController+Selection.m`、Web `messageContent.ts`。
 *
 * 隐私那几条排在最前：它们破了界面上完全看不出来，只有收件人才看得到。
 */
class SelectionActionsTest {

    private fun msg(
        seq: Long,
        sender: String = "peer",
        type: String = ContentType.TEXT,
        content: String = "hi",
        fromNickname: String? = null,
        groupId: String? = null,
        recalled: Long? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        duration: Int? = null,
        waveform: String? = null,
        caption: String? = null,
        timestamp: Long = seq * 1000,
    ) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = seq, sender = sender, fromNickname = fromNickname,
        contentType = type, content = content, timestamp = timestamp, groupId = groupId,
        recalledAt = recalled, fileName = fileName, fileSize = fileSize, duration = duration,
        waveform = waveform, caption = caption,
    )

    private fun items(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject["items"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    // ————————————————— 合并转发：隐私 —————————————————

    @Test
    fun `条目 u 是卡片内匿名序号 不是真 uid`() {
        val kept = listOf(msg(1, sender = "7741990777"), msg(2, sender = "me"), msg(3, sender = "7741990777"))
        val json = SelectionActions.encodeRecord(
            "t", kept, SelectionActions.RecordSenders(myUid = "me", myName = "@alice", isGroup = true),
        )
        assertEquals(listOf("s1", "s2", "s1"), items(json).map { it.s("u") })
        assertFalse("真 uid 不能出现在发出去的字节里", json.contains("7741990777"))
    }

    @Test
    fun `我发的条目写我的公开名 不写我`() {
        val json = SelectionActions.encodeRecord(
            "t", listOf(msg(1, sender = "me")),
            SelectionActions.RecordSenders(myUid = "me", myName = "@alice", isGroup = false, peerPublic = "Bob"),
        )
        assertEquals("@alice", items(json)[0].s("n"))
        assertFalse(json.contains("\"我\""))
    }

    @Test
    fun `单聊对方公开名 昵称优先 绝不回落 uid`() {
        assertEquals("Bob", SelectionActions.peerPublicName("Bob", "bob", "旧昵称"))
        assertEquals("@bob", SelectionActions.peerPublicName("", "bob", "旧昵称"))
        assertEquals("旧昵称", SelectionActions.peerPublicName(null, " ", "旧昵称"))
        assertEquals("拿不到任何公开名就给空串，让标题自己降级", "", SelectionActions.peerPublicName(null, null, null))
    }

    @Test
    fun `群聊条目名取成员公开名 取不到回落消息上的昵称快照再回落未命名`() {
        val s = SelectionActions.RecordSenders(
            myUid = "me", myName = "@alice", isGroup = true, memberNames = mapOf("u1" to "群昵称"),
        )
        assertEquals("群昵称", s.nameOf(msg(1, sender = "u1", fromNickname = "快照")))
        assertEquals("快照", s.nameOf(msg(2, sender = "u2", fromNickname = "快照")))
        assertEquals(DisplayName.UNNAMED, s.nameOf(msg(3, sender = "u3")))
    }

    // ————————————————— 合并转发：标题与字段 —————————————————

    @Test
    fun `标题 群聊固定文案 单聊双方公开名 逐字对齐 iOS 与 Web`() {
        assertEquals("群聊的聊天记录", SelectionActions.chatRecordTitle(true, "真实群名", "@alice"))
        assertEquals("Bob和@alice的聊天记录", SelectionActions.chatRecordTitle(false, " Bob ", "@alice"))
        assertEquals("Bob的聊天记录", SelectionActions.chatRecordTitle(false, "Bob", "  "))
        assertEquals("@alice的聊天记录", SelectionActions.chatRecordTitle(false, null, "@alice"))
        assertEquals("聊天记录", SelectionActions.chatRecordTitle(false, "", null))
    }

    @Test
    fun `空值一律省略 文件带 fn fs 语音带 d w 图说带 cap`() {
        val kept = listOf(
            msg(1, type = ContentType.TEXT, timestamp = 0),
            msg(2, type = ContentType.FILE, content = "/media/f/abc/报告.pdf", fileSize = 2048),
            msg(3, type = ContentType.VOICE, content = "/media/v/1.m4a", duration = 3200, waveform = "AAEC"),
            msg(4, type = ContentType.IMAGE, content = "/media/i/1.jpg", caption = "看这个"),
        )
        val s = SelectionActions.RecordSenders(
            myUid = "me", myName = "@alice", isGroup = false, peerUid = "peer", peerPublic = "Bob",
            peerAvatar = "/avatars/bob.jpg",
        )
        val it = items(SelectionActions.encodeRecord("t", kept, s))
        assertNull("ts=0 不发", it[0].s("ts"))
        assertEquals("/avatars/bob.jpg", it[0].s("a"))
        assertEquals("无文件名从 URL 末段反推", "报告.pdf", it[1].s("fn"))
        assertEquals("2048", it[1].s("fs"))
        assertEquals("3200", it[2].s("d"))
        assertEquals("AAEC", it[2].s("w"))
        assertEquals("看这个", it[3].s("cap"))
        assertNull("非语音不带 d", it[3].s("d"))
        // 与本端读端（CardContent.parseRecordDoc）逐键对得上
        val doc = CardContent.parseRecordDoc(SelectionActions.encodeRecord("Bob和@alice的聊天记录", kept, s))!!
        assertEquals("Bob和@alice的聊天记录", doc.title)
        assertEquals(4, doc.items.size)
        assertEquals("报告.pdf", doc.items[1].fileName)
    }

    @Test
    fun `我自己在单聊里没有头像快照 不发 a`() {
        val s = SelectionActions.RecordSenders(
            myUid = "me", myName = "@alice", isGroup = false, peerUid = "peer", peerAvatar = "/avatars/bob.jpg",
        )
        assertEquals("", s.avatarOf(msg(1, sender = "me")))
        assertEquals("/avatars/bob.jpg", s.avatarOf(msg(2, sender = "peer")))
    }

    @Test
    fun `入卡筛选 剔掉撤回 系统 空内容 未确认 失效媒体`() {
        val expired = msg(6, type = ContentType.IMAGE, content = "/gone.jpg")
        val all = listOf(
            msg(1), msg(2, recalled = 9L), msg(3, type = ContentType.SYSTEM), msg(4, content = " "),
            msg(0), expired, msg(7, type = ContentType.IMAGE, content = "/ok.jpg"),
        )
        val kept = SelectionActions.mergeable(all) { it === expired }
        assertEquals(listOf(1L, 7L), kept.map { it.convSeq })
    }

    @Test
    fun `失效只对图片视频文件成立 视频按视频查`() {
        var askedVideo: Boolean? = null
        val gone = { _: String, v: Boolean -> askedVideo = v; true }
        assertTrue(SelectionActions.isExpiredMedia(msg(1, type = ContentType.VIDEO, content = "/v.mp4"), gone))
        assertEquals(true, askedVideo)
        assertTrue(SelectionActions.isExpiredMedia(msg(2, type = ContentType.FILE, content = "/f.zip"), gone))
        assertFalse(SelectionActions.isExpiredMedia(msg(3, type = ContentType.TEXT), gone))
        assertFalse(SelectionActions.isExpiredMedia(msg(4, type = ContentType.VOICE, content = "/a.m4a"), gone))
    }

    // ————————————————— 举报 —————————————————

    @Test
    fun `举报三条判据 非空 不含我 同一个人`() {
        assertEquals("peer", SelectionActions.reportableSender(listOf(msg(1), msg(2)), "me"))
        assertNull("空", SelectionActions.reportableSender(emptyList(), "me"))
        assertNull("含我自己", SelectionActions.reportableSender(listOf(msg(1), msg(2, sender = "me")), "me"))
        // 上一条混了两个人，靠"同一个人"也能判掉；这条单独钉"不含我"
        assertNull("只勾了我自己的", SelectionActions.reportableSender(listOf(msg(1, sender = "me"), msg(2, sender = "me")), "me"))
        assertNull("跨发送者", SelectionActions.reportableSender(listOf(msg(1), msg(2, sender = "other")), "me"))
        assertNull("未确认的本地件", SelectionActions.reportableSender(listOf(msg(1), msg(0)), "me"))
        assertNull("发送者为空", SelectionActions.reportableSender(listOf(msg(1, sender = "")), "me"))
    }

    @Test
    fun `举报灰着被点 说清原因`() {
        assertEquals("不能举报自己的消息",
            SelectionActions.reportBlockedHint(listOf(msg(1), msg(2, sender = "me")), "me"))
        assertEquals("不能举报自己的消息", SelectionActions.reportBlockedHint(listOf(msg(1, sender = "me")), "me"))
        assertEquals("一次只能举报同一个人的消息",
            SelectionActions.reportBlockedHint(listOf(msg(1), msg(2, sender = "other")), "me"))
        assertNull("可举报时不给提示", SelectionActions.reportBlockedHint(listOf(msg(1)), "me"))
        assertNull("什么都没勾时全栏皆灰 不单独解释", SelectionActions.reportBlockedHint(emptyList(), "me"))
    }

    @Test
    fun `举报标题 单条不带名字 多条带本机显示名`() {
        assertEquals("举报这条消息", SelectionActions.reportTitle(1, "老王"))
        assertEquals("举报 老王 的 3 条消息", SelectionActions.reportTitle(3, "老王"))
    }

    // ————————————————— 逐条转发 —————————————————

    @Test
    fun `同一相册选了两张以上才重新分组 且用新 ID`() {
        var n = 0
        val msgs = listOf(
            msg(1, type = ContentType.IMAGE, content = "/1", groupId = "alb-A"),
            msg(2, type = ContentType.VIDEO, content = "/2", groupId = "alb-A"),
            msg(3, type = ContentType.IMAGE, content = "/3", groupId = "alb-B"),   // 只选了一张
            msg(4, type = ContentType.FILE, content = "/4", groupId = "alb-A"),    // 文件不进宫格
            msg(5, type = ContentType.IMAGE, content = "/5", groupId = "alb-C"),
            msg(6, type = ContentType.IMAGE, content = "/6", groupId = "alb-C"),
        )
        val g = SelectionActions.regroupAlbums(msgs) { "new-${++n}" }
        assertEquals(setOf(1L, 2L, 5L, 6L), g.keys)
        assertEquals(g[1], g[2])
        assertEquals(g[5], g[6])
        assertNotEquals("两个原相册不能串成一个", g[1], g[5])
        assertFalse("不能沿用原 ID", g.values.any { it.startsWith("alb-") })
    }

    @Test
    fun `单条转发撞上失效媒体 按类型说清是什么失效了`() {
        assertEquals("该视频已失效，无法转发", SelectionActions.expiredForwardText(ContentType.VIDEO))
        assertEquals("该文件已失效，无法转发", SelectionActions.expiredForwardText(ContentType.FILE))
        assertEquals("该图片已失效，无法转发", SelectionActions.expiredForwardText(ContentType.IMAGE))
    }

    @Test
    fun `转发回执 如实报失效跳过的条数`() {
        assertEquals("已转发", SelectionActions.forwardDoneText(1, 0))
        assertEquals("已转发到 3 个会话（2 条已失效未转发）", SelectionActions.forwardDoneText(3, 2))
    }

    // ————————————————— 收藏 —————————————————

    @Test
    fun `收藏筛选与回执`() {
        val kept = SelectionActions.favoritable(
            listOf(msg(1), msg(2, recalled = 5L), msg(3, type = ContentType.SYSTEM), msg(4, content = ""), msg(0)),
        )
        assertEquals(listOf(1L), kept.map { it.convSeq })
        assertEquals("已收藏", SelectionActions.favoriteSummary(1, 1))
        assertEquals("已收藏 5 条", SelectionActions.favoriteSummary(5, 5))
        assertEquals("已收藏 3/5 条，2 条失败", SelectionActions.favoriteSummary(3, 5))
        assertEquals("收藏失败", SelectionActions.favoriteSummary(0, 5))
    }
}
