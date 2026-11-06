package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.AboutLinkValues
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/about-links`：关于我们页四个链接（后台可配置，见 IMServer `internal/aboutlinks`）。
 * 免登录；带 token 才会回 `customer_service_url`（否则空串）。
 */
class AboutLinksApi(private val http: HttpClient, private val authenticated: Boolean = true) {
    @Serializable
    internal data class Dto(
        @SerialName("privacy_url") val privacyUrl: String = "",
        @SerialName("terms_url") val termsUrl: String = "",
        @SerialName("support_url") val supportUrl: String = "",
        @SerialName("customer_service_url") val customerServiceUrl: String = "",
    )

    suspend fun fetch(): AboutLinkValues {
        val dto = decode(
            http.call("GET", "/api/v1/about-links", authenticated = authenticated),
            Dto.serializer(),
        )
        return AboutLinkValues.of(dto.privacyUrl, dto.termsUrl, dto.supportUrl, dto.customerServiceUrl)
    }

    companion object {
        /** 登录页用：host 是输入框里还没登录的地址，不带任何凭据。 */
        fun anonymous(host: String, useTls: Boolean) = AboutLinksApi(HttpClient(host, useTls), authenticated = false)
    }
}
