package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleGroupRowsTest {
    private fun row(id: String, group: Boolean) = ConversationEntity(ownerUid = "me", convId = id, isGroup = group)

    @Test
    fun `快照里没有的群行是陈旧的，单聊行不动`() {
        val local = listOf(row("g1", true), row("g2", true), row("c1", false))
        assertEquals(listOf("g2"), StaleGroupRows.of(local, setOf("g1"), setOf("g1", "g2", "c1")))
    }

    @Test
    fun `快照里都有则一行不删`() {
        val local = listOf(row("g1", true), row("c1", false))
        assertEquals(emptyList<String>(), StaleGroupRows.of(local, setOf("g1", "c1"), setOf("g1", "c1")))
    }

    @Test
    fun `请求在途时才出现的群行不删`() {
        val local = listOf(row("g1", true), row("gNew", true))
        assertEquals(emptyList<String>(), StaleGroupRows.of(local, setOf("g1"), beforeRequest = setOf("g1")))
    }
}
