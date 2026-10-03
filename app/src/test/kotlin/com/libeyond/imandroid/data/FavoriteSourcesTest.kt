package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSourcesTest {
    private fun fav(id: Long, conv: String, from: String, at: Long) =
        Favorite(id = id, sourceConvId = conv, sourceFrom = from, createdAt = at)

    @Test fun `我发的与无来源的归入我，其余按会话，组按最近时间倒序`() {
        val items = listOf(
            fav(1, "g_a", "u2", 10), fav(2, "g_a", "me", 50), fav(3, "", "u3", 20),
            fav(4, "u_x_u_y", "u2", 30), fav(5, "g_a", "u4", 5),
        )
        val groups = FavoriteSources.group(items, "me")
        assertEquals(listOf(FavoriteSources.ME, "u_x_u_y", "g_a"), groups.map { it.key })
        assertEquals(listOf(2L, 3L), groups[0].items.map { it.id })
        assertEquals(2, groups[2].items.size)
        assertEquals(30L, groups[1].latest.createdAt)
    }

    @Test fun `来源搜索只匹配名字且忽略大小写`() {
        val groups = FavoriteSources.group(listOf(fav(1, "g_a", "u", 1), fav(2, "g_b", "u", 2)), "me")
        val names = mapOf("g_a" to "Alpha 群", "g_b" to "beta")
        assertEquals(listOf("g_a"), FavoriteSources.filter(groups, "ALPHA") { names.getValue(it) }.map { it.key })
        assertEquals(2, FavoriteSources.filter(groups, "  ") { names.getValue(it) }.size)
    }

    @Test fun `模式只有存 1 才是聊天模式`() {
        assertTrue(FavoriteSources.isChatMode(1)); assertFalse(FavoriteSources.isChatMode(0)); assertFalse(FavoriteSources.isChatMode(7))
    }
}
