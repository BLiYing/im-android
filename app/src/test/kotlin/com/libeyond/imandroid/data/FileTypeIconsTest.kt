package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扩展名 → 文件类型。**清单逐条照抄 iOS `IMFileTypeIdentifierForName`**：
 * 两端认的类型不一样，同一个文件在两端就是两种图标。
 */
class FileTypeIconsTest {

    @Test
    fun `办公文档按 iOS 的清单归类`() {
        assertEquals("word", FileTypeIcons.kindFor("合同.docx"))
        assertEquals("excel", FileTypeIcons.kindFor("报表.XLSX"))      // 大小写不敏感
        assertEquals("powerpoint", FileTypeIcons.kindFor("汇报.pptx"))
        assertEquals("pdf", FileTypeIcons.kindFor("说明书.pdf"))
        assertEquals("csv", FileTypeIcons.kindFor("data.tsv"))
    }

    @Test
    fun `媒体与压缩包`() {
        assertEquals("image", FileTypeIcons.kindFor("a.heic"))
        assertEquals("video", FileTypeIcons.kindFor("a.mkv"))
        assertEquals("audio", FileTypeIcons.kindFor("a.flac"))
        assertEquals("archive", FileTypeIcons.kindFor("a.tgz"))
        assertEquals("package", FileTypeIcons.kindFor("a.apk"))
    }

    @Test
    fun `源码类归到 code`() {
        assertEquals("code", FileTypeIcons.kindFor("Main.kt"))
        assertEquals("code", FileTypeIcons.kindFor("a.tsx"))
        assertEquals("code", FileTypeIcons.kindFor("deploy.yml"))
    }

    /** 认不出就是 unknown——**猜错类型比给个问号更糟**（用户会以为是别的文件）。 */
    @Test
    fun `认不出一律 unknown`() {
        assertEquals(FileTypeIcons.UNKNOWN, FileTypeIcons.kindFor("没有扩展名"))
        assertEquals(FileTypeIcons.UNKNOWN, FileTypeIcons.kindFor(null))
        assertEquals(FileTypeIcons.UNKNOWN, FileTypeIcons.kindFor(""))
        assertEquals(FileTypeIcons.UNKNOWN, FileTypeIcons.kindFor("a.zzzz"))
    }

    /** 只认最后一个点之后那截：`归档.tar.gz` 是 gz 不是 tar。 */
    @Test
    fun `多重扩展名只认最后一截`() {
        assertEquals("archive", FileTypeIcons.kindFor("归档.tar.gz"))
        assertEquals("word", FileTypeIcons.kindFor("我的.文件.doc"))
    }

    /** 超长"扩展名"多半是文件名里带点，不是真扩展名。 */
    @Test
    fun `超长后缀不当扩展名`() {
        assertEquals(FileTypeIcons.UNKNOWN, FileTypeIcons.kindFor("会议纪要.2026年9月8日讨论稿"))
    }

    /** 每一类都要有渐变色与角标定义（新加一类忘了配色，这条会红）。 */
    @Test
    fun `每一类都有配色`() {
        val kinds = listOf(
            "pdf", "word", "excel", "powerpoint", "csv", "pages", "numbers", "keynote",
            "text", "markdown", "xml", "json", "image", "video", "audio", "archive",
            "code", "database", "font", "ebook", "package", FileTypeIcons.UNKNOWN,
        )
        kinds.forEach { k ->
            val (a, b) = FileTypeIcons.gradient(k)
            assertEquals("$k 的渐变两端不该相同", false, a == b)
        }
    }
}
