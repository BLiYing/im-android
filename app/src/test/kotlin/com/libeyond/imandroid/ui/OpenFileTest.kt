package com.libeyond.imandroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 打开文件时的**副本命名**：交给别的应用的名字必须是给人看的那个，且不能带路径。 */
class OpenFileNameTest {

    @Test
    fun `路径分隔符要扒掉——否则会写到 share 目录之外`() {
        assertEquals("a_b.pdf", OpenFile.safeName("a/b.pdf"))
        assertEquals("a_b.pdf", OpenFile.safeName("a\\b.pdf"))
        assertEquals("_etc_passwd", OpenFile.safeName("/etc/passwd"))
    }

    @Test
    fun `空名兜一个——File(dir, 空串) 指向目录本身`() {
        assertEquals("file", OpenFile.safeName(""))
        assertEquals("file", OpenFile.safeName("   "))
    }

    @Test
    fun `正常名字原样保留（含中文与空格）`() {
        assertEquals("需要预测的 化合物.xlsx", OpenFile.safeName("需要预测的 化合物.xlsx"))
    }
}
