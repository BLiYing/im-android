package com.libeyond.imandroid.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通话界面的名字缓存与取数去重：Kit 每次重画都同步问一遍，没缓存的只能发一次请求。 */
class RtcProfileBookTest {

    @Test
    fun unknown_uid_is_claimed_once_until_resolved() {
        val book = RtcProfileBook()
        assertNull(book.name("u1"))
        assertTrue(book.claim("u1", 0))
        assertFalse("在途中不重复取", book.claim("u1", 1))
        book.put("u1", "小明", "/a.png")
        assertEquals("小明", book.name("u1"))
        assertEquals("/a.png", book.avatarUrl("u1"))
        assertFalse("取到了就不再取", book.claim("u1", 2))
    }

    @Test
    fun cached_entry_is_never_refetched_by_time() {
        val book = RtcProfileBook()
        book.put("u1", "小明", "")
        assertFalse("不按时间过期：通话界面跟着 IM 走，兜底缓存不自己刷新", book.claim("u1", 24 * 3600_000L))
    }

    @Test
    fun failure_cools_down_then_allows_retry() {
        val book = RtcProfileBook(retryAfterMs = 1000)
        assertTrue(book.claim("u1", 0))
        book.fail("u1", 0)
        assertFalse("冷却期内不取", book.claim("u1", 999))
        assertTrue("冷却期过了可重试", book.claim("u1", 1000))
    }

    @Test
    fun empty_uid_is_never_claimed_and_unknown_avatar_is_blank() {
        val book = RtcProfileBook()
        assertFalse(book.claim("", 0))
        assertEquals("", book.avatarUrl("nobody"))
    }
}
