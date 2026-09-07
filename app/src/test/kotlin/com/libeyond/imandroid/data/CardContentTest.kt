package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片消息的解析与摘要（名片 / 合并转发）。
 *
 * 这组测试钉两件事：
 * ① **非法内容一律降级，绝不把裸 JSON 铺给用户**——本端此前正是那样，
 *    聊天页里显示 `{"u":"7741990777",…}`（2026-09-07 实体机实测）；
 * ② **摘要口径与 iOS `IMRecordItemPreview` 逐条一致**——三端摘要不一致时，
 *    同一条合并转发在三端显示不同文字，而这种不一致没有任何自动化手段能发现。
 */
class CardContentTest {

    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s) as JsonObject

    // ——— 名片 ———

    @Test
    fun `名片解析出昵称与句柄，副标题恒是 @句柄`() {
        val c = CardContent.parseContact("""{"u":"7741990777","un":"user4996","n":"用户4996"}""")!!
        assertEquals("用户4996", c.displayName)
        assertEquals("@user4996", c.handle)
    }

    @Test
    fun `老名片没有 un —— 副标题留空，绝不退化成显示 uid`() {
        val c = CardContent.parseContact("""{"u":"7741990777","n":"用户4996"}""")!!
        assertEquals("", c.handle)
        // 主标题也不能落到 uid（有昵称时）
        assertEquals("用户4996", c.displayName)
    }

    @Test
    fun `没有昵称时主标题退到 uid，但副标题仍不显示 uid`() {
        val c = CardContent.parseContact("""{"u":"7741990777"}""")!!
        assertEquals("7741990777", c.displayName)
        assertEquals("", c.handle)
    }

    @Test
    fun `非法名片返回 null，由 UI 走降级而不是显示裸 JSON`() {
        assertNull(CardContent.parseContact("not json"))
        assertNull(CardContent.parseContact("{}"))            // 无 uid = 点不动，等同非法
        assertNull(CardContent.parseContact("""{"n":"没有uid"}"""))
    }

    // ——— 合并转发条目摘要（对齐 iOS IMRecordItemPreview）———

    @Test
    fun `图说有字显字，超 60 截断`() {
        assertEquals("说点什么", CardContent.itemPreview(obj("""{"ct":"image","c":"/a.jpg","cap":"说点什么"}""")))
        val long = "字".repeat(80)
        val got = CardContent.itemPreview(obj("""{"ct":"image","c":"/a.jpg","cap":"$long"}"""))
        assertEquals(61, got.length)          // 60 + 省略号
        assertEquals("…", got.takeLast(1))
    }

    @Test
    fun `无图说的媒体走类型占位`() {
        assertEquals("[图片]", CardContent.itemPreview(obj("""{"ct":"image","c":"/a.jpg"}""")))
        assertEquals("[视频]", CardContent.itemPreview(obj("""{"ct":"video","c":"/a.mp4"}""")))
    }

    @Test
    fun `文件带原名——fn 随包带，收端不该只显「文件」`() {
        assertEquals(
            "[文件] 陶哲轩教你学数学.pdf",
            CardContent.itemPreview(obj("""{"ct":"file","c":"/x/abc.pdf","fn":"陶哲轩教你学数学.pdf"}""")),
        )
        // 没有 fn 时从 URL 末段兜底
        assertEquals("[文件] abc.pdf", CardContent.itemPreview(obj("""{"ct":"file","c":"/x/abc.pdf"}""")))
    }

    @Test
    fun `语音显时长，老记录没有 d 只显「语音」`() {
        assertEquals("[语音] 1:05", CardContent.itemPreview(obj("""{"ct":"voice","c":"/a.m4a","d":65000}""")))
        assertEquals("[语音] 0:07", CardContent.itemPreview(obj("""{"ct":"voice","c":"/a.m4a","d":7400}""")))
        assertEquals("[语音]", CardContent.itemPreview(obj("""{"ct":"voice","c":"/a.m4a"}""")))
    }

    @Test
    fun `套娃合并转发只取子标题，不叠加成「聊天记录 聊天记录」`() {
        val inner = """{"t":"和张三的聊天记录","items":[]}"""
        val esc = inner.replace("\"", "\\\"")
        assertEquals(
            "[聊天记录] 和张三的聊天记录",
            CardContent.itemPreview(obj("""{"ct":"chat_record","c":"$esc"}""")),
        )
        // 子标题缺失回落「聊天记录」时**不叠加**
        val bare = """{"items":[]}""".replace("\"", "\\\"")
        assertEquals("[聊天记录]", CardContent.itemPreview(obj("""{"ct":"chat_record","c":"$bare"}""")))
    }

    @Test
    fun `名片条目走名片摘要`() {
        val card = """{"u":"1","n":"老王"}""".replace("\"", "\\\"")
        assertEquals("[个人名片] 老王", CardContent.itemPreview(obj("""{"ct":"contact","c":"$card"}""")))
    }

    // ——— 合并转发卡片 ———

    @Test
    fun `卡片取标题 + 前三条，并报出总条数`() {
        val json = """
            {"t":"群聊的聊天记录","items":[
              {"n":"张三","ct":"text","c":"第一条"},
              {"n":"李四","ct":"image","c":"/a.jpg"},
              {"n":"王五","ct":"text","c":"第三条"},
              {"n":"赵六","ct":"text","c":"第四条"}
            ]}
        """.trimIndent()
        val r = CardContent.parseRecord(json)!!
        assertEquals("群聊的聊天记录", r.title)
        assertEquals(listOf("张三: 第一条", "李四: [图片]", "王五: 第三条"), r.lines)
        assertEquals(4, r.total)
    }

    @Test
    fun `maxLines 0 只要标题——套娃时不再展开子条目`() {
        val r = CardContent.parseRecord("""{"t":"子记录","items":[{"n":"a","ct":"text","c":"x"}]}""", maxLines = 0)!!
        assertEquals("子记录", r.title)
        assertEquals(emptyList<String>(), r.lines)
    }

    @Test
    fun `标题缺失回落「聊天记录」；非法 JSON 返回 null`() {
        assertEquals("聊天记录", CardContent.parseRecord("""{"items":[]}""")!!.title)
        assertNull(CardContent.parseRecord("not json"))
    }
}
