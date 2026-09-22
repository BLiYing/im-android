package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 * 名片码扫后展示的对方资料（对齐后端 `internal/qrcode.UserCard`）。
 * **绝不显示 [userId]**（10 位随机内部 ID）——公开句柄用 [username]。
 */
@Serializable
data class QrUserCard(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    /** stranger | friend | self | blocked */
    val relation: String = "",
)

/** 群码扫后的群预览 + 准入判定（对齐后端 `internal/qrcode.GroupCard`）。 */
@Serializable
data class QrGroupCard(
    @SerialName("group_id") val groupId: String = "",
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    /** 群简介（未入群也可见，G3）。 */
    val intro: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("inviter_nickname") val inviterNickname: String = "",
    val joined: Boolean = false,
    val joinable: Boolean = false,
    /** "" 可加入 | approval 需审批 | full 已满 | banned 被拉黑 | invite_revoked 邀请权已收回 */
    val reason: String = "",
)

/** `POST /qr/resolve` 的 `{kind,data}`——kind 决定 data 的形状，本端手动按 kind 分支解码。 */
sealed interface QrResolved {
    data class User(val card: QrUserCard) : QrResolved
    data class Group(val card: QrGroupCard) : QrResolved
    /** 扫码登录码（QR P1）：resolve 只回 ticket，设备/IP/位置由 [QrApi.loginScan] 返回给确认页。 */
    data class Login(val ticket: String) : QrResolved
    /** 非本站码：原样回显，不查库（不给爆破者反馈，也不自动跳转）。 */
    data class Unknown(val text: String) : QrResolved
}

/**
 * 手机端已扫网页版登录码（QR P1）：`POST /qr/login/scan` 的返回，供确认页展示
 * （对齐后端 `webDeviceName`/`internal/qrcode.login.go`）。
 */
@Serializable
data class QrLoginScanInfo(
    val ticket: String = "",
    val device: String = "",
    val ip: String = "",
    val location: String = "",
)

/**
 * 二维码体系（QRCODE P0 出示/接收方半 + QR P1 扫码登录·手机侧）全部已接。
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

    /**
     * 扫码/点链接解析管道：端只识别出一串字符（完整链接或裸 token），语义全部由服务端判定。
     * [raw] 原样透传——服务端 `parseRaw` 自己从字符串里摘 `/q/<u|g|l>/<token>` 段，不用端上先拆。
     */
    suspend fun resolve(raw: String): QrResolved {
        val root = http.call("POST", "/api/v1/qr/resolve", buildJsonObject { put("raw", raw) })
            ?: throw ApiException(ApiException.TRANSPORT, "服务端未返回 data")
        val obj = try {
            root.jsonObject
        } catch (e: Exception) {
            throw ApiException(ApiException.TRANSPORT, "data 结构不符：${e.message}", cause = e)
        }
        val kind = obj["kind"]?.jsonPrimitive?.content.orEmpty()
        val data = obj["data"]
        return when (kind) {
            "user" -> QrResolved.User(decode(data, QrUserCard.serializer()))
            "group" -> QrResolved.Group(decode(data, QrGroupCard.serializer()))
            "login" -> QrResolved.Login(decodeOrNull(data, QrLoginTicketData.serializer())?.ticket.orEmpty())
            else -> QrResolved.Unknown(decodeOrNull(data, QrUnknownData.serializer())?.text.orEmpty())
        }
    }

    /**
     * 手机端已扫（QR P1）：拿 Web 端设备/IP/位置供确认页展示。resolve 已校验票据可用；
     * 这里若并发过期/被抢会回 [com.libeyond.imandroid.sdk.protocol.ErrCode.QR_EXPIRED]。
     */
    suspend fun loginScan(ticket: String): QrLoginScanInfo =
        decode(
            http.call("POST", "/api/v1/qr/login/scan", buildJsonObject { put("ticket", ticket) }),
            QrLoginScanInfo.serializer(),
        )

    /** 手机端确认登录：Web 端换 JWT 登录。非 scanned/非本人回 `200110`。 */
    suspend fun loginConfirm(ticket: String) {
        http.call("POST", "/api/v1/qr/login/confirm", buildJsonObject { put("ticket", ticket) })
    }

    /** 手机端拒绝登录（终态）。 */
    suspend fun loginReject(ticket: String) {
        http.call("POST", "/api/v1/qr/login/reject", buildJsonObject { put("ticket", ticket) })
    }
}

/** kind=login 时 data 的形状：`{"ticket": "<票据>"}`。 */
@Serializable
private data class QrLoginTicketData(val ticket: String = "")

/** kind=unknown 时 data 的形状：`{"text": "<原文>"}`。 */
@Serializable
private data class QrUnknownData(val text: String = "")
