package com.libeyond.imandroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表页搜索口径（对齐 iOS `IMListSearchMatches` / Web `listSearch.ts`）。 */
class ListSearchTest {

    @Test
    fun `没在搜就全部命中`() {
        assertTrue(ListSearch.matches("", listOf("张三")))
        assertTrue(ListSearch.matches("   ", listOf("")))
    }

    @Test
    fun `去首尾空白后大小写不敏感地子串匹配`() {
        assertTrue(ListSearch.matches(" alice ", listOf("Team Alice")))
        assertTrue(ListSearch.matches("三", listOf("张三")))
        assertFalse(ListSearch.matches("李四", listOf("张三")))
    }

    @Test
    fun `任一字段命中即算且空字段不参与`() {
        assertTrue(ListSearch.matches("1001", listOf("张三", "1001234567")))
        assertFalse(ListSearch.matches("x", listOf("", "")))
    }
}
