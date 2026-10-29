package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ClearAllResult
import com.libeyond.imandroid.sdk.api.ClearedConv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearAllConversationsTest {
    private val ok = ClearAllResult(2, listOf(ClearedConv("a", 5), ClearedConv("b", 9)))

    @Test
    fun `请求失败时本机零变更 半清禁止`() = runBlocking {
        var applied = false
        var sideEffects = false
        val err = runCatching {
            ClearAllConversations.run(
                request = { error("network") },
                applyLocal = { applied = true },
                sideEffects = { sideEffects = true },
            )
        }.exceptionOrNull()
        assertEquals("network", err?.message)
        assertTrue(!applied && !sideEffects)
    }

    @Test
    fun `成功后先落位点再清缓存 返回服务端 cleared`() = runBlocking {
        val order = mutableListOf<String>()
        val n = ClearAllConversations.run(
            request = { ok },
            applyLocal = { order += "local:" + it.items.joinToString { i -> "${i.convId}@${i.clearedAt}" } },
            sideEffects = { order += "side" },
        )
        assertEquals(2, n)
        assertEquals(listOf("local:a@5, b@9", "side"), order)
    }

    @Test
    fun `缓存清理失败不回滚也不报错`() = runBlocking {
        val n = ClearAllConversations.run({ ok }, {}, { error("disk") })
        assertEquals(2, n)
    }

    @Test
    fun `解析 items 并以服务端 cleared 为准 缺字段容错`() {
        val r = ClearAllResult.parse(Json.parseToJsonElement("""{"cleared":3,"items":[{"conv_id":"a","cleared_at":7},{"conv_id":"b"},{"cleared_at":1}]}"""))
        assertEquals(3, r.cleared)
        assertEquals(listOf(ClearedConv("a", 7), ClearedConv("b", 0)), r.items)
        assertEquals(ClearAllResult(0, emptyList()), ClearAllResult.parse(null))
        assertEquals(1, ClearAllResult.parse(Json.parseToJsonElement("""{"items":[{"conv_id":"a","cleared_at":1}]}""")).cleared)
    }
}
