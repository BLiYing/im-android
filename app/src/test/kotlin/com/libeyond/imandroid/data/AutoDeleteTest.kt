package com.libeyond.imandroid.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动删除「是否过期」的判据，读**三端共用向量** `auto_delete.json`（真源在 IMServer `docs/conformance/`，
 * `src/test/resources/` 是拷贝；真源在旁边时会比对，防漂移）。改口径先改向量。设计 AUTO_DELETE_DESIGN §2。
 */
class AutoDeleteTest {
    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("auto_delete.json")) { "缺 auto_delete.json" }.readText()
    private val root = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `向量与真源一致`() {
        val src = File("../../IMServer/docs/conformance/auto_delete.json")
        if (src.exists()) assertEquals("测试资源与真源漂移，重新拷贝", src.readText().trim(), text.trim())
    }

    @Test
    fun `expired 向量`() {
        val cases = root["expired"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (el in cases) {
            val c = el.jsonObject
            val got = AutoDelete.expired(c["ts"]!!.jsonPrimitive.long, c["sec"]!!.jsonPrimitive.int, c["now"]!!.jsonPrimitive.long)
            assertEquals(c["name"]!!.jsonPrimitive.content, c["expect"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun `floorTs 向量`() {
        val cases = root["floor_ts"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (el in cases) {
            val c = el.jsonObject
            assertEquals(c["expect"]!!.jsonPrimitive.long, AutoDelete.floorTs(c["sec"]!!.jsonPrimitive.int, c["now"]!!.jsonPrimitive.long))
        }
    }

    @Test
    fun `floorTs 与 expired 同口径：ts 等于 floor 即过期，比它大 1 不过期`() {
        val now = 200_000_000L
        val floor = AutoDelete.floorTs(86_400, now)
        assertTrue(AutoDelete.expired(floor, 86_400, now))
        assertFalse(AutoDelete.expired(floor + 1, 86_400, now))
    }

    @Test
    fun `档位白名单与向量一致`() {
        assertEquals(root["valid_sec"]!!.jsonArray.map { it.jsonPrimitive.int }, AutoDelete.TIERS)
        root["valid_sec"]!!.jsonArray.forEach { assertTrue(AutoDelete.isValid(it.jsonPrimitive.int)) }
        root["invalid_sec"]!!.jsonArray.forEach { assertFalse(AutoDelete.isValid(it.jsonPrimitive.int)) }
    }

    /** 设计 §3.3 C：新档位>0 且（旧=关 或 新<旧）才弹确认；关闭、放宽、不变都直接保存。 */
    @Test
    fun `确认框触发条件`() {
        assertTrue(AutoDelete.needsConfirm(0, 86_400))
        assertTrue(AutoDelete.needsConfirm(0, 2_592_000))
        assertTrue(AutoDelete.needsConfirm(604_800, 86_400))
        assertTrue(AutoDelete.needsConfirm(2_592_000, 604_800))
        assertFalse(AutoDelete.needsConfirm(86_400, 0))
        assertFalse(AutoDelete.needsConfirm(86_400, 604_800))
        assertFalse(AutoDelete.needsConfirm(604_800, 604_800))
        assertFalse(AutoDelete.needsConfirm(0, 0))
    }

    @Test
    fun `入库过滤：关闭原样放行，开启丢过期项`() {
        val ts = listOf(1_000L, 100_000_000L, 199_999_000L)
        assertEquals(ts, AutoDelete.dropExpired(ts, 0, 200_000_000L) { it })
        assertEquals(listOf(199_999_000L), AutoDelete.dropExpired(ts, 86_400, 200_000_000L) { it })
        assertEquals(ts, AutoDelete.dropExpired(ts, 604_800, 200_000_000L) { it }) // 1 周档：now 才 2 亿毫秒，全未到期
    }
}
