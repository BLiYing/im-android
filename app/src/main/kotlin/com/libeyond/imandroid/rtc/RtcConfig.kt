package com.libeyond.imandroid.rtc

import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * im-rtc 接入配置。值来自 `local.properties`（已被 .gitignore 忽略）→ `BuildConfig`。
 *
 * 接入票不再由本端签发，改由 IMServer 的 `POST /api/v1/rtc/token` 代为向 im-rtc-server 换票
 * （见 `RtcCall.signToken`）——本端既不需要也不该知道 SDKAppID / SDKSecretKey。
 */
data class RtcConfig(
    /** 信令地址，`ws://` 或 `wss://`。真机别填 127.0.0.1（那指的是手机自己）。 */
    val wsUrl: String,
) {
    /** 缺哪几项（配置名，给人看的）。空 = 可用。 */
    val missing: List<String>
        get() = buildList {
            if (wsUrl.isBlank()) add("rtc.wsUrl")
        }

    val isUsable: Boolean get() = missing.isEmpty()

    companion object {
        fun fromBuild() = RtcConfig(wsUrl = BuildConfig.RTC_WS_URL)
    }
}

/** 宿主 id 送进 im-rtc 前的字段校验（协议 §2.5：非空、无空白、≤64 字节）。判据单独放这里好测。 */
object RtcIds {
    private const val MAX_BYTES = 64

    /** 不合规返回原因，合规返回 null。 */
    fun problem(kind: String, id: String?): String? = when {
        id.isNullOrEmpty() -> Str.s(R.string.rtc_error_id_empty, kind)
        id.any { it.isWhitespace() } -> Str.s(R.string.rtc_error_id_has_whitespace, kind, id)
        id.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Str.s(R.string.rtc_error_id_too_long, kind, MAX_BYTES)
        else -> null
    }
}
