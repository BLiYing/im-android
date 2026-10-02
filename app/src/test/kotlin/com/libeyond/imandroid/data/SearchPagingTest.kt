package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPagingTest {
    private fun h(vararg seqs: Long) = seqs.map { SearchHit(it, it * 10) }

    @Test
    fun `更旧的一页拼到前面且按升序`() {
        val m = SearchPaging.prependOlder(h(100, 120), h(60, 40, 80)) // 服务端给的是倒序，顺序不限
        assertEquals(listOf(40L, 60L, 80L, 100L, 120L), m.hits.map { it.convSeq })
        assertEquals(3, m.added)
    }

    @Test
    fun `重复页或游标回退不会把已有命中再塞一遍`() {
        val m = SearchPaging.prependOlder(h(100, 120), h(100, 120, 130))
        assertEquals(listOf(100L, 120L), m.hits.map { it.convSeq })
        assertEquals(0, m.added)
    }

    @Test
    fun `页内重复的 seq 只算一次`() {
        val m = SearchPaging.prependOlder(h(100), h(50, 50))
        assertEquals(1, m.added)
    }

    @Test
    fun `当前为空时整页都算新增，非正 seq 丢弃`() {
        val m = SearchPaging.prependOlder(emptyList(), h(0, 5, 3))
        assertEquals(listOf(3L, 5L), m.hits.map { it.convSeq })
        assertEquals(2, m.added)
    }
}
