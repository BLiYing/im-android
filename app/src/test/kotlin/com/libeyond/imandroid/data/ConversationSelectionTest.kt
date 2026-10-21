package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话列表编辑模式选择集：读**三端共用向量** `conv_selection.json`（真源在 IMServer `docs/conformance/`，
 * `src/test/resources/` 是拷贝；真源在旁边时会比对，防漂移——同 `ChatFolderLogicTest`）。改口径先改向量。
 */
class ConversationSelectionTest {
    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("conv_selection.json")) { "缺 conv_selection.json" }.readText()
    private val root = Json.parseToJsonElement(text).jsonObject

    private fun conv(o: JsonObject) = ConversationEntity(
        ownerUid = "me", convId = o["id"]!!.jsonPrimitive.content,
        unread = o["unread"]!!.jsonPrimitive.int, markedUnread = o["marked"]!!.jsonPrimitive.boolean,
    )

    @Test
    fun `向量与真源一致`() {
        val src = File("../../IMServer/docs/conformance/conv_selection.json")
        if (src.exists()) assertEquals("测试资源与真源漂移，重新拷贝", src.readText().trim(), text.trim())
    }

    @Test
    fun `有未读判据向量`() {
        val cases = root["has_unread"]!!.jsonArray
        assertTrue(cases.size >= 4)
        for (el in cases) {
            val c = el.jsonObject
            val e = ConversationEntity(
                ownerUid = "me", convId = "x", unread = c["unread"]!!.jsonPrimitive.int, markedUnread = c["marked"]!!.jsonPrimitive.boolean,
            )
            assertEquals(c["name"]!!.jsonPrimitive.content, c["expect"]!!.jsonPrimitive.boolean, ConversationSelection.hasUnread(e))
        }
    }

    @Test
    fun `场景向量`() {
        val scenarios = root["scenarios"]!!.jsonArray
        assertTrue(scenarios.size >= 9)
        for (el in scenarios) {
            val s = el.jsonObject
            val name = s["name"]!!.jsonPrimitive.content
            var list = s["convs"]!!.jsonArray.map { conv(it.jsonObject) }
            var sel = ConversationSelection()
            for (opEl in s["ops"]!!.jsonArray) {
                val op = opEl.jsonArray
                when (val kind = op[0].jsonPrimitive.content) {
                    "toggle" -> sel = sel.toggle(op[1].jsonPrimitive.content)
                    "select_all" -> sel = sel.selectAll(list)
                    "clear" -> sel = ConversationSelection()
                    "prune" -> {
                        val keep = op[1].jsonArray.map { it.jsonPrimitive.content }.toSet()
                        list = list.filter { it.convId in keep }
                        sel = sel.pruneTo(list)
                    }
                    else -> error("未知 op $kind")
                }
            }
            val expect = s["expect"]!!.jsonObject
            assertEquals(name, expect["ids"]!!.jsonArray.map { it.jsonPrimitive.content }, sel.ids.sorted())
            assertEquals("$name all_selected", expect["all_selected"]!!.jsonPrimitive.boolean, sel.isAllSelectedIn(list))
            assertEquals("$name has_unread", expect["has_unread"]!!.jsonPrimitive.boolean, sel.hasUnreadIn(list))
        }
    }

    @Test
    fun `已选按列表顺序取出`() {
        val list = listOf("a", "b", "c").map { ConversationEntity(ownerUid = "me", convId = it) }
        val sel = ConversationSelection().toggle("c").toggle("a")
        assertEquals(listOf("a", "c"), sel.selectedIn(list).map { it.convId })
    }

    @Test
    fun `有界并发执行器限并发并分组成败`() = runTest {
        val inflight = AtomicInteger(0)
        var peak = 0
        val result = runBounded((0 until 10).toList(), 3) { i ->
            val now = inflight.incrementAndGet()
            peak = maxOf(peak, now)
            delay(10)
            inflight.decrementAndGet()
            if (i % 4 == 0) error("boom $i")
        }
        assertEquals(listOf(0, 4, 8), result.failed)
        assertEquals(7, result.succeeded.size)
        assertTrue("peak=$peak", peak in 2..3)
    }

    @Test
    fun `执行器空列表直接返回`() = runTest {
        val r = runBounded(emptyList<Int>(), 4) { error("不该被调用") }
        assertTrue(r.succeeded.isEmpty() && r.failed.isEmpty())
    }
}
