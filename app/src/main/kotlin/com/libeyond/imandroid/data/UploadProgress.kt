package com.libeyond.imandroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 分片上传的进度，按 `client_msg_id` 索引。
 *
 * **刻意只活在内存里，不落库**：上传本身就不跨进程续传（`upload_id` 只活在这次调用里，
 * 见 current_task「已知坑」），进程被杀时传到一半的那条注定要重来——
 * 把百分比写进 Room 只会在下次冷启动时显示一条**永远停在 43% 的幽灵进度**，
 * 比没有进度更糟。
 *
 * 只覆盖**分片**那条路（视频 / 文件）。图片走整包上传、压缩后几百 KB，
 * 进度条闪一下就没了，接了反而抖。
 */
class UploadProgress {

    private val _state = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** clientMsgId → 百分比（0~100）。没有条目 = 不在上传。 */
    val state: StateFlow<Map<String, Int>> = _state

    /**
     * 上报进度。同一百分比重复上报**不会触发重组**——`StateFlow` 按 `equals` 合并，
     * 值相等时连订阅者都不通知。
     *
     * （这里原本还手写了一道 `if (cur[id] == p) cur else …` 的短路，变异验证时发现
     * 去掉它测试照样绿：那是死代码，`StateFlow` 已经覆盖了。删掉，但测试留着——
     * 钉的是「同值不重组」这个行为，不是某一行实现。）
     */
    fun report(clientMsgId: String, sent: Long, total: Long) {
        val p = percent(sent, total)
        _state.update { cur -> cur + (clientMsgId to p) }
    }

    /**
     * 摘掉一条。**成功、失败、取消都必须调**——留着就是一条永不消失的进度环。
     * 调用方用 `finally` 保证，别写在 happy path 上。
     */
    fun clear(clientMsgId: String) {
        _state.update { cur -> cur - clientMsgId }
    }

    companion object {
        /**
         * 已发字节 → 百分比。
         *
         * **向下取整，绝不四舍五入**：99.6% 显示成 100% 会让用户以为传完了、
         * 结果气泡还压着暗底不动，看着像卡死。只有真正 `sent >= total` 才是 100。
         *
         * `total <= 0` 回 0 而不是除零崩——调用方虽然已经挡过（分片协议要求正数大小），
         * 但这是个纯函数，不该假设调用方守规矩。
         */
        fun percent(sent: Long, total: Long): Int {
            if (total <= 0L || sent <= 0L) return 0
            if (sent >= total) return 100
            return ((sent * 100L) / total).toInt()
        }
    }
}
