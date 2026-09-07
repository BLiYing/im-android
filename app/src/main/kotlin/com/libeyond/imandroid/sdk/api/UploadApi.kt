package com.libeyond.imandroid.sdk.api

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
 * 服务端上限：图片 20MB / 视频 100MB / 文件 100MB；`?as=voice` 切 voice 白名单 + 16MB。
 * 超限回 `500001`，格式不支持回 `500002`。
 */
class UploadApi(
    private val http: HttpClient,
    private val tokenProvider: () -> String?,
) {
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
            throw ApiException(env.code, env.message.ifEmpty { "上传失败" }, env.requestId, status)
        }
        val data = env.data ?: throw ApiException(ApiException.TRANSPORT, "上传未返回 data", requestId)
        val r = ProtocolJson.decodeFromJsonElement(UploadResult.serializer(), data)
        log.i("upload_ok", "name" to fileName, "size" to r.size, "reqId" to env.requestId)
        r
    }
}
