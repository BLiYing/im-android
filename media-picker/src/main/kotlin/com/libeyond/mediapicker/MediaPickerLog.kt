package com.libeyond.mediapicker

/**
 * 日志接缝：本模块**不 import** app 的 `IMLog`，也**绝不**直接用 `android.util.Log`
 * （那是三端日志红线，见 IMServer `docs/LOGGING.md` §7.1）。
 *
 * 调用方注入一个把事件转发给自家统一日志入口的实现；不注入就静默丢弃。
 * 事件名与字段沿用本仓约定：`snake_case` 事件名 + 键值对，不拼句子。
 */
fun interface MediaPickerLog {
    fun log(level: Level, event: String, fields: Map<String, Any?>)

    enum class Level { DEBUG, WARN }

    companion object {
        /** 默认实现：什么都不做。**不是**打到 logcat——静默好过绕过红线。 */
        val None = MediaPickerLog { _, _, _ -> }
    }
}

internal fun MediaPickerLog.d(event: String, vararg f: Pair<String, Any?>) =
    log(MediaPickerLog.Level.DEBUG, event, f.toMap())

internal fun MediaPickerLog.w(event: String, vararg f: Pair<String, Any?>) =
    log(MediaPickerLog.Level.WARN, event, f.toMap())
