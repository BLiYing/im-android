package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET/PUT /api/v1/users/me/privacy`（PROTOCOL §6.8.1）。目前只有账号级「新会话默认」自动删除档位。 */
@Serializable
data class PrivacySettings(
    @SerialName("auto_delete_default_sec") val autoDeleteDefaultSec: Int = 0,
)

class PrivacyApi(private val http: HttpClient) {

    suspend fun get(): PrivacySettings = decode(http.call("GET", PATH), PrivacySettings.serializer())

    /** 整体替换：**必须带** `auto_delete_default_sec`（缺字段 / 非白名单 → 100001）。 */
    suspend fun setAutoDeleteDefault(sec: Int) {
        http.call("PUT", PATH, buildJsonObject { put("auto_delete_default_sec", sec) })
    }

    private companion object {
        const val PATH = "/api/v1/users/me/privacy"
    }
}
