package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * 本人资料副本的编解码（落 [SessionStore.myProfileJson]）。纯函数，JVM 单测直接覆盖。
 *
 * 只存头部兜底要用的三样（昵称/头像/句柄）+ uid；**不存手机号**（PII，离线显示价值低），`remark` 等看自己时恒空的字段也不存。
 * 解码失败（脏数据/旧格式）一律当没有，**绝不抛**——这条路径在断网兜底上，不能再炸一次。
 */
object MyProfileCodec {
    @Serializable
    private data class Stored(
        val uid: String,
        val nickname: String = "",
        val username: String = "",
        val avatarUrl: String = "",
    )

    fun encode(uid: String, card: UserCard): String = ProtocolJson.encodeToString(
        Stored.serializer(),
        Stored(uid, card.nickname, card.username, card.avatarUrl),
    )

    /** uid 不符（换号）或解码失败 → null。 */
    fun decode(uid: String, json: String?): UserCard? {
        if (uid.isEmpty() || json.isNullOrEmpty()) return null
        val s = runCatching { ProtocolJson.decodeFromString(Stored.serializer(), json) }.getOrNull() ?: return null
        if (s.uid != uid) return null
        return UserCard(userId = uid, username = s.username, nickname = s.nickname, avatarUrl = s.avatarUrl)
    }
}
