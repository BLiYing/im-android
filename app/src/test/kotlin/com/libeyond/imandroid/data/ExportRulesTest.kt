package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ExportJob
import com.libeyond.imandroid.sdk.api.ExportProgress
import com.libeyond.imandroid.sdk.api.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRulesTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    private fun job(status: String, finished: Long = 0, expires: Long = 0, done: Int = 0, total: Int = 0) = ExportJob(
        id = "j1", status = status, progress = ExportProgress(done, total),
        result = ExportResult(134_217_728, 12, 8431), finishedAt = finished, expiresAt = expires,
    )

    private fun view(j: ExportJob?, cd: Long = 0, retrying: Boolean = false) = ExportRules.viewOf(j, cd, now, retrying)

    @Test fun `无任务与终态回配置态`() {
        assertEquals(ExportView.Config(), view(null))
        assertEquals(ExportView.Config(), view(job("canceled")))
        assertEquals(ExportView.Config(), view(job("bogus_future_status")))
    }

    @Test fun `排队与运行中是进行中并带进度`() {
        assertEquals(ExportView.Running(3, 12), view(job("running", done = 3, total = 12)))
        assertEquals(ExportView.Running(0, 0), view(job("queued")))
    }

    @Test fun `完成未过期是完成态带摘要`() {
        assertEquals(ExportView.Done(134_217_728, 12, 8431, now + h), view(job("done", finished = now - h, expires = now + h)))
    }

    @Test fun `完成但文件已过期不再给下载`() {
        // 过期时刻已到而状态还没被服务端刷成 expired：按过期处理，且 24h 冷却自 finished_at 起算
        val v = view(job("done", finished = now - 24 * h + h, expires = now - 1))
        assertEquals(ExportView.Cooldown(1), v)
    }

    @Test fun `过期任务且冷却已过回配置态带过期提示`() {
        assertEquals(ExportView.Config(expiredNote = true), view(job("expired", finished = now - 25 * h, expires = now - h)))
    }

    @Test fun `过期任务冷却未过显示冷却`() {
        assertEquals(ExportView.Cooldown(18), view(job("expired", finished = now - 6 * h, expires = now - 1)))
    }

    @Test fun `失败态与重试后回配置`() {
        assertEquals(ExportView.Failed, view(job("failed")))
        assertEquals(ExportView.Config(), view(job("failed"), retrying = true))
    }

    @Test fun `200011 回包记下的冷却压过失败与配置，进行中仍优先`() {
        assertEquals(ExportView.Cooldown(2), view(null, cd = now + 2 * h))
        assertEquals(ExportView.Running(1, 2), view(job("running", done = 1, total = 2), cd = now + 2 * h))
    }

    @Test fun `冷却小时数向上取整`() {
        assertEquals(0, ExportRules.cooldownHours(0))
        assertEquals(0, ExportRules.cooldownHours(-5))
        assertEquals(1, ExportRules.cooldownHours(1))
        assertEquals(1, ExportRules.cooldownHours(3600))
        assertEquals(2, ExportRules.cooldownHours(3601))
        assertEquals(18, ExportRules.cooldownHours(17 * 3600 + 60))
        assertEquals(24, ExportRules.cooldownHours(86_400))
    }

    @Test fun `按钮可用性 密码空或无格式或提交中均置灰`() {
        assertTrue(ExportRules.canStart("pw", html = true, json = false, submitting = false))
        assertTrue(ExportRules.canStart("pw", html = false, json = true, submitting = false))
        assertFalse(ExportRules.canStart("", html = true, json = true, submitting = false))
        assertFalse(ExportRules.canStart("pw", html = false, json = false, submitting = false))
        assertFalse(ExportRules.canStart("pw", html = true, json = true, submitting = true))
    }

    @Test fun `格式顺序与进度比例与文件名`() {
        assertEquals(listOf("html", "json"), ExportRules.formats(true, true))
        assertEquals(listOf("json"), ExportRules.formats(false, true))
        assertEquals(0f, ExportRules.progressFraction(0, 0), 0f)
        assertEquals(0.25f, ExportRules.progressFraction(3, 12), 0f)
        assertEquals(1f, ExportRules.progressFraction(15, 12), 0f)
        assertEquals("im-export-20261026.zip", ExportRules.fileName(2026, 10, 26))
        assertEquals(1_700_000_000_000L, ExportRules.toMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, ExportRules.toMillis(1_700_000_000_000L))
    }

    @Test fun `下一次视图变化时刻 取完成到期 本地冷却 finished+24h 的最小者`() {
        assertEquals(now + h, ExportRules.nextViewChangeAt(job("done", finished = now - h, expires = now + h), now, 0))
        // 完成任务：到期(1h 后) 早于 finished+24h(23h 后)
        assertEquals(now + 2 * h, ExportRules.nextViewChangeAt(job("done", finished = now - h, expires = now + 5 * h), now, now + 2 * h))
        // 过期任务只剩冷却结束
        assertEquals(now + 18 * h, ExportRules.nextViewChangeAt(job("expired", finished = now - 6 * h, expires = now - 1), now, 0))
        // 秒级时间戳同样换算
        assertEquals(now + h, ExportRules.nextViewChangeAt(job("done", finished = (now - h) / 1000, expires = (now + h) / 1000), now, 0))
    }

    @Test fun `下一次视图变化时刻 无变化为 0`() {
        assertEquals(0L, ExportRules.nextViewChangeAt(null, now, 0))
        assertEquals(0L, ExportRules.nextViewChangeAt(job("running"), now, 0))
        assertEquals(0L, ExportRules.nextViewChangeAt(job("failed", finished = now - h), now, 0))
        assertEquals(0L, ExportRules.nextViewChangeAt(job("expired", finished = now - 30 * h), now, now - 1))
        // 进行中不因 expires 变化
        assertEquals(0L, ExportRules.nextViewChangeAt(job("queued", expires = now + h), now, 0))
    }
}
