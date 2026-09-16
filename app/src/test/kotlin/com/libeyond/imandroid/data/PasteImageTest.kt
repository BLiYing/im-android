package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「粘贴图片」的判据（[PasteImage]）。
 *
 * 本端的剪贴板里放的是 `content://`（安卓没有通用的"把位图放进剪贴板"做法，
 * 见 `ui/CopyImageAction.kt`），而 Compose 的 `BasicTextField` 只收纯文本：
 * 系统会把 URI 型剪贴项 `coerceToText` 成一行字符串插进正文。用户按「粘贴」后
 * 看到的是一行 `content://…`，图既没进来也没处去（2026-09-16 用户报：在本 App 里粘不上）。
 *
 * 所以要在正文里把它认出来。三条边界必须钉死：**不能误吞用户真的在发的网址**、
 * **不能连带摘走旁边的字**、**没被认领的那段要留在原处**——后两条都是在用户正在写的话里挖洞。
 */
class PasteImageTest {

    private fun uris(text: String) = PasteImage.find(text).map { it.uri }

    @Test
    fun `没有 URI 时找不到，正文一字不改`() {
        val s = "今晚八点老地方  见"
        assertTrue(PasteImage.find(s).isEmpty())
        assertEquals(s, PasteImage.removing(s, emptyList()))
    }

    @Test
    fun `认出 content 型 URI 并从正文摘掉`() {
        val uri = "content://com.libeyond.imandroid.fileprovider/share/IMG_1.jpg"
        val found = PasteImage.find(uri)
        assertEquals(listOf(uri), found.map { it.uri })
        assertEquals("", PasteImage.removing(uri, found))
    }

    // 用户真的在发一条链接——吞掉它就是「我粘的网址消失了」
    @Test
    fun `http 链接不认，原样留在正文里`() {
        assertTrue(PasteImage.find("看这个 https://example.com/a.jpg").isEmpty())
    }

    @Test
    fun `URI 前后用户自己打的字要留着`() {
        val uri = "content://com.libeyond.imandroid.fileprovider/share/IMG_2.jpg"
        val text = "你看 $uri 这张"
        assertEquals(listOf(uri), uris(text))
        assertEquals("你看 这张", PasteImage.removing(text, PasteImage.find(text)))
    }

    @Test
    fun `一次粘进多张`() {
        val a = "content://x/share/1.jpg"
        val b = "content://x/share/2.jpg"
        val text = "$a $b"
        assertEquals(listOf(a, b), uris(text))
        assertEquals("", PasteImage.removing(text, PasteImage.find(text)))
    }

    // 中文紧跟在 URI 后面没有空格：URI 的合法字符集里没有汉字，边界就落在这儿。
    // （`Char.isLetterOrDigit()` 是 Unicode 感知的，用它会把「好看吗」一起吞掉——这条第一次跑就是红的）
    @Test
    fun `URI 后面紧跟中文也能断开`() {
        val uri = "content://x/share/3.jpg"
        val text = "${uri}好看吗"
        assertEquals(listOf(uri), uris(text))
        assertEquals("好看吗", PasteImage.removing(text, PasteImage.find(text)))
    }

    // —— 2026-09-16 /code-review 抓出的那条：一部分认领、一部分没认领 ——
    // 早先的实现把没认领的统一拼到正文末尾，用户没删没改，字却被搬了家。

    @Test
    fun `只摘走认领的那一段，没认领的留在原处`() {
        val weird = "content://weird/x"
        val real = "content://x/share/real.jpg"
        val text = "看这个 $weird 和这个 $real"
        val found = PasteImage.find(text)
        // 调用方只认领了 real 那一段（weird 被系统判定为非图片）
        val claimed = found.filter { it.uri == real }
        assertEquals("看这个 $weird 和这个", PasteImage.removing(text, claimed))
    }

    @Test
    fun `一个都没认领时正文原样`() {
        val text = "看这个 content://weird/x 好吗"
        assertEquals(text, PasteImage.removing(text, emptyList()))
    }

    @Test
    fun `区间对得上原文`() {
        val uri = "content://x/share/4.jpg"
        val text = "abc $uri def"
        val f = PasteImage.find(text).single()
        assertEquals(uri, text.substring(f.start, f.end))
    }
}
