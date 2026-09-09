package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MentionSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群 @提及的判据（M4-8）。对端 im-web `src/mention.test.ts` 与 iOS `IMChatMessageLogic.m`，
 * 协议见 `IMServer/docs/PROTOCOL.md` §4.1。
 *
 * 这里钉的每一条都是**三端必须一致**的口径（SYMMETRY 登记表 `*mention*` 那条）：
 * token 边界、长名优先、UTF-16 偏移、片段优先于昵称表、脏片段逐段丢弃。
 * 分叉的后果多半是**静默**的——某个人少收一条强提醒、或正文里一段普通文字被染成可点的提及。
 */
class MentionTest {

    // ————————————————— 输入态识别 —————————————————

    @Test
    fun `光标前最近一个 @ 到光标之间就是查询词`() {
        assertEquals("", Mention.activeQuery("@", 1))
        assertEquals("小", Mention.activeQuery("@小", 2))
        assertEquals("小明", Mention.activeQuery("在吗 @小明", 6))
        // 全角 ＠（中文输入法默认）同样触发
        assertEquals("小明", Mention.activeQuery("＠小明", 3))
    }

    @Test
    fun `@ 后打过空白就不再是输入态——用户在正常打字`() {
        assertNull(Mention.activeQuery("@小明 在吗", 6))
        assertNull(Mention.activeQuery("邮箱 a@b c", 8))
        assertNull(Mention.activeQuery("没有 at 符号", 5))
        assertNull(Mention.activeQuery("", 0))
    }

    @Test
    fun `不间断空格也算空白——差这一个字符三端就分叉`() {
        // JS 的 /\s/ 与 iOS 的 whitespaceAndNewlineCharacterSet 都认 U+00A0。
        // Kotlin 的 Char.isWhitespace() 也认（它 = isWhitespace || isSpaceChar），
        // 但 **Java 的 Character.isWhitespace 不认**——"顺手"换成那个，同一条
        // 「@小明\u00A0在吗」就会变成两端 token 完整（小明收到提醒）、本端不完整（收不到）。
        // 这里写转义而不是真字符：源码里藏一个看不见的空格，下一个人只会以为是普通空格
        assertTrue(Mention.containsToken("@小明\u00A0在吗", "小明"))
        // U+00A0 之后就不在 @ 输入态了。caret 取 5（串长 6）——取 7 是越界，
        // 那样 activeQuery 恒回 null，这条断言等于没测
        assertNull(Mention.activeQuery("@小明\u00A0在吗", 5))
    }

    // ————————————————— 回填 token —————————————————

    @Test
    fun `回填把整个查询词换成 token 并补尾随空格`() {
        val r = Mention.applyToken("@小", 2, "小明")
        assertEquals("@小明 ", r.text)
        assertEquals(4, r.caret) // 光标落在 token 之后
    }

    @Test
    fun `光标后本来就是空白时不再补空格——否则留下双空格`() {
        val r = Mention.applyToken("@小 在吗", 2, "小明")
        assertEquals("@小明 在吗", r.text)
        assertEquals(3, r.caret)
    }

    @Test
    fun `token 插在光标处而不是末尾——后面还有正文时不能错位`() {
        val r = Mention.applyToken("@x 你看下", 2, "小明")
        assertEquals("@小明 你看下", r.text)
        // 光标停在 token 之后、原有那个空格之前（原文已带空格，故 token 不再补）——与 im-web 逐字一致
        assertEquals(3, r.caret)
    }

    @Test
    fun `找不到 @ 时兜底在光标处插入`() {
        val r = Mention.applyToken("你好", 2, "小明")
        assertEquals("你好@小明 ", r.text)
    }

    // ————————————————— token 边界（最容易错的一条）—————————————————

    @Test
    fun `token 后必须紧跟空白或结尾，否则不算命中`() {
        assertTrue(Mention.containsToken("@小明", "小明"))
        assertTrue(Mention.containsToken("@小明 在吗", "小明"))
        assertTrue(Mention.containsToken("在吗 @小明", "小明"))
        assertTrue(Mention.containsToken("@小明\n下一行", "小明"))
        assertFalse(Mention.containsToken("@小明们 开会", "小明"))
        assertFalse(Mention.containsToken("邮箱小明@x", "小明"))
    }

    @Test
    fun `名字互为前缀时不能误伤短名——错了会发出一条穿透免打扰的错误强提醒`() {
        // 「小美」根本没被提及，裸子串判定会让他收到强提醒
        assertFalse(Mention.containsToken("@小美丽 开会", "小美"))
        assertTrue(Mention.containsToken("@小美丽 开会", "小美丽"))
        // 同一句里两个人都在，短名也要判出来
        assertTrue(Mention.containsToken("@小美丽 @小美 开会", "小美"))
    }

    // ————————————————— 发送前还原 —————————————————

    @Test
    fun `手动删掉 token 就自动不再 @ 他`() {
        val cands = linkedMapOf("u1" to "小明", "u2" to "小红")
        assertEquals(listOf("u1", "u2"), Mention.resolveMentions("@小明 @小红 开会", cands))
        assertEquals(listOf("u2"), Mention.resolveMentions("@小红 开会", cands))
        assertEquals(emptyList<String>(), Mention.resolveMentions("开会", cands))
    }

    @Test
    fun `同名两人都要收到提醒——片段只能链一个，提醒不受影响`() {
        val cands = linkedMapOf("u1" to "小明", "u2" to "小明")
        assertEquals(listOf("u1", "u2"), Mention.resolveMentions("@小明 在吗", cands))
        // 而片段只有一段、只链到先插入的那个
        val spans = Mention.resolveSpans("@小明 在吗", cands, mentionAll = false)
        assertEquals(listOf(MentionSpan(0, 3, "u1")), spans)
    }

    @Test
    fun `@所有人 要标记在且文本里还留着 token`() {
        assertTrue(Mention.resolveMentionAll("@所有人 开会", pending = true))
        assertFalse(Mention.resolveMentionAll("开会", pending = true))
        assertFalse(Mention.resolveMentionAll("@所有人 开会", pending = false))
    }

    @Test
    fun `仅群主管理员能 @所有人`() {
        assertTrue(Mention.canMentionAll("owner"))
        assertTrue(Mention.canMentionAll("admin"))
        assertFalse(Mention.canMentionAll("member"))
        assertFalse(Mention.canMentionAll(null))
    }

    // ————————————————— 片段：偏移与覆盖 —————————————————

    @Test
    fun `偏移是 UTF-16 码元——emoji 一出现，码点口径就与另外两端全对不上`() {
        // 🎉 是代理对，占 2 个码元。`@` 的 UTF-16 偏移是 2、码点偏移是 1、UTF-8 字节偏移是 4
        val spans = Mention.resolveSpans("🎉@小明 在吗", linkedMapOf("u1" to "小明"), false)
        assertEquals(listOf(MentionSpan(2, 3, "u1")), spans)
    }

    @Test
    fun `片段覆盖整个 token 含前导 @`() {
        val text = "@小明 在吗"
        val s = Mention.resolveSpans(text, linkedMapOf("u1" to "小明"), false).single()
        assertEquals('@', text[s.offset])
        assertEquals("@小明", text.substring(s.offset, s.offset + s.length))
    }

    @Test
    fun `所有人覆盖同名成员——否则服务端会因不在 mentions 里整段丢弃`() {
        val cands = linkedMapOf("u1" to "所有人") // 群里真有人叫「所有人」
        val spans = Mention.resolveSpans("@所有人 开会", cands, mentionAll = true)
        assertEquals(listOf(MentionSpan(0, 4, "")), spans) // 空 uid = @所有人
    }

    // ————————————————— 片段：收端切段与降级 —————————————————

    @Test
    fun `按片段切段不需要任何成员表——超级群的全部意义`() {
        val segs = Mention.segmentBySpans("你好 @小明 在吗", listOf(MentionSpan(3, 3, "u1")))
        assertEquals(
            listOf(
                MentionSegment("你好 ", false),
                MentionSegment("@小明", true, "u1"),
                MentionSegment(" 在吗", false),
            ),
            segs,
        )
    }

    @Test
    fun `@所有人 只高亮不可点——uid 为空`() {
        val segs = Mention.segmentBySpans("@所有人 开会", listOf(MentionSpan(0, 4, "")))
        assertEquals(MentionSegment("@所有人", true, null), segs[0])
    }

    @Test
    fun `位置对不上的片段逐段丢弃，不整条崩也不乱染`() {
        val text = "开会时间改了"
        // 编辑过的老消息：偏移还指着原文的位置，那里已经不是 @ 了
        assertEquals(emptyList<MentionSpan>(), Mention.validSpans(text, listOf(MentionSpan(0, 3, "u1"))))
        // 越界 / 长度非正 / 互相重叠
        assertEquals(emptyList<MentionSpan>(), Mention.validSpans("@小明", listOf(MentionSpan(0, 99, "u1"))))
        assertEquals(emptyList<MentionSpan>(), Mention.validSpans("@小明", listOf(MentionSpan(0, 0, "u1"))))
        val overlap = Mention.validSpans("@小明 @小红", listOf(MentionSpan(0, 3, "u1"), MentionSpan(1, 3, "u2")))
        assertEquals(listOf(MentionSpan(0, 3, "u1")), overlap)
    }

    @Test
    fun `一段片段都不剩就整段不高亮——调用方据此回落老路`() {
        assertEquals(
            listOf(MentionSegment("开会时间改了", false)),
            Mention.segmentBySpans("开会时间改了", listOf(MentionSpan(0, 3, "u1"))),
        )
    }

    @Test
    fun `老路按昵称切段，切出来的段点不动`() {
        val segs = Mention.segmentByNames("@小明 在吗", listOf("小明"))
        assertEquals(listOf(MentionSegment("@小明", true, null), MentionSegment(" 在吗", false)), segs)
        // 名字表为空（超级群不下发成员表）→ 整段不高亮，别在这里想办法补救
        assertEquals(
            listOf(MentionSegment("@小明 在吗", false)),
            Mention.segmentByNames("@小明 在吗", emptyList()),
        )
    }

    @Test
    fun `老路同样守长名优先`() {
        val segs = Mention.segmentByNames("@小美丽 开会", listOf("小美", "小美丽"))
        assertEquals("@小美丽", segs[0].text)
        assertTrue(segs[0].mention)
    }

    @Test
    fun `名字带空格时才真正吃到长名优先——短名此时也是合法 token`() {
        // 上一条其实靠的是 token 边界（「@小美」后面是「丽」，不是空白，本来就不成立），
        // 把长名优先那一档删掉照样绿。真正吃到它的是**名字里带空格**的情形：
        // 「小美」后面确实是空白，短名自己就是一个合法 token，只能靠长名先匹配才不会切碎。
        // 显示名带空格很常见（「张 三」「Li Ming」），三端都按长度降序排就是为了这个。
        val cands = linkedMapOf("u1" to "小美", "u2" to "小美 丽")
        val spans = Mention.resolveSpans("@小美 丽 开会", cands, mentionAll = false)
        assertEquals(listOf(MentionSpan(0, 5, "u2")), spans)
    }

    // ————————————————— 落库往返 —————————————————

    @Test
    fun `片段 JSON 往返，字段名是 user_id`() {
        val spans = listOf(MentionSpan(2, 3, "u1"), MentionSpan(7, 4, ""))
        val json = Mention.encodeSpans(spans)!!
        assertTrue("实际是 $json", json.contains("\"user_id\""))
        assertEquals(spans, Mention.parseSpans(json))
    }

    @Test
    fun `坏数据解不出就空表，绝不抛`() {
        assertEquals(emptyList<MentionSpan>(), Mention.parseSpans(null))
        assertEquals(emptyList<MentionSpan>(), Mention.parseSpans(""))
        assertEquals(emptyList<MentionSpan>(), Mention.parseSpans("不是 json"))
        assertEquals(emptyList<MentionSpan>(), Mention.parseSpans("""[{"offset":-1,"length":3}]"""))
        assertNull(Mention.encodeSpans(emptyList()))
    }
}
