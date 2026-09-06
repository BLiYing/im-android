package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 本端**唯一**的 REST 出口（对应 iOS 的 `IMHTTPService`、Web 的 `sdk/http.ts#tracedFetch`）。
 *
 * 职责：拼 URL、带鉴权头、生成并透传 Request ID、把 `{code,message,request_id,data}`
 * 拆成「成功的 data」或「带业务码的 [ApiException]」。
 *
 * **业务代码不得自己 new OkHttp 请求**——绕过这里就没有 Request ID、没有统一错误码、
 * 也不进日志，三端排障链路当场断掉。
 */
class HttpClient(
    /** 形如 `10.0.2.2:8080`，不含 scheme。 */
    @Volatile var host: String,
    @Volatile var useTls: Boolean = false,
    /** 取当前访问 token；未登录返回 null。由会话层注入，HttpClient 不持有会话状态。 */
    private val tokenProvider: () -> String? = { null },
) {
    private val log = IMLog.tag("IM.HTTP")

    private val ok = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scheme: String get() = if (useTls) "https" else "http"

    fun baseUrl(): String = "$scheme://$host"

    /**
     * 发一个 JSON 请求，返回 `data` 段（可能为 null）。
     *
     * @throws ApiException 业务码非 0，或传输层失败（[ApiException.TRANSPORT]）。
     */
    suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        /** 免鉴权接口（register/login/token refresh/qr login）置 false。 */
        authenticated: Boolean = true,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestId = newRequestId()
        val url = buildUrl(path, query)

        val builder = Request.Builder()
            .url(url)
            .header(HEADER_REQUEST_ID, requestId)

        if (authenticated) {
            tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        }

        val payload = body?.let { ProtocolJson.encodeToString(JsonElement.serializer(), it) }
        builder.method(method, payload?.toRequestBody(JSON_MEDIA))

        val started = System.currentTimeMillis()
        val raw: String
        val status: Int
        try {
            ok.newCall(builder.build()).execute().use { resp ->
                status = resp.code
                raw = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            // 传输层失败没有业务码——**不要**在这里编一个，编了上层就分不清
            // 「服务端明确拒绝」与「根本没连上」。
            log.w("http_transport_failed", "method" to method, "path" to path,
                "reqId" to requestId, "err" to e.javaClass.simpleName)
            throw ApiException(ApiException.TRANSPORT, e.message ?: "网络请求失败", requestId, cause = e)
        }

        val cost = System.currentTimeMillis() - started
        val env = try {
            ProtocolJson.decodeFromString(ApiEnvelope.serializer(), raw)
        } catch (e: Exception) {
            log.w("http_bad_envelope", "method" to method, "path" to path,
                "http" to status, "reqId" to requestId, "len" to raw.length)
            throw ApiException(ApiException.TRANSPORT, "响应不是合法的 JSON", requestId, status, e)
        }

        // 服务端回的 request_id 优先——它才是 imserver.log 里那一条。
        val serverReqId = env.requestId.ifEmpty { requestId }

        if (env.code != 0) {
            log.w("http_biz_error", "method" to method, "path" to path,
                "code" to env.code, "http" to status, "reqId" to serverReqId, "ms" to cost)
            throw ApiException(env.code, env.message.ifEmpty { "请求失败" }, serverReqId, status)
        }

        log.d("http_ok", "method" to method, "path" to path, "reqId" to serverReqId, "ms" to cost)
        env.data
    }

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val base = "${baseUrl()}${if (path.startsWith("/")) path else "/$path"}"
        if (query.isEmpty()) return base
        val hu = base.toHttpUrlOrNull() ?: return base
        return hu.newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()
    }

    companion object {
        const val HEADER_REQUEST_ID = "X-Request-ID"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 客户端侧 Request ID。服务端若回了自己的就以服务端的为准
         * （见上方 `serverReqId`）——三端与后端要对得上同一个词才能对账。
         */
        fun newRequestId(): String = "and-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
