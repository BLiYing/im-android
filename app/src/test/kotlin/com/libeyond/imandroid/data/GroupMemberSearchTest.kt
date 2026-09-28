package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMemberSearchTest {

    private fun member(uid: String) = GroupMember(userId = uid, username = uid, nickname = uid)

    // —— shouldOffer ——

    @Test
    fun shouldOffer_uses_memberCount_when_present() {
        assertTrue(GroupMemberSearch.shouldOffer(memberCount = 51, loadedCount = 1))
        assertFalse(GroupMemberSearch.shouldOffer(memberCount = 50, loadedCount = 999))
    }

    @Test
    fun shouldOffer_falls_back_to_loadedCount_when_memberCount_absent() {
        // 超级群列表页只有我自己（memberCount 未下发时恒 0），不能拿它当"人少"
        assertTrue(GroupMemberSearch.shouldOffer(memberCount = 0, loadedCount = 51))
        assertFalse(GroupMemberSearch.shouldOffer(memberCount = 0, loadedCount = 50))
    }

    // —— shouldAutoLoadMore ——

    @Test
    fun shouldAutoLoadMore_true_within_lead_rows_of_end() {
        assertTrue(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 40, loadedCount = 50, hasMore = true, loading = false))
        assertTrue(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 35, loadedCount = 50, hasMore = true, loading = false))
    }

    @Test
    fun shouldAutoLoadMore_false_far_from_end_or_no_more_or_already_loading() {
        assertFalse(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 10, loadedCount = 50, hasMore = true, loading = false))
        assertFalse(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 49, loadedCount = 50, hasMore = false, loading = false))
        assertFalse(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 49, loadedCount = 50, hasMore = true, loading = true))
    }

    @Test
    fun shouldAutoLoadMore_false_before_first_page_lands() {
        // loadedCount<=0：首页还没回来，交给各页自己的首屏加载，别在这里抢跑
        assertFalse(GroupMemberSearch.shouldAutoLoadMore(displayedIndex = 0, loadedCount = 0, hasMore = true, loading = false))
    }

    // —— mergePage ——

    @Test
    fun mergePage_first_page_replaces_existing() {
        val existing = listOf(member("stale1"))
        val incoming = listOf(member("a"), member("b"))
        val merged = GroupMemberSearch.mergePage(existing, incoming, isFirstPage = true)
        assertEquals(listOf("a", "b"), merged.map { it.userId })
    }

    @Test
    fun mergePage_next_page_appends_and_dedupes_by_userId() {
        val existing = listOf(member("a"), member("b"))
        // keyset 游标翻页期间有人进群/退群，相邻两页可能覆盖同一个人（这里覆盖 "b"）
        val incoming = listOf(member("b"), member("c"))
        val merged = GroupMemberSearch.mergePage(existing, incoming, isFirstPage = false)
        assertEquals(listOf("a", "b", "c"), merged.map { it.userId })
    }
}
