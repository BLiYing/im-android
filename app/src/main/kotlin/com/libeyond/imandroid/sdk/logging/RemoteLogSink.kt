package com.libeyond.imandroid.sdk.logging

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 开发期日志回传（对齐 iOS `IMRemoteLogSink` / Web 的 vite `devLogSink`，LOGGING.md §7.2）：
 * 攒批（2 秒一发、一批 ≤200 行）`POST http(s)://<IMServer>/__devlog`，服务端把 NDJSON 原样追加到
 * `-dev-logsink` 指定的文件。**仅 Debug 构建注册**（见 `IMApp`）。
 *
 * - **独立的裸 OkHttp**，不走 `HttpClient`：否则每次上报再生成一条 HTTP 日志，自我循环。
 * - **这里面绝不能调 [IMLog]**，同理。失败就丢这一批，不重试、不记日志。
 * - 服务器地址惰性读（[host]）：换服务器 / 登录之后才知道，取不到就继续攒。
 */
internal class RemoteLogSink(
    private val host: () -> String,
    private val useTls: Boolean,
    /** 设备短 id（多台真机汇聚到同一文件时区分来源）。 */
    private val deviceTag: String,
) : IMLog.Sink {

    private val buffer = LogBuffer()
    private val lock = Any()
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "im-remote-log").apply { isDaemon = true }
    }

    init {
        flusher.scheduleWithFixedDelay({ flush() }, FLUSH_SEC, FLUSH_SEC, TimeUnit.SECONDS)
    }

    override fun write(
        level: IMLog.Level,
        tag: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        val line = RemoteLogFormat.line(System.currentTimeMillis(), level, deviceTag, tag, event, fields, throwable)
        synchronized(lock) { buffer.add(line) }
    }

    private fun flush() {
        val h = host()
        if (h.isEmpty()) return // 还不知道服务器：继续攒，别丢
        val batch = synchronized(lock) { buffer.takeBatch() }
        if (batch.isEmpty()) return
        val url = (if (useTls) "https://" else "http://") + h + "/__devlog"
        val request = runCatching {
            Request.Builder().url(url)
                .post(batch.joinToString("\n").toRequestBody(NDJSON))
                .build()
        }.getOrNull() ?: return // host 非法：这一批丢掉，别死攒
        // fire-and-forget：结果不关心、不重试。
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }

    private companion object {
        const val FLUSH_SEC = 2L
        val NDJSON = "application/x-ndjson".toMediaType()
    }
}
