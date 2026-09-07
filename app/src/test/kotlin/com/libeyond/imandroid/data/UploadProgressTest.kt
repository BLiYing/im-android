package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UploadProgress] 的判据。重点在「不许提前显示 100%」和「一定要摘干净」。 */
class UploadProgressTest {

    @Test
    fun `没开始是 0`() {
        assertEquals(0, UploadProgress.percent(0, 100))
    }

    @Test
    fun `传完是 100`() {
        assertEquals(100, UploadProgress.percent(100, 100))
    }

    /**
     * **这条是本文件的核心**：向下取整，绝不四舍五入。
     * 99.9% 显示成 100% 会让用户以为传完了，而气泡还压着暗底不动——看着就是卡死。
     */
    @Test
    fun `差一个字节也不能显示 100`() {
        assertEquals(99, UploadProgress.percent(999, 1000))
        assertEquals(99, UploadProgress.percent(9_999_999, 10_000_000))
    }

    @Test
    fun `total 非法时回 0 而不是崩`() {
        assertEquals(0, UploadProgress.percent(50, 0))
        assertEquals(0, UploadProgress.percent(50, -1))
    }

    /** 服务端上限 2GB：`sent * 100` 必须在 Long 里算，用 Int 会溢出成负数。 */
    @Test
    fun `2GB 量级不溢出`() {
        val total = 2L * 1024 * 1024 * 1024
        assertEquals(50, UploadProgress.percent(total / 2, total))
        assertEquals(99, UploadProgress.percent(total - 1, total))
    }

    @Test
    fun `report 后能读到，clear 后读不到`() {
        val p = UploadProgress()
        p.report("cid-1", 30, 100)
        assertEquals(30, p.state.value["cid-1"])
        p.clear("cid-1")
        assertTrue(p.state.value.isEmpty())
    }

    /** 多条同时在传，互不干扰。 */
    @Test
    fun `多条并存互不覆盖`() {
        val p = UploadProgress()
        p.report("a", 10, 100)
        p.report("b", 80, 100)
        p.clear("a")
        assertEquals(mapOf("b" to 80), p.state.value)
    }

    /**
     * 同一百分比重复上报**不换实例**——分片回调很密，每次都让下游重组的话，
     * 传一个大文件就是整屏消息列表反复重建。
     *
     * 这条性质由 `StateFlow` 的 equals 合并提供，**不是**实现里某一道守卫
     * （写这版时确实手写过一道，变异验证发现去掉它测试照样绿 → 是死代码，已删）。
     * 测试留着钉行为：哪天换成 SharedFlow 或加了个时间戳字段，这里会立刻红。
     */
    @Test
    fun `同值重复上报不换实例`() {
        val p = UploadProgress()
        p.report("a", 50, 100)
        val first = p.state.value
        p.report("a", 50, 100)
        assertSame(first, p.state.value)
        p.report("a", 51, 100)
        assertEquals(51, p.state.value["a"])
    }

    /** 摘一条不存在的不该产生新实例，也不该抛。 */
    @Test
    fun `clear 不存在的条目是空操作`() {
        val p = UploadProgress()
        p.report("a", 50, 100)
        val first = p.state.value
        p.clear("nobody")
        assertSame(first, p.state.value)
    }
}
