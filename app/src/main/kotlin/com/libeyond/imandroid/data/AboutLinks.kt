package com.libeyond.imandroid.data

/**
 * 「关于我们」用的纯函数：法务页链接拼接、版本串。无 Android 依赖，可单测。
 * 设计：IMServer/docs/design/ABOUT_LEGAL_DESIGN.md；路径与后端 `internal/legal` 的 `Paths` 一致。
 */
object AboutLinks {
    const val PATH_PRIVACY = "/legal/privacy"
    const val PATH_TERMS = "/legal/terms"
    const val PATH_SUPPORT = "/support"

    private val allowedPaths = setOf(PATH_PRIVACY, PATH_TERMS, PATH_SUPPORT)

    /**
     * 法务页完整链接：服务器地址 + 路径 + `?lang=<zh-Hans|en>`（用 App 内语言偏好，不是系统语言）。
     * [host] 为空或 [path] 不在白名单返回 null（调用方提示“无法打开”，而不是拼出一个坏链接）。
     */
    fun legalUrl(host: String, useTls: Boolean, path: String, lang: ResolvedLanguage): String? {
        if (host.isBlank() || path !in allowedPaths) return null
        return "${if (useTls) "https" else "http"}://${host.trim()}$path?lang=${lang.wire}"
    }

    /**
     * 后台下发的字段值 → 最终可打开的链接；null = 不可打开（调用方隐藏该行或提示「无法打开」）。
     * - 绝对 http(s) 地址：**原样**使用，不追加 `?lang=`（外部页面自己决定多语言）；
     * - 以 "/" 开头：本服务器托管页，走 [legalUrl] 拼 host + `?lang=`；
     * - 空 / 其它 scheme（javascript:、file:…）：null；`useTls=true`（release）时明文 `http://` 也是 null。
     */
    fun resolve(value: String?, host: String, useTls: Boolean, lang: ResolvedLanguage): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.startsWith("/")) return legalUrl(host, useTls, v, lang)
        val lower = v.lowercase()
        // release（useTls=true）不接受明文 http：network_security_config 本就会拦，这里直接隐藏/提示，别打开一个白屏页。
        val schemes = if (useTls) listOf("https://") else listOf("https://", "http://")
        for (scheme in schemes) {
            if (lower.startsWith(scheme)) return v.takeIf { it.length > scheme.length }
        }
        return null
    }

    /** 两个 URL 是否同源（scheme + 主机 + 端口，默认端口按 scheme 补齐）；任一解析失败为 false。 */
    fun sameOrigin(a: String, b: String): Boolean {
        fun origin(u: String): Triple<String, String, Int>? = runCatching {
            val x = java.net.URI(u.trim())
            val scheme = x.scheme?.lowercase() ?: return null
            val host = x.host?.lowercase() ?: return null
            Triple(scheme, host, if (x.port != -1) x.port else if (scheme == "https") 443 else 80)
        }.getOrNull()
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    private const val BEACON_UA_MARKER = "BeaconApp/"

    /** 在 WebView 默认 UA 末尾追加 App 身份标记（Beacon 客服后台据此显示「Android App」）；已带标记不重复追加。 */
    fun beaconUserAgent(base: String, versionName: String): String =
        if (base.contains(BEACON_UA_MARKER)) base else "$base $BEACON_UA_MARKER$versionName (Android)"

    /** 「0.1.0 (1)」：营销版本 + 构建号；任一缺失退化为另一个，都缺为「-」。 */
    fun versionString(name: String?, code: Long?): String = when {
        !name.isNullOrBlank() && code != null -> "$name ($code)"
        !name.isNullOrBlank() -> name
        code != null -> code.toString()
        else -> "-"
    }

    /** 点「版本」行复制的整串，带平台名便于贴给客服：「Android 0.1.0 (1)」。 */
    fun clipboardText(version: String): String = "Android $version"
}
