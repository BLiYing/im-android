package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement

/**
 * 把 `data` 段解成具体类型。`data` 为空或结构不符一律抛 [ApiException.TRANSPORT]
 * ——**不要静默返回默认值**：那会把「服务端没给」伪装成「服务端给了个空的」。
 */
internal fun <T> decode(data: JsonElement?, serializer: DeserializationStrategy<T>): T {
    if (data == null) {
        throw ApiException(ApiException.TRANSPORT, "服务端未返回 data")
    }
    return try {
        ProtocolJson.decodeFromJsonElement(serializer, data)
    } catch (e: Exception) {
        throw ApiException(ApiException.TRANSPORT, "data 结构不符：${e.message}", cause = e)
    }
}

/** 同上，但允许 `data` 缺失（返回 null）——用于本就可能没有负载的接口。 */
internal fun <T> decodeOrNull(data: JsonElement?, serializer: DeserializationStrategy<T>): T? {
    if (data == null) return null
    return try {
        ProtocolJson.decodeFromJsonElement(serializer, data)
    } catch (e: Exception) {
        null
    }
}
