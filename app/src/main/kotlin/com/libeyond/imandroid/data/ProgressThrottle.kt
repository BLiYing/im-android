package com.libeyond.imandroid.data

/** 下载字节进度快照；[total] <= 0 表示总长度未知（不定长进度条）。 */
data class DownloadProgress(val done: Long, val total: Long) {
    val known: Boolean get() = total > 0
    val fraction: Float get() = if (known) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * 进度回调节流（DATA_EXPORT_DESIGN §5）：首次、完成、距上次发出 ≥ [minIntervalMs]、或进度较上次发出 ≥ [minFraction] 才放行。
 * 非线程安全，单个下载协程内使用。
 */
class ProgressThrottle(
    private val minIntervalMs: Long = 100,
    private val minFraction: Float = 0.01f,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastAt = Long.MIN_VALUE
    private var lastFraction = -1f

    fun shouldEmit(p: DownloadProgress): Boolean {
        val t = now()
        val emit = lastAt == Long.MIN_VALUE ||
            (p.known && p.done >= p.total) ||
            t - lastAt >= minIntervalMs ||
            (p.known && p.fraction - lastFraction >= minFraction)
        if (emit) { lastAt = t; lastFraction = p.fraction }
        return emit
    }
}
