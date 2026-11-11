package com.libeyond.imandroid.rtc

import android.content.Context
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import java.net.URI

/**
 * im-rtc 接入配置。信令地址优先取登录页「长按 Logo」填的覆盖值（[RtcEndpointStore]），
 * 没填才回退 `local.properties`（已被 .gitignore 忽略）→ `BuildConfig`——与 iOS `IMRtcConfig.load` 同口径。
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

        /** 实际生效的配置：覆盖值优先，没有再用构建时的默认值。 */
        fun load() = resolve(RtcEndpointStore.override, BuildConfig.RTC_WS_URL)

        /** [load] 的纯函数核心，便于单测。覆盖值不合法（理论上写入前已校验）时同样回退默认。 */
        fun resolve(override: String?, buildDefault: String) =
            RtcConfig(wsUrl = normalizedWsUrl(override) ?: buildDefault.trim())

        /**
         * 校验并规整用户输入的信令地址：去首尾空白，须为 `ws://` / `wss://` 且带主机名，中间不能有空白。
         * 不合法返回 null。与 iOS `IMRtcConfig normalizedWSURL:` 同判据。
         */
        fun normalizedWsUrl(input: String?): String? {
            val s = input?.trim().orEmpty()
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "ws" && scheme != "wss") return null
            return if (uri.host.isNullOrEmpty()) null else s
        }
    }
}

/**
 * 信令地址的本机覆盖值（登录页长按 Logo 填写）。key 与 iOS 的 NSUserDefaults 同名，便于对照排查。
 * 只在登录页改：那时通话引擎已随退出登录停掉，登录后新建引擎自然读到新值，无需重建逻辑。
 */
object RtcEndpointStore {
    private const val PREFS = "im_rtc"
    private const val KEY_WS_URL = "im_rtc_ws_url_override"

    private lateinit var prefs: android.content.SharedPreferences

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次。 */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 未初始化（如 JVM 单测）时当作没有覆盖值。 */
    val override: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_WS_URL, null) else null

    /** null = 删除覆盖值，恢复构建时的默认地址。 */
    fun save(wsUrl: String?) {
        if (!::prefs.isInitialized) return
        prefs.edit().apply { if (wsUrl == null) remove(KEY_WS_URL) else putString(KEY_WS_URL, wsUrl) }.apply()
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
