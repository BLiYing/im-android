package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 后端统一响应外壳 `{code,message,request_id,data}`
 * （对齐 `internal/errcode/errcode.go` 的 `Response`）。
 *
 * `data` 保持未解析：先看 `code`，成功了再按接口解自己的负载。
 */
@Serializable
data class ApiEnvelope(
    val code: Int = 0,
    val message: String = "",
    @SerialName("request_id") val requestId: String = "",
    val data: JsonElement? = null,
)

/**
 * 携带**业务码**的 API 异常。
 *
 * ## 为什么必须带 code
 * iOS 与 Web **各踩过一次同一个坑**：网络层把业务码抹掉，只往上抛一个字符串。
 * 于是 UI 要区分「你已被移出该群」和「群已满」时，只能去 parse 错误文案——
 * 文案一改就失效，多语言下更是直接失灵。
 * - iOS：`runOKRequest` 把码抹成 `-1`，2026-08-13 为了给邀请场景分支才改用 `runDataRequest`。
 * - Web：`imSdk.api()` 后来把 errcode 挂到 `Error.code`，配 `errorCode()` 读取。
 *
 * Android 是第三次机会：**从第一版就让码一路传到调用方**。
 * 调用方一律 `catch (e: ApiException) { when (e.code) { ErrCode.GROUP_BANNED -> … } }`，
 * **禁止 parse [message]**。
 *
 * @param code 6 位业务码；[ErrCode.SUCCESS] 之外均为失败。传输层失败（无响应体）用 [TRANSPORT]。
 * @param requestId 服务端回的排障 id，报错时带上便于与 `imserver.log` 对账。
 */
class ApiException(
    val code: Int,
    override val message: String,
    val requestId: String = "",
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** token 失效——调用方据此触发续期或回登录页。 */
    val isAuthExpired: Boolean get() = ErrCode.isAuthExpired(code)

    /** 传输层失败（连不上/超时/响应体不是合法 JSON），**没有**业务码。 */
    val isTransport: Boolean get() = code == TRANSPORT

    override fun toString(): String =
        "ApiException(code=$code, http=$httpStatus, reqId=$requestId, msg=$message)"

    companion object {
        /**
         * 传输层失败的哨兵码。**刻意用负数**：业务码是 6 位正整数，
         * 用 0 会与 `SUCCESS` 撞、用 500001 会与「文件过大」撞
         * ——后者正是另一套骨架犯过的错（把 500001 当成"内部错误"）。
         */
        const val TRANSPORT = -1
    }
}
