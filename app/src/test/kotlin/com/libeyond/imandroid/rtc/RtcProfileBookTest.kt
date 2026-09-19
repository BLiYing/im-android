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
        book.put("u1", "小明", "/a.png", 0)
        assertEquals("小明", book.name("u1"))
        assertEquals("/a.png", book.avatarUrl("u1"))
        assertFalse("取到了就不再取", book.claim("u1", 2))
    }

    @Test
    fun cached_entry_is_refreshed_once_stale_but_still_served() {
        val book = RtcProfileBook(staleAfterMs = 1000)
        book.put("u1", "小明", "", 0)
        assertFalse("没到期不刷新", book.claim("u1", 999))
        assertTrue("到期后再问要刷新", book.claim("u1", 1000))
        assertEquals("刷新期间旧值照常答", "小明", book.name("u1"))
        book.put("u1", "小明", "/new.png", 1000)
        assertEquals("/new.png", book.avatarUrl("u1"))
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
