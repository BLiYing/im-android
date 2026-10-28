package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ExportJob
import com.libeyond.imandroid.sdk.api.ExportStatus

/** 「导出我的数据」页当前该画哪一态（DATA_EXPORT_DESIGN §1）。 */
sealed interface ExportView {
    /** 配置态；[expiredNote] = 上一份文件已过期，顶部提示重新导出。 */
    data class Config(val expiredNote: Boolean = false) : ExportView
    data class Running(val done: Int, val total: Int) : ExportView
    data class Done(val sizeBytes: Long, val convs: Int, val msgs: Long, val expiresAt: Long) : ExportView
    /** 冷却：[hours] 已向上取整、至少 1。 */
    data class Cooldown(val hours: Int) : ExportView
    data object Failed : ExportView
}

/** 纯规则：任务对象 -> 视图态、冷却小时数、按钮可用性、进度。无 Android 依赖，便于单测。 */
object ExportRules {
    const val COOLDOWN_MS = 24L * 3600 * 1000
    const val FORMAT_HTML = "html"
    const val FORMAT_JSON = "json"

    /** 冷却剩余秒 -> 小时，**向上取整**（17h01m 显示 18）；已无剩余为 0，有剩余至少 1。 */
    fun cooldownHours(remainingSec: Long): Int = if (remainingSec <= 0) 0 else ((remainingSec + 3599) / 3600).toInt()

    /** 服务端时间戳（毫秒；小于 1e12 视为秒）-> 毫秒；0 / 负数保持 0。 */
    fun toMillis(v: Long): Long = if (v <= 0) 0 else if (v < 1_000_000_000_000L) v * 1000 else v

    /**
     * 当前视图态。优先级：进行中 > 未过期的完成 > 冷却（200011 回包记下的截止时刻，或 expired 任务自 finished_at 起 24h）
     * > 失败（用户点「重试」后 [retrying] 进配置） > 配置。
     */
    fun viewOf(job: ExportJob?, cooldownUntilMs: Long, nowMs: Long, retrying: Boolean): ExportView {
        val state = job?.state
        if (job != null && state != null && state.isActive) {
            return ExportView.Running(job.progress.doneConvs, job.progress.totalConvs)
        }
        if (job != null && state == ExportStatus.Done && !isExpired(job, nowMs)) {
            val r = job.result
            return ExportView.Done(r?.sizeBytes ?: 0, r?.convCount ?: 0, r?.msgCount ?: 0L, toMillis(job.expiresAt))
        }
        val derived = if (job != null && (state == ExportStatus.Expired || state == ExportStatus.Done)) {
            toMillis(job.finishedAt).takeIf { it > 0 }?.plus(COOLDOWN_MS) ?: 0
        } else 0
        val until = maxOf(cooldownUntilMs, derived)
        if (until > nowMs) return ExportView.Cooldown(cooldownHours((until - nowMs + 999) / 1000))
        if (state == ExportStatus.Failed && !retrying) return ExportView.Failed
        return ExportView.Config(expiredNote = state == ExportStatus.Expired || state == ExportStatus.Done)
    }

    /**
     * 视图下一次「仅因时间流逝」而变的时刻（毫秒；0=不会变）：完成态的 expires_at、本地冷却截止、
     * 成功任务 finished_at+24h 冷却结束。与 Web `nextViewChangeAt` 等价。
     */
    fun nextViewChangeAt(job: ExportJob?, nowMs: Long, cooldownUntilMs: Long): Long {
        val c = mutableListOf<Long>()
        val state = job?.state
        if (job != null && state == ExportStatus.Done) toMillis(job.expiresAt).takeIf { it > nowMs }?.let(c::add)
        if (cooldownUntilMs > nowMs) c.add(cooldownUntilMs)
        if (job != null && (state == ExportStatus.Done || state == ExportStatus.Expired)) {
            toMillis(job.finishedAt).takeIf { it > 0 }?.plus(COOLDOWN_MS)?.takeIf { it > nowMs }?.let(c::add)
        }
        return c.minOrNull() ?: 0L
    }

    private fun isExpired(job: ExportJob, nowMs: Long): Boolean {
        val exp = toMillis(job.expiresAt)
        return exp in 1..nowMs
    }

    /** 「开始导出」可用：密码非空、至少勾一种格式、不在提交中。 */
    fun canStart(password: String, html: Boolean, json: Boolean, submitting: Boolean): Boolean =
        password.isNotEmpty() && (html || json) && !submitting

    /** 选中的格式（固定顺序 html, json）。 */
    fun formats(html: Boolean, json: Boolean): List<String> =
        buildList { if (html) add(FORMAT_HTML); if (json) add(FORMAT_JSON) }

    /** 进度条 0..1；total 为 0（还没统计出来）给 0。 */
    fun progressFraction(done: Int, total: Int): Float =
        if (total <= 0) 0f else (done.coerceIn(0, total).toFloat() / total)

    /** 下载另存为的默认文件名 `im-export-YYYYMMDD.zip`。 */
    fun fileName(year: Int, month: Int, day: Int): String = "im-export-%04d%02d%02d.zip".format(year, month, day)
}
