package com.libeyond.imandroid.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 导出 ZIP 的进程级下载器：协程挂在应用作用域（[scope]，不是页面组合），旋转 / 离开页面都不中断；
 * 只有 [cancel]（用户点取消）或退出登录才中断，并清掉半截文件。
 *
 * [run] 负责「换下载地址 + 流式写入 target」（SAF 流用 `use` 关闭，取消时同样关闭）；[discard] 删 target。
 * target 用字符串（Uri.toString）以便脱离 Android 单测。
 */
class ExportDownloader(
    private val scope: CoroutineScope,
    private val run: suspend (target: String, jobId: String?, report: (done: Long, total: Long) -> Unit) -> Unit,
    private val discard: suspend (target: String) -> Unit,
    /** 下载失败（非取消）时回调，用于记日志；默认不做事。 */
    private val onFailure: (Throwable) -> Unit = {},
) {
    enum class State { Idle, Running, Succeeded, Failed }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    /** 下载中的字节进度（已节流）；非 Running 时为 null。 */
    val progress: StateFlow<DownloadProgress?> = _progress
    private var current: Job? = null
    private val lock = Any()

    /** 开始下载；已有进行中的返回 false（调用方应丢弃新建的空文件）。 */
    @OptIn(DelicateCoroutinesApi::class)
    fun start(target: String, jobId: String?): Boolean = synchronized(lock) {
        if (_state.value == State.Running) return false
        _state.value = State.Running
        _progress.value = null
        var self: Job? = null
        // ATOMIC：即便在首次调度前就被 cancel，body 也会进入，execute 才能走到 discard 清空壳。
        self = scope.launch(start = CoroutineStart.ATOMIC) { execute(target, jobId) { synchronized(lock) { current === self } } }
        current = self
        true
    }

    private suspend fun execute(target: String, jobId: String?, isCurrent: () -> Boolean) {
        var ok = false
        try {
            currentCoroutineContext().ensureActive() // 已被取消：不碰下载，直接走清理
            val throttle = ProgressThrottle()
            run(target, jobId) { done, total ->
                val p = DownloadProgress(done, total)
                if (isCurrent() && throttle.shouldEmit(p)) _progress.value = p
            }
            ok = true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { discard(target) }
            throw e
        } catch (e: Exception) {
            onFailure(e) // 失败：记录原因，清文件后报 Failed
        }
        if (!ok) withContext(NonCancellable) { discard(target) }
        synchronized(lock) { if (isCurrent()) { _progress.value = null; _state.value = if (ok) State.Succeeded else State.Failed } }
    }

    /** 用户取消 / 退出登录：中断并（异步）清半截文件，回到 Idle，不产生结果。 */
    fun cancel() = synchronized(lock) {
        current?.cancel()
        current = null
        _progress.value = null
        _state.value = State.Idle
    }

    /** UI 展示完 toast 后调用，Succeeded / Failed 回 Idle；Running 不动。 */
    fun consumeResult() {
        _state.value.let { s ->
            if (s == State.Succeeded || s == State.Failed) _state.compareAndSet(s, State.Idle)
        }
    }
}
