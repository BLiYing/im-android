package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(cases.size >= 20)
        for (el in cases) {
            val c = el.jsonObject
            val got = AutoDelete.expired(
                c["ts"]!!.jsonPrimitive.long, c["sec"]!!.jsonPrimitive.int, c["from"]!!.jsonPrimitive.long, c["now"]!!.jsonPrimitive.long,
            )
            assertEquals(c["name"]!!.jsonPrimitive.content, c["expect"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun `window 向量`() {
        val cases = root["window"]!!.jsonArray
        assertTrue(cases.size >= 5)
        for (el in cases) {
            val c = el.jsonObject
            val got = AutoDelete.window(c["sec"]!!.jsonPrimitive.int, c["from"]!!.jsonPrimitive.long, c["now"]!!.jsonPrimitive.long)
            val name = c["name"]!!.jsonPrimitive.content
            val expect = c["expect"]
            if (expect == null || expect is JsonNull) assertNull(name, got)
            else assertEquals(name, expect.jsonObject["lo"]!!.jsonPrimitive.long to expect.jsonObject["hi"]!!.jsonPrimitive.long, got?.let { it.first to it.last })
        }
    }

    @Test
    fun `from_after_system_message 向量`() {
        val cases = root["from_after_system_message"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (el in cases) {
            val c = el.jsonObject
            assertEquals(c["expect_from"]!!.jsonPrimitive.long, AutoDelete.fromAfterSystemMessage(c["sys_ts"]!!.jsonPrimitive.long, c["sec"]!!.jsonPrimitive.int))
        }
    }

    @Test
    fun `窗口与 expired 同口径：窗口两端含，之外不过期`() {
        val (sec, from, now) = Triple(86_400, 50_000_000L, 200_000_000L)
        val w = AutoDelete.window(sec, from, now)!!
        assertTrue(AutoDelete.expired(w.first, sec, from, now))
        assertTrue(AutoDelete.expired(w.last, sec, from, now))
        assertFalse(AutoDelete.expired(w.first - 1, sec, from, now))
        assertFalse(AutoDelete.expired(w.last + 1, sec, from, now))
    }

    @Test
    fun `档位白名单与向量一致`() {
        assertEquals(root["valid_sec"]!!.jsonArray.map { it.jsonPrimitive.int }, AutoDelete.TIERS)
        root["valid_sec"]!!.jsonArray.forEach { assertTrue(AutoDelete.isValid(it.jsonPrimitive.int)) }
        root["invalid_sec"]!!.jsonArray.forEach { assertFalse(AutoDelete.isValid(it.jsonPrimitive.int)) }
    }

    /** v2 设计 §3.3 C：新档位>0 且 ≠ 当前（开启 / 改档位都弹一次说明）；关闭、不变不弹。 */
    @Test
    fun `确认框触发条件`() {
        assertTrue(AutoDelete.needsConfirm(0, 86_400))
        assertTrue(AutoDelete.needsConfirm(604_800, 86_400))
        assertTrue(AutoDelete.needsConfirm(86_400, 604_800)) // v1 放宽不弹，v2 改档位也弹
        assertFalse(AutoDelete.needsConfirm(86_400, 0))
        assertFalse(AutoDelete.needsConfirm(604_800, 604_800))
        assertFalse(AutoDelete.needsConfirm(0, 0))
    }

    /** §3.2：sec 或 from 变化且 sec>0 → 扫该会话；关闭 / 没变不扫。 */
    @Test
    fun `清理触发条件`() {
        assertTrue(AutoDelete.needsSweep(0, 0, 86_400, 1_000))
        assertTrue(AutoDelete.needsSweep(86_400, 1_000, 86_400, 2_000)) // 只 from 变
        assertTrue(AutoDelete.needsSweep(86_400, 1_000, 604_800, 1_000)) // 只 sec 变
        assertFalse(AutoDelete.needsSweep(86_400, 1_000, 86_400, 1_000))
        assertFalse(AutoDelete.needsSweep(86_400, 1_000, 0, 0)) // 关闭
        assertFalse(AutoDelete.needsSweep(0, 0, 0, 0))
    }

    @Test
    fun `入库过滤：关闭原样放行，开启只丢窗口内的，from 之前的保留`() {
        val ts = listOf(1_000L, 100_000_000L, 199_999_000L)
        assertEquals(ts, AutoDelete.dropExpired(ts, 0, 0, 200_000_000L) { it })
        assertEquals(listOf(1_000L, 199_999_000L), AutoDelete.dropExpired(ts, 86_400, 50_000_000L, 200_000_000L) { it }) // 1_000 在 from 之前
        assertEquals(listOf(199_999_000L), AutoDelete.dropExpired(ts, 86_400, 0, 200_000_000L) { it })
        assertEquals(ts, AutoDelete.dropExpired(ts, 604_800, 0, 200_000_000L) { it }) // 1 周档：now 才 2 亿毫秒，全未到期
    }

    @Test
    fun `18 档列表升序且与向量一致`() {
        assertEquals(18, AutoDelete.CUSTOM_TIERS.size)
        assertEquals(AutoDelete.CUSTOM_TIERS.sorted(), AutoDelete.CUSTOM_TIERS)
        assertEquals(listOf(120, 3_600, 86_400, 172_800, 518_400, 604_800, 1_814_400, 2_592_000, 15_552_000, 31_536_000),
            listOf(0, 1, 2, 3, 7, 8, 10, 11, 16, 17).map { AutoDelete.CUSTOM_TIERS[it] })
        assertEquals(listOf(0) + AutoDelete.CUSTOM_TIERS, AutoDelete.TIERS)
        assertEquals(listOf(0, 120, 86_400, 604_800, 2_592_000), AutoDelete.PRESETS)
        assertTrue(AutoDelete.PRESETS.all { AutoDelete.isValid(it) })
    }

    @Test
    fun `折算优先级 年月周天时分`() {
        assertEquals(AutoDelete.Unit.MINUTE to 2, AutoDelete.unitOf(120))
        assertEquals(AutoDelete.Unit.HOUR to 1, AutoDelete.unitOf(3_600))
        assertEquals(AutoDelete.Unit.DAY to 1, AutoDelete.unitOf(86_400))
        assertEquals(AutoDelete.Unit.DAY to 6, AutoDelete.unitOf(518_400))
        assertEquals(AutoDelete.Unit.WEEK to 1, AutoDelete.unitOf(604_800)) // 7 天 = 1 周
        assertEquals(AutoDelete.Unit.WEEK to 3, AutoDelete.unitOf(1_814_400))
        assertEquals(AutoDelete.Unit.MONTH to 1, AutoDelete.unitOf(2_592_000)) // 30 天 = 1 个月，不是 4 周
        assertEquals(AutoDelete.Unit.MONTH to 6, AutoDelete.unitOf(15_552_000))
        assertEquals(AutoDelete.Unit.YEAR to 1, AutoDelete.unitOf(31_536_000))
        assertEquals(AutoDelete.Unit.MINUTE to 90, AutoDelete.unitOf(5_400)) // 非整小时 → 分钟
    }

    @Test
    fun `自定义行选中态 固定档归固定行 其余归自定义`() {
        AutoDelete.PRESETS.forEach { assertFalse(AutoDelete.isCustomSelected(it)) }
        assertTrue(AutoDelete.isCustomSelected(3_600))
        assertTrue(AutoDelete.isCustomSelected(259_200)) // 3 天
        assertTrue(AutoDelete.isCustomSelected(31_536_000))
        assertEquals(14, AutoDelete.CUSTOM_TIERS.count { AutoDelete.isCustomSelected(it) })
    }

    @Test
    fun `确认框触发 新档位含分钟与年`() {
        assertTrue(AutoDelete.needsConfirm(0, 120))
        assertTrue(AutoDelete.needsConfirm(31_536_000, 15_552_000))
        assertTrue(AutoDelete.needsConfirm(120, 3_600))
    }

    /** §3.3 B/C：谁能设 / 失败文案 / 确认框与页脚的单聊·群两套。 */
    @Test
    fun `群里只有群主和管理员能设 单聊任一方能设`() {
        assertTrue(AutoDelete.canSet(isGroup = false, iAmManager = false))
        assertTrue(AutoDelete.canSet(isGroup = true, iAmManager = true))
        assertFalse(AutoDelete.canSet(isGroup = true, iAmManager = false))
    }

    @Test
    fun `300204 映射到仅管理员可设 其它失败是保存失败`() {
        assertEquals(R.string.autodel_group_admin_only, AutoDelete.saveFailedRes(ErrCode.NO_GROUP_PERMISSION))
        assertEquals(R.string.autodel_save_failed, AutoDelete.saveFailedRes(ErrCode.MSG_OP_FORBIDDEN))
        assertEquals(R.string.autodel_save_failed, AutoDelete.saveFailedRes(null))
    }

    @Test
    fun `确认框正文与页脚 单聊群两套`() {
        assertEquals(R.string.autodel_confirm_message, AutoDelete.confirmMessageRes(isGroup = false))
        assertEquals(R.string.autodel_confirm_message_group, AutoDelete.confirmMessageRes(isGroup = true))
        assertEquals(R.string.autodel_conv_footer, AutoDelete.footerRes(isGroup = false))
        assertEquals(R.string.autodel_conv_footer_group, AutoDelete.footerRes(isGroup = true))
    }

    @Test
    fun `脏数据 from小于等于0 且 sec大于0 按关闭`() {
        assertEquals(0, AutoDelete.effectiveSec(120, 0L))
        assertEquals(0, AutoDelete.effectiveSec(120, -1L))
        assertEquals(120, AutoDelete.effectiveSec(120, 500L))
        assertEquals(0, AutoDelete.effectiveSec(0, 0L))
    }
}
