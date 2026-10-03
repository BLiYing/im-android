package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Serializable
data class UploadResult(
    val url: String = "",
    @SerialName("content_type") val contentType: String = "",
    /** 实际写入字节数。 */
    val size: Long = 0,
)

/**
 * 文件上传（PROTOCOL §11 `POST /api/v1/upload`）。
 *
 * **单独一个 OkHttpClient**，不复用 [HttpClient] 的：
 * 上传要长得多的超时（大文件几分钟），而聊天的 REST 请求要短超时快失败。
 * 共用一个 client 就得在两者间选一个折中值，两头都不好。
 *
 * 服务端上限：图片 / 视频 / 文件均 **2GB**（`uploadLimitByKind`）；`?as=voice` 切 voice 白名单 + 16MB。
 * （本行 2026-09-07 更正：原文写「图片 20MB / 视频 100MB」，是服务端提额前的旧值。）
 * 超限回 `500001`，格式不支持回 `500002`。
 */
class UploadApi(
    private val http: HttpClient,
    private val tokenProvider: () -> String?,
) : UploadTransport {
    private val log = IMLog.tag("IM.Upload")

    private val ok = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /**
     * @param bytes 文件内容
     * @param fileName 原始文件名——服务端用它推扩展名过白名单，**不能瞎给**
     * @param mimeType
     * @param asVoice 走 voice 白名单（16MB 上限）
     */
    suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        asVoice: Boolean = false,
    ): UploadResult = post(
        path = "/api/v1/upload" + if (asVoice) "?as=voice" else "",
        bytes = bytes, fileName = fileName, mimeType = mimeType,
    )

    /**
     * 头像专用上传（`POST /api/v1/avatar`）。
     *
     * **不是** `/upload` 的一个参数而是独立接口，因为服务端对头像另有一套规矩：
     * 只收 jpg/png、上限 **2MB**、内容寻址去重、存独立目录且**永不参与清理**
     * （消息媒体会过期，头像不能——用户三年前设的头像不该有一天变成裂图）。
     * 超限回 500001，格式不对回 500002。
     */
    suspend fun uploadAvatar(
        bytes: ByteArray,
        fileName: String = "avatar.jpg",
        mimeType: String = "image/jpeg",
    ): UploadResult = post("/api/v1/avatar", bytes, fileName, mimeType)

    private suspend fun post(
        path: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): UploadResult = withContext(Dispatchers.IO) {
        val requestId = HttpClient.newRequestId()
        val url = http.baseUrl() + path

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()

        val req = Request.Builder()
            .url(url)
            .header(HttpClient.HEADER_REQUEST_ID, requestId)
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .post(body)
            .build()

        val raw: String
        val status: Int
        try {
            ok.newCall(req).execute().use { resp ->
                status = resp.code
                raw = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            log.w("upload_transport_failed", "name" to fileName, "reqId" to requestId)
            throw ApiException(ApiException.TRANSPORT, e.message ?: "上传失败", requestId, cause = e)
        }

        val env = try {
            ProtocolJson.decodeFromString(
                com.libeyond.imandroid.sdk.http.ApiEnvelope.serializer(), raw,
            )
        } catch (e: Exception) {
            throw ApiException(ApiException.TRANSPORT, "上传响应不是合法 JSON", requestId, status, e)
        }
        if (env.code != 0) {
            log.w("upload_biz_error", "code" to env.code, "reqId" to env.requestId)
            throw ApiException(env.code, env.message.ifEmpty { Str.s(R.string.net_error_upload_failed) }, env.requestId, status)
        }
        val data = env.data ?: throw ApiException(ApiException.TRANSPORT, "上传未返回 data", requestId)
        val r = ProtocolJson.decodeFromJsonElement(UploadResult.serializer(), data)
        log.i("upload_ok", "name" to fileName, "size" to r.size, "reqId" to env.requestId)
        r
    }

    // —— 分片上传（PROTOCOL：/upload/init → /upload/{id}/chunk → /upload/{id}/complete）——
    //
    // **为什么必须有**：上面的 upload() 把整个文件读进 ByteArray 再发。图片压缩后几百 KB
    // 无所谓，**视频不行**——服务端上限 2GB，真读进内存就是当场 OOM。
    // 分片全程流式：峰值内存 = 一片的大小（服务端给的 chunk_size，当前 8MB）。

    @Serializable
    private data class InitData(
        @SerialName("upload_id") val uploadId: String = "",
        val offset: Long = 0,
        @SerialName("chunk_size") val chunkSize: Long = 0,
    )

    @Serializable
    private data class OffsetData(
        @SerialName("upload_id") val uploadId: String = "",
        val offset: Long = 0,
        val size: Long = 0,
    )

    /**
     * 流式上传一个大文件。
     *
     * ### 已知限制（别当成做完了）
     * - **不跨进程续传**：`upload_id` 只活在这次调用里，杀进程要重传。
     *   服务端 `/status` 是支持续传的，缺的是把 `upload_id` 落库。
     * - **不并发**：服务端要求分片顺序追加（offset 必须等于已收长度），所以一片接一片。
     * - **offset 对不上就整体失败**：服务端遇到不同步会回当前 offset 而不报错，
     *   但本端输入是个只能向前读的流、没法回退重发，只能失败重来。
     *   静默继续会写出错位的文件，那比失败更糟。
     *
     * @param openStream 返回一个**从头开始**的新流；返回 null 视为读不到文件
     * @param onProgress 已发字节 / 总字节，在 IO 线程回调
     */
    suspend fun uploadStream(
        openStream: () -> InputStream?,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        require(totalBytes > 0) {
            "totalBytes 必须为正（0 说明 MediaStore 那行是坏的，调用方应先挡掉）"
        }
        val init = envelope(
            Request.Builder()
                .url(http.baseUrl() + "/api/v1/upload/init")
                .post(
                    ProtocolJson.encodeToString(
                        InitBody.serializer(), InitBody(fileName, totalBytes),
                    ).toRequestBody(JSON_MEDIA),
                ),
            InitData.serializer(), "upload_init",
        )
        val chunk = if (init.chunkSize > 0) init.chunkSize.toInt() else DEFAULT_CHUNK
        log.d("chunked_init", "id" to init.uploadId, "size" to totalBytes, "chunk" to chunk)

        var sent = 0L
        (openStream() ?: throw ApiException(ApiException.TRANSPORT, "读不到文件内容")).use { input ->
            val buf = ByteArray(chunk)
            while (sent < totalBytes) {
                // 一片可能要读好几次才满——InputStream.read **不保证**一次读满
                var filled = 0
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled <= 0) break
                val res = envelope(
                    Request.Builder()
                        .url(http.baseUrl() + "/api/v1/upload/${init.uploadId}/chunk?offset=$sent")
                        // 只发这一片：copyOf(filled) 而不是整个 buf——最后一片没填满，
                        // 多发的那截零字节会被服务端当成真实内容追加进去。
                        .put(buf.copyOf(filled).toRequestBody(mimeType.toMediaTypeOrNull())),
                    OffsetData.serializer(), "upload_chunk",
                )
                if (res.offset != sent + filled) {
                    log.w("chunked_desync", "expect" to (sent + filled), "server" to res.offset)
                    throw ApiException(ApiException.TRANSPORT, "上传中断，请重试")
                }
                sent += filled
                onProgress?.invoke(sent, totalBytes)
            }
        }
        if (sent != totalBytes) {
            log.w("chunked_short_read", "sent" to sent, "total" to totalBytes)
            throw ApiException(ApiException.TRANSPORT, "文件读取不完整，请重试")
        }
        envelope(
            Request.Builder()
                .url(http.baseUrl() + "/api/v1/upload/${init.uploadId}/complete")
                // 无体 POST 也必须发 `{}`：OkHttp 对 POST 传 null body 当场抛，
                // 而调用侧普遍 runCatching 兜底 → 表现成「点了没反应」且服务端日志一行都没有。
                // 这个坑 2026-09-07 在 /logout 等四处刚踩过，别在这里再踩一遍。
                .post("{}".toRequestBody(JSON_MEDIA)),
            UploadResult.serializer(), "upload_complete",
        ).also { log.i("chunked_done", "url" to it.url, "size" to it.size) }
    }

    @Serializable
    private data class InitBody(val name: String, val size: Long)

    // —— 可续传分片原语（[com.libeyond.imandroid.data.ChunkedUploader] 用）——
    // 与上面 uploadStream 的区别：**协程可取消**（取消即 `Call.cancel()`，暂停/取消要能立刻掐断在途请求，
    // 一片最大 8MB，只作废回调不掐请求等于白白占带宽）、**可从服务端 offset 续传**、`upload_id` 由调用方持有。

    override suspend fun sessionInit(name: String, size: Long): UploadSession {
        val d = call(
            Request.Builder().url(http.baseUrl() + "/api/v1/upload/init")
                .post(ProtocolJson.encodeToString(InitBody.serializer(), InitBody(name, size)).toRequestBody(JSON_MEDIA)),
            InitData.serializer(), "upload_init",
        )
        return UploadSession(d.uploadId, d.offset, if (d.chunkSize > 0) d.chunkSize.toInt() else DEFAULT_CHUNK)
    }

    override suspend fun sessionStatus(id: String): Long =
        call(Request.Builder().url(http.baseUrl() + "/api/v1/upload/$id/status").get(), OffsetData.serializer(), "upload_status").offset

    override suspend fun sessionChunk(id: String, offset: Long, bytes: ByteArray, len: Int, mime: String): Long =
        call(
            Request.Builder().url(http.baseUrl() + "/api/v1/upload/$id/chunk?offset=$offset")
                // 只发这一片：copyOf(len)——最后一片没填满，多发的零字节会被服务端当真实内容追加
                .put(bytes.copyOf(len).toRequestBody(mime.toMediaTypeOrNull())),
            OffsetData.serializer(), "upload_chunk",
        ).offset

    override suspend fun sessionComplete(id: String): UploadResult =
        call(
            // 无体 POST 也必须发 `{}`（OkHttp 对 null body 当场抛）
            Request.Builder().url(http.baseUrl() + "/api/v1/upload/$id/complete").post("{}".toRequestBody(JSON_MEDIA)),
            UploadResult.serializer(), "upload_complete",
        )

    /** [envelope] 的协程版：enqueue + 取消时掐掉 Call。失败一律抛 [ApiException]。 */
    private suspend fun <T> call(
        builder: Request.Builder,
        serializer: kotlinx.serialization.KSerializer<T>,
        event: String,
    ): T = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val requestId = HttpClient.newRequestId()
        val req = builder
            .header(HttpClient.HEADER_REQUEST_ID, requestId)
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        val c = ok.newCall(req)
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isCancelled) return
                log.w("${event}_transport_failed", "reqId" to requestId, "err" to e.javaClass.simpleName)
                cont.resumeWith(Result.failure(ApiException(ApiException.TRANSPORT, e.message ?: "上传失败", requestId, cause = e)))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val res = runCatching {
                    response.use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        val env = try {
                            ProtocolJson.decodeFromString(com.libeyond.imandroid.sdk.http.ApiEnvelope.serializer(), raw)
                        } catch (e: Exception) {
                            throw ApiException(ApiException.TRANSPORT, "上传响应不是合法 JSON", requestId, resp.code, e)
                        }
                        if (env.code != 0) {
                            log.w("${event}_biz_error", "code" to env.code, "reqId" to env.requestId)
                            throw ApiException(env.code, env.message.ifEmpty { Str.s(R.string.net_error_upload_failed) }, env.requestId, resp.code)
                        }
                        val data = env.data ?: throw ApiException(ApiException.TRANSPORT, "上传未返回 data", requestId)
                        ProtocolJson.decodeFromJsonElement(serializer, data)
                    }
                }
                if (!cont.isCancelled) cont.resumeWith(res)
            }
        })
    }

    /** 发一个请求并解出信封里的 data，失败一律抛 [ApiException]。 */
    private fun <T> envelope(
        builder: Request.Builder,
        serializer: kotlinx.serialization.KSerializer<T>,
        event: String,
    ): T {
        val requestId = HttpClient.newRequestId()
        val req = builder
            .header(HttpClient.HEADER_REQUEST_ID, requestId)
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        val raw: String
        val status: Int
        try {
            ok.newCall(req).execute().use { resp ->
                status = resp.code
                raw = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            log.w("${event}_transport_failed", "reqId" to requestId, "err" to e.javaClass.simpleName)
            throw ApiException(ApiException.TRANSPORT, e.message ?: "上传失败", requestId, cause = e)
        }
        val env = try {
            ProtocolJson.decodeFromString(
                com.libeyond.imandroid.sdk.http.ApiEnvelope.serializer(), raw,
            )
        } catch (e: Exception) {
            throw ApiException(ApiException.TRANSPORT, "上传响应不是合法 JSON", requestId, status, e)
        }
        if (env.code != 0) {
            log.w("${event}_biz_error", "code" to env.code, "reqId" to env.requestId)
            throw ApiException(env.code, env.message.ifEmpty { Str.s(R.string.net_error_upload_failed) }, env.requestId, status)
        }
        val data = env.data
            ?: throw ApiException(ApiException.TRANSPORT, "上传未返回 data", requestId)
        return ProtocolJson.decodeFromJsonElement(serializer, data)
    }

    private companion object {
        /** 服务端没给 chunk_size 时的兜底（与服务端 maxChunkBytes 同值 8MB）。 */
        const val DEFAULT_CHUNK = 8 shl 20
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}

/** 一次 `POST /upload/init` 的结果：会话 id、已收偏移（新会话为 0）、服务端建议的分片大小。 */
data class UploadSession(val id: String, val offset: Long, val chunkSize: Int)

/**
 * 可续传分片上传的传输层原语（真实现是 [UploadApi]；单测里换成内存假实现）。
 * `offset` 的真相永远在服务端：每次 [sessionChunk] 回的是服务端**当前**已收长度，对不上它自己会对齐而不报错。
 */
interface UploadTransport {
    suspend fun sessionInit(name: String, size: Long): UploadSession
    suspend fun sessionStatus(id: String): Long
    suspend fun sessionChunk(id: String, offset: Long, bytes: ByteArray, len: Int, mime: String): Long
    suspend fun sessionComplete(id: String): UploadResult
}
