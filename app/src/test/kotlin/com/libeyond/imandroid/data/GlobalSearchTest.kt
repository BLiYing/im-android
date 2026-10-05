package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    private fun conv(id: String, title: String, group: Boolean = false) =
        ConversationEntity(ownerUid = "me", convId = id, isGroup = group, title = title)

    private fun msg(conv: String, seq: Long, text: String = "", caption: String? = null, file: String? = null, type: String = "text") =
        MessageEntity(ownerUid = "me", convId = conv, convSeq = seq, contentType = type, content = text, caption = caption, fileName = file)

    @Test fun convHitsMatchTitleCaseInsensitive() {
        val list = listOf(conv("a", "Alpha 群", true), conv("b", "Beta"))
        assertEquals(listOf("a"), GlobalSearch.convHits(list, " ALPHA ") { it.title }.map { it.convId })
        assertTrue(GlobalSearch.convHits(list, "  ") { it.title }.isEmpty())
    }

    @Test fun friendHitsOnlyAcceptedAndMatchAnyField() {
        val f = listOf(
            FriendEntry(userId = "u1", username = "tom", nickname = "汤姆", status = FriendEntry.ACCEPTED),
            FriendEntry(userId = "u2", username = "tommy", nickname = "汤米", status = FriendEntry.PENDING),
        )
        assertEquals(listOf("u1"), GlobalSearch.friendHits(f, "tom").map { it.userId })
        assertEquals(listOf("u1"), GlobalSearch.friendHits(f, "汤姆").map { it.userId })
        assertEquals(listOf("u1"), GlobalSearch.friendHits(f, "U1").map { it.userId })
    }

    @Test fun groupHitsMatchNameCaseInsensitiveAndBlankIsEmpty() {
        val g = listOf(
            com.libeyond.imandroid.sdk.api.GroupInfo(convId = "g1", name = "Android 群"),
            com.libeyond.imandroid.sdk.api.GroupInfo(convId = "g2", name = "设计"),
        )
        assertEquals(listOf("g1"), GlobalSearch.groupHits(g, " android ").map { it.convId })
        assertTrue(GlobalSearch.groupHits(g, " ").isEmpty())
        assertTrue(GlobalSearch.groupHits(g, "zzz").isEmpty())
    }

    @Test fun recordHitsSkipOrphanConversationsAndKeepOnePerMessage() {
        val convs = listOf(conv("a", "A"))
        val msgs = listOf(msg("a", 2, "hello world"), msg("a", 1, "hello"), msg("gone", 9, "hello"))
        val hits = GlobalSearch.recordHits(msgs, convs, "Hello")
        assertEquals(listOf(2L, 1L), hits.map { it.msg.convSeq })
    }

    @Test fun snippetShowsTheFieldThatActuallyMatched() {
        val m = msg("a", 1, text = "", caption = "夕阳", file = "report.pdf", type = "file")
        assertEquals("report.pdf", GlobalSearch.snippet(m, "report"))
        assertEquals("夕阳", GlobalSearch.snippet(m, "夕阳"))
        assertEquals("你好", GlobalSearch.snippet(msg("a", 1, "你好"), "好"))
    }
}
