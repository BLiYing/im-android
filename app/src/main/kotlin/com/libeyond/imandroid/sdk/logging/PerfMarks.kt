package com.libeyond.imandroid.sdk.logging

/**
 * 离线积压压测（B0 / B5，`../IMServer/docs/ops/LOAD_TESTING.md`）的**低频时间戳埋点**。
 *
 * 事件名与字段对齐 iOS（`app_launched` / `conv_list_visible` / `chat_open` / `chat_initial_position` /
 * `jump_bottom_begin|done`，字段 `conv_id`），读数脚本按时间戳相减（`scripts/b0-android-timings.py`）。
 * 每个事件都带 `heap_mb`（已用 Java 堆），用来看大积压下的内存走势。
 *
 * **口径（与 iOS 不可横比，只给 Android 自己的 B5 当参照）**：
 * - `conv_list_visible`：本地库第一份会话列表**发出**时，不含随后的绘制；
 * - `chat_initial_position`：首屏定位指令发出后的**下一帧**；
 * - `jump_bottom_done`：贴底收敛完成（含渲染），比 iOS 那个「应答到达时」的测点**晚**——B5 复压必须沿用本口径。
 *
 * 只写日志，不改任何行为；Release 构建没有回传 sink，开销就是几次 `Runtime` 读数。
 */
object PerfMarks {
    private val log = IMLog.tag("IM.Perf")

    private var listVisibleLogged = false
    private val jumpPending = HashSet<String>()

    fun appLaunched() = log.i("app_launched", *heap())

    /** 只记第一次（冷启动口径）。 */
    fun conversationListVisible() {
        if (listVisibleLogged) return
        listVisibleLogged = true
        log.i("conv_list_visible", *heap())
    }

    fun chatOpen(convId: String) = log.i("chat_open", "conv_id" to convId, *heap())

    fun chatInitialPosition(convId: String) = log.i("chat_initial_position", "conv_id" to convId, *heap())

    fun jumpBottomBegin(convId: String) {
        synchronized(jumpPending) { jumpPending += convId }
        log.i("jump_bottom_begin", "conv_id" to convId, *heap())
    }

    /** 没有对应的 begin 就不记：自己发消息、新消息跟底也会走到贴底收敛，不是 ↓。 */
    fun jumpBottomDone(convId: String) {
        val had = synchronized(jumpPending) { jumpPending.remove(convId) }
        if (had) log.i("jump_bottom_done", "conv_id" to convId, *heap())
    }

    /** 仅测试：清掉进程内状态。 */
    internal fun resetForTest() {
        listVisibleLogged = false
        synchronized(jumpPending) { jumpPending.clear() }
    }

    private fun heap(): Array<Pair<String, Any?>> {
        val rt = Runtime.getRuntime()
        return arrayOf("heap_mb" to (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
    }
}
