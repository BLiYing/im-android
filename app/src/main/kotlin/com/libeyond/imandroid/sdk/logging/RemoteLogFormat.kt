package com.libeyond.imandroid.sdk.logging

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 开发期日志回传的**纯逻辑**：一行 NDJSON 怎么拼、缓冲怎么限界。IO 在 [RemoteLogSink]。
 *
 * 格式对齐 iOS `IMRemoteLogSink`（`ts / level / dev / uid / msg`），另带 `tag` 与 `plat`：
 * 三端日志汇到 IMServer 的同一个 `-dev-logsink` 文件里，`grep '"plat":"android"'` 就能只看本端。
 * `dev` / `uid` 是 grep 过滤标签，**不是安全边界**（LOGGING.md §7.2）。
 */
internal object RemoteLogFormat {

    /** [uid] 已在 fields 里时（[IMLog] 会塞进去）不再重复渲染进 msg。 */
    fun line(
        tsMs: Long,
        level: IMLog.Level,
        dev: String,
        tag: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable?,
    ): String {
        val uid = fields["uid"]?.toString().orEmpty().ifEmpty { "-" }
        return buildJsonObject {
            put("ts", tsMs)
            put("level", level.label)
            put("dev", dev)
            put("uid", uid)
            put("plat", "android")
            put("tag", tag)
            put("msg", render(tag, event, fields, throwable))
        }.toString()
    }

    /** 与 iOS 的 `[tag] 内容` 同形：`[IM.WS] ws_connected host=… attempt=…`。 */
    internal fun render(tag: String, event: String, fields: Map<String, Any?>, throwable: Throwable?): String =
        buildString {
            append('[').append(tag).append("] ").append(event)
            fields.forEach { (k, v) -> if (k != "uid") append(' ').append(k).append('=').append(v) }
            if (throwable != null) append(" err=").append(throwable.javaClass.simpleName).append(':').append(throwable.message)
        }
}

/**
 * 有界缓冲：超过 [cap] 丢**最旧**的（服务器不可达时内存不能无限涨），一次最多取 [batch] 行。
 * 非线程安全——调用方（[RemoteLogSink]）自己串行化。
 */
internal class LogBuffer(private val cap: Int = 1000, private val batch: Int = 200) {
    private val lines = ArrayDeque<String>()

    val size: Int get() = lines.size

    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > cap) lines.removeFirst()
    }

    /** 取走最多一批，按先后顺序。空则返回空表。 */
    fun takeBatch(): List<String> {
        val n = minOf(batch, lines.size)
        return List(n) { lines.removeFirst() }
    }
}
