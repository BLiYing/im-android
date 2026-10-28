package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.OutputStream

/** 任务状态（DATA_EXPORT_DESIGN §2）；服务端新增未知值落到 [Unknown]，不抛。 */
enum class ExportStatus(val wire: String) {
    Queued("queued"), Running("running"), Done("done"), Failed("failed"), Canceled("canceled"), Expired("expired"), Unknown("");

    /** 服务端还在跑（页面要继续轮询）。 */
    val isActive: Boolean get() = this == Queued || this == Running

    companion object {
        fun parse(wire: String): ExportStatus = entries.firstOrNull { it.wire.isNotEmpty() && it.wire == wire } ?: Unknown
    }
}

@Serializable
data class ExportProgress(
    @SerialName("done_convs") val doneConvs: Int = 0,
    @SerialName("total_convs") val totalConvs: Int = 0,
)

@Serializable
data class ExportResult(
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("conv_count") val convCount: Int = 0,
    @SerialName("msg_count") val msgCount: Long = 0,
)

/** 导出任务对象（`{id,status,include_media,formats,progress,result,created_at,finished_at,expires_at,error_code}`，error_code 为字符串或 null）。 */
@Serializable
data class ExportJob(
    val id: String = "",
    val status: String = "",
    @SerialName("include_media") val includeMedia: Boolean = false,
    val formats: List<String> = emptyList(),
    val progress: ExportProgress = ExportProgress(),
    val result: ExportResult? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    /** 服务端是字符串枚举（`interrupted` / `internal`），失败态文案统一 export.failed，不按它区分；null 或缺省 = 无。 */
    @SerialName("error_code") val errorCode: String? = null,
) {
    val state: ExportStatus get() = ExportStatus.parse(status)
}

/** `download_url` 回包：`url` 是相对路径（客户端自拼基址），`expires_in` 秒。 */
@Serializable
data class ExportDownloadUrl(val url: String = "", @SerialName("expires_in") val expiresIn: Int = 0)

/** 导出我的数据（DATA_EXPORT_DESIGN §2）。 */
class ExportApi(private val http: HttpClient) {

    suspend fun start(password: String, includeMedia: Boolean, formats: List<String>): ExportJob =
        decode(http.call("POST", BASE, startBody(password, includeMedia, formats)), ExportJob.serializer())

    /** 最近一个任务；从没导出过为 null（`data:null`）。 */
    suspend fun latest(): ExportJob? = decodeOrNull(http.call("GET", "$BASE/latest"), ExportJob.serializer())

    suspend fun cancel(id: String) {
        http.call("POST", "$BASE/$id/cancel")
    }

    suspend fun downloadUrl(id: String): ExportDownloadUrl =
        decode(http.call("POST", "$BASE/$id/download_url"), ExportDownloadUrl.serializer())
            .also { if (it.url.isBlank()) throw com.libeyond.imandroid.sdk.http.ApiException(com.libeyond.imandroid.sdk.http.ApiException.TRANSPORT, "download_url 为空") }

    /** 流式写入 [out]，返回字节数。 */
    suspend fun download(url: String, out: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long =
        http.download(url, out, onProgress)

    companion object {
        private const val BASE = "/api/v1/users/me/exports"

        fun startBody(password: String, includeMedia: Boolean, formats: List<String>): JsonObject = buildJsonObject {
            put("password", password)
            put("include_media", includeMedia)
            put("formats", buildJsonArray { formats.forEach { add(JsonPrimitive(it)) } })
        }

        /** 200011 回包 `data.retry_after_sec`；缺失/非法为 null（调用方回退 24 小时）。 */
        fun retryAfterSec(data: JsonElement?): Long? =
            ((data as? JsonObject)?.get("retry_after_sec") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    }
}
