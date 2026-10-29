package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET/PUT /api/v1/users/me/privacy`（PROTOCOL §6.8.1 / §11；PRIVACY_VISIBILITY_DESIGN §2）。
 * 账号级「新会话默认」自动删除档位 + 「谁能看到」的上次上线 / 头像 / 个人简介 / 手机号码档位（`everyone|contacts|nobody`）。
 */
@Serializable
data class PrivacySettings(
    @SerialName("auto_delete_default_sec") val autoDeleteDefaultSec: Int = 0,
    /** 读出时服务端已落到具体档位（未设置 = `contacts`）。老服务端无此字段时同样按默认。 */
    @SerialName("last_seen_visibility") val lastSeenVisibility: String = "contacts",
    /** 未设置 = `everyone`。 */
    @SerialName("avatar_visibility") val avatarVisibility: String = "everyone",
    /** 「个人简介」，未设置 = `everyone`。 */
    @SerialName("bio_visibility") val bioVisibility: String = "everyone",
    /** 「手机号码」，未设置 = `nobody`（= 既有行为：手机号从不对他人展示）。 */
    @SerialName("phone_visibility") val phoneVisibility: String = "nobody",
)

class PrivacyApi(private val http: HttpClient) {

    suspend fun get(): PrivacySettings = decode(http.call("GET", PATH), PrivacySettings.serializer())

    /** 账号默认自动删除档位。部分更新：只带这一项，不动可见性档位。 */
    suspend fun setAutoDeleteDefault(sec: Int) = update(autoDeleteDefaultSec = sec)

    /** 「谁能看到」：只带被改的那一项（[PrivacyApi.body] 保证未传的字段不进请求体）。 */
    suspend fun setLastSeenVisibility(level: String) = update(lastSeen = level)

    suspend fun setAvatarVisibility(level: String) = update(avatar = level)

    suspend fun setBioVisibility(level: String) = update(bio = level)

    suspend fun setPhoneVisibility(level: String) = update(phone = level)

    private suspend fun update(
        autoDeleteDefaultSec: Int? = null, lastSeen: String? = null, avatar: String? = null,
        bio: String? = null, phone: String? = null,
    ) {
        http.call("PUT", PATH, body(autoDeleteDefaultSec, lastSeen, avatar, bio, phone))
    }

    companion object {
        private const val PATH = "/api/v1/users/me/privacy"

        /** **部分更新**：只写非 null 的字段（缺省 = 服务端保持不变；全缺省服务端回 100001，调用方不会这么用）。 */
        fun body(
            autoDeleteDefaultSec: Int? = null, lastSeen: String? = null, avatar: String? = null,
            bio: String? = null, phone: String? = null,
        ): JsonObject =
            buildJsonObject {
                autoDeleteDefaultSec?.let { put("auto_delete_default_sec", it) }
                lastSeen?.let { put("last_seen_visibility", it) }
                avatar?.let { put("avatar_visibility", it) }
                bio?.let { put("bio_visibility", it) }
                phone?.let { put("phone_visibility", it) }
            }
    }
}
