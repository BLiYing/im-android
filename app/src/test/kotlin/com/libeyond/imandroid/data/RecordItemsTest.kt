package com.libeyond.imandroid.data

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聊天记录详情页的逐条解析、连续同人键、时间文案（十七条对齐 #17，对齐 iOS `IMChatRecordViewController`）。 */
class RecordItemsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `逐条解析出全部字段`() {
        val doc = CardContent.parseRecordDoc(
            """{"t":"群聊的聊天记录","items":[
              {"n":"小明","ct":"file","c":"/uploads/a.pdf","fn":"a.pdf","fs":2048,"u":"u1","a":"/avatars/x.jpg","ts":1700000000000},
              {"n":"小红","ct":"voice","c":"/uploads/v.m4a","d":3200.0,"w":"AAEC"}
            ]}""",
        )!!
        assertEquals("群聊的聊天记录", doc.title)
        val f = doc.items[0]
        assertEquals(CardContent.RecordItem("小明", "file", "/uploads/a.pdf", "a.pdf", 2048, "", "u1", "/avatars/x.jpg", 1700000000000), f)
        assertEquals("数字写成小数也认", 3200L, doc.items[1].durationMs)
        assertEquals("AAEC", doc.items[1].waveform)
    }

    @Test
    fun `老记录缺字段照样出行，类型缺省为文本，标题缺省为「聊天记录」`() {
        val doc = CardContent.parseRecordDoc("""{"items":[{"c":"hi"}]}""")!!
        assertEquals("聊天记录", doc.title)
        assertEquals(CardContent.RecordItem("", "text", "hi"), doc.items.single())
    }

    @Test
    fun `不是对象返回 null，不是记录不下钻`() {
        assertNull(CardContent.parseRecordDoc("not json"))
        assertFalse(CardContent.looksLikeRecord(""))
        assertFalse(CardContent.looksLikeRecord("[1,2]"))
        assertTrue(CardContent.looksLikeRecord("""{"t":"x","items":[]}"""))
    }

    @Test
    fun `连续同人键：有 uid 认 uid，没有退到名字`() {
        assertEquals("u:u1", CardContent.RecordItem("小明", "text", "", uid = "u1").senderKey)
        assertEquals("n:小明", CardContent.RecordItem("小明", "text", "").senderKey)
        // 同名不同人不能连成一段
        assertFalse(
            CardContent.RecordItem("小明", "text", "", uid = "u1").senderKey ==
                CardContent.RecordItem("小明", "text", "", uid = "u2").senderKey,
        )
    }

    @Test
    fun `时间：没有为空，今天只显时分，跨天带月日`() {
        val now = 1700000000000L                         // 2023-11-14 22:13:20 UTC
        assertEquals("", CardContent.recordItemTime(0, now, utc))
        assertEquals("21:00", CardContent.recordItemTime(now - 4_400_000L, now, utc))
        assertEquals("11月13日 22:13", CardContent.recordItemTime(now - 86_400_000L, now, utc))
    }
}
