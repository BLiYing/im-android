package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 服务端给空串 = 不可见 / 无头像：权威来源里有这个人就以它为准，不退到旧缓存。 */
class AvatarPrecedenceTest {

    @Test
    fun member_row_with_empty_avatar_beats_stale_cache() {
        assertEquals("", AvatarPrecedence.sender(mapOf("u1" to ""), "u1", "/old.png"))
    }

    @Test
    fun member_row_with_avatar_wins() {
        assertEquals("/m.png", AvatarPrecedence.sender(mapOf("u1" to "/m.png"), "u1", "/old.png"))
    }

    @Test
    fun not_a_member_falls_back_to_cache_then_null() {
        assertEquals("/c.png", AvatarPrecedence.sender(emptyMap(), "u1", "/c.png"))
        assertEquals(null, AvatarPrecedence.sender(emptyMap(), "u1", null))
    }

    @Test
    fun friend_row_with_empty_avatar_beats_passed_in_one() {
        assertEquals("", AvatarPrecedence.profile(friendAvatar = "", passed = "/old.png"))
        assertEquals("/f.png", AvatarPrecedence.profile(friendAvatar = "/f.png", passed = "/old.png"))
        assertEquals("/p.png", AvatarPrecedence.profile(friendAvatar = null, passed = "/p.png"))
    }
}
