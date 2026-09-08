package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内搜索的判据（SEARCH_DESIGN §4）。
 *
 * ### 为什么这几条值得单测
 * 搜索这一族的错**全是静默的**：判据漂了不会崩、不会报错，只是"某些消息搜不到"，
 * 而"搜不到"和"真的没有"在界面上长得一模一样。本仓另外两端都各栽过一次：
 * - im-web 把渲染窗口当成整个会话，3 万条的群里只命中 98 条（= 窗口条数）；
 * - iOS 的 `head_conv_seq` 一直是 0，于是"有缺口"那条分支从未真正生效。
 *
 * 所以判据一律抽成纯函数钉在这里，UI 那侧只负责画。
 */
class ChatSearchTest {

    // ————————————————— 问谁：本地 / 服务端 / 降级 —————————————————

    @Test
    fun `本地齐全时联不联网都走本地`() {
        assertEquals(QuerySource.Local, ChatSearch.pickSource(complete = true, online = true))
        assertEquals(QuerySource.Local, ChatSearch.pickSource(complete = true, online = false))
    }

    @Test
    fun `有缺口且在线问服务端，离线只能降级并说出来`() {
        assertEquals(QuerySource.Server, ChatSearch.pickSource(complete = false, online = true))
        assertEquals(QuerySource.LocalDegraded, ChatSearch.pickSource(complete = false, online = false))
        // 降级文案与 im-web `DEGRADED_SEARCH_NOTICE` 逐字一致——同一处境不给两副说辞
        assertEquals("离线：仅搜索已下载的消息", ChatSearch.DEGRADED_SEARCH_NOTICE)
    }

    // ————————————————— 本地齐不齐 —————————————————

    @Test
    fun `游标追上会话上界才算齐全`() {
        assertTrue(ChatSearch.isLocalComplete(syncedConvSeq = 120, lastConvSeq = 120))
        assertTrue(ChatSearch.isLocalComplete(syncedConvSeq = 130, lastConvSeq = 120))
        assertFalse(ChatSearch.isLocalComplete(syncedConvSeq = 80, lastConvSeq = 120))
    }

    @Test
    fun `空会话恒算齐全——本地就是全部`() {
        assertTrue(ChatSearch.isLocalComplete(syncedConvSeq = 0, lastConvSeq = 0))
    }

    @Test
    fun `清空聊天记录之后仍算齐全——搜索该如实回无匹配，不去服务端把刚清掉的搜回来`() {
        // 清空只删本地、**刻意保留同步游标**（不该把刚删掉的再拉回来）。
        // 判成"有缺口"的话，在线时会走服务端把用户刚亲手清掉的消息整整齐齐搜回来，
        // 点过去还只能得到一句"这条不在本机"。im-web 的 clearMessages 同样不动区间清单。
        assertTrue(ChatSearch.isLocalComplete(syncedConvSeq = 120, lastConvSeq = 120))
        assertEquals(QuerySource.Local, ChatSearch.pickSource(complete = true, online = true))
        // 于是命中集为空 → 界面显示「无匹配」，而不是一串跳不过去的服务端命中
        assertEquals("无匹配", ChatSearch.hitLabel(idx = 0, count = 0, truncated = false))
    }

    // ————————————————— LIKE 转义（镜像后端 escapeLike） —————————————————

    @Test
    fun `通配符要转义，否则百分号会变成前缀匹配`() {
        // 对端：IMServer `internal/store/sqlite_message.go` 的 escapeLike，
        // 那侧有一条 `SearchConvMessages(ctx, "c1", "50%")` 的测试钉着同一件事。
        assertEquals("50\\%", ChatSearch.escapeLike("50%"))
        assertEquals("a\\_b", ChatSearch.escapeLike("a_b"))
    }

    @Test
    fun `反斜杠必须先转，否则会把自己补上的转义符再转一遍`() {
        assertEquals("\\\\\\%", ChatSearch.escapeLike("\\%"))
        assertEquals("abc", ChatSearch.escapeLike("abc"))
    }

    // ————————————————— 命中口径 —————————————————

    private fun hit(
        type: String = ContentType.TEXT,
        content: String = "",
        caption: String? = null,
        fileName: String? = null,
        needle: String,
    ) = ChatSearch.matches(type, content, caption, fileName, needle)

    @Test
    fun `文本正文命中，且大小写不敏感`() {
        assertTrue(hit(content = "Hello World", needle = "world"))
        assertTrue(hit(content = "预算三万", needle = "预算"))
        assertFalse(hit(content = "预算三万", needle = "开会"))
    }

    @Test
    fun `图说与文件名也算命中——与后端 G4 三源一致`() {
        assertTrue(hit(type = ContentType.IMAGE, content = "/uploads/x.jpg", caption = "会议白板", needle = "白板"))
        assertTrue(hit(type = ContentType.FILE, content = "/uploads/y", fileName = "Q3报表.xlsx", needle = "q3"))
    }

    @Test
    fun `媒体的 content 是 URL，不参与命中`() {
        // 参与的话搜 "uploads" 会命中一堆屏幕上根本看不见这几个字的图片
        assertFalse(hit(type = ContentType.IMAGE, content = "/uploads/req-1__a.jpg", needle = "uploads"))
        assertFalse(hit(type = ContentType.VIDEO, content = "/uploads/req-1__a.mp4", needle = "req"))
    }

    @Test
    fun `空词一律不命中，不是全命中`() {
        assertFalse(hit(content = "随便什么", needle = ""))
    }

    // ————————————————— 命中词高亮的位置 —————————————————

    @Test
    fun `多处命中全部标出且不重叠`() {
        assertEquals(listOf(0..1, 3..4), ChatSearch.matchRanges("预算和预算表", "预算"))
    }

    @Test
    fun `高亮大小写不敏感，位置按原文算`() {
        assertEquals(listOf(6..10), ChatSearch.matchRanges("Hello World", "WORLD"))
    }

    @Test
    fun `重叠的词只取不重叠的那几段`() {
        // "aaaa" 里搜 "aa"：0..1 与 2..3，不是 0..1/1..2/2..3
        assertEquals(listOf(0..1, 2..3), ChatSearch.matchRanges("aaaa", "aa"))
    }

    @Test
    fun `空词与搜不到都回空表——不是整段高亮`() {
        assertEquals(emptyList<IntRange>(), ChatSearch.matchRanges("预算", ""))
        assertEquals(emptyList<IntRange>(), ChatSearch.matchRanges("预算", "开会"))
        assertEquals(emptyList<IntRange>(), ChatSearch.matchRanges("短", "很长的词"))
    }

    // ————————————————— 计数与命中下标 —————————————————

    @Test
    fun `计数从 1 开始数给人看`() {
        assertEquals("1 / 12", ChatSearch.hitLabel(idx = 0, count = 12, truncated = false))
        assertEquals("12 / 12", ChatSearch.hitLabel(idx = 11, count = 12, truncated = false))
    }

    @Test
    fun `被单页上限截断时必须补加号`() {
        // 悄悄截成 50 条还写「/ 50」会让人以为大群里就只有这些命中
        assertEquals("50 / 50+", ChatSearch.hitLabel(idx = 49, count = 50, truncated = true))
    }

    @Test
    fun `无命中显示无匹配而不是 0 分之 0`() {
        assertEquals("无匹配", ChatSearch.hitLabel(idx = 0, count = 0, truncated = false))
        assertEquals("无匹配", ChatSearch.hitLabel(idx = 3, count = 0, truncated = true))
    }

    @Test
    fun `还没输入关键词时不能说无匹配`() {
        // 什么都没搜就说"没有匹配"，说的是一件没发生过的事
        assertEquals("输入关键词搜索本会话", ChatSearch.hitLabel(0, 0, false, hasQuery = false))
        assertEquals("输入关键词搜索本会话", ChatSearch.hitLabel(0, 7, false, hasQuery = false))
    }

    @Test
    fun `默认停在最新一条命中`() {
        // 命中集升序（0 = 最早），所以"最新"是最后一个——贴合"找刚才那条"的直觉
        assertEquals(11, ChatSearch.defaultHitIndex(12))
        assertEquals(0, ChatSearch.defaultHitIndex(0))
    }

    @Test
    fun `下标越界一律夹回范围内，不抛`() {
        assertEquals(0, ChatSearch.clampHitIndex(-3, 5))
        assertEquals(4, ChatSearch.clampHitIndex(99, 5))
        assertEquals(0, ChatSearch.clampHitIndex(99, 0))
    }
}
