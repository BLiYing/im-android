package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
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
        builder.method(method, requestBodyFor(method, payload)?.toRequestBody(JSON_MEDIA))

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
            throw ApiException(
                env.code,
                env.message.ifEmpty { Str.s(R.string.err_request_failed, env.code.toString()) },
                serverReqId,
                status,
            )
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

        /** OkHttp 规定必须带请求体的方法；给它们传 null 会当场抛 IllegalArgumentException。 */
        private val REQUIRES_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        /**
         * 决定要发出去的请求体文本。
         *
         * **无体 POST 必须发 `{}` 而不是 null**：OkHttp 的 `Request.Builder.method("POST", null)`
         * 直接抛 `IllegalArgumentException: method POST must have a request body`，而调用侧
         * 普遍用 `runCatching` 兜底，于是表现成「点了没反应」——请求根本没上路，
         * 服务端日志里连一行都没有，最难查的那种。
         *
         * 本仓有 4 个无体 POST 踩在这上面：`/logout`、`/devices/{sid}/revoke`、
         * `/devices/revoke-others`、`/qr/me/reset`（2026-09-07 接「我」页设备管理时实测发现，
         * 此前只有 `/logout` 在跑且失败被吞掉——退出登录只清了本地，服务端会话一直没吊销）。
         *
         * 发 `{}` 而不是空串：Go 那边若有 handler 做 `json.Decode(r.Body)`，空体会得到
         * `EOF` 而 `{}` 得到一组零值——前者报 100001，后者行为正常。
         *
         * 反过来 **GET 一定要给 null**：OkHttp 对 GET 带体同样直接抛
         * （`method GET must not have a request body`）。所以这里不能图省事一律发 `{}`。
         *
         * @return null = 不带请求体。
         */
        internal fun requestBodyFor(method: String, payload: String?): String? = when {
            payload != null -> payload
            method.uppercase() in REQUIRES_BODY -> "{}"
            else -> null
        }

        /**
         * 客户端侧 Request ID。服务端若回了自己的就以服务端的为准
         * （见上方 `serverReqId`）——三端与后端要对得上同一个词才能对账。
         */
        fun newRequestId(): String = "and-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
