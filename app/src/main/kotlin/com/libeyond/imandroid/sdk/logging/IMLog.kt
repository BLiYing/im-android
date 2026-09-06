package com.libeyond.imandroid.sdk.logging

import com.libeyond.imandroid.BuildConfig

/**
 * 本端**唯一**日志入口，对齐 iOS 的 `IMLog*WithTag` 宏与 Web 的 `logger.*`。
 *
 * ## 红线（`../IMServer/docs/LOGGING.md` §7.1）
 * **业务代码禁止直接用 `android.util.Log` / `println` / `System.out`**，本文件自身除外。
 * 绕过统一入口写的日志无法按字段过滤、无法脱敏、无法与另外两端按 Request ID 对账。
 *
 * 自查（应为 0 条）：
 * ```
 * grep -rnE "android\.util\.Log|\bprintln\(|System\.out" app/src/main/kotlin --include="*.kt" | grep -v IMLog.kt
 * ```
 *
 * ## 刻意不做兼容桥接
 * Go 后端曾把标准 `log` 桥接到 slog，违规日志照样输出成 JSON、只多一个 `component:legacy` 标记
 * ——**"看起来没坏"，于是累计 54 处无人察觉**，直到 2026-08-05 才清理。iOS/Web 没有这层兜底，
 * 写错了立刻显眼，反而一直干净。本端照 iOS/Web 办：不加桥接，写错就是写错。
 *
 * ## 用法
 * ```kotlin
 * private val log = IMLog.tag("IM.WS")
 * log.i("ws_connected", "host" to host, "attempt" to attempt)
 * log.e("ws_handshake_failed", t, "code" to 401)
 * ```
 * 事件名（第一个参数）要**稳定、可 grep**，别把变量拼进去——变量放 fields。
 */
object IMLog {

    enum class Level(val label: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error") }

    /** 单条正文上限 16KB，超出截断并标注（LOGGING.md §5）。 */
    private const val MAX_FIELD_LEN = 16 * 1024

    /**
     * 任何环境都不得明文入日志的字段（LOGGING.md §5）。
     * 命中即替换为 `***`。新增敏感字段先加这里、再补测试。
     */
    private val SENSITIVE_KEYS = setOf(
        "password", "passcode", "token", "access_token", "refresh_token",
        "authorization", "cookie", "secret", "credential", "phone", "jwt",
    )

    /** 日志落点。默认只有 logcat；dev 汇聚 sink（POST /__devlog）后续按 §7.2 接上。 */
    fun interface Sink {
        fun write(level: Level, tag: String, event: String, fields: Map<String, Any?>, throwable: Throwable?)
    }

    private val sinks = mutableListOf<Sink>(LogcatSink)

    /** 当前登录账号，用于多账号日志分离（LOGGING.md §7.2）。未登录为 `-`。 */
    @Volatile
    var currentUid: String = "-"

    /** 最低输出级别：Debug 构建全开，Release 只留 INFO 及以上。 */
    @Volatile
    var minLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO

    fun addSink(sink: Sink) { synchronized(sinks) { sinks.add(sink) } }

    fun tag(tag: String): Tagged = Tagged(tag)

    /** 绑定了 tag 的记录器——业务代码持有它，不直接调 [IMLog]。 */
    class Tagged internal constructor(private val tag: String) {
        fun d(event: String, vararg fields: Pair<String, Any?>) = emit(Level.DEBUG, tag, event, fields, null)
        fun i(event: String, vararg fields: Pair<String, Any?>) = emit(Level.INFO, tag, event, fields, null)
        fun w(event: String, vararg fields: Pair<String, Any?>) = emit(Level.WARN, tag, event, fields, null)
        fun e(event: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) =
            emit(Level.ERROR, tag, event, fields, throwable)
    }

    private fun emit(
        level: Level,
        tag: String,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        throwable: Throwable?,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val safe = redact(fields.toMap()) + ("uid" to currentUid)
        val snapshot = synchronized(sinks) { sinks.toList() }
        snapshot.forEach { it.write(level, tag, event, safe, throwable) }
    }

    /**
     * 脱敏 + 截断。**纯函数**，便于单测（LOGGING.md §7.5 要求补脱敏与超长边界测试）。
     * 内部可见以供测试直接调用。
     */
    internal fun redact(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { (k, v) ->
        when {
            k.lowercase() in SENSITIVE_KEYS -> "***"
            v is String && v.length > MAX_FIELD_LEN ->
                v.take(MAX_FIELD_LEN) + "…<truncated ${v.length - MAX_FIELD_LEN}>"
            else -> v
        }
    }

    /** logcat 落点。**本文件是唯一允许 import android.util.Log 的地方**。 */
    private object LogcatSink : Sink {
        override fun write(
            level: Level,
            tag: String,
            event: String,
            fields: Map<String, Any?>,
            throwable: Throwable?,
        ) {
            val line = buildString {
                append(event)
                if (fields.isNotEmpty()) {
                    fields.entries.joinTo(this, prefix = " {", postfix = "}") { "${it.key}=${it.value}" }
                }
            }
            when (level) {
                Level.DEBUG -> android.util.Log.d(tag, line, throwable)
                Level.INFO -> android.util.Log.i(tag, line, throwable)
                Level.WARN -> android.util.Log.w(tag, line, throwable)
                Level.ERROR -> android.util.Log.e(tag, line, throwable)
            }
        }
    }
}
