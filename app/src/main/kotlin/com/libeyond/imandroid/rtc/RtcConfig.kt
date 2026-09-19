package com.libeyond.imandroid.rtc

import com.libeyond.imandroid.BuildConfig

/**
 * im-rtc 联调配置。值来自 `local.properties`（已被 .gitignore 忽略）→ `BuildConfig`，**secret 不进源码**。
 *
 * 联调期用「调试密钥」在本机签接入票（im-rtc-server `docs/design/DEBUG_KEY_DESIGN.md`）；
 * 上线前换成 IMServer 换票接口，那时 [secret] 整个删掉。
 */
data class RtcConfig(
    /** 信令地址，`ws://` 或 `wss://`。真机别填 127.0.0.1（那指的是手机自己）。 */
    val wsUrl: String,
    val appId: String,
    /** 调试密钥 ID，形如 `dbg-1`。 */
    val keyId: String,
    val secret: String,
) {
    /** 缺哪几项（配置名，给人看的）。空 = 可用。 */
    val missing: List<String>
        get() = buildList {
            if (wsUrl.isBlank()) add("rtc.wsUrl")
            if (appId.isBlank()) add("rtc.appId")
            if (keyId.isBlank()) add("rtc.keyId")
            if (secret.isBlank()) add("rtc.debugSecret")
        }

    val isUsable: Boolean get() = missing.isEmpty()

    companion object {
        fun fromBuild() = RtcConfig(
            wsUrl = BuildConfig.RTC_WS_URL,
            appId = BuildConfig.RTC_APP_ID,
            keyId = BuildConfig.RTC_KEY_ID,
            secret = BuildConfig.RTC_DEBUG_SECRET,
        )
    }
}

/** 宿主 id 送进 im-rtc 前的字段校验（协议 §2.5：非空、无空白、≤64 字节）。判据单独放这里好测。 */
object RtcIds {
    private const val MAX_BYTES = 64

    /** 不合规返回原因，合规返回 null。 */
    fun problem(kind: String, id: String?): String? = when {
        id.isNullOrEmpty() -> "$kind 为空"
        id.any { it.isWhitespace() } -> "$kind 含空白：$id"
        id.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "$kind 超过 $MAX_BYTES 字节"
        else -> null
    }
}
