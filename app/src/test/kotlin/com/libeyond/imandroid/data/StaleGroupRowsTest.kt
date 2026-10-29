package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleGroupRowsTest {
    private fun row(id: String, group: Boolean, seq: Long = 0) =
        ConversationEntity(ownerUid = "me", convId = id, isGroup = group, lastConvSeq = seq)

    private fun ids(rows: List<ConversationEntity>) = rows.map { it.convId }

    @Test
    fun `快照里没有的群行是陈旧的，没发过消息的本地单聊行不动`() {
        val local = listOf(row("g1", true), row("g2", true), row("c1", false))
        assertEquals(listOf("g2"), ids(StaleGroupRows.of(local, setOf("g1"), mapOf("g1" to 0L, "g2" to 0L, "c1" to 0L))))
    }

    @Test
    fun `已有过消息的单聊行快照里没有 = 别端删除或清除过，也是陈旧的`() {
        val local = listOf(row("c1", false, seq = 5), row("c2", false, seq = 3), row("cNew", false))
        assertEquals(listOf("c1"), ids(StaleGroupRows.of(local, setOf("c2"), mapOf("c1" to 5L, "c2" to 3L, "cNew" to 0L))))
    }

    @Test
    fun `快照里都有则一行不删`() {
        val local = listOf(row("g1", true), row("c1", false))
        assertEquals(emptyList<String>(), ids(StaleGroupRows.of(local, setOf("g1", "c1"), mapOf("g1" to 0L, "c1" to 0L))))
    }

    @Test
    fun `请求在途时才出现的群行不删`() {
        val local = listOf(row("g1", true), row("gNew", true))
        assertEquals(emptyList<String>(), ids(StaleGroupRows.of(local, setOf("g1"), beforeRequest = mapOf("g1" to 0L))))
    }

    @Test
    fun `请求在途时单聊来了新消息 lastConvSeq 变了则不删，群不受此限`() {
        val local = listOf(row("c1", false, seq = 6), row("g1", true, seq = 9))
        assertEquals(listOf("g1"), ids(StaleGroupRows.of(local, emptySet(), mapOf("c1" to 5L, "g1" to 8L))))
    }
}
