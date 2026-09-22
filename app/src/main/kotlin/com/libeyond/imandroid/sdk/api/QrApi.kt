package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一枚码（对齐后端 `internal/qrcode.Card`）。
 *
 * [url] 是可直接渲染成二维码、也可当链接复制出去的那一串（`https://host/q/u/<token>`）；
 * [token] 是裸令牌。**渲染优先用 url**——扫到裸 token 的外部扫码器什么也做不了。
 */
@Serializable
data class QrCard(
    val url: String = "",
    val token: String = "",
    /** 毫秒；**0 = 长期有效**（名片码恒为 0，群码 7 天）。 */
    @SerialName("expires_at") val expiresAt: Long = 0,
    /** 群码才有：邀请人 uid。 */
    val inviter: String = "",
) {
    /** 要编进二维码 / 复制给别人的串。 */
    val codeString: String get() = url.ifBlank { token }
}

/**
 * 二维码体系（QRCODE P0）。名片码 + 群码（出示这一半）已接；
 * **扫码解析 / 点链接加群这一半仍未接**（`resolve`/`join` 需要相机权限 + 深链接接入，
 * 另立一批，见 `docs/UI_PARITY_IOS.md`）。
 */
class QrApi(private val http: HttpClient) {

    /** 我的名片码。服务端懒生成：已有未吊销的长期码就复用，所以反复进页拿到的是同一枚。 */
    suspend fun myCard(): QrCard =
        decode(http.call("GET", "/api/v1/qr/me"), QrCard.serializer())

    /** 重置名片码：**旧码立即失效**，已经把码发出去的人将无法通过它加我。 */
    suspend fun resetMyCard(): QrCard =
        decode(http.call("POST", "/api/v1/qr/me/reset"), QrCard.serializer())

    /**
     * 群二维码 / 群邀请链接（同一枚 token，端上只是标题文案不同）。
     * 服务端 7 天内复用同一枚；`perm_invite=1` 且出码人已无邀请权时强制换新——
     * 这条端上不用管，服务端自己判。
     */
    suspend fun groupQR(convId: String): QrCard =
        decode(http.call("GET", "/api/v1/groups/$convId/qr"), QrCard.serializer())

    /** 重置群码：**旧码/旧链接立即失效**，仅群主/管理员可调（服务端二次校验，端上只隐藏入口）。 */
    suspend fun resetGroupQR(convId: String): QrCard =
        decode(http.call("POST", "/api/v1/groups/$convId/qr/reset"), QrCard.serializer())
}
